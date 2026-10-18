package es.davidrg.rommsync.core.sync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RommContentHashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `plain file hashes as md5 of its bytes`() {
        val srm = File(tmp.root, "Game.srm").apply { writeText("abc") }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", RommContentHash.of(srm))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", RommContentHash.of("abc".toByteArray()))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", RommContentHash.of(ByteArray(0)))
    }

    @Test
    fun `zip hashes as md5 of sorted name-md5 lines without directories`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                // Deliberately unsorted, with a directory entry.
                zip.putNextEntry(ZipEntry("b.srm")); zip.write("abc".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("a/")); zip.closeEntry()
                zip.putNextEntry(ZipEntry("a/x.bin")); zip.closeEntry()
            }
        }.toByteArray()
        val expected = md5(
            "a/x.bin:d41d8cd98f00b204e9800998ecf8427e\n" +
                "b.srm:900150983cd24fb0d6963f7d28e17f72",
        )
        val file = File(tmp.root, "save.zip").apply { writeBytes(bytes) }

        assertEquals(expected, RommContentHash.of(file))
        assertEquals(expected, RommContentHash.of(bytes))
    }

    @Test
    fun `bytes starting with PK but not a valid zip fall back to raw md5`() {
        val bogus = "PK\u0003\u0004 not really a zip".toByteArray()
        val file = File(tmp.root, "bogus.srm").apply { writeBytes(bogus) }
        val raw = MessageDigest.getInstance("MD5").digest(bogus).joinToString("") { "%02x".format(it) }
        assertEquals(raw, RommContentHash.of(file))
        assertEquals(raw, RommContentHash.of(bogus))
    }
}
