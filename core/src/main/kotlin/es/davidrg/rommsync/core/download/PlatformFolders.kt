package es.davidrg.rommsync.core.download

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * Maps RomM platforms to the launcher folder name shared by ES-DE (Steam
 * Deck, Android HOME) and Cocoon (Android), e.g. `3ds` -> `n3ds`, `dc` -> `dreamcast`.
 *
 * Backed by two resources:
 * - `platform_folders.json`: RomM slug -> folder, derived from RomM's own ES-DE
 *   alias table and filtered to folders the launchers know. A slug without a row
 *   has no launcher folder: [folderFor] returns null and callers treat the
 *   platform as not installable here (never fall back to `<root>/<slug>`).
 * - `platform_fs_folders.json`: RomM slug -> (platform `fs_slug` -> folder), for
 *   slugs that RomM shares across several library folders which the launchers
 *   keep apart (RomM files NAOMI, Atomiswave, Daphne and Triforce under the
 *   `arcade` slug; ES-DE has a system for each). Consulted first; an fs_slug
 *   without a row falls back to the slug's folder.
 */
object PlatformFolders {

    private const val RESOURCE = "/platform_folders.json"
    private const val FS_RESOURCE = "/platform_fs_folders.json"

    private val moshi = Moshi.Builder().build()

    private val folders: Map<String, String> by lazy {
        load(RESOURCE, Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))
    }

    private val fsFolders: Map<String, Map<String, String>> by lazy {
        val inner = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        load(FS_RESOURCE, Types.newParameterizedType(Map::class.java, String::class.java, inner))
    }

    private fun <T> load(resource: String, type: java.lang.reflect.Type): T {
        val stream = PlatformFolders::class.java.getResourceAsStream(resource)
            ?: error("Missing resource $resource")
        val json = stream.bufferedReader().use { it.readText() }
        return moshi.adapter<T>(type).fromJson(json) ?: error("Empty resource $resource")
    }

    /**
     * Launcher folder for a RomM platform, or null when it has no folder on devices.
     * [fsSlug] is the platform's library folder (`platform_fs_slug` on roms,
     * `fs_slug` on platforms); null when unknown.
     */
    fun folderFor(rommSlug: String, fsSlug: String? = null): String? {
        val slug = rommSlug.trim().lowercase()
        val base = folders[slug] ?: return null
        val fs = fsSlug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return base
        return fsFolders[slug]?.get(fs) ?: base
    }

    /** `<romsRoot>/<folder>` for a RomM platform, or null when it is not installable here. Not created. */
    fun platformDir(romsRoot: String, rommSlug: String, fsSlug: String? = null): File? =
        folderFor(rommSlug, fsSlug)?.let { File(romsRoot, it) }
}
