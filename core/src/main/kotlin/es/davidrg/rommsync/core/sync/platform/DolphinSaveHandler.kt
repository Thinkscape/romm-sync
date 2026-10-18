package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * Handler de saves para Dolphin (GameCube y Wii).
 *
 * GameCube (modo GCI Folder, Dolphin's default slot A device on desktop and
 * Android), basePath = directorio GC; see [DolphinGciLayout]:
 * ```
 * {basePath}/{USA|EUR|JAP}/Card A/{maker}-{gamecode}-{filename}.gci
 * ```
 *
 * Wii, basePath = directorio Wii/title:
 * ```
 * {basePath}/{titleIdHigh}/{titleIdLow}/data/
 * ```
 * where `titleIdLow` is the 4-char game code as 8 hex digits (`RSBE` → `52534245`).
 *
 * Bases por defecto:
 *  - Android GC:  `/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/GC`
 *  - Android Wii: `/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii/title`
 *  - Linux flatpak (EmuDeck): `~/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu/{GC,Wii/title}`
 *
 * The game id (`GAFE01`: game code + 2-char maker) comes from the local disc
 * header, else the ROM file name, else (on restore) the asset name
 * `<gameId>_gc_saves.zip` that [findSaves] gives uploads. GC saves of a game
 * travel as one deterministic zip of its GCI files; Wii saves as a zip of the
 * title folder relative to `Wii/title`.
 */
class DolphinSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val gameId = resolveGameId(romFileName, platformSlug, romLocalPath) ?: return@withContext results

        if (platformSlug.lowercase() in GC_SLUGS) {
            findGcSaves(romId, gameId, savesBasePath, results)
        } else {
            findWiiSaves(romId, gameId, savesBasePath, results)
        }

        results
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun savesFingerprint(
        romId: Int, romFileName: String, platformSlug: String,
        savesBasePath: String, romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val gameId = resolveGameId(romFileName, platformSlug, romLocalPath) ?: return@withContext null
        if (platformSlug.lowercase() in GC_SLUGS) {
            DolphinGciLayout.findSaves(File(savesBasePath), gameId)
                .joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
                .ifEmpty { null }
        } else {
            findWiiDataDir(File(savesBasePath), gameId)?.let { folderFingerprint(it) }
        }
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
        romLocalPath: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (platformSlug.lowercase() in GC_SLUGS) {
            val gameId = resolveGameId(romFileName, platformSlug, romLocalPath)
                ?: extractGameId(targetFileName)
                ?: return@withContext false
            DolphinGciLayout.restore(tempFile, File(savesBasePath), gameId)
        } else {
            // The zip carries `{high}/{low}/...`: no game id needed to restore it.
            extractWiiDownload(tempFile, savesBasePath)
        }
    }

    private fun resolveGameId(romFileName: String, platformSlug: String, romLocalPath: String?): String? =
        resolveRomId(romLocalPath, platformSlug) { extractGameId(romFileName) }?.uppercase()

    // ── GameCube ──────────────────────────────────────────────────────────

    private fun findGcSaves(
        romId: Int,
        gameId: String,
        basePath: String,
        results: MutableList<LocalSave>,
    ) {
        val gciFiles = DolphinGciLayout.findSaves(File(basePath), gameId)
        if (gciFiles.isEmpty()) return

        val newestMtime = gciFiles.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("dolphin_gc_${gameId}_", ".zip")
        zipFilesDeterministic(gciFiles, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${gameId}_gc_saves.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
    }

    // ── Wii ───────────────────────────────────────────────────────────────

    private fun findWiiSaves(
        romId: Int,
        gameId: String,
        basePath: String,
        results: MutableList<LocalSave>,
    ) {
        // La estructura es: {basePath}/{high}/{low}/data/ donde basePath = Wii/title
        val wiiTitleDir = File(basePath)
        if (!wiiTitleDir.isDirectory) return

        val dataDir = findWiiDataDir(wiiTitleDir, gameId) ?: return
        if (dataDir.listFiles()?.isEmpty() != false) return

        // La carpeta de save del juego es {high}/{low} (contiene data/ y, a veces,
        // content/). Se zipea relativa a Wii/title para preservar {high}/{low}/...
        val gameSaveDir = dataDir.parentFile ?: return
        val newestMtime = folderLastModified(gameSaveDir)
        val zipFile = File.createTempFile("dolphin_wii_${gameId}_", ".zip")
        zipFolderRelativeToDeterministic(root = wiiTitleDir, folder = gameSaveDir, output = zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${gameId}_wii_save.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
    }

    private fun extractWiiDownload(tempFile: File, basePath: String): Boolean {
        // basePath ya apunta a Wii/title — el zip contiene la estructura {high}/{low}/data/...
        val wiiTitleDir = File(basePath)
        wiiTitleDir.mkdirs()

        return try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(wiiTitleDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(wiiTitleDir.canonicalPath)) {
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
     * Busca la carpeta data/ del título Wii en title/{high}/{low}/data. Dolphin
     * names `{low}` after the game code in hex (`RSBE` → `52534245`); a folder
     * literally containing the game code is accepted too.
     */
    private fun findWiiDataDir(titleDir: File, gameId: String): File? {
        val gameCode = gameId.take(4)
        val hexLow = gameCode.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        titleDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { highDir ->
            highDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { lowDir ->
                if (lowDir.name.equals(hexLow, ignoreCase = true) || lowDir.name.contains(gameCode, ignoreCase = true)) {
                    val dataDir = File(lowDir, "data")
                    if (dataDir.isDirectory) return dataDir
                }
            }
        }
        return null
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    /**
     * Extrae el game-id (6 chars como GALE01) de un nombre de fichero.
     * Formatos: "GALE01 - Name.iso", "[GALE01] Name.gcm", "GALE01.iso",
     * "GALE01_gc_saves.zip".
     */
    private fun extractGameId(fileName: String): String? =
        GAME_ID_IN_NAME.findAll(fileName).map { it.groupValues[1] }.firstOrNull { it.matches(GAME_ID) }

    private fun folderLastModified(dir: File): Long {
        var newest = dir.lastModified()
        dir.walkTopDown().forEach { file ->
            if (file.lastModified() > newest) newest = file.lastModified()
        }
        return newest
    }

    companion object {
        const val DEFAULT_GC_SAVES_PATH =
            "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/GC"
        const val DEFAULT_WII_SAVES_PATH =
            "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii/title"
        val GC_SLUGS = setOf("gc", "gamecube", "ngc")

        private val GAME_ID_IN_NAME = Regex("""(?<![A-Za-z0-9])([A-Z0-9]{6})(?![A-Za-z0-9])""")

        /** Game code (system letter first) + 2-char maker code. */
        private val GAME_ID = Regex("[A-Z][A-Z0-9]{3}[A-Z0-9]{2}")
    }
}

/**
 * Dolphin's GameCube "GCI Folder" memory card layout, identical on desktop and
 * Android:
 * ```
 * <GC root>/<USA|EUR|JAP>/Card A/<maker>-<gamecode>-<filename>.gci
 * ```
 * e.g. `GC/USA/Card A/01-GAFE-DobutsunoMori.gci` for game id `GAFE01`
 * (game code `GAFE` + maker `01`). Every GCI also starts with the same 6
 * bytes (game code, maker), which identifies files renamed by hand.
 */
internal object DolphinGciLayout {

    val REGIONS = listOf("USA", "EUR", "JAP")
    const val CARD_DIR = "Card A"

    private val GCI_NAME = Regex("""^([A-Za-z0-9]{2})-([A-Za-z0-9]{4})-.*\.gci$""", RegexOption.IGNORE_CASE)

    /** Region folder Dolphin uses for a disc: from the region letter (4th char) of its id; null if unknown. */
    fun regionFor(gameId: String): String? = when (gameId.getOrNull(3)?.uppercaseChar()) {
        'E' -> "USA"
        'P' -> "EUR"
        'J' -> "JAP"
        else -> null
    }

    fun cardDir(gcRoot: File, region: String): File = File(gcRoot, "$region/$CARD_DIR")

    /** Whether [file] is a GCI save of [gameId] (game code chars 0-3, maker chars 4-5 when known). */
    fun matches(file: File, gameId: String): Boolean {
        if (!file.isFile || !file.extension.equals("gci", ignoreCase = true)) return false
        val gameCode = gameId.take(4)
        val maker = gameId.drop(4).take(2).takeIf { it.length == 2 }
        val (fileMaker, fileCode) = GCI_NAME.matchEntire(file.name)
            ?.let { it.groupValues[1] to it.groupValues[2] }
            ?: readHeader(file)
            ?: return false
        return fileCode.equals(gameCode, ignoreCase = true) &&
            (maker == null || fileMaker.equals(maker, ignoreCase = true))
    }

    /**
     * GCI files of [gameId], sorted by name, from the first card folder that has
     * any: the disc's region folder first, then the others.
     */
    fun findSaves(gcRoot: File, gameId: String): List<File> {
        for (region in searchOrder(gameId)) {
            val found = cardDir(gcRoot, region).listFiles()
                ?.filter { matches(it, gameId) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Replaces the GCIs of [gameId] with the `.gci` entries of [zip] (flat file
     * names; folders in entry names are ignored). Target: the card folder that
     * already holds the game's saves, else the disc's region folder, else the
     * first existing card folder, else `USA`.
     */
    fun restore(zip: File, gcRoot: File, gameId: String): Boolean {
        val existing = findSaves(gcRoot, gameId)
        val cardDir = existing.firstOrNull()?.parentFile
            ?: regionFor(gameId)?.let { cardDir(gcRoot, it) }
            ?: REGIONS.map { cardDir(gcRoot, it) }.firstOrNull { it.isDirectory }
            ?: cardDir(gcRoot, REGIONS.first())
        if (!cardDir.isDirectory && !cardDir.mkdirs()) return false

        return try {
            // Read the whole archive first so a corrupt download leaves the card untouched.
            val entries = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(FileInputStream(zip)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.endsWith(".gci", ignoreCase = true)) {
                        entries += name to zipIn.readBytes()
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            if (entries.isEmpty()) return false
            existing.forEach { it.delete() }
            cardDir.listFiles()?.filter { matches(it, gameId) }?.forEach { it.delete() }
            entries.forEach { (name, bytes) -> File(cardDir, name).writeBytes(bytes) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun searchOrder(gameId: String): List<String> {
        val primary = regionFor(gameId) ?: return REGIONS
        return listOf(primary) + (REGIONS - primary)
    }

    /** (maker, game code) from the first 6 bytes of a GCI. */
    private fun readHeader(file: File): Pair<String, String>? = try {
        if (file.length() < 6) {
            null
        } else {
            val header = ByteArray(6)
            RandomAccessFile(file, "r").use { it.readFully(header) }
            val text = String(header, Charsets.US_ASCII)
            if (text.all { it in 'A'..'Z' || it in '0'..'9' }) text.substring(4, 6) to text.substring(0, 4) else null
        }
    } catch (_: Exception) {
        null
    }
}
