package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.auth.PairingDeniedException
import es.davidrg.rommsync.core.auth.PairingExpiredException
import es.davidrg.rommsync.core.install.InstallClient
import es.davidrg.rommsync.core.install.InstallListener
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
        val installClient = InstallClient(
            baseUrl = config.pairedBaseUrl,
            token = config.pairedToken,
            deviceId = config.pairedDeviceId,
            romsRoot = { config.romsRoot },
            downloadsAllowed = { true },
            store = DesktopInstalledRomStore(library),
            listener = object : InstallListener {
                override fun onInstallFinished(requestId: String, romId: Int, status: String, reason: String?, files: List<File>) {
                    log("install $requestId rom $romId -> $status${reason?.let { " ($it)" } ?: ""}")
                    // A new game may already have saves in RomM from the other device.
                    if (status != InstallClient.STATUS_FAILED) syncTriggers.trySend("install")
                }
            },
            logger = ::log,
        )

        log("romm-sync ${DesktopConfig.appVersion} daemon: device ${config.pairedDeviceName} (${config.pairedDeviceId}) at ${config.pairedBaseUrl}, roms ${config.romsRoot}")
        config.saveProfile()?.let { log("save profile ${it.name}") }

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

        installClient.start()
        scope.launch {
            while (isActive) {
                syncTriggers.trySend("timer")
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
