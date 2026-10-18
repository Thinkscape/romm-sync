package es.davidrg.rommsync.core.util

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Builders for tiny synthetic disc images (ISO9660, CSO/ZSO, CHD v5) used by the tests. */
internal object DiscFixtures {
    private const val SECTOR = 2048

    /**
     * Minimal ISO9660 image: PVD at LBA 16, terminator at 17, root directory at 18,
     * one sector per sub-directory, then file data. [files] maps `NAME` or
     * `DIR/NAME` (one level) to contents.
     */
    fun iso(files: Map<String, ByteArray>): ByteArray {
        val dirs = files.keys.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
        var nextLba = 19 + dirs.size
        val fileLba = files.mapValues { (_, data) ->
            nextLba.also { nextLba += maxOf(1, (data.size + SECTOR - 1) / SECTOR) }
        }
        val image = ByteArray(nextLba * SECTOR)

        fun record(name: ByteArray, lba: Int, size: Int, dir: Boolean): ByteArray {
            val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
            val r = ByteArray(len)
            r[0] = len.toByte()
            putLe32(r, 2, lba); putBe32(r, 6, lba)
            putLe32(r, 10, size); putBe32(r, 14, size)
            r[25] = if (dir) 2 else 0
            r[28] = 1; r[31] = 1
            r[32] = name.size.toByte()
            System.arraycopy(name, 0, r, 33, name.size)
            return r
        }

        fun writeDir(lba: Int, parentLba: Int, entries: List<ByteArray>) {
            var p = lba * SECTOR
            for (r in listOf(record(byteArrayOf(0), lba, SECTOR, true), record(byteArrayOf(1), parentLba, SECTOR, true)) + entries) {
                System.arraycopy(r, 0, image, p, r.size)
                p += r.size
            }
        }

        // PVD + terminator
        val pvd = 16 * SECTOR
        image[pvd] = 1
        "CD001".toByteArray().copyInto(image, pvd + 1)
        image[pvd + 6] = 1
        record(byteArrayOf(0), 18, SECTOR, true).copyInto(image, pvd + 156)
        image[17 * SECTOR] = 0xFF.toByte()
        "CD001".toByteArray().copyInto(image, 17 * SECTOR + 1)

        val rootEntries = mutableListOf<ByteArray>()
        dirs.forEachIndexed { i, d -> rootEntries += record(d.toByteArray(), 19 + i, SECTOR, true) }
        files.filterKeys { '/' !in it }.forEach { (name, data) ->
            rootEntries += record("$name;1".toByteArray(), fileLba.getValue(name), data.size, false)
        }
        writeDir(18, 18, rootEntries)
        dirs.forEachIndexed { i, d ->
            val entries = files.filterKeys { it.startsWith("$d/") }.map { (path, data) ->
                record("${path.substringAfter('/')};1".toByteArray(), fileLba.getValue(path), data.size, false)
            }
            writeDir(19 + i, 18, entries)
        }
        files.forEach { (path, data) -> data.copyInto(image, fileLba.getValue(path) * SECTOR) }
        return image
    }

    /** PARAM.SFO with CATEGORY and DISC_ID entries. */
    fun paramSfo(discId: String): ByteArray {
        val keys = "CATEGORY\u0000DISC_ID\u0000"
        val data = "UG\u0000$discId\u0000"
        val keyTableStart = 0x14 + 2 * 16
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { for (i in 0 until 4) out.write((v shr (8 * i)) and 0xFF) }
        out.write(byteArrayOf(0x00, 0x50, 0x53, 0x46))
        u32(0x101); u32(keyTableStart); u32(keyTableStart + keys.length); u32(2)
        u16(0); u16(0x0204); u32(3); u32(4); u32(0)
        u16(9); u16(0x0204); u32(discId.length + 1); u32(16); u32(3)
        out.write(keys.toByteArray()); out.write(data.toByteArray())
        return out.toByteArray()
    }

    /**
     * CISO v1 (deflate) or ZISO (LZ4, literal-only blocks) with 2048-byte blocks;
     * blocks that do not shrink are stored with the high index bit set.
     */
    fun cso(image: ByteArray, ziso: Boolean = false): ByteArray {
        val blocks = (image.size + SECTOR - 1) / SECTOR
        val headerSize = 0x18 + (blocks + 1) * 4
        val out = ByteArrayOutputStream()
        val index = IntArray(blocks + 1)
        val body = ByteArrayOutputStream()
        for (b in 0 until blocks) {
            val raw = image.copyOfRange(b * SECTOR, minOf(image.size, (b + 1) * SECTOR))
            val packed = if (ziso) lz4Literals(raw) else deflateRaw(raw)
            index[b] = headerSize + body.size()
            if (packed.size < raw.size) {
                body.write(packed)
            } else {
                index[b] = index[b] or Int.MIN_VALUE
                body.write(raw)
            }
        }
        index[blocks] = headerSize + body.size()
        out.write((if (ziso) "ZISO" else "CISO").toByteArray())
        writeLe(out, 0x18L, 4)
        writeLe(out, image.size.toLong(), 8)
        writeLe(out, SECTOR.toLong(), 4)
        out.write(1); out.write(0); out.write(0); out.write(0)
        index.forEach { writeLe(out, it.toLong() and 0xFFFFFFFFL, 4) }
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /**
     * LZ4 block holding [raw] as literals, with runs of zeros encoded as
     * offset-1 matches so the block actually shrinks.
     */
    fun lz4Literals(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        var litStart = 0
        fun emit(litEnd: Int, matchLen: Int) {
            val lit = litEnd - litStart
            val m = if (matchLen > 0) matchLen - 4 else 0
            out.write((minOf(lit, 15) shl 4) or (if (matchLen > 0) minOf(m, 15) else 0))
            if (lit >= 15) { var r = lit - 15; while (r >= 255) { out.write(255); r -= 255 }; out.write(r) }
            out.write(raw, litStart, lit)
            if (matchLen > 0) {
                out.write(1); out.write(0)
                if (m >= 15) { var r = m - 15; while (r >= 255) { out.write(255); r -= 255 }; out.write(r) }
            }
        }
        // LZ4 requires the last 5 bytes to be literals.
        while (i < raw.size - 12) {
            if (raw[i].toInt() == 0 && i > litStart) {
                var run = 0
                while (i + 1 + run < raw.size - 5 && raw[i + 1 + run].toInt() == 0) run++
                if (run >= 4) {
                    emit(i + 1, run)
                    i += 1 + run
                    litStart = i
                    continue
                }
            }
            i++
        }
        emit(raw.size, 0)
        return out.toByteArray()
    }

    fun deflateRaw(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Raw LZMA (no header, no end marker), lc=3 lp=0 pb=2 as MAME's CHD codec writes it. */
    fun lzmaRaw(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val options = LZMA2Options(6).apply { dictSize = maxOf(4096, data.size) }
        LZMAOutputStream(out, options, false).use { it.write(data) }
        return out.toByteArray()
    }

    enum class ChdCodec(val tag: String) { CDZL("cdzl"), CDLZ("cdlz") }

    /**
     * CHD v5 CD image holding [iso] as a single data track of [trackType]
     * (`MODE1` = cooked 2048, `MODE1_RAW`/`MODE2_RAW` = 2352 with data at 16/24).
     * With [codec] null the map is uncompressed; otherwise the map is the v5
     * Huffman-coded form, hunk 0 is stored with that codec, the remaining hunks
     * alternate codec / NONE, and a trailing duplicate hunk is a SELF reference.
     */
    fun chd(iso: ByteArray, trackType: String, codec: ChdCodec?, framesPerHunk: Int = 8): ByteArray {
        val frameSize = 2448
        val hunkBytes = frameSize * framesPerHunk
        val dataOffset = when (trackType) { "MODE1_RAW" -> 16; "MODE2_RAW" -> 24; else -> 0 }
        val sectors = (iso.size + SECTOR - 1) / SECTOR
        val frames = (sectors + 3) / 4 * 4
        var hunks = (frames + framesPerHunk - 1) / framesPerHunk
        val withSelf = codec != null
        if (withSelf) hunks += 1 // extra zero hunk duplicated via SELF
        val logical = ByteArray(hunks * hunkBytes)
        for (s in 0 until sectors) {
            val f = s * frameSize
            if (dataOffset > 0) {
                logical[f] = 0
                for (k in 1..10) logical[f + k] = -1
                logical[f + 15] = if (trackType == "MODE2_RAW") 2 else 1
            }
            iso.copyInto(logical, f + dataOffset, s * SECTOR, minOf(iso.size, (s + 1) * SECTOR))
        }

        val meta = "TRACK:1 TYPE:$trackType SUBTYPE:NONE FRAMES:$frames PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0\u0000"
            .toByteArray()
        val metaOffset = 124L
        val metaEntry = ByteArrayOutputStream().apply {
            write("CHT2".toByteArray()); write(1); writeBe(this, meta.size.toLong(), 3); writeBe(this, 0, 8); write(meta)
        }.toByteArray()

        val file = ByteArrayOutputStream()
        val header = ByteArray(124)
        "MComprHD".toByteArray().copyInto(header)
        putBe(header, 8, 4, 124); putBe(header, 12, 4, 5)
        if (codec != null) codec.tag.toByteArray().copyInto(header, 16)
        putBe(header, 32, 8, logical.size.toLong())
        putBe(header, 48, 8, metaOffset)
        putBe(header, 56, 4, hunkBytes.toLong()); putBe(header, 60, 4, frameSize.toLong())

        if (codec == null) {
            // Uncompressed map: u32 per hunk = file offset / hunkBytes; data is hunk-aligned.
            val mapOffset = metaOffset + metaEntry.size
            val firstDataHunk = ((mapOffset + hunks * 4 + hunkBytes - 1) / hunkBytes).toInt()
            putBe(header, 40, 8, mapOffset)
            file.write(header); file.write(metaEntry)
            for (h in 0 until hunks) writeBe(file, (firstDataHunk + h).toLong(), 4)
            while (file.size() < firstDataHunk * hunkBytes) file.write(0)
            file.write(logical)
            return file.toByteArray()
        }

        // Compressed: hunk data first, then the map.
        val dataStart = metaOffset + metaEntry.size
        val types = IntArray(hunks)
        val lengths = IntArray(hunks)
        val crcs = IntArray(hunks)
        val body = ByteArrayOutputStream()
        for (h in 0 until hunks) {
            val hunk = logical.copyOfRange(h * hunkBytes, (h + 1) * hunkBytes)
            crcs[h] = Crc16().apply { update(hunk) }.value
            when {
                withSelf && h == hunks - 1 -> types[h] = 5 // SELF → hunk (hunks - 2), filled below
                h % 2 == 0 -> {
                    val packed = cdHunk(hunk, framesPerHunk, codec)
                    types[h] = 0; lengths[h] = packed.size; body.write(packed)
                }
                else -> {
                    types[h] = 4; lengths[h] = hunkBytes; body.write(hunk)
                }
            }
        }
        val selfTarget = hunks - 2

        val lengthBits = 24
        val selfBits = 16
        val bits = BitWriter()
        repeat(16) { bits.write(4, 4) }            // RLE tree: every code 4 bits long
        types.forEach { bits.write(it.toLong(), 4) } // canonical code == value
        val crc = Crc16()
        var cur = dataStart
        val entry = ByteArray(12)
        for (h in 0 until hunks) {
            var offset = cur
            var length = 0
            var hunkCrc = 0
            when (types[h]) {
                0 -> { bits.write(lengths[h].toLong(), lengthBits); bits.write(crcs[h].toLong(), 16); length = lengths[h]; hunkCrc = crcs[h]; cur += length }
                4 -> { bits.write(crcs[h].toLong(), 16); length = hunkBytes; hunkCrc = crcs[h]; cur += length }
                5 -> { bits.write(selfTarget.toLong(), selfBits); offset = selfTarget.toLong() }
            }
            entry[0] = types[h].toByte()
            putBe(entry, 1, 3, length.toLong()); putBe(entry, 4, 6, offset); putBe(entry, 10, 2, hunkCrc.toLong())
            crc.update(entry)
        }
        val mapBits = bits.toByteArray()
        val mapOffset = dataStart + body.size()
        putBe(header, 40, 8, mapOffset)
        file.write(header); file.write(metaEntry); file.write(body.toByteArray())
        writeBe(file, mapBits.size.toLong(), 4)
        writeBe(file, dataStart, 6)
        writeBe(file, crc.value.toLong(), 2)
        file.write(lengthBits); file.write(selfBits); file.write(0); file.write(0)
        file.write(mapBits)
        return file.toByteArray()
    }

    /** MAME CD codec layout: ECC bitmap, base length, base (2352 × frames), subcode (96 × frames). */
    private fun cdHunk(hunk: ByteArray, frames: Int, codec: ChdCodec): ByteArray {
        val base = ByteArray(frames * 2352)
        val sub = ByteArray(frames * 96)
        for (f in 0 until frames) {
            System.arraycopy(hunk, f * 2448, base, f * 2352, 2352)
            System.arraycopy(hunk, f * 2448 + 2352, sub, f * 96, 96)
        }
        val packedBase = if (codec == ChdCodec.CDLZ) lzmaRaw(base) else deflateRaw(base)
        val packedSub = deflateRaw(sub)
        val out = ByteArrayOutputStream()
        out.write(ByteArray((frames + 7) / 8))
        writeBe(out, packedBase.size.toLong(), if (hunk.size < 65536) 2 else 3)
        out.write(packedBase)
        out.write(packedSub)
        return out.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var acc = 0
        private var n = 0
        fun write(value: Long, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1).toInt()
                if (++n == 8) { out.write(acc); acc = 0; n = 0 }
            }
        }
        fun toByteArray(): ByteArray {
            if (n > 0) { out.write(acc shl (8 - n)); acc = 0; n = 0 }
            return out.toByteArray()
        }
    }

    private fun putLe32(b: ByteArray, off: Int, v: Int) { for (i in 0 until 4) b[off + i] = (v shr (8 * i)).toByte() }
    private fun putBe32(b: ByteArray, off: Int, v: Int) { for (i in 0 until 4) b[off + i] = (v shr (8 * (3 - i))).toByte() }
    private fun putBe(b: ByteArray, off: Int, len: Int, v: Long) { for (i in 0 until len) b[off + i] = (v ushr (8 * (len - 1 - i))).toByte() }
    private fun writeBe(out: ByteArrayOutputStream, v: Long, len: Int) { for (i in len - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF) }
    private fun writeLe(out: ByteArrayOutputStream, v: Long, len: Int) { for (i in 0 until len) out.write((v ushr (8 * i)).toInt() and 0xFF) }
}
