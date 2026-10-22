package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.download.PlatformFolders
import es.davidrg.rommsync.core.download.RomFileVerification
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.install.planInstall
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.RomDto
import es.davidrg.rommsync.core.remote.dto.RomFileDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Motor de descargas de la app desktop. Replica la lógica del DownloadWorker
 * de Android: fichero único → disco directo con reanudación vía Range;
 * multi-fichero (RomM zipea al vuelo con mod_zip, sin Content-Length) →
 * extracción en streaming con contador de bytes.
 *
 * Cancelación: los streams comprueban el Job de la corrutina llamante en cada
 * reporte de progreso; al cancelar se lanza CancellationException, se cierra
 * la conexión y el fichero parcial queda en disco para reanudar luego.
 */
class DownloadEngine(
    private val api: RomMApiService,
    private val romsRoot: File,
    private val cacheDir: File,
    private val library: DesktopLibrary? = null,
) {

    /** Progreso notificado durante la descarga. total=-1 → indeterminado (zip). */
    data class Progress(val bytesRead: Long, val total: Long, val speedBps: Long)

    data class Result(val message: String, val failed: Boolean = false)

    suspend fun download(
        rom: RomDto,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val fileName = rom.fileName
        // Carpeta por plataforma (override del usuario) o slug por defecto
        val folderOverride = library?.platform(rom.platformSlug ?: "")?.romsFolderOverride
        val targetDir = if (!folderOverride.isNullOrBlank()) {
            File(romsRoot, folderOverride).also { it.mkdirs() }
        } else {
            // Platforms without a launcher folder are not installable here.
            val slug = rom.platformSlug.orEmpty()
            val mapped = PlatformFolders.platformDir(romsRoot.absolutePath, slug, rom.platformFsSlug)
                ?: return@withContext Result(tr("download.unsupported_platform", slug), failed = true)
            mapped.also { it.mkdirs() }
        }

        // Multi-file roms go file by file into `<platform>/<fs_name>/` (`<fs_name>.m3u/`
        // when the set has an .m3u), the same layout RomM pushes produce.
        if (rom.files.size > 1 || rom.hasMultipleFiles) {
            return@withContext downloadGameFolder(rom, targetDir, coroutineContext[Job], onProgress)
        }

        // Fichero objetivo y parcial previo (para reanudar) se calculan ANTES
        // de la petición: el Range depende de lo que ya hay en disco.
        val target = File(targetDir, fileName)
        val existing = if (target.isFile) target.length() else 0L
        val range = if (existing > 0L) "bytes=$existing-" else null

        val response = api.downloadRom(rom.id, fileName, range)
        if (!response.isSuccessful) {
            when (response.code()) {
                416 -> {
                    // Range insatisfiable: el fichero ya está completo en disco.
                    registerInLibrary(rom, target)
                    return@withContext Result(tr("download.done_already_complete", rom.name))
                }
                else -> {
                    response.body()?.close()
                    response.raw().close()
                    return@withContext Result(tr("download.http_error", response.code()), failed = true)
                }
            }
        }
        val body = response.body() ?: return@withContext Result(tr("download.empty_response"))

        val job = coroutineContext[Job]

        // ¿Zip en streaming? (multi-fichero: sin Content-Length)
        val isZipStream = body.contentLength() == -1L
        if (isZipStream) {
            val created = extractZipStream(body, targetDir, job, onProgress)
            registerInLibrary(rom, targetDir)
            return@withContext Result(tr("download.done_extracted", rom.name, created.size))
        }

        // Fichero único — con reanudación: si hay un parcial de una descarga
        // previa (fallida o cancelada) se pide Range y se concatena (206);
        // si el servidor ignora el Range (200) se empieza de cero.
        val resumed = existing > 0L && response.code() == 206
        if (existing > 0L && !resumed && target.isFile) {
            target.delete() // 200 ignorando Range: descartar el parcial
        }
        // En 206 Content-Length es lo que QUEDA, no el total.
        val total = if (resumed) existing + body.contentLength() else body.contentLength()
        val baseBytes = if (resumed) existing else 0L
        val counting = CountingInputStream(body.byteStream(), baseBytes) { read, speed ->
            if (job?.isActive == false) throw CancellationException("Descarga cancelada")
            onProgress(Progress(read, total, speed))
        }
        FileOutputStream(target, resumed).use { out -> counting.copyTo(out) }

        // Verificación de integridad contra el hash del servidor (mismo
        // criterio que Android: algoritmo por longitud hex; desconocida = skip).
        val hashError = verifyHash(rom, target)
        if (hashError != null) {
            target.delete() // corrupto: no dejarlo en la biblioteca
            return@withContext Result(hashError, failed = true)
        }

        registerInLibrary(rom, target)
        return@withContext Result(tr("download.done_path", rom.name, target.absolutePath))
    }

    /** Multi-file rom: each RomFile to its place under the game folder, resumable `.part` + size/hash check. */
    private suspend fun downloadGameFolder(
        rom: RomDto,
        platformDir: File,
        job: Job?,
        onProgress: (Progress) -> Unit,
    ): Result {
        val plan = try {
            planInstall(api.getRomDetail(rom.id), emptyList(), platformDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(tr("common.error_detail", e.message), failed = true)
        }
        val total = plan.targets.sumOf { it.file.fileSizeBytes }
        var done = 0L
        for (target in plan.targets) {
            val file = target.file
            val dest = target.target
            if (dest.isFile && (file.fileSizeBytes <= 0 || dest.length() == file.fileSizeBytes) && hashMatches(file, dest) != false) {
                done += file.fileSizeBytes
                continue
            }
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            val existing = if (part.isFile) part.length() else 0L
            val response = api.downloadRomFile(file.id, file.fileName, if (existing > 0) "bytes=$existing-" else null)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return Result(tr("download.http_error", response.code()), failed = true)
            }
            val body = response.body() ?: return Result(tr("download.empty_response"), failed = true)
            val resumed = existing > 0 && response.code() == 206
            val base = done + if (resumed) existing else 0L
            val counting = CountingInputStream(body.byteStream(), base) { read, speed ->
                if (job?.isActive == false) throw CancellationException("Descarga cancelada")
                onProgress(Progress(read, total, speed))
            }
            counting.use { input -> FileOutputStream(part, resumed).use { out -> input.copyTo(out) } }
            if ((file.fileSizeBytes > 0 && part.length() != file.fileSizeBytes) || hashMatches(file, part) == false) {
                part.delete() // corrupt: never leave it for the next resume
                return Result(tr("desktop.download.file_corrupt", file.fileName), failed = true)
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            done += file.fileSizeBytes
        }
        library?.upsertRom(
            DesktopLibrary.RomEntry(
                romId = rom.id,
                name = rom.name,
                fileName = rom.fileName,
                platformSlug = rom.platformSlug ?: "unknown",
                localPath = plan.primary.absolutePath,
            ),
        )
        // Downloaded by hand: collection sync must never remove it.
        library?.markManual(rom.id)
        return Result(tr("download.done_path", rom.name, plan.primary.absolutePath))
    }

    /** sha1 (else md5) check of [local] against [file]; null when RomM reports no usable hash (none, or an archive). */
    private fun hashMatches(file: RomFileDto, local: File): Boolean? {
        val (algo, expected) = RomFileVerification.expectedSha1(file.fileName, file.sha1Hash)?.let { "SHA-1" to it }
            ?: RomFileVerification.expectedMd5(file.fileName, file.md5Hash)?.let { "MD5" to it }
            ?: return null
        val md = java.security.MessageDigest.getInstance(algo)
        java.security.DigestInputStream(local.inputStream(), md).use { dis ->
            val buf = ByteArray(64 * 1024)
            while (dis.read(buf) >= 0) { /* consume */ }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    /**
     * Compara el hash del fichero descargado con el que reporta el servidor
     * (rom.files[0].hash). Devuelve un mensaje de error si NO coincide, o
     * null si está OK o no hay hash verificable.
     */
    private fun verifyHash(rom: RomDto, target: File): String? {
        // Only a single-file rom downloads as that exact file; multi-file roms arrive zipped.
        val single = rom.files.singleOrNull() ?: return null
        val expected = RomFileVerification.expectedSha1(single.fileName, single.sha1Hash) ?: return null
        val algo = when (expected.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            64 -> "SHA-256"
            else -> return null // algoritmo desconocido: no verificar, no fallar
        }
        val actual = runCatching {
            java.security.MessageDigest.getInstance(algo).let { md ->
                java.security.DigestInputStream(target.inputStream(), md).use { dis ->
                    val buf = ByteArray(64 * 1024)
                    while (dis.read(buf) >= 0) { /* consume */ }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return null
        return if (!actual.equals(expected, ignoreCase = true)) {
            tr("download.hash_mismatch", expected.take(8), actual.take(8))
        } else {
            null
        }
    }

    private fun registerInLibrary(rom: RomDto, localPath: File) {
        library?.upsertRom(
            DesktopLibrary.RomEntry(
                romId = rom.id,
                name = rom.name,
                fileName = rom.fileName,
                platformSlug = rom.platformSlug ?: "unknown",
                localPath = localPath.absolutePath,
            ),
        )
        library?.markManual(rom.id)
    }

    private fun extractZipStream(
        body: okhttp3.ResponseBody,
        targetDir: File,
        job: Job?,
        onProgress: (Progress) -> Unit,
    ): List<File> {
        val createdFiles = mutableListOf<File>()
        val counting = CountingInputStream(body.byteStream(), 0L) { read, speed ->
            if (job?.isActive == false) throw CancellationException("Descarga cancelada")
            onProgress(Progress(read, -1L, speed))
        }
        try {
            ZipInputStream(counting).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        zipIn.closeEntry(); entry = zipIn.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                        createdFiles.add(outFile)
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            createdFiles.forEach { if (it.exists()) it.delete() }
            throw e
        }
        return createdFiles
    }
}

/**
 * InputStream que cuenta bytes leídos y notifica progreso con velocidad EMA
 * (misma suavización que el DownloadWorker de Android para que el número no
 * oscile con las ráfagas de TCP/writeback de disco).
 */
private class CountingInputStream(
    private val delegate: java.io.InputStream,
    /** Bytes ya en disco (reanudación): el contador arranca desde aquí. */
    private val baseBytes: Long,
    private val onReport: (bytesRead: Long, speed: Long) -> Unit,
) : java.io.FilterInputStream(delegate) {

    private var count = baseBytes
    private var emaBps = 0.0
    private var lastReport = baseBytes
    private var lastReportAt = System.currentTimeMillis()

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) tally(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) tally(n.toLong())
        return n
    }

    private fun tally(n: Long) {
        count += n
        val now = System.currentTimeMillis()
        val elapsed = now - lastReportAt
        if (elapsed >= 500) {
            val windowBps = (count - lastReport) * 1000.0 / elapsed
            // EMA con alfa de la ventana: ~2s de memoria
            emaBps = if (emaBps == 0.0) windowBps else emaBps * 0.75 + windowBps * 0.25
            lastReport = count
            lastReportAt = now
            onReport(count, emaBps.toLong())
        }
    }
}
