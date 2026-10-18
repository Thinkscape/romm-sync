package es.davidrg.rommsync.core.util

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Random-access view over the bytes of a (possibly virtual) disc image.
 * Implementations read only what is asked for; nothing loads a whole file.
 */
internal interface ByteSource : Closeable {
    val size: Long

    /** Fills [buf] from [pos]; false when the range is outside the source or unreadable. */
    fun readFully(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size): Boolean

    fun read(pos: Long, len: Int): ByteArray? {
        if (pos < 0 || len < 0 || pos + len > size) return null
        val buf = ByteArray(len)
        return if (readFully(pos, buf)) buf else null
    }
}

internal class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || pos + len > size) return false
        raf.seek(pos)
        raf.readFully(buf, off, len)
        return true
    }

    override fun close() = raf.close()
}

/**
 * One entry of a zip read through its (inflating) stream. Forward reads skip ahead;
 * a backward read reopens the entry, so access stays bounded by the furthest byte
 * needed and nothing is extracted to disk. Owns and closes the [ZipFile].
 */
internal class ZipEntryByteSource(private val zip: ZipFile, private val entry: ZipEntry) : ByteSource {
    override val size: Long = entry.size
    private var stream: InputStream? = null
    private var position = 0L

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || pos + len > size) return false
        if (stream == null || pos < position) {
            stream?.close()
            stream = zip.getInputStream(entry)
            position = 0L
        }
        val input = stream!!
        while (position < pos) {
            val skipped = input.skip(pos - position)
            if (skipped <= 0) {
                if (input.read() < 0) return false
                position++
            } else {
                position += skipped
            }
        }
        var done = 0
        while (done < len) {
            val n = input.read(buf, off + done, len - done)
            if (n < 0) return false
            done += n
        }
        position += len
        return true
    }

    override fun close() {
        stream?.close()
        zip.close()
    }
}

/** A view of [inner] starting at [base], used for container formats with a fixed header. */
internal class OffsetByteSource(private val inner: ByteSource, private val base: Long) : ByteSource {
    override val size: Long = inner.size - base
    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Boolean =
        pos >= 0 && inner.readFully(base + pos, buf, off, len)
    override fun close() = inner.close()
}

/** 2048-byte user-data sectors of a data track, addressed by LBA. */
internal interface SectorSource : Closeable {
    fun readSector(lba: Long): ByteArray?

    /** Concatenated user data of sectors [0, count), stopping at the first unreadable one. */
    fun readUserData(count: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(count * SECTOR_SIZE)
        for (lba in 0 until count) out.write(readSector(lba.toLong()) ?: break)
        return out.toByteArray()
    }

    companion object {
        const val SECTOR_SIZE = 2048
    }
}

/**
 * Sectors stored every [frameSize] bytes from [base], user data at [dataOffset]
 * inside each frame: 2048/0 for a cooked ISO, 2352/16 (MODE1) or 2352/24 (MODE2
 * form 1) for raw BIN images, 2448/x for CHD CD frames.
 */
internal class FramedSectorSource(
    private val src: ByteSource,
    private val frameSize: Int,
    private val dataOffset: Int,
    private val base: Long = 0L,
) : SectorSource {
    override fun readSector(lba: Long): ByteArray? =
        src.read(base + lba * frameSize + dataOffset, SectorSource.SECTOR_SIZE)

    override fun close() = src.close()
}

/** Minimal ISO9660 lookup: enough to read a few small files by path. */
internal object Iso9660 {
    private const val MAX_DIR_SECTORS = 64

    data class Entry(val lba: Long, val size: Long, val isDir: Boolean)

    fun isIso(src: SectorSource): Boolean {
        val pvd = src.readSector(16) ?: return false
        return pvd[0].toInt() == 1 && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001"
    }

    /** Looks up a `/`-separated path case-insensitively, ignoring `;1` version suffixes. */
    fun find(src: SectorSource, path: String): Entry? {
        val pvd = src.readSector(16) ?: return null
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        var current = parseRecord(pvd, 156) ?: return null
        for (part in path.trim('/').split('/')) {
            if (!current.isDir) return null
            current = listDir(src, current).firstOrNull { it.first.equals(part, ignoreCase = true) }?.second
                ?: return null
        }
        return current
    }

    fun readFile(src: SectorSource, path: String, maxBytes: Int): ByteArray? {
        val entry = find(src, path) ?: return null
        if (entry.isDir) return null
        val len = minOf(entry.size, maxBytes.toLong()).toInt()
        val out = ByteArray(len)
        var done = 0
        var lba = entry.lba
        while (done < len) {
            val sector = src.readSector(lba++) ?: return null
            val n = minOf(SectorSource.SECTOR_SIZE, len - done)
            System.arraycopy(sector, 0, out, done, n)
            done += n
        }
        return out
    }

    private fun listDir(src: SectorSource, dir: Entry): List<Pair<String, Entry>> {
        val result = mutableListOf<Pair<String, Entry>>()
        val sectors = minOf((dir.size + SectorSource.SECTOR_SIZE - 1) / SectorSource.SECTOR_SIZE, MAX_DIR_SECTORS.toLong())
        for (i in 0 until sectors) {
            val sector = src.readSector(dir.lba + i) ?: break
            var p = 0
            while (p < SectorSource.SECTOR_SIZE) {
                val recLen = sector[p].toInt() and 0xFF
                if (recLen == 0 || p + recLen > SectorSource.SECTOR_SIZE) break
                val nameLen = sector[p + 32].toInt() and 0xFF
                val entry = parseRecord(sector, p)
                if (entry != null && nameLen > 0 && p + 33 + nameLen <= SectorSource.SECTOR_SIZE) {
                    val raw = String(sector, p + 33, nameLen, Charsets.ISO_8859_1)
                    // "\u0000" and "\u0001" are "." and ".."
                    if (nameLen != 1 || raw[0].code > 1) {
                        result += raw.substringBefore(';').trimEnd('.') to entry
                    }
                }
                p += recLen
            }
        }
        return result
    }

    private fun parseRecord(b: ByteArray, off: Int): Entry? {
        if (off + 34 > b.size) return null
        val lba = le32(b, off + 2)
        val size = le32(b, off + 10)
        val isDir = (b[off + 25].toInt() and 0x02) != 0
        return Entry(lba, size, isDir)
    }

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
}
