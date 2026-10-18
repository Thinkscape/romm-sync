package es.davidrg.rommsync.core.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Title id of an Azahar Z3DS container (`.zcci`, `.zcxi`, `.zcia`, `.z3dsx`) without
 * decompressing anything.
 *
 * Layout (Azahar `src/common/file_derived.h` `Z3DSFileHeader`, `zstd_compression.cpp`
 * `Z3DSMetadata`), all little-endian:
 * ```
 * 0x00  magic "Z3DS"
 * 0x04  underlying magic ("NCSD", "NCCH", "CIA\0", "3DSX")
 * 0x08  version (u8) = 1, reserved (u8)
 * 0x0A  header_size (u16) = 0x20
 * 0x0C  metadata_size (u32, padded to 0x10)
 * 0x10  compressed_size (u64), 0x18 uncompressed_size (u64)
 * header_size: metadata block = version (u8) = 1, then items
 *        { type u8 (0 = end, 1 = binary), name_len u8, data_len u16, name, data }
 * header_size + metadata_size: zstd seekable stream of the plain file
 * ```
 * Azahar's compressor (every release since July 2025, `AppLoader_NCCH::GetLoaderFileInfo`
 * and the CIA path in `loader.cpp`) stores a `titleinfo` item = `Service::AM::TitleInfo`
 * `{ u64 tid; u64 size; u16 version; u16 unused; u32 type }`. For CCI/CXI `tid` is the
 * NCCH program id, which equals partition 0's partition id that [RomHeaderIdReader] reads
 * from a plain `.3ds`, so both forms of the same game yield the same id.
 */
internal object Z3dsReader {

    private val MAGIC = byteArrayOf(0x5A, 0x33, 0x44, 0x53) // "Z3DS"
    private const val HEADER_SIZE = 0x20
    private const val VERSION = 1
    private const val METADATA_VERSION = 1
    private const val MAX_METADATA_BYTES = 1 shl 20 // a few items, each ≤ 0xFFFF bytes
    private const val ITEM_END = 0
    private const val ITEM_BINARY = 1
    private const val TITLE_INFO = "titleinfo"
    private const val TITLE_INFO_SIZE = 0x18

    /** True when [file] starts with the Z3DS magic, whatever its extension. */
    fun isZ3ds(file: File): Boolean {
        if (file.length() < HEADER_SIZE) return false
        val magic = ByteArray(MAGIC.size)
        RandomAccessFile(file, "r").use { it.readFully(magic) }
        return magic.contentEquals(MAGIC)
    }

    /** `titleinfo.tid` as a 64-bit value, or null without a well-formed `titleinfo` item. */
    fun readTitleId(file: File): Long? = RandomAccessFile(file, "r").use { raf -> readTitleId(raf) }

    private fun readTitleId(raf: RandomAccessFile): Long? {
        if (raf.length() < HEADER_SIZE) return null
        val header = ByteArray(HEADER_SIZE)
        raf.readFully(header)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        if (u8(header, 0x08) != VERSION) return null
        val headerSize = u16(header, 0x0A)
        val metadataSize = u32(header, 0x0C)
        if (headerSize < HEADER_SIZE || metadataSize <= 0 || metadataSize > MAX_METADATA_BYTES) return null
        if (headerSize + metadataSize > raf.length()) return null
        val metadata = ByteArray(metadataSize.toInt())
        raf.seek(headerSize.toLong())
        raf.readFully(metadata)
        return findItem(metadata, TITLE_INFO)
            ?.takeIf { it.size >= TITLE_INFO_SIZE }
            ?.let { u64(it, 0) }
    }

    private fun findItem(metadata: ByteArray, wanted: String): ByteArray? {
        if (u8(metadata, 0) != METADATA_VERSION) return null
        var pos = 1
        while (pos + 4 <= metadata.size) {
            val type = u8(metadata, pos)
            val nameLen = u8(metadata, pos + 1)
            val dataLen = u16(metadata, pos + 2)
            if (type == ITEM_END) return null
            val nameStart = pos + 4
            val dataStart = nameStart + nameLen
            val next = dataStart + dataLen
            if (next > metadata.size) return null
            if (type == ITEM_BINARY && String(metadata, nameStart, nameLen, Charsets.US_ASCII) == wanted) {
                return metadata.copyOfRange(dataStart, next)
            }
            pos = next
        }
        return null
    }

    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    private fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)

    private fun u64(b: ByteArray, off: Int): Long = u32(b, off) or (u32(b, off + 4) shl 32)
}
