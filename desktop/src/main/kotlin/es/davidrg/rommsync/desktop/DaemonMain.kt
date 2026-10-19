package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.collections.CollectionReconciler
import es.davidrg.rommsync.core.auth.PairingDeniedException
import es.davidrg.rommsync.core.auth.PairingExpiredException
import es.davidrg.rommsync.core.install.InstallClient
import es.davidrg.rommsync.core.install.InstallListener
import es.davidrg.rommsync.core.metadata.EsdeCollectionLedger
import es.davidrg.rommsync.core.metadata.EsdeCollectionWriter
import es.davidrg.rommsync.core.metadata.EsdeGameRemover
import es.davidrg.rommsync.core.metadata.EsdeRomPaths
import es.davidrg.rommsync.core.metadata.EsdeSettings
import es.davidrg.rommsync.core.sync.ConflictPolicy
import es.davidrg.rommsync.core.sync.SaveBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Headless modes (no Compose, no AWT): `--daemon` for the systemd user service
 * on the Steam Deck and `--pair` for pairing over SSH. Logs go to stdout
 * (journald adds timestamps).
 *
 * Only the daemon runs an [InstallClient]: the GUI never claims installs, so a
 * GUI open next to the daemon cannot double-claim the same device's requests.
 * The daemon also runs collection sync ([CollectionReconciler]): RomM collections
 * marked "Sync to device" are installed and mirrored as ES-DE custom collections.
 */
object DaemonMain {

    private const val SYNC_INTERVAL_MS = 10 * 60_000L

    /** Exit codes: 0 stopped, 2 not paired. */
    fun run(): Int {
        val config = DesktopConfig
        if (!config.isPaired) {
            println("not paired: run romm-sync --pair (or the GUI) once")
            return 2
        }
        val library = DesktopLibrary(File(config.configDir, "library.properties"))
        val coordinator = DesktopSyncCoordinator(
            config = config,
            library = library,
            cacheDir = config.cacheDir,
            backupManager = SaveBackupManager(File(config.configDir, "save-backups")),
            hashStore = DesktopHashStore(File(config.configDir, "sync-hashes.properties")),
            // Conflicts are never auto-resolved headless: logged, skipped, and left for the GUI.
            conflictPolicy = ConflictPolicy.ASK,
            logger = ::log,
        )

        val syncTriggers = Channel<String>(Channel.CONFLATED)
        val launcherRefresh = LauncherRefreshScheduler(
            refresher = LauncherRefresher(LinuxProcessSystem()),
            // Re-read per batch: the toggle may be changed without restarting the daemon.
            enabled = {
                config.sync()
                config.launcherRefreshOnInstall
            },
            log = ::log,
        )
        val romStore = DesktopInstalledRomStore(library)
        val home = System.getProperty("user.home", ".")
        val esdePaths = EsdeRomPaths {
            config.esdeRomDirectory(EsdeSettings.file(File(config.esdeDataDir)), home)
        }
        val esdeLedger = EsdeCollectionLedger(File(config.configDir, "esde-collections.properties"))
        // ES-DE files are edited only while ES-DE is closed: the scheduler stops an idle ES-DE around the edit.
        val collectionWriter = EsdeCollectionWriter(
            esdeDir = {
                config.sync()
                File(config.esdeDataDir).takeIf { it.isDirectory }
            },
            romsRoot = { config.romsRoot },
            paths = esdePaths,
            ledger = esdeLedger,
            editor = launcherRefresh,
            log = ::log,
        )
        val gameRemover = EsdeGameRemover(
            target = {
                config.sync()
                config.esdeTarget()
            },
            paths = esdePaths,
            ledger = esdeLedger,
            editor = launcherRefresh,
            log = ::log,
        )
        lateinit var reconciler: CollectionReconciler
        val installClient = InstallClient(
            baseUrl = config.pairedBaseUrl,
            token = config.pairedToken,
            deviceId = config.pairedDeviceId,
            romsRoot = { config.romsRoot },
            downloadsAllowed = { true },
            store = romStore,
            listener = object : InstallListener {
                // Fresh installs whose ES-DE gamelist/media export is still queued: ES-DE is
                // refreshed once the entry is written, so the restart shows title and media.
                private val awaitingExport = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                override fun onInstallFinished(requestId: String, romId: Int, status: String, reason: String?, files: List<File>) {
                    log("install $requestId rom $romId -> $status${reason?.let { " ($it)" } ?: ""}")
                    // A new game may already have saves in RomM from the other device.
                    if (status != InstallClient.STATUS_FAILED) syncTriggers.trySend("install")
                    // Only a fresh install adds files ES-DE has not scanned yet.
                    if (status != InstallClient.STATUS_DONE) return
                    if (config.esdeInstallTarget() != null) awaitingExport += requestId else launcherRefresh.installFinished()
                }

                override fun onMetadataExported(requestId: String, romId: Int, mediaFiles: Int, failures: List<String>) {
                    if (awaitingExport.remove(requestId)) launcherRefresh.installFinished()
                }

                override fun onUninstallFinished(requestId: String?, romId: Int, status: String, reason: String?, files: List<File>) {
                    log("uninstall ${requestId ?: "(collection sync)"} rom $romId -> $status${reason?.let { " ($it)" } ?: ""}")
                    // The ES-DE cleanup queued by the remover restarts ES-DE; without an ES-DE folder refresh here.
                    if (status == InstallClient.STATUS_DONE && config.esdeTarget() == null) launcherRefresh.installFinished()
                }

                override fun onCollectionsChanged(reason: String) {
                    reconciler.request(reason)
                }
            },
            logger = ::log,
            // Re-read per install: the GUI may have changed the ES-DE folder or the toggle.
            esdeTarget = {
                config.sync()
                config.esdeInstallTarget()
            },
            provenance = romStore,
            gameRemover = gameRemover,
        )
        reconciler = CollectionReconciler(
            backend = installClient,
            installed = romStore,
            provenance = romStore,
            state = DesktopCollectionState(File(config.configDir, "collections.properties")),
            writer = collectionWriter,
            coverDir = File(config.cacheDir, "collection-covers"),
            logger = ::log,
        )

        log("romm-sync ${DesktopConfig.appVersion} daemon: device ${config.pairedDeviceName} (${config.pairedDeviceId}) at ${config.pairedBaseUrl}, roms ${config.romsRoot}")
        config.saveProfile()?.let { log("save profile ${it.name}") }
        when {
            !config.esdeMediaOnInstall -> log("media: ES-DE export on install disabled (esde.media_on_install=false)")
            else -> config.esdeTarget()?.let { log("media: ES-DE gamelists in ${it.esdeDir}, media in ${it.mediaDir}") }
                ?: log("media: no ES-DE folder at ${config.esdeDataDir}; export on install off")
        }
        log(
            if (config.launcherRefreshOnInstall) "launcher: ES-DE refresh after installs on (restarts ES-DE only when idle)"
            else "launcher: ES-DE refresh after installs off (launcher.refresh_on_install=false)",
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                log("shutting down")
                installClient.stop()
                scope.cancel()
            },
        )

        runCatching {
            sun.misc.Signal.handle(sun.misc.Signal("USR1")) { syncTriggers.trySend("SIGUSR1") }
        }.onFailure { log("SIGUSR1 trigger unavailable: ${it.message}") }

        // Devices paired by an older build lack remote_uninstall/collection_sync.
        scope.launch {
            try {
                DesktopPairing.advertiseCapabilities()
                log("capabilities advertised (remote install/uninstall, collection sync)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("capabilities: update failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        installClient.start()
        launcherRefresh.start(scope)
        reconciler.start(scope)
        scope.launch {
            while (isActive) {
                syncTriggers.trySend("timer")
                // Smart collections change on scans without an event: re-check every cycle.
                reconciler.request("periodic")
                delay(SYNC_INTERVAL_MS)
            }
        }
        val syncLoop = scope.launch {
            for (reason in syncTriggers) {
                log("sync: starting ($reason)")
                try {
                    val result = coordinator.runSync()
                    if (result.error != null) {
                        log("sync: error: ${result.error}")
                    } else {
                        log(
                            "sync: uploaded=${result.uploaded} downloaded=${result.downloaded} " +
                                "conflicts=${result.conflicts} failed=${result.failedDetails.size}",
                        )
                        config.lastSyncAt = System.currentTimeMillis()
                    }
                    result.failedDetails.forEach { log("sync: failed ${it.action} ${it.fileName} (rom ${it.romId}): ${it.reason}") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("sync: failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        runBlocking { syncLoop.join() }
        return 0
    }

    /** Exit codes: 0 paired, 2 no server URL, 3 code expired, 4 denied, 1 other error. */
    fun pair(name: String?, url: String?): Int {
        val config = DesktopConfig
        val baseUrl = url ?: config.pairedBaseUrl.ifBlank { config.serverUrl }
        if (baseUrl.isBlank()) {
            System.err.println("no RomM server configured: pass --url https://romm.example.com")
            return 2
        }
        val deviceName = name ?: config.pairedDeviceName.ifBlank { DesktopPairing.defaultDeviceName() }
        return try {
            val device = runBlocking {
                DesktopPairing.pair(baseUrl, deviceName) { code ->
                    println("Pairing \"$deviceName\" with $baseUrl")
                    println("Code: ${code.userCode}")
                    println("Approve it in RomM (logged in): ${code.verificationUrl}")
                    println("Waiting up to ${code.expiresInSeconds / 60} min for approval…")
                }
            }
            println("paired: device_id=${device.deviceId}")
            0
        } catch (e: PairingExpiredException) {
            System.err.println("pairing failed: ${e.message}")
            3
        } catch (e: PairingDeniedException) {
            System.err.println("pairing failed: ${e.message}")
            4
        } catch (e: Exception) {
            System.err.println("pairing failed: ${e.javaClass.simpleName}: ${e.message}")
            1
        }
    }

    private fun log(message: String) = println(message)
}
