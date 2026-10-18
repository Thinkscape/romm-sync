package es.davidrg.rommsync.core.metadata

import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Where ES-DE (or Cocoon's ES-DE integration) reads gamelists and media. */
data class EsdeTarget(val esdeDir: File, val mediaDir: File)

/**
 * Writes RomM's scraped media and metadata for one rom in the layout ES-DE
 * reads without scraping: media by file-name convention
 * (`<mediaDir>/<system>/<type>/<sub path><stem>.<ext>`, see [EsdeGameRef]) and
 * the `<game>` entry in `<esdeDir>/gamelists/<system>/gamelist.xml`.
 *
 * Shared by push installs and the manual export screens. Never throws except
 * on cancellation: per-item problems end up in [Result.failures].
 */
class EsdeMediaExporter(
    httpClient: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val logger: (String) -> Unit = {},
) {
    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val serverHost: String? = baseUrl.toHttpUrlOrNull()?.host

    data class Options(
        val mediaTypes: Set<EsdeMediaType> = EsdeMediaType.entries.toSet(),
        /** false: the caller batches [Result.entry] into the gamelist itself. */
        val writeGamelist: Boolean = true,
    )

    data class Result(
        /** Media downloaded by this call. */
        val written: Map<EsdeMediaType, File> = emptyMap(),
        /** Media already on disk (same size as RomM's, or another accepted extension). */
        val present: Map<EsdeMediaType, File> = emptyMap(),
        val failures: List<String> = emptyList(),
        /** The gamelist entry for the rom; null when the game could not be addressed. */
        val entry: EsdeGameEntry? = null,
        val gamelistUpdated: Boolean = false,
    ) {
        val files: Map<EsdeMediaType, File> get() = present + written
    }

    /**
     * Exports [rom] installed at [primaryFile] (rom file or game folder, directly
     * inside the `systemFolder` ROM folder).
     */
    suspend fun exportFor(
        rom: RomDetailDto,
        primaryFile: File,
        systemFolder: String,
        esdeDir: File,
        mediaDir: File,
        options: Options = Options(),
    ): Result = withContext(Dispatchers.IO) {
        val ref = EsdeMetadata.gameRef(primaryFile)
            ?: return@withContext Result(failures = listOf("no launchable file in ${primaryFile.path}"))
        val written = LinkedHashMap<EsdeMediaType, File>()
        val present = LinkedHashMap<EsdeMediaType, File>()
        val failures = mutableListOf<String>()
        val deadHosts = mutableSetOf<String>()

        val sources = EsdeMetadata.mediaSources(rom, baseUrl)
        for (type in EsdeMediaType.entries) {
            if (type !in options.mediaTypes) continue
            val candidates = sources[type] ?: continue
            when (val outcome = fetchMedia(type, candidates, ref, systemFolder, mediaDir, deadHosts)) {
                is MediaOutcome.Written -> written[type] = outcome.file
                is MediaOutcome.Present -> present[type] = outcome.file
                is MediaOutcome.Failed -> failures += "${type.folder}: ${outcome.reason}"
            }
        }

        val entry = EsdeMetadata.gameEntry(rom, ref)
        var gamelistUpdated = false
        if (options.writeGamelist) {
            try {
                EsdeGamelist.upsertFile(gamelistFile(esdeDir, systemFolder), listOf(entry))
                gamelistUpdated = true
            } catch (e: Exception) {
                failures += "gamelist: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        failures.forEach { logger("media: rom ${rom.id}: $it") }
        Result(written, present, failures, entry, gamelistUpdated)
    }

    private sealed class MediaOutcome {
        class Written(val file: File) : MediaOutcome()
        class Present(val file: File) : MediaOutcome()
        class Failed(val reason: String) : MediaOutcome()
    }

    /** Tries [candidates] in order until one is present or downloaded. */
    private suspend fun fetchMedia(
        type: EsdeMediaType,
        candidates: List<EsdeMediaSource>,
        ref: EsdeGameRef,
        systemFolder: String,
        mediaDir: File,
        deadHosts: MutableSet<String>,
    ): MediaOutcome {
        val existing = type.extensions.map { ref.mediaFile(mediaDir, systemFolder, type, it) }.filter { it.isFile }
        var lastError = "no source"
        for (source in candidates) {
            currentCoroutineContext().ensureActive()
            // Media already present under another accepted name (user scrape, earlier export) is kept.
            if (existing.isNotEmpty() && (source.extension == null || existing.none { it.extension == source.extension })) {
                return MediaOutcome.Present(existing.first())
            }
            val url = source.url.toHttpUrlOrNull() ?: run { lastError = "bad url ${source.url}"; null } ?: continue
            if (url.host in deadHosts) continue
            val request = Request.Builder().url(url).apply {
                // The token only goes to the RomM server, never to scraper hosts.
                if (url.host == serverHost) header("Authorization", "Bearer $token")
            }.build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code} for ${url.host}"
                        return@use null
                    }
                    val body = response.body ?: run { lastError = "empty body from ${url.host}"; return@use null }
                    val ext = source.extension ?: EsdeMetadata.extensionForContentType(body.contentType()?.toString(), type)
                    val target = ref.mediaFile(mediaDir, systemFolder, type, ext)
                    val length = body.contentLength()
                    if (target.isFile && (length < 0 || target.length() == length)) {
                        return MediaOutcome.Present(target)
                    }
                    val dir = target.parentFile
                    if (!dir.isDirectory && !dir.mkdirs()) {
                        return MediaOutcome.Failed("cannot create folder ${dir.path}")
                    }
                    val part = File(target.path + ".part")
                    try {
                        var bytes = 0L
                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    bytes += n
                                }
                            }
                        }
                        if (bytes == 0L || (length >= 0 && bytes != length)) {
                            throw IOException("short read: $bytes of $length bytes")
                        }
                        moveIntoPlace(part, target)
                        return MediaOutcome.Written(target)
                    } finally {
                        if (part.exists()) part.delete()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedIOException) {
                // A scraper host that times out once is skipped for the rest of this rom.
                deadHosts += url.host
                lastError = "timeout from ${url.host}"
            } catch (e: IOException) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return MediaOutcome.Failed(lastError)
    }

    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun gamelistFile(esdeDir: File, systemFolder: String): File =
            File(File(File(esdeDir, "gamelists"), systemFolder), "gamelist.xml")
    }
}
