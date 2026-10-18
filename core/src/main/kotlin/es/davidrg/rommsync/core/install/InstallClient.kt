package es.davidrg.rommsync.core.install

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
import okhttp3.OkHttpClient
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

/** Where the app records ROMs that are present locally (feeds the library and save sync). */
interface InstalledRomStore {
    /** [primaryFile] is the rom file, or the game folder for multi-file roms; [files] every file written. */
    fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>)
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
}

/**
 * Push-install pipeline for a paired RomM device.
 *
 * Holds a socket.io connection to RomM's `/devices` namespace (path
 * `/ws/socket.io`, handshake `auth: {token}`), which keeps the device online
 * and delivers `install:queued` / `install:cancelled`. A drain (claim + install
 * every taken request, one download at a time) runs on connect, on
 * `install:queued`, on [drainNow], and every 60 s regardless of the socket,
 * since a claim alone also keeps the device online for 90 s.
 *
 * Files are streamed to `<target>.part`, checked against size and hash, then
 * renamed into `<romsRoot>/<platform folder>/` (multi-file roms go into a
 * `<fs_name>/` game folder, `<fs_name>.m3u/` when the set has an `.m3u`).
 * Nothing is ever deleted except this client's own `.part` files.
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
) {
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
     * Claims every pending request and installs each one in turn. Leaves requests
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
                    log("install ${request.id}: cancelled before start")
                    continue
                }
                if (!downloadsAllowed()) {
                    log("downloads paused; remaining requests stay queued")
                    break
                }
                runInstall(request, root)
            }
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
        s.connect()
        return s
    }

    /** Namespace-level refusals destroy the socket without a manager reconnect; retry it on the drain tick. */
    private fun reconnectIfIdle() {
        val s = synchronized(lock) { socket } ?: return
        if (!authRejected && !s.connected() && !s.isActive) s.connect()
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
            performInstall(request, root, parts).also { finish(request, it.status, it.reason, it.files) }
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
        enqueueExport(request, outcome)
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
    private fun enqueueExport(request: InstallRequestDto, outcome: Outcome) {
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
        queue.trySend(ExportJob(request.id, rom, primary, folder, target))
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
        request: InstallRequestDto,
        root: String,
        parts: MutableList<File>,
    ): Outcome {
        val rom = api.getRomDetail(request.romId)
        val folder = PlatformFolders.folderFor(rom.platformSlug)
            ?: return Outcome(STATUS_FAILED, "unsupported platform ${rom.platformSlug}", emptyList())
        val plan = planInstall(rom, request.fileIds, File(root, folder))
        val installedFiles = plan.targets.map { it.target }

        val intact = withContext(Dispatchers.IO) { plan.targets.map { isIntact(it) } }
        if (intact.all { it }) {
            record(rom, plan, installedFiles)
            return Outcome(STATUS_ALREADY_INSTALLED, null, installedFiles, rom, plan.primary, folder)
        }

        val total = plan.targets.sumOf { it.file.fileSizeBytes }
        val missing = plan.targets.filterIndexed { i, _ -> !intact[i] }
        checkFreeSpace(plan.primary, missing.sumOf { it.file.fileSizeBytes })

        var doneBytes = total - missing.sumOf { it.file.fileSizeBytes }
        val downloaded = mutableListOf<Pair<File, File>>()
        for (target in missing) {
            val parent = target.target.parentFile
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw InstallFailure("cannot create folder ${parent.path}")
            }
            val part = File(target.target.path + PART_SUFFIX)
            parts += part
            val base = doneBytes
            download(target.file, part) { bytes -> listener.onInstallProgress(request.id, base + bytes, total) }
            doneBytes += target.file.fileSizeBytes
            downloaded += part to target.target
        }
        withContext(Dispatchers.IO) {
            for ((part, target) in downloaded) moveIntoPlace(part, target)
        }
        parts.clear()
        listener.onInstallProgress(request.id, total, total)
        record(rom, plan, installedFiles)
        return Outcome(STATUS_DONE, null, installedFiles, rom, plan.primary, folder)
    }

    private fun record(rom: RomDetailDto, plan: InstallPlan, files: List<File>) {
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
        try {
            api.reportInstall(deviceId, request.id, InstallReportRequest(status, trimmed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("install ${request.id}: report failed: ${describe(e)}")
        }
        listener.onInstallFinished(request.id, request.romId, status, trimmed, files)
    }

    private fun deleteParts(parts: List<File>) {
        for (part in parts) if (part.exists() && !part.delete()) log("could not delete ${part.path}")
    }

    private fun log(message: String) = logger(message)

    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_ALREADY_INSTALLED = "already_installed"
        const val STATUS_FAILED = "failed"
        const val MAX_REASON_LENGTH = 500

        const val SOCKET_PATH = "/ws/socket.io"
        const val NAMESPACE = "/devices"
        const val EVENT_INSTALL_QUEUED = "install:queued"
        const val EVENT_INSTALL_CANCELLED = "install:cancelled"

        private const val DRAIN_INTERVAL_MS = 60_000L
        private const val EXPORT_TIMEOUT_MS = 5 * 60_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RECONNECT_DELAY_MAX_MS = 300_000L
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 1L shl 20
        private val AUTH_REFUSALS = setOf("unauthorized", "disabled")

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
 */
fun planInstall(rom: RomDetailDto, fileIds: List<Int>, platformDir: File): InstallPlan {
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
    val dirName = if (hasM3u && !baseName.endsWith(".m3u", ignoreCase = true)) "$baseName.m3u" else baseName
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
private fun gameSubPath(rom: RomDetailDto, file: RomFileDto): String {
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

private fun safeName(name: String): String {
    if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
        throw InstallFailure("unsafe path component '$name'")
    }
    return name
}
