package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.collections.CollectionBackend
import es.davidrg.rommsync.core.collections.DeviceCollection
import es.davidrg.rommsync.core.collections.LauncherGameRemover
import es.davidrg.rommsync.core.collections.LocalInstallResult
import es.davidrg.rommsync.core.collections.LocalUninstallResult
import es.davidrg.rommsync.core.collections.Provenance
import es.davidrg.rommsync.core.collections.RomProvenanceStore
import es.davidrg.rommsync.core.download.PlatformFolders
import es.davidrg.rommsync.core.download.RomFileVerification
import es.davidrg.rommsync.core.metadata.EsdeMediaExporter
import es.davidrg.rommsync.core.metadata.EsdeTarget
import es.davidrg.rommsync.core.remote.AuthInterceptor
import es.davidrg.rommsync.core.remote.NetworkModule
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.InstallReportRequest
import es.davidrg.rommsync.core.remote.dto.InstallRequestDto
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.core.remote.dto.RomFileDto
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A rom present on this device as recorded at install: [primary] is the rom file or game folder. */
data class InstalledRom(val romId: Int, val platformSlug: String, val primary: File, val files: List<File>)

/** Where the app records ROMs that are present locally (feeds the library and save sync). */
interface InstalledRomStore {
    /** [primaryFile] is the rom file, or the game folder for multi-file roms; [files] every file written. */
    fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>)

    /** The recorded install of [romId]; null when unknown (uninstall then falls back to RomM's file list). */
    fun installed(romId: Int): InstalledRom? = null

    /** Drops the record of [romId] after its files were removed. */
    fun forgetInstalled(romId: Int) {}

    /**
     * Every recorded install. Several RomM platforms map to one folder (gb/sgb,
     * genesis/megadrive, …), so two roms can record the same path: install and
     * uninstall consult the other records before writing or deleting a file.
     */
    fun allInstalled(): List<InstalledRom> = emptyList()
}

/** Callbacks for UI/notifications. Invoked from background threads. */
interface InstallListener {
    fun onConnection(connected: Boolean, detail: String?) {}
    fun onInstallStarted(requestId: String, romId: Int) {}
    fun onInstallProgress(requestId: String, bytes: Long, total: Long) {}
    fun onInstallFinished(requestId: String, romId: Int, status: String, reason: String?, files: List<File>) {}

    /**
     * ES-DE media/gamelist export after a successful install (runs after the
     * report, never changes the install outcome). [failures] lists per-item
     * problems; [mediaFiles] counts media written or already present.
     */
    fun onMetadataExported(requestId: String, romId: Int, mediaFiles: Int, failures: List<String>) {}

    /** RomM's collection subscriptions may have changed (`connect` or `event`); re-run collection sync. */
    fun onCollectionsChanged(reason: String) {}

    /**
     * An uninstall finished: [requestId] is RomM's request id, null when collection
     * sync removed the rom. [files] are the files deleted.
     */
    fun onUninstallFinished(requestId: String?, romId: Int, status: String, reason: String?, files: List<File>) {}
}

/**
 * Push-install pipeline for a paired RomM device.
 *
 * Holds a socket.io connection to RomM's `/devices` namespace (path
 * `/ws/socket.io`, handshake `auth: {token}`), which keeps the device online
 * and delivers `install:queued` / `install:cancelled` / `collections:changed`.
 * A drain (claim + handle every taken request, one at a time) runs on connect,
 * on `install:queued`, on [drainNow], and every 60 s regardless of the socket,
 * since a claim alone also keeps the device online for 90 s.
 *
 * Files are streamed to `<target>.part`, checked against size and hash, then
 * renamed into `<romsRoot>/<platform folder>/` (multi-file roms go into a
 * `<fs_name>/` game folder, `<fs_name>.m3u/` when the set has an `.m3u`).
 * Requests with `action=uninstall` delete exactly the rom files recorded at
 * install (fallback: the files RomM lists for the rom, at their install
 * placement) and the folders left empty; anything else in a game folder, and
 * saves/states, are never touched. Otherwise only `.part` files are deleted.
 *
 * Several RomM platforms share a folder (gb/sgb, genesis/megadrive, …), so two
 * roms can target the same path. A file another recorded install covers is
 * never overwritten (identical → recorded for both; different → `failed`) and
 * never deleted (uninstall keeps it and only drops its own record).
 *
 * Pushes add the [Provenance.MANUAL] marker; collection sync installs and
 * removes through [installRom]/[uninstallRom] ([CollectionBackend]).
 *
 * After a successful install (`done`/`already_installed`, once reported), when
 * [esdeTarget] yields a folder, RomM's scraped media and metadata are exported
 * there in ES-DE's layout ([EsdeMediaExporter]) so the launcher needs no scrape.
 * Exports run one at a time on their own queue, each bounded by a timeout, so
 * slow media never holds up the next claim or install.
 *
 * Request ids are strings (RomM `InstallRequestSchema.id: str`).
 */
class InstallClient(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String,
    private val romsRoot: () -> String?,
    private val downloadsAllowed: () -> Boolean,
    private val store: InstalledRomStore,
    private val listener: InstallListener,
    httpClient: OkHttpClient? = null,
    private val logger: (String) -> Unit = { println("[romm-sync] $it") },
    /** ES-DE folders to export media/gamelists into after an install; null = off. Read per install. */
    private val esdeTarget: () -> EsdeTarget? = { null },
    /** Provenance markers per rom; null = not tracked. */
    private val provenance: RomProvenanceStore? = null,
    /** Launcher cleanup after an uninstall removed a rom's files. */
    private val gameRemover: LauncherGameRemover? = null,
) : CollectionBackend {
    private val baseClient: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: RomMApiService = NetworkModule
        .createRetrofit(baseUrl, baseClient.newBuilder().addInterceptor(AuthInterceptor { token }).build())
        .create(RomMApiService::class.java)

    private val mediaExporter = EsdeMediaExporter(baseClient, baseUrl, token, logger)

    private class Running(val requestId: String, val job: Job)

    private val lock = Any()
    private val drainMutex = Mutex()
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var running: Running? = null
    @Volatile private var authRejected = false
    private var scope: CoroutineScope? = null
    private var triggers: Channel<Unit>? = null
    private var socket: Socket? = null
    private var exportScope: CoroutineScope? = null
    private var exports: Channel<ExportJob>? = null

    /** Opens the socket and starts the periodic drain. No-op when already started. */
    fun start() {
        synchronized(lock) {
            if (scope != null) return
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val channel = Channel<Unit>(Channel.CONFLATED)
            scope = newScope
            triggers = channel
            newScope.launch {
                for (signal in channel) {
                    try {
                        drainNow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("drain failed: ${describe(e)}")
                    }
                }
            }
            newScope.launch {
                while (isActive) {
                    channel.trySend(Unit)
                    reconnectIfIdle()
                    delay(DRAIN_INTERVAL_MS)
                }
            }
            socket = openSocket()
        }
    }

    /**
     * Closes the socket and cancels every coroutine, aborting a running download
     * (its `.part` files are removed) and any queued or running media export.
     */
    fun stop() {
        synchronized(lock) {
            socket?.let {
                it.off()
                it.close()
            }
            socket = null
            triggers?.close()
            triggers = null
            scope?.cancel()
            scope = null
            exports?.close()
            exports = null
            exportScope?.cancel()
            exportScope = null
        }
    }

    /**
     * Claims every pending request and handles each one in turn. Leaves requests
     * pending (no claim) while downloads are not allowed or no roms root is set.
     */
    suspend fun drainNow() {
        drainMutex.withLock {
            if (!downloadsAllowed()) {
                log("downloads not allowed now; leaving install requests pending")
                return
            }
            val root = romsRoot()?.takeIf { it.isNotBlank() }
            if (root == null) {
                log("no roms root configured; leaving install requests pending")
                return
            }
            val requests = api.claimInstalls(deviceId)
            log("claim: ${requests.size} pending")
            for (request in requests) {
                if (cancelledIds.remove(request.id)) {
                    log("${request.action} ${request.id}: cancelled before start")
                    continue
                }
                when (request.action) {
                    ACTION_UNINSTALL -> runUninstall(request, root)
                    ACTION_INSTALL -> {
                        if (!downloadsAllowed()) {
                            log("downloads paused; remaining requests stay queued")
                            break
                        }
                        runInstall(request, root)
                    }
                    else -> report(request.id, STATUS_FAILED, "unsupported action ${request.action}")
                }
            }
        }
    }

    // ── Collection sync backend ─────────────────────────────────────────

    override suspend fun fetchCollections(): List<DeviceCollection> =
        api.getDeviceCollections(deviceId).map {
            DeviceCollection(it.kind, it.id, it.name, it.description, it.romIds, it.coverUrl, it.updatedAt)
        }

    /**
     * Installs [romId] like a push (same placement, hash checks, library record and
     * ES-DE export) without a RomM request, adding [marker] to its provenance.
     * Waits for a running drain; listener callbacks use the request id `local-<romId>`.
     */
    override suspend fun installRom(romId: Int, marker: String): LocalInstallResult {
        val requestId = "$LOCAL_REQUEST_PREFIX$romId"
        val outcome = drainMutex.withLock {
            if (!downloadsAllowed()) return LocalInstallResult(STATUS_FAILED, "downloads not allowed now")
            val root = romsRoot()?.takeIf { it.isNotBlank() }
                ?: return LocalInstallResult(STATUS_FAILED, "no roms root configured")
            listener.onInstallStarted(requestId, romId)
            val parts = mutableListOf<File>()
            try {
                performInstall(requestId, romId, emptyList(), root, parts, marker)
            } catch (e: CancellationException) {
                deleteParts(parts)
                throw e
            } catch (e: Exception) {
                deleteParts(parts)
                Outcome(STATUS_FAILED, describe(e), emptyList())
            }
        }
        log("install rom $romId ($marker): ${outcome.status}${outcome.reason?.let { " ($it)" } ?: ""}")
        listener.onInstallFinished(requestId, romId, outcome.status, outcome.reason, outcome.files)
        enqueueExport(requestId, outcome)
        return LocalInstallResult(outcome.status, outcome.reason, outcome.files)
    }

    /** Uninstalls [romId] without a RomM request (collection sync); same rules as `action=uninstall`. */
    override suspend fun uninstallRom(romId: Int): LocalUninstallResult {
        val result = drainMutex.withLock { uninstallSafely(romId, romsRoot()?.takeIf { it.isNotBlank() }) }
        log("uninstall rom $romId: ${result.status}${result.reason?.let { " ($it)" } ?: ""}")
        listener.onUninstallFinished(null, romId, result.status, result.reason, result.files)
        return result
    }

    /**
     * Fetches the collection's cover into [dir] as `<kind>-<id>-<url hash>.<ext>`,
     * reusing the cached file while the cover URL is unchanged and dropping older
     * covers of the same collection. The token is only sent to the RomM host.
     */
    override suspend fun downloadCover(collection: DeviceCollection, dir: File): File? = withContext(Dispatchers.IO) {
        val raw = collection.coverUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val base = baseUrl.trimEnd('/').plus('/').toHttpUrlOrNull() ?: return@withContext null
        val url = (raw.toHttpUrlOrNull() ?: base.resolve(raw.trimStart('/'))) ?: return@withContext null
        val prefix = "${collection.kind}-${collection.id}-"
        val stem = prefix + sha1(raw).take(12)
        dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$stem.") && it.length() > 0 }?.let { return@withContext it }
        if (!dir.isDirectory && !dir.mkdirs()) {
            log("collections: cannot create ${dir.path}")
            return@withContext null
        }
        val request = Request.Builder().url(url).apply {
            if (url.host == base.host) header("Authorization", "Bearer $token")
        }.build()
        try {
            baseClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    log("collections: cover for ${collection.key}: HTTP ${response.code}")
                    return@withContext null
                }
                val ext = url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase()
                    ?.takeIf { it in COVER_EXTENSIONS }
                    ?: when (body.contentType()?.subtype?.lowercase()) {
                        "jpeg", "jpg" -> "jpg"
                        "webp" -> "webp"
                        "gif" -> "gif"
                        else -> "png"
                    }
                val target = File(dir, "$stem.$ext")
                val part = File(dir, "${target.name}$PART_SUFFIX")
                try {
                    body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (part.length() == 0L) return@withContext null
                    moveIntoPlace(part, target)
                } finally {
                    if (part.exists()) part.delete()
                }
                dir.listFiles()?.filter { it.name.startsWith(prefix) && it != target }?.forEach { it.delete() }
                target
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("collections: cover for ${collection.key}: ${describe(e)}")
            null
        }
    }

    // ── Socket ──────────────────────────────────────────────────────────

    private fun trigger() {
        triggers?.trySend(Unit)
    }

    private fun openSocket(): Socket {
        val base = URI(baseUrl.trimEnd('/'))
        val namespaceUri = URI(base.scheme, base.rawAuthority, NAMESPACE, null, null)
        val options = socketOptions(baseUrl, token, baseClient)

        val s = IO.socket(namespaceUri, options)
        s.on(Socket.EVENT_CONNECT) {
            authRejected = false
            log("socket connected")
            listener.onConnection(true, null)
            trigger()
            // Subscriptions may have changed while disconnected.
            notifyCollectionsChanged("connect")
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString()
            log("socket disconnected: $reason")
            listener.onConnection(false, reason)
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = errorMessage(args.firstOrNull())
            if (message != null && message in AUTH_REFUSALS) {
                // The token was revoked/unbound or installs are disabled: retrying cannot help.
                authRejected = true
                s.io().reconnection(false)
                s.close()
                log("socket refused ($message); not reconnecting until the token changes")
            } else {
                log("socket connect error: $message")
            }
            listener.onConnection(false, message)
        }
        s.on(EVENT_INSTALL_QUEUED) { args ->
            log("install queued: ${requestIdOf(args.firstOrNull())}")
            trigger()
        }
        s.on(EVENT_INSTALL_CANCELLED) { args ->
            requestIdOf(args.firstOrNull())?.let(::cancelRequest)
        }
        s.on(EVENT_COLLECTIONS_CHANGED) {
            log("collections changed")
            notifyCollectionsChanged("event")
        }
        s.connect()
        return s
    }

    /** Namespace-level refusals destroy the socket without a manager reconnect; retry it on the drain tick. */
    private fun reconnectIfIdle() {
        val s = synchronized(lock) { socket } ?: return
        if (!authRejected && !s.connected() && !s.isActive) s.connect()
    }

    private fun notifyCollectionsChanged(reason: String) {
        try {
            listener.onCollectionsChanged(reason)
        } catch (e: Exception) {
            log("collections: listener failed: ${describe(e)}")
        }
    }

    private fun cancelRequest(requestId: String) {
        cancelledIds.add(requestId)
        val current = running
        if (current != null && current.requestId == requestId) {
            log("install $requestId: cancelled by RomM; aborting download")
            current.job.cancel(CancellationException("install request cancelled"))
        } else {
            log("install $requestId: cancelled by RomM")
        }
    }

    // ── Install pipeline ────────────────────────────────────────────────

    private suspend fun runInstall(request: InstallRequestDto, root: String) = coroutineScope {
        val job = launch(start = CoroutineStart.LAZY) { installOne(request, root) }
        running = Running(request.id, job)
        try {
            if (cancelledIds.remove(request.id)) {
                job.cancel()
                log("install ${request.id}: cancelled before start")
            } else {
                job.start()
            }
            job.join()
        } finally {
            running = null
            cancelledIds.remove(request.id)
        }
    }

    private suspend fun installOne(request: InstallRequestDto, root: String) {
        listener.onInstallStarted(request.id, request.romId)
        log("install ${request.id}: rom ${request.romId}")
        val parts = mutableListOf<File>()
        val outcome = try {
            performInstall(request.id, request.romId, request.fileIds, root, parts, Provenance.MANUAL)
                .also { finish(request, it.status, it.reason, it.files) }
        } catch (e: CancellationException) {
            deleteParts(parts)
            log("install ${request.id}: aborted")
            throw e
        } catch (e: Exception) {
            deleteParts(parts)
            if (!currentCoroutineContext().isActive) {
                // Closing the stream on cancel surfaces as an I/O error: still an abort, never reported.
                log("install ${request.id}: aborted")
                throw CancellationException("install request cancelled").apply { initCause(e) }
            }
            finish(request, STATUS_FAILED, describe(e), emptyList())
            return
        }
        // Reported first so RomM shows the result promptly; media follows off the drain path.
        enqueueExport(request.id, outcome)
    }

    // ── Uninstall ───────────────────────────────────────────────────────

    private suspend fun runUninstall(request: InstallRequestDto, root: String) {
        log("uninstall ${request.id}: rom ${request.romId}")
        val result = uninstallSafely(request.romId, root)
        val reason = result.reason?.take(MAX_REASON_LENGTH)
        log("uninstall ${request.id}: ${result.status}${reason?.let { " ($it)" } ?: ""}")
        report(request.id, result.status, reason)
        listener.onUninstallFinished(request.id, request.romId, result.status, reason, result.files)
    }

    private suspend fun uninstallSafely(romId: Int, root: String?): LocalUninstallResult = try {
        uninstall(romId, root)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LocalUninstallResult(STATUS_FAILED, describe(e))
    }

    /** The files to delete for a rom and the platform folder holding them. */
    private class Removal(val platformDir: File, val primary: File, val files: List<File>, val recorded: Boolean)

    /**
     * Deletes the rom's files and the folders they leave empty, then cleans the
     * launcher and forgets the rom (library record + provenance). Only files
     * inside the rom's platform folder are ever deleted, and never a file another
     * recorded install still covers ([sharedOwner]): that file stays, only this
     * rom's record goes, and the launcher entry (shared too) is left alone.
     */
    private suspend fun uninstall(romId: Int, root: String?): LocalUninstallResult {
        val removal = locateRemoval(romId, root)
        if (removal == null) {
            forget(romId)
            return LocalUninstallResult(STATUS_NOT_INSTALLED, "not on this device")
        }
        val platformDir = removal.platformDir.absoluteFile.normalize()
        val outside = removal.files.firstOrNull { !it.absoluteFile.normalize().startsWith(platformDir) || it.absoluteFile.normalize() == platformDir }
        if (outside != null) {
            return LocalUninstallResult(STATUS_FAILED, "refusing to delete ${outside.path}: outside ${platformDir.path}")
        }
        val others = otherInstalls(romId)
        val deleted = mutableListOf<File>()
        val kept = mutableListOf<File>()
        val failures = mutableListOf<File>()
        withContext(Dispatchers.IO) {
            for (file in removal.files) {
                val owner = sharedOwner(file, others)
                if (owner != null) {
                    if (file.exists()) {
                        kept += file
                        log("uninstall rom $romId: kept ${file.path} (rom ${owner.romId} uses it)")
                    }
                    continue
                }
                if (!file.isFile) continue
                if (file.delete()) deleted += file else failures += file
            }
            pruneEmptyDirs(removal, platformDir)
        }
        if (failures.isNotEmpty()) {
            val more = if (failures.size > 1) " (+${failures.size - 1} more)" else ""
            return LocalUninstallResult(STATUS_FAILED, "cannot delete ${failures.first().path}$more", deleted)
        }
        if (kept.isEmpty() && removal.primary.isDirectory) {
            log("uninstall rom $romId: kept ${removal.primary.path} (holds files that are not part of the rom)")
        }
        if (kept.isEmpty() && (deleted.isNotEmpty() || removal.recorded)) {
            try {
                gameRemover?.onRomRemoved(romId, platformDir.name, removal.files)
            } catch (e: Exception) {
                log("uninstall rom $romId: launcher cleanup failed: ${describe(e)}")
            }
        }
        forget(romId)
        return if (deleted.isEmpty() && kept.isEmpty()) {
            LocalUninstallResult(STATUS_NOT_INSTALLED, "files already gone")
        } else {
            LocalUninstallResult(STATUS_DONE, null, deleted)
        }
    }

    /** Recorded installs of roms other than [romId]; unreadable records stop the install/uninstall. */
    private fun otherInstalls(romId: Int): List<InstalledRom> = try {
        store.allInstalled().filter { it.romId != romId }
    } catch (e: Exception) {
        throw InstallFailure("cannot read library records: ${describe(e)}")
    }

    /** Recorded files first; else (or when none were recorded) the install plan from RomM's file list. */
    private suspend fun locateRemoval(romId: Int, root: String?): Removal? {
        val recorded = try {
            store.installed(romId)
        } catch (e: Exception) {
            log("uninstall rom $romId: cannot read library record: ${describe(e)}")
            null
        }
        if (recorded != null) {
            val platformDir = recorded.primary.absoluteFile.parentFile ?: return null
            val files = recorded.files.ifEmpty {
                when {
                    recorded.primary.isFile -> listOf(recorded.primary)
                    else -> plannedFiles(romId, platformDir, recorded.primary.name) ?: throw InstallFailure(
                        "no file list recorded for ${recorded.primary.path} and RomM has none",
                    )
                }
            }
            return Removal(platformDir, recorded.primary, files, recorded = true)
        }
        if (root == null) return null
        val rom = try {
            api.getRomDetail(romId)
        } catch (e: HttpException) {
            if (e.code() == 404) return null
            throw e
        }
        val folder = PlatformFolders.folderFor(rom.platformSlug, rom.platformFsSlug) ?: return null
        val platformDir = File(root, folder)
        val plan = try {
            planFor(rom, emptyList(), platformDir, otherInstalls(romId), current = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return Removal(platformDir, plan.primary, plan.targets.map { it.target }, recorded = false)
    }

    /** RomM's file list placed into the recorded game folder [gameDirName]. */
    private suspend fun plannedFiles(romId: Int, platformDir: File, gameDirName: String): List<File>? {
        val rom = api.getRomDetail(romId)
        return runCatching { planInstall(rom, emptyList(), platformDir, gameDirName).targets.map { it.target } }.getOrNull()
    }

    /** [planInstall], under a short folder for PS3 games RPCS3 would refuse ([Ps3Folders]). */
    private suspend fun planFor(
        rom: RomDetailDto,
        fileIds: List<Int>,
        platformDir: File,
        others: List<InstalledRom>,
        current: InstalledRom?,
    ): InstallPlan {
        val plan = planInstall(rom, fileIds, platformDir)
        val dir = platformDir.absoluteFile.normalize()
        val short = Ps3Folders.replan(
            rom, fileIds, platformDir, plan,
            current = current?.primary?.absoluteFile?.normalize()?.takeIf { it.parentFile == dir }?.name,
            taken = { name -> sharedOwner(File(platformDir, name), others) != null },
            fetch = { file -> fetchSmallFile(rom.id, file) },
        )
        if (short.primary != plan.primary) {
            log("rom ${rom.id}: game folder ${plan.primary.name} is ${short.primary.name} on this device (RPCS3 needs names under 32 characters)")
        }
        return short
    }

    /** One rom file's bytes (PARAM.SFO), null when the download fails. */
    private suspend fun fetchSmallFile(romId: Int, file: RomFileDto): ByteArray? = try {
        val response = api.downloadRomFile(file.id, file.fileName)
        if (response.isSuccessful) {
            withContext(Dispatchers.IO) { response.body()?.use { it.bytes() } }
        } else {
            response.errorBody()?.close()
            log("install rom $romId: ${file.fileName}: HTTP ${response.code()}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("install rom $romId: ${file.fileName}: ${describe(e)}")
        null
    }

    /** Removes folders under [platformDir] that the deletion left empty, deepest first. */
    private fun pruneEmptyDirs(removal: Removal, platformDir: File) {
        val dirs = removal.files.flatMap { file ->
            generateSequence(file.absoluteFile.normalize().parentFile) { it.parentFile }
                .takeWhile { it != platformDir && it.startsWith(platformDir) }
                .toList()
        }.toSet().sortedByDescending { it.path.length }
        for (dir in dirs) {
            if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete()
        }
    }

    private fun forget(romId: Int) {
        try {
            store.forgetInstalled(romId)
        } catch (e: Exception) {
            log("uninstall rom $romId: cannot drop library record: ${describe(e)}")
        }
        try {
            provenance?.setMarkers(romId, emptySet())
        } catch (e: Exception) {
            log("uninstall rom $romId: cannot clear provenance: ${describe(e)}")
        }
    }

    private class ExportJob(
        val requestId: String,
        val rom: RomDetailDto,
        val primary: File,
        val folder: String,
        val target: EsdeTarget,
    )

    /**
     * Queues the media/metadata export for a successful install. Exports run one
     * at a time on a worker independent of [start], so [drainNow] never waits for them.
     */
    private fun enqueueExport(requestId: String, outcome: Outcome) {
        val rom = outcome.rom ?: return
        val primary = outcome.primary ?: return
        val folder = outcome.folder ?: return
        if (outcome.status != STATUS_DONE && outcome.status != STATUS_ALREADY_INSTALLED) return
        val target = try {
            esdeTarget()
        } catch (e: Exception) {
            log("media: cannot resolve ES-DE folder: ${describe(e)}")
            null
        } ?: return
        val queue = synchronized(lock) {
            exports ?: Channel<ExportJob>(Channel.UNLIMITED).also { channel ->
                val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                worker.launch { for (job in channel) exportMetadata(job) }
                exportScope = worker
                exports = channel
            }
        }
        queue.trySend(ExportJob(requestId, rom, primary, folder, target))
    }

    private suspend fun exportMetadata(job: ExportJob) {
        val rom = job.rom
        try {
            val result = withTimeout(EXPORT_TIMEOUT_MS) {
                mediaExporter.exportFor(rom, job.primary, job.folder, job.target.esdeDir, job.target.mediaDir)
            }
            log("media: ${result.files.size} files for rom ${rom.id} (${result.written.size} downloaded, ${result.failures.size} failed)")
            listener.onMetadataExported(job.requestId, rom.id, result.files.size, result.failures)
        } catch (e: TimeoutCancellationException) {
            val reason = "media export timed out after ${EXPORT_TIMEOUT_MS / 1000} s"
            log("media: $reason for rom ${rom.id}")
            listener.onMetadataExported(job.requestId, rom.id, 0, listOf(reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = describe(e)
            log("media: export failed for rom ${rom.id}: $reason")
            listener.onMetadataExported(job.requestId, rom.id, 0, listOf(reason))
        }
    }

    private class Outcome(
        val status: String,
        val reason: String?,
        val files: List<File>,
        val rom: RomDetailDto? = null,
        val primary: File? = null,
        val folder: String? = null,
    )

    private suspend fun performInstall(
        progressId: String,
        romId: Int,
        fileIds: List<Int>,
        root: String,
        parts: MutableList<File>,
        marker: String,
    ): Outcome {
        val rom = api.getRomDetail(romId)
        val folder = PlatformFolders.folderFor(rom.platformSlug, rom.platformFsSlug)
            ?: return Outcome(STATUS_FAILED, "unsupported platform ${rom.platformSlug}", emptyList())
        val others = otherInstalls(rom.id)
        val own = try {
            store.installed(rom.id)
        } catch (e: Exception) {
            null
        }
        val plan = planFor(rom, fileIds, File(root, folder), others, own)
        val installedFiles = plan.targets.map { it.target }
        val states = withContext(Dispatchers.IO) { plan.targets.map { targetState(it, others, own) } }
        states.firstNotNullOfOrNull { it as? TargetState.Conflict }?.let { throw InstallFailure(it.reason) }
        if (states.all { it is TargetState.Intact }) {
            val foundOnDisk = states.any { !(it as TargetState.Intact).shared }
            record(rom, plan, installedFiles, marker, foundOnDisk)
            return Outcome(STATUS_ALREADY_INSTALLED, null, installedFiles, rom, plan.primary, folder)
        }

        val total = plan.targets.sumOf { it.file.fileSizeBytes }
        val fetch = plan.targets.zip(states).filter { (_, state) -> state !is TargetState.Intact }
        val fetchBytes = fetch.sumOf { it.first.file.fileSizeBytes }
        checkFreeSpace(plan.primary, fetchBytes)

        var doneBytes = total - fetchBytes
        val downloaded = mutableListOf<Triple<File, File, TargetState>>()
        for ((target, state) in fetch) {
            val parent = target.target.parentFile
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw InstallFailure("cannot create folder ${parent.path}")
            }
            val part = File(target.target.path + PART_SUFFIX)
            parts += part
            val base = doneBytes
            download(target.file, part) { bytes -> listener.onInstallProgress(progressId, base + bytes, total) }
            doneBytes += target.file.fileSizeBytes
            downloaded += Triple(part, target.target, state)
        }
        val moves = withContext(Dispatchers.IO) {
            // Every shared file is compared before anything moves, so a conflict leaves the folder as it was.
            for ((part, target, state) in downloaded) {
                if (state !is TargetState.Compare) continue
                if (!sameContent(part, target)) throw InstallFailure(conflictReason(target, state.owner))
                log("install rom ${rom.id}: ${target.path} is identical to the file of rom ${state.owner}; kept")
                part.delete()
            }
            downloaded.filter { it.third !is TargetState.Compare }.onEach { (part, target) -> moveIntoPlace(part, target) }
        }
        parts.clear()
        listener.onInstallProgress(progressId, total, total)
        record(rom, plan, installedFiles, marker, foundOnDisk = false)
        val status = if (moves.isEmpty()) STATUS_ALREADY_INSTALLED else STATUS_DONE
        return Outcome(status, null, installedFiles, rom, plan.primary, folder)
    }

    /** What an install does with one target: keep it, fetch it, fetch it to compare, or refuse. */
    private sealed interface TargetState {
        /** Already in place; [shared] when another rom's record covers it. */
        class Intact(val shared: Boolean) : TargetState
        object Missing : TargetState
        /** Another rom's file is there and RomM gives no usable hash: download, then compare bytes. */
        class Compare(val owner: Int) : TargetState
        class Conflict(val reason: String) : TargetState
    }

    /**
     * A target another recorded rom covers ([sharedOwner]) is never overwritten: it is
     * kept when it matches this rom's file (size + RomM hash; without a usable hash,
     * the downloaded bytes) and the install is refused when it differs.
     */
    private fun targetState(target: InstallTarget, others: List<InstalledRom>, own: InstalledRom?): TargetState {
        val file = target.target
        val owner = sharedOwner(file, others)
        if (owner == null || !file.isFile) return if (isIntact(target)) TargetState.Intact(shared = false) else TargetState.Missing
        val conflict = TargetState.Conflict(conflictReason(file, owner.romId))
        if (target.file.fileSizeBytes > 0 && file.length() != target.file.fileSizeBytes) return conflict
        val expected = expectedHash(target.file)
        return when {
            expected != null ->
                if (hashFile(file, expected.first).equals(expected.second, ignoreCase = true)) TargetState.Intact(shared = true) else conflict
            // Compared when this rom first recorded it.
            own != null && sharedOwner(file, listOf(own)) != null -> TargetState.Intact(shared = true)
            else -> TargetState.Compare(owner.romId)
        }
    }

    private fun conflictReason(file: File, owner: Int) =
        "${file.path} is installed for rom $owner and differs from this rom's file; not overwriting it"

    /**
     * Adds [marker] to the rom's provenance, then records it in the library. A
     * collection install that finds a rom already on disk without provenance
     * (present before collection sync; [foundOnDisk], not a file another rom's
     * record covers) also gets [Provenance.MANUAL], so collection sync never deletes it.
     */
    private fun record(rom: RomDetailDto, plan: InstallPlan, files: List<File>, marker: String, foundOnDisk: Boolean) {
        provenance?.let { markers ->
            try {
                val current = markers.markers(rom.id)
                val preExisting = marker != Provenance.MANUAL && current.isEmpty() &&
                    (foundOnDisk || store.installed(rom.id) != null)
                val next = current + marker + (if (preExisting) setOf(Provenance.MANUAL) else emptySet())
                if (next != current) markers.setMarkers(rom.id, next)
            } catch (e: Exception) {
                log("install: could not record provenance of rom ${rom.id}: ${describe(e)}")
            }
        }
        try {
            store.recordInstalled(rom, plan.primary, files)
        } catch (e: Exception) {
            log("install: could not record rom ${rom.id} locally: ${describe(e)}")
        }
    }

    private suspend fun download(file: RomFileDto, part: File, onProgress: (Long) -> Unit) {
        val response = api.downloadRomFile(file.id, file.fileName)
        if (!response.isSuccessful) {
            val detail = response.errorBody()?.use { it.string() }?.take(200)?.ifBlank { null }
            throw InstallFailure("HTTP ${response.code()}: ${detail ?: response.message()}")
        }
        val body = response.body() ?: throw InstallFailure("HTTP ${response.code()}: empty body for ${file.fileName}")
        val expected = expectedHash(file)
        val digest = expected?.let { MessageDigest.getInstance(it.first) }
        var bytes = 0L
        withContext(Dispatchers.IO) {
            // Closing the body unblocks a stalled read when the request is cancelled.
            val closeOnCancel = currentCoroutineContext().job.invokeOnCompletion { runCatching { body.close() } }
            try {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var reported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest?.update(buffer, 0, n)
                            bytes += n
                            if (bytes - reported >= PROGRESS_STEP_BYTES) {
                                reported = bytes
                                onProgress(bytes)
                            }
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                closeOnCancel.dispose()
            }
        }
        currentCoroutineContext().ensureActive()
        if (file.fileSizeBytes > 0 && bytes != file.fileSizeBytes) {
            throw InstallFailure("size mismatch for ${file.fileName}: expected ${file.fileSizeBytes}, got $bytes")
        }
        if (expected != null && digest != null) {
            val actual = digest.digest().toHex()
            if (!actual.equals(expected.second, ignoreCase = true)) {
                throw InstallFailure("${expected.third} mismatch for ${file.fileName}: expected ${expected.second}, got $actual")
            }
        }
        onProgress(bytes)
    }

    private fun isIntact(target: InstallTarget): Boolean {
        val f = target.target
        if (!f.isFile) return false
        if (target.file.fileSizeBytes > 0 && f.length() != target.file.fileSizeBytes) return false
        val expected = expectedHash(target.file) ?: return true
        return hashFile(f, expected.first).equals(expected.second, ignoreCase = true)
    }

    private fun checkFreeSpace(primary: File, needed: Long) {
        var probe: File? = primary
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val free = probe?.usableSpace ?: return
        if (free in 1 until needed) {
            throw InstallFailure("insufficient space: need $needed bytes, $free free")
        }
    }

    private suspend fun finish(request: InstallRequestDto, status: String, reason: String?, files: List<File>) {
        val trimmed = reason?.take(MAX_REASON_LENGTH)
        log("install ${request.id}: $status${trimmed?.let { " ($it)" } ?: ""}")
        report(request.id, status, trimmed)
        listener.onInstallFinished(request.id, request.romId, status, trimmed, files)
    }

    /** Ends a RomM request; a failed report is logged (RomM expires the request). */
    private suspend fun report(requestId: String, status: String, reason: String?) {
        try {
            api.reportInstall(deviceId, requestId, InstallReportRequest(status, reason?.take(MAX_REASON_LENGTH)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("request $requestId: report failed: ${describe(e)}")
        }
    }

    private fun deleteParts(parts: List<File>) {
        for (part in parts) if (part.exists() && !part.delete()) log("could not delete ${part.path}")
    }

    private fun log(message: String) = logger(message)

    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_ALREADY_INSTALLED = "already_installed"
        const val STATUS_FAILED = "failed"
        const val STATUS_NOT_INSTALLED = "not_installed"
        const val MAX_REASON_LENGTH = 500

        const val ACTION_INSTALL = "install"
        const val ACTION_UNINSTALL = "uninstall"

        /** Request id prefix of local (collection sync) installs in listener callbacks. */
        const val LOCAL_REQUEST_PREFIX = "local-"

        const val SOCKET_PATH = "/ws/socket.io"
        const val NAMESPACE = "/devices"
        const val EVENT_INSTALL_QUEUED = "install:queued"
        const val EVENT_INSTALL_CANCELLED = "install:cancelled"
        const val EVENT_COLLECTIONS_CHANGED = "collections:changed"

        private const val DRAIN_INTERVAL_MS = 60_000L
        private const val EXPORT_TIMEOUT_MS = 5 * 60_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RECONNECT_DELAY_MAX_MS = 300_000L
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 1L shl 20
        private val AUTH_REFUSALS = setOf("unauthorized", "disabled")
        private val COVER_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        /**
         * Socket.io options for RomM's `/devices` namespace. WebSocket only: RomM runs several
         * web workers without session affinity, so Engine.IO long-polling fails with
         * "Invalid session" when consecutive polls hit different workers. The Bearer
         * header goes into the WebSocket handshake through `extraHeaders`.
         */
        internal fun socketOptions(baseUrl: String, token: String, httpClient: OkHttpClient): IO.Options {
            val prefix = URI(baseUrl.trimEnd('/')).rawPath.orEmpty().trimEnd('/')
            val options = IO.Options.builder()
                .setForceNew(true)
                .setPath(prefix + SOCKET_PATH)
                .setTransports(arrayOf(WebSocket.NAME))
                .setAuth(mapOf("token" to token))
                .setExtraHeaders(mapOf("Authorization" to listOf("Bearer $token")))
                .setReconnection(true)
                .setReconnectionDelay(RECONNECT_DELAY_MS)
                .setReconnectionDelayMax(RECONNECT_DELAY_MAX_MS)
                .build()
            options.callFactory = httpClient
            options.webSocketFactory = httpClient
            return options
        }

        private fun errorMessage(arg: Any?): String? = when (arg) {
            is JSONObject -> arg.optString("message").ifBlank { arg.toString() }
            is Throwable -> arg.message ?: arg.javaClass.simpleName
            null -> null
            else -> arg.toString()
        }

        private fun requestIdOf(arg: Any?): String? =
            (arg as? JSONObject)?.opt("id")?.toString()

        /**
         * (JCA algorithm, expected hex, label) — SHA-1 preferred, MD5 as fallback; null for
         * archives, whose RomM hash covers the decompressed members, not the served bytes.
         */
        private fun expectedHash(file: RomFileDto): Triple<String, String, String>? {
            RomFileVerification.expectedSha1(file.fileName, file.sha1Hash)?.let { return Triple("SHA-1", it, "sha1") }
            RomFileVerification.expectedMd5(file.fileName, file.md5Hash)?.let { return Triple("MD5", it, "md5") }
            return null
        }

        private fun hashFile(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** Same length and SHA-1. */
        private fun sameContent(a: File, b: File): Boolean =
            a.length() == b.length() && hashFile(a, "SHA-1") == hashFile(b, "SHA-1")

        private fun moveIntoPlace(part: File, target: File) {
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun describe(e: Throwable): String = when (e) {
            is InstallFailure -> e.message.orEmpty()
            is HttpException -> {
                val detail = runCatching { e.response()?.errorBody()?.string() }.getOrNull()?.take(200)?.ifBlank { null }
                "HTTP ${e.code()}: ${detail ?: e.message()}"
            }
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }.take(MAX_REASON_LENGTH)
    }
}

/** A request that cannot be installed; its message becomes the reported reason. */
internal class InstallFailure(message: String) : Exception(message)

data class InstallTarget(val file: RomFileDto, val target: File)

/** [primary] is the single rom file, or the game folder for multi-file roms. */
data class InstallPlan(val primary: File, val targets: List<InstallTarget>)

/**
 * Where each requested file of [rom] goes under [platformDir]. A single file at
 * the top of the rom goes straight into the platform folder; anything else goes
 * into `<fs_name>/` keeping the sub-paths RomM reports, renamed `<fs_name>.m3u/`
 * when the set contains an `.m3u` (ES-DE and Cocoon then show one entry).
 * [gameDirName] replaces that folder name ([Ps3Folders]).
 */
fun planInstall(rom: RomDetailDto, fileIds: List<Int>, platformDir: File, gameDirName: String? = null): InstallPlan {
    val byId = rom.files.associateBy { it.id }
    val files = if (fileIds.isEmpty()) {
        rom.files
    } else {
        fileIds.map { id -> byId[id] ?: throw InstallFailure("file $id is not part of rom ${rom.id}") }
    }
    if (files.isEmpty()) throw InstallFailure("rom ${rom.id} has no files to install")

    val subDirs = files.map { gameSubPath(rom, it) }
    val multi = files.size > 1 || subDirs.any { it.isNotEmpty() }
    if (!multi) {
        val target = File(platformDir, safeName(files.single().fileName))
        return InstallPlan(target, listOf(InstallTarget(files.single(), target)))
    }

    val baseName = safeName(rom.fsName)
    val hasM3u = files.any { it.fileName.endsWith(".m3u", ignoreCase = true) }
    val dirName = gameDirName?.let(::safeName)
        ?: if (hasM3u && !baseName.endsWith(".m3u", ignoreCase = true)) "$baseName.m3u" else baseName
    val gameDir = File(platformDir, dirName)
    val targets = files.zip(subDirs).map { (file, sub) ->
        val dir = if (sub.isEmpty()) gameDir else File(gameDir, sub)
        InstallTarget(file, File(dir, safeName(file.fileName)))
    }
    if (targets.map { it.target.path }.toSet().size != targets.size) {
        throw InstallFailure("rom ${rom.id} has duplicate file paths")
    }
    return InstallPlan(gameDir, targets)
}

/** Sub-path of [file] inside its game folder (`file_path` minus `<fs_path>/<fs_name>`), or "". */
internal fun gameSubPath(rom: RomDetailDto, file: RomFileDto): String {
    val path = file.filePath?.replace('\\', '/')?.trim('/').orEmpty()
    if (path.isEmpty()) return ""
    val fsPath = rom.fsPath?.replace('\\', '/')?.trim('/')
    val segments: List<String> = if (fsPath != null) {
        val base = if (fsPath.isEmpty()) rom.fsName else "$fsPath/${rom.fsName}"
        when {
            path == base -> emptyList()
            path.startsWith("$base/") -> path.removePrefix("$base/").split('/')
            else -> emptyList()
        }
    } else {
        // Without fs_path, the game folder is the last segment named like the rom.
        val all = path.split('/')
        val index = all.lastIndexOf(rom.fsName)
        if (index >= 0) all.drop(index + 1) else emptyList()
    }
    segments.forEach { safeName(it) }
    return segments.joinToString(File.separator)
}

/**
 * The first of [installs] whose record covers [file]: the same path, a path inside
 * its game folder, or — when [file] is a folder — one of its files inside [file].
 */
fun sharedOwner(file: File, installs: List<InstalledRom>): InstalledRom? {
    val path = file.absoluteFile.normalize()
    return installs.firstOrNull { rom ->
        (rom.files + rom.primary).any { recorded ->
            val other = recorded.absoluteFile.normalize()
            path.startsWith(other) || other.startsWith(path)
        }
    }
}

private fun safeName(name: String): String {
    if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
        throw InstallFailure("unsafe path component '$name'")
    }
    return name
}
