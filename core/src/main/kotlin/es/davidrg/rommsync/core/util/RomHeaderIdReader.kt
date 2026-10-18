package es.davidrg.rommsync.core.util

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Extracción de identificadores de juego desde los headers binarios de las
 * imágenes de disco.
 *
 * Sustituye a la inferencia por nombre de fichero, que falla cuando el ROM
 * no incluye el ID en el nombre (p. ej. "Pokemon Esmeralda.gba").
 *
 * Disc formats (only the sectors/headers needed are read, never whole files):
 * - **PSP** (ISO, CSO/ZSO): `UMD_DATA.BIN` or `PSP_GAME/PARAM.SFO` (DISC_ID) via
 *   ISO9660 → `ULUS10041`; fallback: scan of the first MB for an embedded SFO.
 * - **PS2** (ISO, CSO/ZSO, raw BIN, CHD v5 CD/DVD): `SYSTEM.CNF` line
 *   `BOOT2 = cdrom0:\SLUS_210.50;1` → `SLUS-21050`; fallback: scan of the first 2 MB.
 * - **GameCube / Wii** (ISO/GCM, RVZ/WIA, WBFS, Dolphin CISO): 6-char game ID
 *   from the disc header (`GAFE01`), or the 4-char game code when the maker code
 *   is not alphanumeric.
 * - Any of the above inside a `.zip` (largest disc entry, streamed). `.7z`
 *   archives are not read (no solid-archive random access): null.
 * - **Switch** (XCI/NSP) and **3DS** (3DS/CCI/CIA): see below; uncompressed only.
 *
 * Null → el llamador hace fallback a la extracción por nombre de fichero.
 * Todos los métodos devuelven null ante cualquier error de lectura.
 */
object RomHeaderIdReader {

    private const val PSP_SCAN_BYTES = 1024 * 1024      // 1 MB
    private const val SWITCH_SCAN_BYTES = 512 * 1024    // 512 KB (tabla PFS0 + inicio NCA)
    private const val PS2_SCAN_BYTES = 2 * 1024 * 1024  // 2 MB
    private const val N3DS_SCAN_BYTES = 2 * 1024 * 1024 // 2 MB (CIA: TMD + primer NCCH)

    /** Entries of a zip considered disc images (the largest one is read). */
    private val ZIP_DISC_EXTENSIONS = setOf(
        "iso", "cso", "zso", "ciso", "bin", "img", "chd", "gcm", "rvz", "wia", "wbfs",
    )

    /**
     * Punto de entrada: extrae el ID del juego leyendo el header binario
     * si el formato está soportado.
     */
    fun readGameId(file: File, platformSlug: String): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            when (platformSlug.lowercase()) {
                "psp" -> readPspDiscId(file)
                "ps2" -> readPs2Serial(file)
                "gc", "gamecube", "ngc", "wii" -> readNintendoDiscId(file)
                "switch", "nx", "ryujinx", "switch-emulators" -> readSwitchTitleId(file)
                in N3DS_SLUGS -> readN3dsTitleId(file)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Slugs de plataforma que corresponden a Nintendo 3DS. */
    private val N3DS_SLUGS = setOf("3ds", "n3ds", "nintendo-3ds", "nintendo_3ds")

    // ── Disc images ──────────────────────────────────────────────────────

    /** Bytes of the image, unwrapping a `.zip`; null for `.7z` or a zip without a disc entry. */
    private fun openImage(file: File): ByteSource? = when (file.extension.lowercase()) {
        "7z" -> null
        "zip" -> {
            val zip = ZipFile(file)
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && it.size > 0 }
                .filter { it.name.substringAfterLast('.').lowercase() in ZIP_DISC_EXTENSIONS }
                .maxByOrNull { it.size }
            if (entry == null) {
                zip.close()
                null
            } else {
                ZipEntryByteSource(zip, entry)
            }
        }
        else -> FileByteSource(file)
    }

    /** 2048-byte data sectors of a CD/DVD image, whatever its container. */
    private fun openSectors(src: ByteSource): SectorSource? {
        CsoReader.open(src)?.let { return FramedSectorSource(it, SectorSource.SECTOR_SIZE, 0) }
        val head = src.read(0, 16) ?: return null
        if (String(head, 0, 8, Charsets.US_ASCII) == "MComprHD") return ChdReader.open(src)?.dataTrack()
        val rawSync = head[0].toInt() == 0 && (1..10).all { head[it].toInt() == -1 } && head[11].toInt() == 0
        if (rawSync) return FramedSectorSource(src, 2352, if (head[15].toInt() == 2) 24 else 16)
        return FramedSectorSource(src, SectorSource.SECTOR_SIZE, 0)
    }

    private fun <T> withSectors(file: File, block: (SectorSource) -> T?): T? =
        openImage(file)?.use { src -> openSectors(src)?.let(block) }

    // ── PSP ──────────────────────────────────────────────────────────────

    /**
     * `UMD_DATA.BIN` (`ULUS-10041|…`) or `PSP_GAME/PARAM.SFO` from the ISO9660
     * tree; without a file system, scans the first MB for an embedded PARAM.SFO.
     */
    private fun readPspDiscId(file: File): String? = withSectors(file) { sectors ->
        val fromFs = if (Iso9660.isIso(sectors)) {
            Iso9660.readFile(sectors, "UMD_DATA.BIN", 16)?.let(::parseUmdData)
                ?: Iso9660.readFile(sectors, "PSP_GAME/PARAM.SFO", 64 * 1024)
                    ?.takeIf { it.size >= 0x14 && indexOf(it, SFO_MAGIC) == 0 }
                    ?.let { parseSfoDiscId(it, 0) }
        } else {
            null
        }
        fromFs ?: run {
            val prefix = sectors.readUserData(PSP_SCAN_BYTES / SectorSource.SECTOR_SIZE)
            val sfoIndex = indexOf(prefix, SFO_MAGIC)
            if (sfoIndex < 0) null else parseSfoDiscId(prefix, sfoIndex)
        }
    }

    private val SFO_MAGIC = byteArrayOf(0x00, 0x50, 0x53, 0x46) // "\0PSF"
    private val PSP_DISC_ID = Regex("[A-Z]{4}\\d{5}")

    /** `UMD_DATA.BIN` starts with the disc id, e.g. `ULUS-10041|…`. */
    private fun parseUmdData(bytes: ByteArray): String? =
        String(bytes, Charsets.US_ASCII).substringBefore('|').replace("-", "").takeIf { it.matches(PSP_DISC_ID) }

    /**
     * Layout del PARAM.SFO:
     * ```
     * 0x00  magic "\0PSF"
     * 0x04  version (u32)
     * 0x08  key_table_start   (u32 LE, relativo al inicio del SFO)
     * 0x0C  data_table_start  (u32 LE, relativo al inicio del SFO)
     * 0x10  entries           (u32)
     * 0x14  índice (16 bytes por entrada):
     *        key_offset(u16) data_fmt(u16) data_len(u32) data_max(u32) data_offset(u32)
     * ```
     * Tanto key_offset como data_offset son relativos a key_table_start y
     * data_table_start respectivamente.
     */
    private fun parseSfoDiscId(prefix: ByteArray, sfoIndex: Int): String? {
        val keyTableStart = readU32LE(prefix, sfoIndex + 0x08)
        val dataTableStart = readU32LE(prefix, sfoIndex + 0x0C)
        val entries = readU32LE(prefix, sfoIndex + 0x10)
        if (entries == 0L || entries > 256L) return null

        var idx = sfoIndex + 0x14
        for (i in 0 until entries) {
            if (idx + 16 > prefix.size) return null
            val keyOffset = readU16LE(prefix, idx)
            val dataOffset = readU32LE(prefix, idx + 12)
            val keyStart = sfoIndex + keyTableStart + keyOffset
            val key = readCString(prefix, keyStart.toInt(), 64)
            if (key == "DISC_ID") {
                val dataStart = (sfoIndex + dataTableStart + dataOffset).toInt()
                val value = readCString(prefix, dataStart, 16)
                return value
                    ?.replace("-", "")
                    ?.takeIf { it.matches(PSP_DISC_ID) }
            }
            idx += 16
        }
        return null
    }

    // ── PS2 ──────────────────────────────────────────────────────────────

    /**
     * `SYSTEM.CNF` from the ISO9660 root (or, without a file system, a scan of
     * the first 2 MB) → línea `BOOT2 = cdrom0:\SLUS_210.50;1` → `SLUS-21050`.
     */
    private fun readPs2Serial(file: File): String? = withSectors(file) { sectors ->
        val cnf = if (Iso9660.isIso(sectors)) Iso9660.readFile(sectors, "SYSTEM.CNF", 4096) else null
        cnf?.let(::parseBoot2) ?: parseBoot2(sectors.readUserData(PS2_SCAN_BYTES / SectorSource.SECTOR_SIZE))
    }

    private fun parseBoot2(bytes: ByteArray): String? {
        val bootIdx = indexOf(bytes, "BOOT2".toByteArray())
        if (bootIdx < 0) return null
        val windowEnd = minOf(bootIdx + 200, bytes.size)
        val text = String(bytes, bootIdx, windowEnd - bootIdx, Charsets.US_ASCII)
        val match = Regex("""cdrom0:\\?([A-Z]{4})_(\d{3})\.(\d{2})""").find(text) ?: return null
        val (code, major, minor) = match.destructured
        return "${code}-${major}${minor}"
    }

    // ── GameCube / Wii ───────────────────────────────────────────────────

    /**
     * Both consoles keep the game ID at disc offset 0 (`GAFE01` = game code
     * `GAFE` + maker `01`); see [NintendoDiscHeader] for the containers.
     */
    private fun readNintendoDiscId(file: File): String? =
        openImage(file)?.use { src -> NintendoDiscHeader.read(src)?.let(NintendoDiscHeader::gameId) }

    // ── Utilidades ───────────────────────────────────────────────────────

    private fun isCompressed(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("cso", "chd", "gz", "zip", "7z", "rvz")
    }

    // ── Nintendo Switch ─────────────────────────────────────────────────

    /**
     * Title-ID de Switch desde XCI/NSP sin descomprimir nada.
     *
     * - XCI: PackageId en offset 0x0012 (big-endian) == TitleID base.
     *   Validado contra la estructura XCI: tamaño de header (F0) = 0x20 y
     *   PackageId con prefijo 0x01 0x00 (los title-ids de juego empiezan así).
     * - NSP (PFS0): la tabla de strings contiene el nombre del CNMT, que
     *   incluye el title-id base en hex: "TitanTitleId_0x0100XXXXXXXXXXXX" o
     *   el propio nombre de fichero "0100XXXXXXXXXXXX.nca". Escaneamos el
     *   prefijo en claro (antes de la zona cifrada) buscando el patrón.
     * Fallback: escaneo alineado a 16B buscando prefijo 0x01 0x00 + ceros.
     */
    private fun readSwitchTitleId(file: File): String? {
        if (isCompressed(file)) return null
        val ext = file.extension.lowercase()
        val prefix = readPrefix(file, SWITCH_SCAN_BYTES) ?: return null

        return when (ext) {
            "xci" -> readXciPackageId(prefix)
            "nsp" -> readNspTitleId(prefix)
            else -> null
        }
    }

    /**
     * XCI: valida el magic del gamecard ("HEADER" en 0x100 o tamaño de header
     * 0x20 en el campo de tamaño) y lee el PackageId como title-id base.
     */
    private fun readXciPackageId(prefix: ByteArray): String? {
        // Campo "headerSize" en offset 0xF0: los XCI oficiales usan 0x20
        val headerSize = readBeLong(prefix, 0x00F0) ?: return null
        if (headerSize != 0x20L) return null
        val packageId = readBeLong(prefix, 0x0012) ?: return null
        // Los title-id de aplicaciones Switch empiezan por 0x0100 en BE
        if (packageId ushr 48 != 0x0100L) return null
        return formatTitleId(packageId)
    }

    /**
     * NSP: el title-id aparece en claro en la tabla de strings del PFS0
     * (nombres de NCA tipo "0100XXXXXXXXXXXX.nca") o en el CNMT. Buscamos
     * ASCII hex de 16 chars empezando por "0100".
     */
    private fun readNspTitleId(prefix: ByteArray): String? {
        val text = String(prefix, Charsets.US_ASCII)
        // Busca "0100" seguido de 12 hex más (title-id completo en texto)
        val m = Regex("0100[0-9A-Fa-f]{12}").find(text)
        return m?.value?.uppercase()
    }

    /** 0x0100XXXXXXXXXXXX -> "0100XXXXXXXXXXXX" (16 hex mayúsculas). */
    private fun formatTitleId(titleId: Long): String =
        "%016X".format(titleId)

    // ── Nintendo 3DS ────────────────────────────────────────────────────

    /**
     * Title-ID de 3DS desde el header NCCH de un .3ds/.cci o del fichero
     * .app (content) de un .cia descomprimido.
     *
     * Layout NCCH: magic "NCCH" en offset 0x100; el title-id está en offset
     * 0x108 como little-endian u64. Para juegos retail el high u32 es
     * 0x00040000 (title-type eShop/retail). El low u32 identifica el juego.
     *
     * Nota: un .cia empaqueta los contenidos con un header TMD delante; el
     * primer NCCH no está en offset fijo. Se escanea el prefijo buscando el
     * magic "NCCH" y validando el prefijo 0x00040000 del title-id leído.
     */
    private fun readN3dsTitleId(file: File): String? {
        if (isCompressed(file)) return null
        val prefix = readPrefix(file, N3DS_SCAN_BYTES) ?: return null
        var i = indexOf(prefix, byteArrayOf(0x4E, 0x43, 0x43, 0x48)) // "NCCH"
        while (i >= 0 && i + 16 <= prefix.size) {
            // Title-id: 8 bytes después del magic (0x108 absoluto cuando el
            // NCCH está en 0x100, como en .3ds/.cci). LE u64.
            val titleId = readLeULong(prefix, i + 8) ?: return null
            if ((titleId shr 32) == 0x00040000L) {
                return "%016X".format(titleId)
            }
            i = indexOf(prefix, byteArrayOf(0x4E, 0x43, 0x43, 0x48), i + 1)
        }
        return null
    }

    private fun readLeULong(bytes: ByteArray, offset: Int): Long? {
        if (offset + 8 > bytes.size) return null
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readBeLong(prefix: ByteArray, offset: Int): Long? {
        if (offset + 8 > prefix.size) return null
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (prefix[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray? {
        if (file.length() < 16) return null
        val size = minOf(file.length().toInt(), maxBytes)
        val buffer = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            raf.readFully(buffer)
        }
        return buffer
    }

    /** Búsqueda ingenua de [needle] en [haystack]. Devuelve -1 si no está. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        indexOf(haystack, needle, 0)

    /** Igual que [indexOf] empezando en [fromIndex] (para buscar más matches). */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in fromIndex..haystack.size - needle.size) {
            if (i < 0) continue
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
        (b[off].toInt() and 0xFF).toLong() or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

    /** Lee un string terminado en \u0000 con longitud máxima [maxLen]. */
    private fun readCString(b: ByteArray, off: Int, maxLen: Int): String? {
        if (off < 0 || off >= b.size) return null
        val end = minOf(off + maxLen, b.size)
        val sb = StringBuilder()
        for (i in off until end) {
            val c = b[i].toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString().ifEmpty { null }
    }
}
