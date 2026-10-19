package es.davidrg.rommsync.core.metadata

import java.io.File

/**
 * Cleans up ES-DE's view of a game whose files were removed: the gamelist entry
 * and the media ES-DE would show for it (what ES-DE's own "Delete" does, minus
 * the game files). Works after the files are gone: everything is derived from
 * the recorded file list.
 */
object EsdeRemoval {

    /**
     * The node directly inside `<roms root>/<platformFolder>/` that holds [files]:
     * the rom file itself, or the game folder of a multi-file rom. Null when no
     * file lies under a folder named [platformFolder].
     */
    fun topLevel(platformFolder: String, files: List<File>): File? {
        for (file in files) {
            var node: File = file.absoluteFile
            var parent: File? = node.parentFile
            while (parent != null) {
                if (parent.name == platformFolder) return node
                node = parent
                parent = node.parentFile
            }
        }
        return null
    }

    /** Gamelist `<path>` of [top] (`./<name>`). */
    fun entryPath(top: File): String = "./${top.name}"

    /**
     * Drops [top]'s `<game>`/`<folder>` entries (and any nested under a game
     * folder) from `<esdeDir>/gamelists/<platformFolder>/gamelist.xml`.
     */
    fun removeGamelistEntries(esdeDir: File, platformFolder: String, top: File): Boolean =
        EsdeGamelist.removeFromFile(EsdeMediaExporter.gamelistFile(esdeDir, platformFolder), listOf(entryPath(top)))

    /**
     * Deletes the media ES-DE names after [top]: `<type>/<stem>.<ext>` for every
     * media type and extension (files and folders interpreted as files), plus the
     * `<type>/<name>/` mirror ES-DE keeps for files inside a game folder.
     * Returns the files deleted.
     */
    fun deleteMedia(mediaDir: File, platformFolder: String, top: File): List<File> {
        val deleted = mutableListOf<File>()
        val systemDir = File(mediaDir, platformFolder)
        val stems = setOf(EsdeMetadata.stemOf(top.name), top.name)
        for (type in EsdeMediaType.entries) {
            val typeDir = File(systemDir, type.folder)
            for (stem in stems) {
                for (ext in type.extensions) {
                    val f = File(typeDir, "$stem.$ext")
                    if (f.isFile && f.delete()) deleted += f
                }
            }
            val mirror = File(typeDir, top.name)
            if (mirror.isDirectory) {
                mirror.walkBottomUp().forEach { f ->
                    if (f.isFile) {
                        if (f.delete()) deleted += f
                    } else {
                        f.delete()
                    }
                }
            }
        }
        return deleted
    }
}
