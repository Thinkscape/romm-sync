package es.davidrg.rommsync.core.sync

import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * RomM's save/state `content_hash` (RomM 5.4 `assets_handler.hash_save_content`),
 * which is what `/api/sync/negotiate` compares and stores. It is NOT a sha1:
 *
 * - a zip archive (Python `zipfile.is_zipfile`: a readable central directory)
 *   hashes as `md5("\n".join(f"{name}:{md5(entry)}" for name in sorted(names)
 *   if not name.endswith("/")))`, entry bytes decompressed;
 * - anything else hashes as the md5 of its raw bytes.
 *
 * Local change tracking keeps using sha1; this value is only for the server.
 */
object RommContentHash {

    /** Python zipfile decodes names without the UTF-8 flag as cp437. */
    private val CP437: Charset = Charset.forName("IBM437")

    fun of(file: File): String = zipHash(file) ?: md5Hex(file)

    fun of(bytes: ByteArray): String {
        if (!looksLikeZip(bytes)) return md5Hex(bytes)
        val tmp = File.createTempFile("romm-hash", ".zip")
        return try {
            tmp.writeBytes(bytes)
            zipHash(tmp) ?: md5Hex(bytes)
        } finally {
            tmp.delete()
        }
    }

    /** Composite hash, or null when [file] is not a readable zip (then the raw md5 applies). */
    private fun zipHash(file: File): String? = try {
        ZipFile(file, CP437).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            val lines = names
                .filterNot { it.endsWith("/") }
                .sortedWith(::compareCodePoints)
                .map { name -> "$name:${md5Hex(zf.getInputStream(zf.getEntry(name)).use { it.readBytes() })}" }
            md5Hex(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    /** Cheap pre-check for in-memory content: an end-of-central-directory signature near the end. */
    private fun looksLikeZip(bytes: ByteArray): Boolean {
        val from = maxOf(0, bytes.size - (0xFFFF + 22))
        for (i in bytes.size - 22 downTo from) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
            ) return true
        }
        return false
    }

    /** Python sorts str by code point; Kotlin's default compares UTF-16 units. */
    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun md5Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(bytes))

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
