package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Handler de saves para Switch (Eden, Yuzu, Sudachi, Citron: same NAND layout).
 *
 * Estructura esperada (`savesBasePath` = `nand/user/save`):
 * ```
 * {savesBasePath}/0000000000000000/{profileId}/{titleId}/
 * ```
 *
 * El `titleId` son 16 caracteres hexadecimales (p.ej. `0100000000010000`
 * para Super Mario Odyssey), leído del header del ROM local, del nombre del
 * ROM o (al restaurar) del nombre del asset `<titleId>_switch_save.zip`.
 * El `profileId` is the 32-hex user UUID the emulator assigns (e.g.
 * `183884E8D54536AD3181506A16527D28` on the Deck); 16-hex names from older
 * layouts are accepted too. A device can hold several profiles: restores go
 * to the profile that already has the most saves.
 *
 * Ruta base por defecto (nand/user/save):
 * `/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save`
 *
 * La carpeta de saves del titulo se zipea entera para subir al servidor.
 * Ryujinx's `bis/user/save/<index>` layout (title ids only in its imkvdb) is
 * not covered.
 */
class SwitchSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        // Title-id: primero desde el header del XCI/NSP local (fiable), luego
        // desde el nombre del fichero (patrón "[0100000000010000]").
        val titleId = resolveRomId(romLocalPath, platformSlug) { extractTitleId(romFileName) }
            ?: return@withContext results

        val saveDir = findSaveDir(savesBasePath, titleId) ?: return@withContext results
        if (!saveDir.isDirectory) return@withContext results

        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext results

        val newestMtime = files.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("switch_save_${titleId}_", ".zip")
        zipFolderDeterministic(saveDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${titleId}_switch_save.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )

        results
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun savesFingerprint(
        romId: Int, romFileName: String, platformSlug: String,
        savesBasePath: String, romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val titleId = resolveRomId(romLocalPath, platformSlug) { extractTitleId(romFileName) }
            ?: return@withContext null
        val saveDir = findSaveDir(savesBasePath, titleId) ?: return@withContext null
        folderFingerprint(saveDir)
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
        romLocalPath: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // Title id: ROM header, ROM file name, then the asset name
        // (`<titleId>_switch_save.zip`, as findSaves names uploads).
        val titleId = resolveRomId(romLocalPath, platformSlug) { extractTitleId(romFileName) }
            ?: extractTitleId(targetFileName)
            ?: return@withContext false

        // Restore over the existing title folder, else into the profile that
        // already holds saves (never an invented profile id when one exists).
        val saveDir = findSaveDir(savesBasePath, titleId)
            ?: File(restoreProfileDir(savesBasePath), titleId)

        // Borrar saves existentes antes de restaurar
        if (saveDir.isDirectory) {
            saveDir.deleteRecursively()
        }
        saveDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(saveDir, entry.name.removePrefix("${saveDir.name}/"))
                    if (!outFile.canonicalPath.startsWith(saveDir.canonicalPath)) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Busca la carpeta de saves para un title-id en el árbol NAND de Eden.
     *
     * Estructura canónica: {basePath}/{userId}/{profileId}/{titleId}/, donde
     * basePath = nand/user/save. Pero el usuario puede configurar la ruta base
     * como `nand` (sin /user/save) o una carpeta custom de Eden (fuera de
     * Android/data), por lo que en vez de asumir exactamente 2 niveles de
     * profundidad se hace una búsqueda acotada (hasta 6 niveles) de cualquier
     * directorio cuyo nombre SEA el title-id. When several profiles hold the
     * title, the one with the newest file wins.
     */
    private fun findSaveDir(basePath: String, titleId: String): File? {
        val saveRoot = File(basePath)
        if (!saveRoot.isDirectory) return null
        val found = mutableListOf<File>()
        collectTitleDirs(saveRoot, titleId, depth = 0, found)

        // Fallback: el title-id puede existir a mayor profundidad en árboles
        // custom (p. ej. {base}/nand/user/save/{u}/{p}/{title}). Un único
        // walkTopDown sería O(tree) siempre; el BFS acotado cubre lo habitual
        // barato y este walk solo se paga si no apareció antes.
        if (found.isEmpty()) {
            saveRoot.walkTopDown()
                .filter { it.isDirectory && it.name.equals(titleId, ignoreCase = true) }
                .toCollection(found)
        }
        return found.maxByOrNull { dir -> dir.walkTopDown().maxOf { it.lastModified() } }
    }

    private fun collectTitleDirs(dir: File, titleId: String, depth: Int, out: MutableList<File>) {
        if (depth > 6) return
        val children = dir.listFiles() ?: return
        // Match directo en este nivel
        children.filter { it.isDirectory && it.name.equals(titleId, ignoreCase = true) }.forEach { out += it }
        // Descender por directorios con nombre hex (user ids de 16, profile ids de 32)
        // y por los segmentos `nand/user/save` cuando la base apunta más arriba.
        children.filter { it.isDirectory && !it.name.equals(titleId, ignoreCase = true) && it.name.isTreeSegment() }
            .sortedBy { it.name }
            .forEach { collectTitleDirs(it, titleId, depth + 1, out) }
    }

    /**
     * Profile folder a restored title goes into: under `<save root>/0000000000000000/`,
     * the profile (32- or 16-hex name, all-zero system profile excluded) with
     * the most title folders; else the most recently used profile; only when
     * none exists the fixed [DEFAULT_PROFILE_ID].
     */
    private fun restoreProfileDir(basePath: String): File {
        val userDir = File(saveRoot(File(basePath)), DEFAULT_USER_ID)
        val profiles = userDir.listFiles()
            ?.filter { it.isDirectory && (it.name.matches(HEX32) || it.name.matches(HEX16)) && it.name.any { c -> c != '0' } }
            .orEmpty()
        val withSaves = profiles
            .map { it to (it.listFiles()?.count { t -> t.isDirectory && t.name.matches(HEX16) } ?: 0) }
            .filter { it.second > 0 }
        return withSaves.maxWithOrNull(compareBy<Pair<File, Int>> { it.second }.thenBy { it.first.name })?.first
            ?: profiles.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: File(userDir, DEFAULT_PROFILE_ID)
    }

    /** `nand/user/save` below [base] when the base points at `nand` or its parent; else [base]. */
    private fun saveRoot(base: File): File =
        listOf("user/save", "nand/user/save").map { File(base, it) }.firstOrNull { it.isDirectory } ?: base

    private fun String.isTreeSegment(): Boolean =
        matches(HEX16) || matches(HEX32) || this == "nand" || this == "user" || this == "save"

    /**
     * Extrae un title-id de Switch (16 hex chars) del nombre del fichero ROM.
     * Formatos: "[0100000000010000] Game.nsp", "Game [0100000000010000].xci"
     */
    private fun extractTitleId(fileName: String): String? {
        val pattern = Regex("""(?<![0-9A-Fa-f])([0-9A-Fa-f]{16})(?![0-9A-Fa-f])""")
        val match = pattern.find(fileName)
        return match?.groupValues?.get(1)?.uppercase()
    }


    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save"
        private const val DEFAULT_USER_ID = "0000000000000000"
        /** Last resort when the device has no profile folder yet. */
        private const val DEFAULT_PROFILE_ID = "0000000000000001"
        private val HEX16 = Regex("^[0-9A-Fa-f]{16}$")
        private val HEX32 = Regex("^[0-9A-Fa-f]{32}$")
    }
}
