package es.davidrg.rommsync.core.download

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * Maps RomM platform slugs to the launcher folder name shared by ES-DE (Steam
 * Deck) and Cocoon (Android), e.g. `3ds` -> `n3ds`, `dc` -> `dreamcast`.
 *
 * Backed by `platform_folders.json`, derived from RomM's own ES-DE alias table
 * and filtered to folders both launchers know. A slug without a row has no
 * launcher folder: [folderFor] returns null and callers treat the platform as
 * not installable here (never fall back to `<root>/<slug>`).
 */
object PlatformFolders {

    private const val RESOURCE = "/platform_folders.json"

    private val folders: Map<String, String> by lazy {
        val stream = PlatformFolders::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing resource $RESOURCE")
        val json = stream.bufferedReader().use { it.readText() }
        val type = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        Moshi.Builder().build().adapter<Map<String, String>>(type).fromJson(json)
            ?: error("Empty resource $RESOURCE")
    }

    /** Launcher folder for a RomM slug, or null when the platform has no folder on devices. */
    fun folderFor(rommSlug: String): String? = folders[rommSlug.trim().lowercase()]

    /** `<romsRoot>/<folder>` for a RomM slug, or null when the platform is not installable here. Not created. */
    fun platformDir(romsRoot: String, rommSlug: String): File? =
        folderFor(rommSlug)?.let { File(romsRoot, it) }
}
