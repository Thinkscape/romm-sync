package es.davidrg.rommsync.core.util

import java.util.zip.Inflater

/**
 * Decompressed view of a PSP/PS2 compressed ISO: CISO v1 (deflate), CISO v2
 * (deflate or LZ4 per block, maxcso) and ZISO (LZ4). Blocks are decoded on demand
 * and only the most recent one is cached.
 *
 * Header (little-endian): magic "CISO"/"ZISO", header size u32, uncompressed size
 * u64 @8, block size u32 @16, version u8 @20, index shift u8 @21; then
 * `blocks + 1` u32 index entries @0x18. An entry's low 31 bits shifted by the
 * index shift give the block's file offset; the next entry gives its end.
 * - CISO v0/v1: high bit = stored uncompressed, else raw deflate.
 * - CISO v2: compressed length >= block size = stored; else high bit = LZ4, clear = deflate.
 * - ZISO: high bit = stored, else raw LZ4 block.
 */
internal class CsoReader private constructor(
    private val src: ByteSource,
    private val lz4Default: Boolean,
    private val version: Int,
    override val size: Long,
    private val blockSize: Int,
    private val indexShift: Int,
) : ByteSource {
    private val blockCount = ((size + blockSize - 1) / blockSize).toInt()
    private var cachedIndex = -1
    private var cached: ByteArray? = null

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || pos + len > size) return false
        var done = 0
        while (done < len) {
            val abs = pos + done
            val block = block((abs / blockSize).toInt()) ?: return false
            val inBlock = (abs % blockSize).toInt()
            val n = minOf(len - done, block.size - inBlock)
            if (n <= 0) return false
            System.arraycopy(block, inBlock, buf, off + done, n)
            done += n
        }
        return true
    }

    private fun block(index: Int): ByteArray? {
        if (index == cachedIndex) return cached
        if (index !in 0 until blockCount) return null
        val idx = src.read(INDEX_OFFSET + index * 4L, 8) ?: return null
        val raw = le32(idx, 0)
        val next = le32(idx, 4)
        val flag = (raw and 0x80000000L) != 0L
        val start = (raw and 0x7FFFFFFFL) shl indexShift
        val end = (next and 0x7FFFFFFFL) shl indexShift
        val expected = minOf(blockSize.toLong(), size - index.toLong() * blockSize).toInt()
        val storedLen = (end - start).toInt()
        if (storedLen <= 0) return null
        val data = src.read(start, minOf(storedLen.toLong(), src.size - start).toInt()) ?: return null
        val out = when {
            lz4Default -> if (flag) data.copyOf(expected) else Lz4.decompressBlock(data, expected)
            version >= 2 && storedLen >= blockSize -> data.copyOf(expected)
            version >= 2 -> if (flag) Lz4.decompressBlock(data, expected) else inflateRaw(data, expected)
            flag -> data.copyOf(expected)
            else -> inflateRaw(data, expected)
        } ?: return null
        cachedIndex = index
        cached = out
        return out
    }

    override fun close() = src.close()

    companion object {
        private const val INDEX_OFFSET = 0x18L

        fun open(src: ByteSource): CsoReader? {
            val h = src.read(0, 0x18) ?: return null
            val magic = String(h, 0, 4, Charsets.US_ASCII)
            if (magic != "CISO" && magic != "ZISO") return null
            val total = le32(h, 8) or (le32(h, 12) shl 32)
            val blockSize = le32(h, 16).toInt()
            // Dolphin's GameCube CISO shares the magic but stores the block size at 4.
            if (total <= 0 || blockSize < 0x800 || blockSize > 0x100000 || blockSize and (blockSize - 1) != 0) return null
            return CsoReader(src, magic == "ZISO", h[20].toInt() and 0xFF, total, blockSize, h[21].toInt() and 0x1F)
        }

        private fun le32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
    }
}

/** Inflates a raw (headerless) deflate stream into exactly [outLen] bytes, or null. */
internal fun inflateRaw(data: ByteArray, outLen: Int): ByteArray? {
    val inflater = Inflater(true)
    return try {
        // nowrap mode may need one trailing dummy byte to finish the stream.
        inflater.setInput(data.copyOf(data.size + 1))
        val out = ByteArray(outLen)
        var done = 0
        while (done < outLen) {
            val n = inflater.inflate(out, done, outLen - done)
            if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            done += n
        }
        if (done == outLen) out else null
    } catch (_: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

/** LZ4 block format decoder (no frame header), as used by ZISO and CISO v2. */
internal object Lz4 {
    fun decompressBlock(src: ByteArray, outLen: Int): ByteArray? {
        val out = ByteArray(outLen)
        var s = 0
        var d = 0
        while (s < src.size) {
            val token = src[s++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) {
                while (true) {
                    if (s >= src.size) return null
                    val b = src[s++].toInt() and 0xFF
                    lit += b
                    if (b != 255) break
                }
            }
            if (s + lit > src.size || d + lit > outLen) return null
            System.arraycopy(src, s, out, d, lit)
            s += lit
            d += lit
            if (s >= src.size || d >= outLen) break // last sequence has literals only
            if (s + 2 > src.size) return null
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            if (offset == 0 || offset > d) return null
            var match = (token and 0x0F) + 4
            if ((token and 0x0F) == 15) {
                while (true) {
                    if (s >= src.size) return null
                    val b = src[s++].toInt() and 0xFF
                    match += b
                    if (b != 255) break
                }
            }
            if (d + match > outLen) return null
            var from = d - offset
            repeat(match) { out[d++] = out[from++] }
        }
        return if (d == outLen) out else null
    }
}
