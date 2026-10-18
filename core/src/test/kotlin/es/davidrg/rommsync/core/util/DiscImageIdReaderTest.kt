package es.davidrg.rommsync.core.util

import es.davidrg.rommsync.core.util.DiscFixtures.ChdCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Game IDs from compressed/containerised disc images (CSO/ZSO, CHD v5, RVZ/WIA,
 * WBFS, Dolphin CISO, zip). Fixtures are built in-test from tiny synthetic ISOs;
 * no real images are committed (chdman is not needed: [DiscFixtures.chd] writes
 * the v5 container, map and CD codec layout directly).
 */
class DiscImageIdReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File = tmp.newFile(name).apply { writeBytes(bytes) }

    private val systemCnf = "BOOT2 = cdrom0:\\SLUS_200.32;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n".toByteArray()

    private fun pspIso(discId: String, umd: Boolean): ByteArray {
        val files = linkedMapOf("PSP_GAME/PARAM.SFO" to DiscFixtures.paramSfo(discId), "PSP_GAME/ICON0.PNG" to ByteArray(3000))
        if (umd) files["UMD_DATA.BIN"] = "${discId.take(4)}-${discId.drop(4)}|0123456789ABCDEF|0001|G".toByteArray()
        return DiscFixtures.iso(files)
    }

    private fun ps2Iso(): ByteArray =
        DiscFixtures.iso(linkedMapOf("SYSTEM.CNF" to systemCnf, "SLUS_200.32" to ByteArray(10_000) { (it % 251).toByte() }))

    // ── PSP ──────────────────────────────────────────────────────────────

    @Test
    fun `psp iso reads PARAM-SFO through ISO9660`() {
        val f = write("game.iso", pspIso("ULUS10041", umd = false))
        assertEquals("ULUS10041", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp cso v1 reads UMD_DATA`() {
        val f = write("game.cso", DiscFixtures.cso(pspIso("NPUZ00085", umd = true)))
        assertEquals("NPUZ00085", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp cso without UMD_DATA falls back to PARAM-SFO`() {
        val f = write("game.cso", DiscFixtures.cso(pspIso("ULES00182", umd = false)))
        assertEquals("ULES00182", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp zso with lz4 blocks`() {
        val f = write("game.zso", DiscFixtures.cso(pspIso("ULJM05000", umd = true), ziso = true))
        assertEquals("ULJM05000", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp cso inside zip`() {
        val f = zip("game.zip", "readme.txt" to "hi".toByteArray(), "Game.cso" to DiscFixtures.cso(pspIso("ULUS10064", umd = true)))
        assertEquals("ULUS10064", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `cso block reader returns the original bytes`() {
        val iso = pspIso("ULUS10041", umd = true)
        val cso = CsoReader.open(FileByteSource(write("game.cso", DiscFixtures.cso(iso))))
        assertNotNull(cso)
        cso!!.use {
            assertEquals(iso.size.toLong(), it.size)
            assertArrayEquals(iso.copyOfRange(30_000, 41_000), it.read(30_000, 11_000))
        }
    }

    @Test
    fun `lz4 block decoder handles overlapping matches`() {
        // literals "abc" + match(offset 3, length 9)
        val block = byteArrayOf(0x35, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 0)
        assertArrayEquals("abcabcabcabc".toByteArray(), Lz4.decompressBlock(block, 12))
    }

    @Test
    fun `7z archives are not read`() {
        val f = write("game.7z", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) + ByteArray(4096))
        assertNull(RomHeaderIdReader.readGameId(f, "psp"))
    }

    // ── PS2 ──────────────────────────────────────────────────────────────

    @Test
    fun `ps2 iso reads SYSTEM-CNF through ISO9660`() {
        val f = write("game.iso", ps2Iso())
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    @Test
    fun `ps2 raw MODE2 bin`() {
        val iso = ps2Iso()
        val sectors = iso.size / 2048
        val bin = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val f = s * 2352
            for (k in 1..10) bin[f + k] = -1
            bin[f + 15] = 2
            iso.copyInto(bin, f + 24, s * 2048, (s + 1) * 2048)
        }
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(write("game.bin", bin), "ps2"))
    }

    @Test
    fun `ps2 chd with uncompressed map`() {
        val f = write("game.chd", DiscFixtures.chd(ps2Iso(), "MODE1", codec = null))
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    @Test
    fun `ps2 chd with compressed map and cdzl hunks`() {
        val f = write("game.chd", DiscFixtures.chd(ps2Iso(), "MODE1", ChdCodec.CDZL))
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    @Test
    fun `ps2 chd with cdlz hunks and MODE2_RAW track`() {
        val f = write("game.chd", DiscFixtures.chd(ps2Iso(), "MODE2_RAW", ChdCodec.CDLZ))
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    @Test
    fun `chd reader decodes every hunk type to the original frames`() {
        val iso = ps2Iso()
        val framesPerHunk = 4
        val hunkBytes = 2448 * framesPerHunk
        val chd = ChdReader.open(FileByteSource(write("game.chd", DiscFixtures.chd(iso, "MODE1_RAW", ChdCodec.CDLZ, framesPerHunk))))
        assertNotNull(chd)
        chd!!.use {
            val hunks = (it.size / hunkBytes).toInt()
            // Sector data of every frame matches the ISO (hunks alternate cdlz / NONE).
            val track = it.dataTrack()!!
            for (lba in 0 until iso.size / 2048) {
                assertArrayEquals("lba $lba", iso.copyOfRange(lba * 2048, (lba + 1) * 2048), track.readSector(lba.toLong()))
            }
            // The last hunk is a SELF reference to the one before it.
            assertArrayEquals(
                it.read((hunks - 2L) * hunkBytes, hunkBytes),
                it.read((hunks - 1L) * hunkBytes, hunkBytes),
            )
        }
    }

    @Test
    fun `chd with corrupt map crc is rejected`() {
        val bytes = DiscFixtures.chd(ps2Iso(), "MODE1", ChdCodec.CDZL)
        val mapOffset = (40 until 48).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xFF) }.toInt()
        bytes[mapOffset + 10] = (bytes[mapOffset + 10] + 1).toByte()
        assertNull(ChdReader.open(FileByteSource(write("bad.chd", bytes))))
    }

    @Test
    fun `ps2 iso inside zip`() {
        val f = zip("game.zip", "Game.iso" to ps2Iso())
        assertEquals("SLUS-20032", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    // ── GameCube / Wii ───────────────────────────────────────────────────

    private fun discHeader(id: String): ByteArray = ByteArray(0x80).also { id.toByteArray().copyInto(it) }

    @Test
    fun `gamecube rvz reads the dhead copy`() {
        val rvz = ByteArray(0x200)
        "RVZ\u0001".toByteArray(Charsets.ISO_8859_1).copyInto(rvz)
        discHeader("GAFE01").copyInto(rvz, 0x58)
        assertEquals("GAFE01", RomHeaderIdReader.readGameId(write("game.rvz", rvz), "gc"))
    }

    @Test
    fun `wii wia reads the dhead copy`() {
        val wia = ByteArray(0x200)
        "WIA\u0001".toByteArray(Charsets.ISO_8859_1).copyInto(wia)
        discHeader("RSBE01").copyInto(wia, 0x58)
        assertEquals("RSBE01", RomHeaderIdReader.readGameId(write("game.wia", wia), "wii"))
    }

    @Test
    fun `wii wbfs reads the disc header at the hd sector`() {
        val wbfs = ByteArray(0x1000)
        "WBFS".toByteArray().copyInto(wbfs)
        wbfs[8] = 9 // 512-byte sectors
        discHeader("SP3E41").copyInto(wbfs, 512)
        assertEquals("SP3E41", RomHeaderIdReader.readGameId(write("game.wbfs", wbfs), "wii"))
    }

    @Test
    fun `gamecube dolphin ciso reads block 0`() {
        val ciso = ByteArray(0x9000)
        "CISO".toByteArray().copyInto(ciso)
        ciso[6] = 0x20 // block size 0x200000 LE
        ciso[8] = 1
        discHeader("GXLE52").copyInto(ciso, 0x8000)
        assertEquals("GXLE52", RomHeaderIdReader.readGameId(write("game.ciso", ciso), "gc"))
    }

    @Test
    fun `gamecube rvz inside zip`() {
        val rvz = ByteArray(0x200)
        "RVZ\u0001".toByteArray(Charsets.ISO_8859_1).copyInto(rvz)
        discHeader("GW7E69").copyInto(rvz, 0x58)
        assertEquals("GW7E69", RomHeaderIdReader.readGameId(zip("game.zip", "Game.rvz" to rvz), "gc"))
    }

    @Test
    fun `gamecube unknown magic with invalid id returns null`() {
        val f = write("game.rvz", ByteArray(0x200) { 0x7F })
        assertNull(RomHeaderIdReader.readGameId(f, "gc"))
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(FileOutputStream(f)).use { z ->
            for ((entryName, data) in entries) {
                z.putNextEntry(ZipEntry(entryName))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }
}
