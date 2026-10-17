package es.davidrg.rommsync.core.download

/**
 * Which RomM file hashes can be compared with the bytes RomM serves.
 *
 * For archives RomM stores a composite hash of the decompressed members
 * (server `roms_handler.py` `ARCHIVE_READERS` / `_hash_archive_entries`), while
 * `/api/roms/{file_id}/files/content/…` serves the raw archive bytes. Those
 * hashes never match a download, so only the size is checked for archives.
 */
object RomFileVerification {

    /** Extensions RomM hashes by archive members (server `ARCHIVE_READERS`). */
    private val ARCHIVE_EXTENSIONS = listOf(
        ".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".7z", ".rar",
    )

    fun isArchive(fileName: String): Boolean {
        val name = fileName.lowercase()
        return ARCHIVE_EXTENSIONS.any { name.endsWith(it) }
    }

    /** RomM's sha1 if it describes the served bytes, else null (archive or no hash). */
    fun expectedSha1(fileName: String, sha1: String?): String? =
        sha1?.takeIf { it.isNotBlank() && !isArchive(fileName) }

    /** RomM's md5 if it describes the served bytes, else null (archive or no hash). */
    fun expectedMd5(fileName: String, md5: String?): String? =
        md5?.takeIf { it.isNotBlank() && !isArchive(fileName) }
}
