package es.davidrg.rommsync.core.util

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream

/**
 * Read-only MAME CHD v5 reader exposing the logical (decompressed) bytes, plus a
 * [SectorSource] for the first data track of CD/DVD images. Ported from MAME
 * `src/lib/util/chd.cpp` (`decompress_v5_map`, `read_hunk`), `chdcodec.cpp`
 * (`chd_cd_decompressor`, zlib/LZMA codecs), `huffman.cpp` and `cdrom.cpp`.
 *
 * Supported: v5 headers, compressed and uncompressed maps, self references, codecs
 * `zlib`, `lzma`, `cdzl`, `cdlz` (data frames only; the CD subcode stream is not
 * decoded and reads back as zeros, ECC is not regenerated). Hunks using any other
 * codec (`cdfl` audio, `flac`, `huff`, `zstd`, `cdzs`) or a parent CHD read as
 * unavailable, so callers get null rather than wrong bytes. v3/v4 files are not
 * supported (chdman has written v5 since 2012).
 *
 * Memory: the decoded map is kept as three arrays (~13 bytes per hunk) plus one
 * cached hunk.
 */
internal class ChdReader private constructor(
    private val src: ByteSource,
    private val codecs: IntArray,
    override val size: Long,
    private val mapOffset: Long,
    private val metaOffset: Long,
    private val hunkBytes: Int,
    private val unitBytes: Int,
) : ByteSource {
    private val hunkCount = ((size + hunkBytes - 1) / hunkBytes).toInt()
    private val compressed = codecs[0] != 0

    // Decoded map (compressed CHDs): type, compressed length, offset per hunk.
    private lateinit var types: ByteArray
    private lateinit var lengths: IntArray
    private lateinit var offsets: LongArray

    private var cachedHunk = -1
    private var cached: ByteArray? = null

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || pos + len > size) return false
        var done = 0
        while (done < len) {
            val abs = pos + done
            val hunk = hunk((abs / hunkBytes).toInt()) ?: return false
            val inHunk = (abs % hunkBytes).toInt()
            val n = minOf(len - done, hunkBytes - inHunk)
            System.arraycopy(hunk, inHunk, buf, off + done, n)
            done += n
        }
        return true
    }

    private fun hunk(index: Int): ByteArray? {
        if (index == cachedHunk) return cached
        val data = readHunk(index, 0) ?: return null
        cachedHunk = index
        cached = data
        return data
    }

    private fun readHunk(index: Int, depth: Int): ByteArray? {
        if (index !in 0 until hunkCount || depth > 8) return null
        if (!compressed) {
            val entry = src.read(mapOffset + index * 4L, 4) ?: return null
            val blockOffset = be(entry, 0, 4) * hunkBytes
            return if (blockOffset == 0L) ByteArray(hunkBytes) else src.read(blockOffset, hunkBytes)
        }
        return when (val type = types[index].toInt()) {
            in COMPRESSION_TYPE_0..COMPRESSION_TYPE_3 -> {
                val data = src.read(offsets[index], lengths[index]) ?: return null
                decompress(codecs[type], data, hunkBytes)
            }
            COMPRESSION_NONE -> src.read(offsets[index], hunkBytes)
            COMPRESSION_SELF -> readHunk(offsets[index].toInt(), depth + 1)
            else -> null // COMPRESSION_PARENT: no parent CHD available
        }
    }

    private fun decompress(codec: Int, data: ByteArray, outLen: Int): ByteArray? = when (codec) {
        CODEC_ZLIB -> inflateRaw(data, outLen)
        CODEC_LZMA -> lzma(data, outLen)
        CODEC_CD_ZLIB -> cdFrames(data, outLen) { base, len -> inflateRaw(base, len) }
        CODEC_CD_LZMA -> cdFrames(data, outLen) { base, len -> lzma(base, len) }
        else -> null
    }

    /**
     * CD codecs: header = ECC bitmap (1 bit per frame) + base length (u16, u24 when
     * the hunk is >= 64 KiB), then the base stream (2352-byte sector data of every
     * frame) and the subcode stream. Frames are reassembled as 2352 data + 96
     * subcode; the subcode is left zeroed.
     */
    private inline fun cdFrames(data: ByteArray, outLen: Int, base: (ByteArray, Int) -> ByteArray?): ByteArray? {
        val frames = outLen / CD_FRAME_SIZE
        val lenBytes = if (outLen < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + lenBytes
        if (data.size < headerBytes) return null
        val baseLen = be(data, eccBytes, lenBytes).toInt()
        if (headerBytes + baseLen > data.size) return null
        val sectors = base(data.copyOfRange(headerBytes, headerBytes + baseLen), frames * CD_SECTOR_DATA) ?: return null
        val out = ByteArray(outLen)
        for (f in 0 until frames) {
            System.arraycopy(sectors, f * CD_SECTOR_DATA, out, f * CD_FRAME_SIZE, CD_SECTOR_DATA)
            if ((data[f / 8].toInt() and (1 shl (f % 8))) != 0) {
                System.arraycopy(CD_SYNC_HEADER, 0, out, f * CD_FRAME_SIZE, CD_SYNC_HEADER.size)
            }
        }
        return out
    }

    /**
     * MAME writes raw LZMA without properties: they are re-derived from the encoder
     * settings (level 6 → lc=3 lp=0 pb=2) and a dictionary no larger than the hunk;
     * any dictionary at least that large decodes the stream.
     */
    private fun lzma(data: ByteArray, outLen: Int): ByteArray? = try {
        LZMAInputStream(ByteArrayInputStream(data), outLen.toLong(), LZMA_PROPS, maxOf(outLen, 4096)).use { input ->
            val out = ByteArray(outLen)
            var done = 0
            while (done < outLen) {
                val n = input.read(out, done, outLen - done)
                if (n < 0) break
                done += n
            }
            if (done == outLen) out else null
        }
    } catch (_: java.io.IOException) {
        null
    }

    /**
     * Sectors of the first data track: CD images use the `CHT2`/`CHTR`/`CHGD` track
     * metadata (frame offset includes chdman's 4-frame track padding and a pregap
     * stored in the image); DVD images are plain 2048-byte sectors.
     */
    fun dataTrack(): SectorSource? {
        val meta = metadata()
        if (meta.any { it.first == TAG_DVD } || (meta.none { it.first in CD_TAGS } && unitBytes == 2048)) {
            return FramedSectorSource(this, 2048, 0)
        }
        if (unitBytes != CD_FRAME_SIZE || hunkBytes % CD_FRAME_SIZE != 0) return null
        var chdFrame = 0L
        val candidates = mutableListOf<Pair<Long, Int>>()
        for ((tag, text) in meta) {
            if (tag !in CD_TAGS) continue
            val fields = Regex("""(\w+):(\S+)""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val frames = fields["FRAMES"]?.toLongOrNull() ?: return null
            val type = fields["TYPE"] ?: return null
            if (type != "AUDIO" && candidates.isEmpty()) {
                val pregap = fields["PREGAP"]?.toLongOrNull() ?: 0L
                val pregapInImage = fields["PGTYPE"]?.startsWith("V") == true
                val start = chdFrame + if (pregapInImage) pregap else 0L
                candidates += start to dataOffsetFor(type)
            }
            chdFrame += frames + ((frames + 3) / 4 * 4 - frames)
        }
        // Fall back to probing common layouts when metadata is missing or odd.
        for (start in listOf(0L, 150L)) for (o in intArrayOf(16, 24, 0, 8)) candidates += start to o
        for ((start, offset) in candidates) {
            val sectors = FramedSectorSource(this, CD_FRAME_SIZE, offset, start * CD_FRAME_SIZE)
            if (Iso9660.isIso(sectors)) return sectors
        }
        val (start, offset) = candidates.first()
        return FramedSectorSource(this, CD_FRAME_SIZE, offset, start * CD_FRAME_SIZE)
    }

    private fun dataOffsetFor(type: String): Int = when (type) {
        "MODE1_RAW" -> 16
        "MODE2_RAW" -> 24
        "MODE2", "MODE2_FORM_MIX" -> 8
        else -> 0 // MODE1, MODE2_FORM1: cooked 2048-byte sectors
    }

    /** Metadata chain as (tag, text) pairs; entry = tag u32, flags u8, length u24, next u64, data. */
    private fun metadata(): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var offset = metaOffset
        while (offset != 0L && result.size < 256) {
            val h = src.read(offset, 16) ?: break
            val tag = be(h, 0, 4).toInt()
            val length = be(h, 5, 3).toInt()
            val next = be(h, 8, 8)
            val data = src.read(offset + 16, minOf(length, 4096)) ?: break
            result += tag to String(data, Charsets.US_ASCII).trimEnd('\u0000')
            offset = next
        }
        return result
    }

    /** Port of `chd_file::decompress_v5_map`; false when the map is corrupt or unsupported. */
    private fun decodeMap(): Boolean {
        val h = src.read(mapOffset, 16) ?: return false
        val mapBytes = be(h, 0, 4)
        val firstOffset = be(h, 4, 6)
        val mapCrc = be(h, 10, 2).toInt()
        val lengthBits = h[12].toInt() and 0xFF
        val selfBits = h[13].toInt() and 0xFF
        val parentBits = h[14].toInt() and 0xFF
        if (mapBytes <= 0 || mapBytes > MAX_MAP_BYTES || mapOffset + 16 + mapBytes > src.size) return false
        val bits = BitReader(src.read(mapOffset + 16, mapBytes.toInt()) ?: return false)

        val decoder = HuffmanDecoder.importTreeRle(bits, numCodes = 16, maxBits = 8) ?: return false
        types = ByteArray(hunkCount)
        lengths = IntArray(hunkCount)
        offsets = LongArray(hunkCount)
        var lastComp = 0
        var repCount = 0
        for (i in 0 until hunkCount) {
            if (repCount > 0) {
                types[i] = lastComp.toByte()
                repCount--
            } else {
                when (val v = decoder.decodeOne(bits)) {
                    COMPRESSION_RLE_SMALL -> {
                        types[i] = lastComp.toByte()
                        repCount = 2 + decoder.decodeOne(bits)
                    }
                    COMPRESSION_RLE_LARGE -> {
                        types[i] = lastComp.toByte()
                        repCount = 2 + 16 + (decoder.decodeOne(bits) shl 4)
                        repCount += decoder.decodeOne(bits)
                    }
                    else -> {
                        lastComp = v
                        types[i] = v.toByte()
                    }
                }
            }
        }

        var curOffset = firstOffset
        var lastSelf = 0L
        var lastParent = 0L
        val crc = Crc16()
        val entry = ByteArray(12)
        for (i in 0 until hunkCount) {
            var offset = curOffset
            var length = 0
            var hunkCrc = 0
            when (types[i].toInt()) {
                COMPRESSION_TYPE_0, COMPRESSION_TYPE_1, COMPRESSION_TYPE_2, COMPRESSION_TYPE_3 -> {
                    length = bits.read(lengthBits).toInt()
                    curOffset += length
                    hunkCrc = bits.read(16).toInt()
                }
                COMPRESSION_NONE -> {
                    length = hunkBytes
                    curOffset += length
                    hunkCrc = bits.read(16).toInt()
                }
                COMPRESSION_SELF -> {
                    offset = bits.read(selfBits)
                    lastSelf = offset
                }
                COMPRESSION_PARENT -> {
                    offset = bits.read(parentBits)
                    lastParent = offset
                }
                COMPRESSION_SELF_1, COMPRESSION_SELF_0 -> {
                    if (types[i].toInt() == COMPRESSION_SELF_1) lastSelf++
                    types[i] = COMPRESSION_SELF.toByte()
                    offset = lastSelf
                }
                COMPRESSION_PARENT_SELF -> {
                    types[i] = COMPRESSION_PARENT.toByte()
                    offset = i.toLong() * hunkBytes / unitBytes
                    lastParent = offset
                }
                COMPRESSION_PARENT_1, COMPRESSION_PARENT_0 -> {
                    if (types[i].toInt() == COMPRESSION_PARENT_1) lastParent += hunkBytes / unitBytes
                    types[i] = COMPRESSION_PARENT.toByte()
                    offset = lastParent
                }
                else -> return false
            }
            lengths[i] = length
            offsets[i] = offset
            entry[0] = types[i]
            putBe(entry, 1, 3, length.toLong())
            putBe(entry, 4, 6, offset)
            putBe(entry, 10, 2, hunkCrc.toLong())
            crc.update(entry)
        }
        return crc.value == mapCrc
    }

    override fun close() = src.close()

    companion object {
        private const val HEADER_V5 = 124
        private const val MAX_MAP_BYTES = 64L * 1024 * 1024

        private const val COMPRESSION_TYPE_0 = 0
        private const val COMPRESSION_TYPE_1 = 1
        private const val COMPRESSION_TYPE_2 = 2
        private const val COMPRESSION_TYPE_3 = 3
        private const val COMPRESSION_NONE = 4
        private const val COMPRESSION_SELF = 5
        private const val COMPRESSION_PARENT = 6
        private const val COMPRESSION_RLE_SMALL = 7
        private const val COMPRESSION_RLE_LARGE = 8
        private const val COMPRESSION_SELF_0 = 9
        private const val COMPRESSION_SELF_1 = 10
        private const val COMPRESSION_PARENT_SELF = 11
        private const val COMPRESSION_PARENT_0 = 12
        private const val COMPRESSION_PARENT_1 = 13

        internal val CODEC_ZLIB = tag("zlib")
        internal val CODEC_LZMA = tag("lzma")
        internal val CODEC_CD_ZLIB = tag("cdzl")
        internal val CODEC_CD_LZMA = tag("cdlz")
        private val TAG_DVD = tag("DVD ")
        private val CD_TAGS = setOf(tag("CHT2"), tag("CHTR"), tag("CHGD"))

        internal const val CD_FRAME_SIZE = 2448
        internal const val CD_SECTOR_DATA = 2352
        private val CD_SYNC_HEADER = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)
        private const val LZMA_PROPS: Byte = ((2 * 5 + 0) * 9 + 3).toByte() // pb=2, lp=0, lc=3

        fun open(src: ByteSource): ChdReader? {
            val h = src.read(0, HEADER_V5) ?: return null
            if (String(h, 0, 8, Charsets.US_ASCII) != "MComprHD") return null
            if (be(h, 8, 4) != HEADER_V5.toLong() || be(h, 12, 4) != 5L) return null
            val codecs = IntArray(4) { be(h, 16 + it * 4, 4).toInt() }
            val logical = be(h, 32, 8)
            val hunkBytes = be(h, 56, 4).toInt()
            val unitBytes = be(h, 60, 4).toInt()
            if (logical <= 0 || hunkBytes <= 0 || unitBytes <= 0 || hunkBytes > 1 shl 24) return null
            val reader = ChdReader(src, codecs, logical, be(h, 40, 8), be(h, 48, 8), hunkBytes, unitBytes)
            if (reader.compressed && !reader.decodeMap()) return null
            return reader
        }

        internal fun tag(s: String): Int =
            (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

        private fun be(b: ByteArray, off: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        private fun putBe(b: ByteArray, off: Int, len: Int, v: Long) {
            for (i in 0 until len) b[off + i] = (v ushr (8 * (len - 1 - i))).toByte()
        }
    }
}

/** MSB-first bit reader (MAME `bitstream_in`); reads past the end return zero bits. */
internal class BitReader(private val data: ByteArray) {
    private var bitPos = 0L

    fun peek(n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            val p = bitPos + i
            val byte = (p ushr 3).toInt()
            val bit = if (byte < data.size) (data[byte].toInt() ushr (7 - (p and 7).toInt())) and 1 else 0
            v = (v shl 1) or bit.toLong()
        }
        return v
    }

    fun remove(n: Int) {
        bitPos += n
    }

    fun read(n: Int): Long = peek(n).also { remove(n) }
}

/** Canonical Huffman decoder with MAME's RLE tree import (`huffman_context_base`). */
internal class HuffmanDecoder private constructor(private val maxBits: Int, private val lookup: IntArray) {
    fun decodeOne(bits: BitReader): Int {
        val entry = lookup[bits.peek(maxBits).toInt()]
        bits.remove(entry and 0x1F)
        return entry ushr 5
    }

    companion object {
        fun importTreeRle(bits: BitReader, numCodes: Int, maxBits: Int): HuffmanDecoder? {
            val numBits = when {
                maxBits >= 16 -> 5
                maxBits >= 8 -> 4
                else -> 3
            }
            val lengths = IntArray(numCodes)
            var cur = 0
            while (cur < numCodes) {
                var nodeBits = bits.read(numBits).toInt()
                if (nodeBits != 1) {
                    lengths[cur++] = nodeBits
                } else {
                    nodeBits = bits.read(numBits).toInt()
                    if (nodeBits == 1) {
                        lengths[cur++] = nodeBits
                    } else {
                        var rep = bits.read(numBits).toInt() + 3
                        while (rep-- > 0) {
                            if (cur >= numCodes) return null
                            lengths[cur++] = nodeBits
                        }
                    }
                }
            }

            // assign_canonical_codes
            val histo = IntArray(33)
            for (len in lengths) {
                if (len > maxBits) return null
                histo[len]++
            }
            var start = 0
            for (len in 32 downTo 1) {
                val next = (start + histo[len]) ushr 1
                if (len != 1 && next * 2 != start + histo[len]) return null
                histo[len] = start
                start = next
            }
            val codes = IntArray(numCodes)
            for (c in 0 until numCodes) if (lengths[c] > 0) codes[c] = histo[lengths[c]]++

            // build_lookup_table
            val lookup = IntArray(1 shl maxBits)
            for (c in 0 until numCodes) {
                val len = lengths[c]
                if (len == 0) continue
                val shift = maxBits - len
                val value = (c shl 5) or (len and 0x1F)
                for (i in (codes[c] shl shift) until ((codes[c] + 1) shl shift)) lookup[i] = value
            }
            return HuffmanDecoder(maxBits, lookup)
        }
    }
}

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), MAME `crc16_creator`. */
internal class Crc16 {
    var value = 0xFFFF
        private set

    fun update(bytes: ByteArray) {
        for (b in bytes) {
            value = value xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                value = if (value and 0x8000 != 0) (value shl 1) xor 0x1021 else value shl 1
                value = value and 0xFFFF
            }
        }
    }
}
