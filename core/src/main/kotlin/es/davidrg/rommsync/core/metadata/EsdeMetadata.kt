package es.davidrg.rommsync.core.metadata

import es.davidrg.rommsync.core.install.planInstall
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ES-DE image extensions, preferred first (images are decoded by content). */
private val IMAGE = listOf("png", "jpg")

/**
 * ES-DE media folders under `<MediaDirectory>/<system>/` and the file
 * extensions ES-DE looks for in each, preferred first.
 */
enum class EsdeMediaType(val folder: String, val extensions: List<String>) {
    COVERS("covers", IMAGE),
    SCREENSHOTS("screenshots", IMAGE),
    TITLESCREENS("titlescreens", IMAGE),
    MARQUEES("marquees", IMAGE),
    FANART("fanart", IMAGE),
    BOXES_3D("3dboxes", IMAGE),
    BACKCOVERS("backcovers", IMAGE),
    PHYSICAL_MEDIA("physicalmedia", IMAGE),
    MIXIMAGES("miximages", IMAGE),
    VIDEOS("videos", listOf("mp4", "mkv", "avi", "mov", "wmv")),
    MANUALS("manuals", listOf("pdf")),
}

/**
 * How ES-DE addresses one game: [path] for `<path>` in the gamelist, and the
 * media file `<MediaDirectory>/<system>/<type>/<mediaSubPath><stem>.<ext>`.
 * ES-DE mirrors ROM sub-folders in the media tree and names media after the
 * entry's file name minus its last extension, also for directories
 * interpreted as files (`Final Fantasy VII.m3u/` → `Final Fantasy VII.png`).
 */
data class EsdeGameRef(val path: String, val mediaSubPath: String, val stem: String) {
    fun mediaFile(mediaDir: File, systemFolder: String, type: EsdeMediaType, ext: String): File =
        File(File(File(mediaDir, systemFolder), type.folder), "$mediaSubPath$stem.$ext")
}

/** A media download candidate; [url] is absolute, [extension] known from the URL path or null. */
data class EsdeMediaSource(val url: String, val extension: String?)

object EsdeMetadata {

    /** Launch files ES-DE should list for a plain multi-file game folder, preferred first. */
    private val LAUNCH_EXTENSIONS = listOf("m3u", "cue", "gdi", "ccd", "mds", "cdi", "chd", "iso", "rvz", "cso", "pbp")

    /**
     * The entry ES-DE shows for a rom installed at [primary] (a file, or the game
     * folder of a multi-file rom) directly inside its system folder. A folder
     * named `*.m3u`, or named exactly like a file it contains, is a directory
     * interpreted as a file. A plain folder is browsed by ES-DE, so the entry is
     * the launch file inside it (by [LAUNCH_EXTENSIONS], else the only/largest
     * top-level file); null when that folder is not on disk or holds no file.
     */
    fun gameRef(primary: File): EsdeGameRef? {
        val name = primary.name
        val isDir = primary.isDirectory
        if (!isDir) {
            // A missing game folder is assumed to be a file unless it is an .m3u set.
            return EsdeGameRef("./$name", "", stemOf(name))
        }
        val dirAsFile = name.endsWith(".m3u", ignoreCase = true) || File(primary, name).isFile
        if (dirAsFile) return EsdeGameRef("./$name", "", stemOf(name))
        val files = primary.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        if (files.isEmpty()) return null
        val launch = LAUNCH_EXTENSIONS.firstNotNullOfOrNull { ext ->
            files.filter { it.extension.equals(ext, ignoreCase = true) }.minByOrNull { it.name }
        } ?: files.maxByOrNull { it.length() }!!
        return EsdeGameRef("./$name/${launch.name}", "$name/", stemOf(launch.name))
    }

    /**
     * Where [rom] lives (or would live) under [platformDir]: the install plan's
     * primary when RomM listed its files, else derived from `fs_name` (list
     * items carry no files): an existing `<fs_name>.m3u/` set, the `<fs_name>/`
     * folder for multi-file roms, or `<fs_name>` itself.
     */
    fun primaryFor(rom: RomDetailDto, platformDir: File): File? {
        if (rom.files.isNotEmpty()) {
            return runCatching { planInstall(rom, emptyList(), platformDir).primary }.getOrNull()
        }
        if (rom.fsName.isBlank() || rom.fsName.contains('/') || rom.fsName.contains('\\')) return null
        val m3uDir = File(platformDir, if (rom.fsName.endsWith(".m3u", true)) rom.fsName else "${rom.fsName}.m3u")
        if (m3uDir.isDirectory) return m3uDir
        return File(platformDir, rom.fsName)
    }

    /** ES-DE strips only the last extension (`Celeste.p8.png` → `Celeste.p8`). */
    fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ── Gamelist fields ─────────────────────────────────────────────────

    /** The `<game>` for [rom] at [ref]: RomM text metadata mapped to ES-DE elements. */
    fun gameEntry(rom: RomDetailDto, ref: EsdeGameRef): EsdeGameEntry {
        val fields = LinkedHashMap<String, String>()
        fun put(tag: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[tag] = it }
        }
        put("name", rom.name)
        put("desc", rom.summary)
        put("rating", rating(rom))
        put("releasedate", releaseDate(rom))
        put("developer", firstList(rom, "developers")?.joinToString(", "))
        put("publisher", firstList(rom, "publishers")?.joinToString(", "))
        put("genre", firstList(rom, "genres")?.joinToString(", "))
        put("players", players(rom))
        return EsdeGameEntry(ref.path, fields)
    }

    private fun providers(rom: RomDetailDto): List<Map<String, Any?>> = listOfNotNull(
        rom.metadatum, rom.igdbMetadata, rom.ssMetadata, rom.launchboxMetadata, rom.gamelistMetadata,
    )

    private fun firstList(rom: RomDetailDto, key: String): List<String>? = providers(rom).firstNotNullOfOrNull { meta ->
        (meta[key] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }?.distinct()?.takeIf { it.isNotEmpty() }
    }

    /** ES-DE rating is 0–1. */
    private fun rating(rom: RomDetailDto): String? {
        val value = number(rom.metadatum?.get("average_rating"))?.div(100.0)
            ?: number(rom.igdbMetadata?.get("total_rating"))?.div(100.0)
            ?: number(rom.launchboxMetadata?.get("community_rating"))?.div(5.0)
            ?: number(rom.gamelistMetadata?.get("rating"))
            ?: return null
        if (value <= 0.0) return null
        return String.format(Locale.US, "%.2f", value.coerceIn(0.0, 1.0)).trimEnd('0').trimEnd('.')
    }

    /** ES-DE `YYYYMMDDT000000`; RomM's merged date is epoch ms, provider dates epoch seconds. */
    private fun releaseDate(rom: RomDetailDto): String? {
        val epochMs = number(rom.metadatum?.get("first_release_date"))?.let { epochToMs(it) }
            ?: listOf(rom.igdbMetadata, rom.ssMetadata, rom.launchboxMetadata)
                .firstNotNullOfOrNull { number(it?.get("first_release_date")) }?.let { epochToMs(it) }
        if (epochMs != null) {
            return SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMs))
                .let { it.substring(0, 9) + "000000" }
        }
        // gamelist imports keep ES-DE's own format.
        return (rom.gamelistMetadata?.get("first_release_date") as? String)
            ?.takeIf { Regex("""\d{8}T\d{6}""").matches(it) }
    }

    private fun epochToMs(value: Double): Long? {
        if (value <= 0) return null
        return if (value > 100_000_000_000.0) value.toLong() else (value * 1000).toLong()
    }

    private fun players(rom: RomDetailDto): String? {
        listOf(rom.metadatum, rom.ssMetadata, rom.igdbMetadata, rom.gamelistMetadata).forEach { meta ->
            val raw = meta?.get("player_count") ?: return@forEach
            val text = when (raw) {
                is Number -> raw.toInt().takeIf { it > 0 }?.toString()
                is String -> raw.trim().takeIf { it.isNotEmpty() && it != "0" }
                else -> null
            }
            if (text != null) return text
        }
        return number(rom.launchboxMetadata?.get("max_players"))?.toInt()?.takeIf { it > 0 }?.toString()
    }

    private fun number(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }

    // ── Media sources ───────────────────────────────────────────────────

    /**
     * Download candidates per media type, best first: media RomM stores itself
     * (served from `<baseUrl>/assets/romm/resources/`, needs the Bearer token),
     * then the provider URLs RomM scraped (ScreenScraper, LaunchBox, gamelist).
     */
    fun mediaSources(rom: RomDetailDto, baseUrl: String): Map<EsdeMediaType, List<EsdeMediaSource>> {
        val ss = rom.ssMetadata.orEmpty()
        val lb = rom.launchboxMetadata.orEmpty()
        val gl = rom.gamelistMetadata.orEmpty()
        fun server(path: Any?): String? = (path as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { serverUrl(baseUrl, it) }
        fun remote(url: Any?): String? = (url as? String)?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        fun lbImage(vararg types: String): String? {
            val images = (lb["images"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            return types.firstNotNullOfOrNull { type -> images.firstOrNull { it["type"] == type }?.get("url") }?.let(::remote)
        }

        val urls: Map<EsdeMediaType, List<String?>> = mapOf(
            EsdeMediaType.COVERS to listOf(
                server(rom.pathCoverLarge), server(ss["box2d_path"]), server(lb["box2d_path"]),
                remote(rom.urlCover), remote(ss["box2d_url"]), remote(lb["box2d_url"]),
                remote(gl["box2d_url"]), remote(gl["image_url"]),
            ),
            EsdeMediaType.SCREENSHOTS to listOf(
                server(rom.mergedScreenshots?.firstOrNull()), remote(ss["screenshot_url"]),
                lbImage("Screenshot - Gameplay"), remote(gl["screenshot_url"]),
            ),
            EsdeMediaType.TITLESCREENS to listOf(
                server(ss["title_screen_path"]), remote(ss["title_screen_url"]),
                lbImage("Screenshot - Game Title"), remote(gl["title_screen_url"]),
            ),
            // ES-DE's "marquees" are wheel/clear logos.
            EsdeMediaType.MARQUEES to listOf(
                server(ss["logo_path"]), remote(ss["logo_url"]), lbImage("Clear Logo"),
                remote(gl["marquee_url"]),
            ),
            EsdeMediaType.FANART to listOf(
                server(ss["fanart_path"]), remote(ss["fanart_url"]), lbImage("Fanart - Background"),
                remote(gl["fanart_url"]),
            ),
            EsdeMediaType.BOXES_3D to listOf(
                server(ss["box3d_path"]), remote(ss["box3d_url"]), remote(lb["box3d_url"]),
                lbImage("Box - 3D"), remote(gl["box3d_url"]),
            ),
            EsdeMediaType.BACKCOVERS to listOf(
                server(ss["box2d_back_path"]), remote(ss["box2d_back_url"]), remote(lb["box2d_back_url"]),
                lbImage("Box - Back"), remote(gl["box2d_back_url"]),
            ),
            EsdeMediaType.PHYSICAL_MEDIA to listOf(
                server(ss["physical_path"]), remote(ss["physical_url"]),
                lbImage("Cart - Front", "Disc"), remote(gl["physical_url"]),
            ),
            EsdeMediaType.MIXIMAGES to listOf(
                server(ss["miximage_path"]), server(ss["miximage_v2_path"]), remote(ss["miximage_url"]),
                remote(ss["miximage_v2_url"]), remote(gl["miximage_url"]),
            ),
            EsdeMediaType.VIDEOS to listOf(
                server(rom.pathVideo), server(ss["video_normalized_path"]), server(ss["video_path"]),
                remote(ss["video_normalized_url"]), remote(ss["video_url"]), remote(gl["video_url"]),
            ),
            EsdeMediaType.MANUALS to listOf(
                server(rom.pathManual), remote(rom.urlManual), remote(ss["manual_url"]), remote(gl["manual_url"]),
            ),
        )
        return urls.mapValues { (type, list) ->
            list.filterNotNull().distinct().map { EsdeMediaSource(it, extensionOf(it, type)) }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * Absolute URL of a RomM-served asset: `/…` paths are server-relative
     * (`/assets/romm/resources/…`), bare paths are resource-relative
     * (`roms/<platform>/<rom>/…`, as in `path_manual` and provider `*_path`).
     */
    fun serverUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trimEnd('/')
        return if (path.startsWith("/")) base + path else "$base/assets/romm/resources/$path"
    }

    /** Extension from the URL path when ES-DE accepts it for [type] (`jpeg` → `jpg`), else null. */
    fun extensionOf(url: String, type: EsdeMediaType): String? {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (!path.contains('.')) return null
        val ext = path.substringAfterLast('.').lowercase(Locale.ROOT).let { if (it == "jpeg") "jpg" else it }
        return ext.takeIf { it in type.extensions }
    }

    /** Extension for a response without a usable URL extension. */
    fun extensionForContentType(contentType: String?, type: EsdeMediaType): String {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val fromType = when (ct) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png" -> "png"
            "video/mp4" -> "mp4"
            "video/x-matroska", "video/webm" -> "mkv"
            "video/x-msvideo", "video/avi" -> "avi"
            "video/quicktime" -> "mov"
            "video/x-ms-wmv" -> "wmv"
            "application/pdf" -> "pdf"
            else -> null
        }
        // ES-DE decodes images by content, so other image formats still load under the preferred name.
        return fromType?.takeIf { it in type.extensions } ?: type.extensions.first()
    }
}
