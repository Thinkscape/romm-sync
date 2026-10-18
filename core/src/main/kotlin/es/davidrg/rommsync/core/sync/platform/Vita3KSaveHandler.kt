package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Save handler for Vita3K (PlayStation Vita).
 *
 * Layout (`savesBasePath` = Vita3K's `ux0/user/00/savedata`):
 * ```
 * {savesBasePath}/{TITLEID}/
 *   ├── sce_sys/…
 *   └── <game files>
 * ```
 * EmuDeck exposes it as `Emulation/saves/Vita3K/saves`, a symlink to
 * `Emulation/storage/Vita3K/ux0/user/00/savedata`.
 *
 * The title id (`PCSE00491`, `PCSB00245`, …) is read from the local ROM when
 * available — `sce_sys/param.sfo` inside an installed folder or a `.vpk`/`.zip`
 * — else from the ROM file name. The title folder is zipped deterministically
 * (same content ⇒ same SHA-1) as `{TITLEID}_vita_save.zip`, entries prefixed
 * with `{TITLEID}/`, like the Switch and PS3 handlers.
 */
class Vita3KSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val titleId = resolveTitleId(romFileName, romLocalPath) ?: return@withContext emptyList()
        val saveDir = findSaveDir(savesBasePath, titleId) ?: return@withContext emptyList()
        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext emptyList()

        val zipFile = File.createTempFile("vita_save_${titleId}_", ".zip")
        zipFolderDeterministic(saveDir, zipFile)
        listOf(
            LocalSave(
                romId = romId,
                fileName = "${titleId}$ZIP_SUFFIX",
                file = zipFile,
                lastModified = files.maxOf { it.lastModified() },
                sha1 = zipFile.sha1(),
            ),
        )
    }

    override suspend fun savesFingerprint(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val titleId = resolveTitleId(romFileName, romLocalPath) ?: return@withContext null
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
        // The zip's top folder is the authoritative title id; the asset name
        // and the ROM are fallbacks for zips produced elsewhere.
        val titleId = firstZipEntryTopDir(tempFile)?.takeIf { TITLE_ID.matches(it) }
            ?: targetFileName.removeSuffix(ZIP_SUFFIX).takeIf { TITLE_ID.matches(it) }
            ?: resolveTitleId(romFileName, romLocalPath)
            ?: return@withContext false

        val targetDir = findSaveDir(savesBasePath, titleId) ?: File(savesBasePath, titleId)
        if (targetDir.isDirectory) targetDir.deleteRecursively()
        if (!targetDir.mkdirs()) return@withContext false

        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outName = entry.name.removePrefix("$titleId/")
                    val outFile = File(targetDir, outName)
                    val inside = outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
                    if (outName.isNotBlank() && inside) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                        }
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

    private fun resolveTitleId(romFileName: String, romLocalPath: String?): String? =
        romLocalPath?.let { readTitleIdFromRom(File(it)) } ?: extractTitleId(romFileName)

    private fun findSaveDir(basePath: String, titleId: String): File? =
        File(basePath).listFiles()?.firstOrNull { it.isDirectory && it.name.equals(titleId, ignoreCase = true) }

    private fun firstZipEntryTopDir(zip: File): String? = try {
        ZipInputStream(FileInputStream(zip)).use { it.nextEntry?.name?.substringBefore('/')?.takeIf(String::isNotBlank) }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Vita3K Android's default `ux0/user/00/savedata` (app-specific storage, internal). */
        const val DEFAULT_SAVES_PATH =
            "/storage/emulated/0/Android/data/org.vita3k.emulator/files/vita/ux0/user/00/savedata"

        private const val ZIP_SUFFIX = "_vita_save.zip"
        private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")
        private val TITLE_ID_IN_NAME = Regex("""(?<![A-Z0-9])((?:PCS|NPX)[A-Z]\d{5})(?![0-9])""")
        private const val SFO_PATH = "sce_sys/param.sfo"

        internal fun extractTitleId(fileName: String): String? =
            TITLE_ID_IN_NAME.find(fileName.uppercase())?.groupValues?.get(1)

        /** Title id from an installed folder, a `.vpk`/`.zip` archive, or null. */
        internal fun readTitleIdFromRom(rom: File): String? = try {
            when {
                rom.isDirectory -> File(rom, SFO_PATH).takeIf { it.isFile }?.readBytes()?.let(::parseSfoTitleId)
                rom.isFile && rom.extension.lowercase() in setOf("vpk", "zip") -> ZipFile(rom).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull {
                        !it.isDirectory && it.name.lowercase().let { n -> n == SFO_PATH || n.endsWith("/$SFO_PATH") }
                    }
                    entry?.let { e -> zip.getInputStream(e).use { parseSfoTitleId(it.readBytes()) } }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Reads `TITLE_ID` from a PARAM.SFO: magic `\0PSF`, then key table
         * offset (u32 LE @0x08), data table offset (@0x0C), entry count (@0x10);
         * 16-byte entries from 0x14: key_offset u16, fmt u16, len u32, max u32,
         * data_offset u32.
         */
        internal fun parseSfoTitleId(sfo: ByteArray): String? {
            if (sfo.size < 0x14 || sfo[0] != 0.toByte() || sfo[1] != 'P'.code.toByte()) return null
            fun u16(off: Int) = (sfo[off].toInt() and 0xFF) or ((sfo[off + 1].toInt() and 0xFF) shl 8)
            fun u32(off: Int) = u16(off) or (u16(off + 2) shl 16)
            fun cString(off: Int): String? {
                if (off !in sfo.indices) return null
                var end = off
                while (end < sfo.size && sfo[end] != 0.toByte()) end++
                return String(sfo, off, end - off, Charsets.UTF_8)
            }
            val keyTable = u32(0x08)
            val dataTable = u32(0x0C)
            val count = u32(0x10)
            if (count !in 1..256) return null
            for (i in 0 until count) {
                val entry = 0x14 + i * 16
                if (entry + 16 > sfo.size) return null
                if (cString(keyTable + u16(entry)) == "TITLE_ID") {
                    return cString(dataTable + u32(entry + 12))?.takeIf { TITLE_ID.matches(it) }
                }
            }
            return null
        }
    }
}
