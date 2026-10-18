package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Save handler for DuckStation (PlayStation) per-game memory cards.
 *
 * DuckStation writes one memory card file per game into its memory card
 * directory (`savesBasePath`), named `<name>_<slot>.mcd` where `<name>` depends
 * on the card type configured in DuckStation:
 * - "Separate card per game (serial)": the disc serial, e.g. `SLUS-00594_1.mcd`;
 * - "Separate card per game (title)" (EmuDeck's default, `PerGameTitle`): the
 *   game title, e.g. `Crash Bandicoot (USA)_1.mcd` or `Crash Bandicoot_1.mcd`;
 * - "Separate card per game (file title)": the ROM file name without extension.
 *
 * A card is matched when its name (minus the `_<slot>` suffix) equals the
 * serial or, after normalisation (region/disc tags and punctuation dropped),
 * the ROM's base name. Every matching card is synced as its own file under its
 * own name, so two devices running DuckStation with the same card type
 * exchange identical file names.
 *
 * The serial comes from the ROM file name (`SLUS-00594`, `[SLUS_005.94]`) or,
 * when a local copy is available, from `SYSTEM.CNF` inside a raw `.bin/.iso/
 * .img` (also through a `.cue`/`.m3u`). Compressed images (`.chd`, `.pbp`) fall
 * back to the title match.
 */
class DuckStationSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        matchingCards(romFileName, savesBasePath, romLocalPath).map { it.toLocalSave(romId) }
    }

    override suspend fun savesFingerprint(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val parts = matchingCards(romFileName, savesBasePath, romLocalPath)
            .map { "${it.name}:${it.length()}:${it.lastModified()}" }
            .sorted()
        if (parts.isEmpty()) null else parts.joinToString("|")
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
        romLocalPath: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // The server echoes the card's own file name; refuse anything that
        // is not a plain `.mcd` name so a download can never escape the dir.
        val name = File(targetFileName).name
        if (name != targetFileName || !name.endsWith(MCD_EXTENSION, ignoreCase = true)) {
            return@withContext false
        }
        val dir = File(savesBasePath)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
        try {
            tempFile.copyTo(File(dir, name), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun matchingCards(romFileName: String, savesBasePath: String, romLocalPath: String?): List<File> {
        val dir = File(savesBasePath)
        val cards = dir.listFiles()?.filter { it.isFile && it.name.endsWith(MCD_EXTENSION, ignoreCase = true) }
        if (cards.isNullOrEmpty()) return emptyList()

        val serial = (romLocalPath?.let { readSerialFromImage(File(it)) } ?: extractSerial(romFileName))
            ?.let(::compactSerial)
        val title = normaliseTitle(romFileName.substringBeforeLast('.'))

        return cards.filter { card ->
            val cardName = card.name.dropLast(MCD_EXTENSION.length).replace(SLOT_SUFFIX, "")
            (serial != null && compactSerial(cardName) == serial) ||
                (title.isNotEmpty() && normaliseTitle(cardName) == title)
        }.sortedBy { it.name }
    }

    companion object {
        /** DuckStation Android's memory card folder under its default data directory. */
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/duckstation/memcards"

        private const val MCD_EXTENSION = ".mcd"
        private const val IMAGE_SCAN_BYTES = 4 * 1024 * 1024

        /** `_1` / `_2` slot suffix DuckStation appends to per-game card names. */
        private val SLOT_SUFFIX = Regex("_\\d$")

        private val SERIAL_IN_NAME = Regex("""(?<![A-Z0-9])([A-Z]{4})[-_]?(\d{3})\.?(\d{2})(?![0-9])""")
        private val SERIAL_IN_CNF = Regex("""cdrom:\\?\\?([A-Z]{4})[_-](\d{3})\.(\d{2})""", RegexOption.IGNORE_CASE)
        private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val NON_ALNUM = Regex("[^a-z0-9]")
        private val RAW_IMAGE_EXTENSIONS = setOf("bin", "iso", "img")

        internal fun extractSerial(fileName: String): String? =
            SERIAL_IN_NAME.find(fileName.uppercase())?.let { m ->
                "${m.groupValues[1]}-${m.groupValues[2]}${m.groupValues[3]}"
            }

        /** `SLUS-00594`, `SLUS_005.94`, `slus00594` → `SLUS00594`. */
        private fun compactSerial(value: String): String =
            value.uppercase().replace(Regex("[^A-Z0-9]"), "")

        /** `Crash Bandicoot (USA) (Disc 1)` → `crashbandicoot`. */
        internal fun normaliseTitle(value: String): String =
            value.replace(TAGS, "").lowercase().replace(NON_ALNUM, "")

        /**
         * Reads the boot serial from `SYSTEM.CNF` (`BOOT = cdrom:\SLUS_005.94;1`)
         * in the first MBs of a raw image; follows `.m3u` and `.cue` to the
         * first data track.
         */
        internal fun readSerialFromImage(file: File): String? {
            if (!file.isFile) return null
            return try {
                when (file.extension.lowercase()) {
                    "m3u" -> file.readLines().map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?.let { readSerialFromImage(File(file.parentFile, it)) }
                    "cue" -> Regex("""FILE\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(file.readText())?.groupValues?.get(1)
                        ?.let { readSerialFromImage(File(file.parentFile, it)) }
                    in RAW_IMAGE_EXTENSIONS -> {
                        val buffer = RandomAccessFile(file, "r").use { raf ->
                            val size = minOf(raf.length(), IMAGE_SCAN_BYTES.toLong()).toInt()
                            ByteArray(size).also { raf.readFully(it) }
                        }
                        SERIAL_IN_CNF.find(String(buffer, Charsets.ISO_8859_1))?.let { m ->
                            "${m.groupValues[1].uppercase()}-${m.groupValues[2]}${m.groupValues[3]}"
                        }
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
