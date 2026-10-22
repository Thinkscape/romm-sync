package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.core.remote.dto.RomFileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Ps3FoldersTest {

    private val arkedo = RomDetailDto(id = 16, platformSlug = "ps3", fsName = "ARKEDO_SERIES_-_01_JUMP!_(Europe).ps3", fsPath = "roms/ps3/arcade-d")

    @Test
    fun `title id is read from param sfo`() {
        assertEquals("NPEB00598", Ps3Folders.titleId(sfo("CATEGORY" to "HG", "TITLE_ID" to "NPEB00598", "TITLE" to "JUMP!")))
    }

    @Test
    fun `malformed or missing title id is null`() {
        assertNull(Ps3Folders.titleId(ByteArray(0)))
        assertNull(Ps3Folders.titleId("not an sfo at all".toByteArray()))
        assertNull(Ps3Folders.titleId(sfo("CATEGORY" to "HG")))
        assertNull(Ps3Folders.titleId(sfo("TITLE_ID" to "../../etc")))
        assertNull(Ps3Folders.titleId(sfo("TITLE_ID" to "NPEB00598").copyOf(40)))
    }

    @Test
    fun `short name is the title id, tagged with the rom id when taken`() {
        assertEquals("NPEB00598.ps3", Ps3Folders.shortName(arkedo, "NPEB00598") { false })
        assertEquals("NPEB00598_16.ps3", Ps3Folders.shortName(arkedo, "NPEB00598") { it == "NPEB00598.ps3" })
        // The folder this rom is recorded in wins, so a re-push never moves the game.
        assertEquals("NPEB00598_16.ps3", Ps3Folders.shortName(arkedo, "NPEB00598", current = "NPEB00598_16.ps3") { false })
        assertEquals("NPEB00598.ps3", Ps3Folders.shortName(arkedo, "NPEB00598", current = "Other.ps3") { false })
    }

    @Test
    fun `without a title id the cut stem keeps the rom id and stays under 32 characters`() {
        val name = Ps3Folders.shortName(arkedo, null) { false }
        assertEquals("ARKEDO_SERIES_-_01_JUMP!_16.ps3", name)
        assertTrue(name.length <= Ps3Folders.MAX_DIR_NAME)
        val big = arkedo.copy(id = 1234567, fsName = "A_Very_Long_Game_Name_That_Goes_On_And_On_(USA).ps3")
        assertTrue(Ps3Folders.shortName(big, null) { false }.let { it.length <= Ps3Folders.MAX_DIR_NAME && it.endsWith("_1234567.ps3") })
    }

    @Test
    fun `only ps3 game folders over 31 characters need a short name`() {
        val dir = File("/roms/ps3")
        val files = listOf(
            file(1, "PARAM.SFO", "roms/ps3/arcade-d/${arkedo.fsName}"),
            file(2, "EBOOT.BIN", "roms/ps3/arcade-d/${arkedo.fsName}/USRDIR"),
        )
        val rom = arkedo.copy(files = files)
        assertTrue(Ps3Folders.needsShortName(rom, planInstall(rom, emptyList(), dir)))

        val shortRom = rom.copy(fsName = "Bejeweled_2_(USA).ps3", files = files.map { it.copy(filePath = it.filePath!!.replace(arkedo.fsName, "Bejeweled_2_(USA).ps3")) })
        assertFalse(Ps3Folders.needsShortName(shortRom, planInstall(shortRom, emptyList(), dir)))

        val psx = rom.copy(platformSlug = "psx")
        assertFalse(Ps3Folders.needsShortName(psx, planInstall(psx, emptyList(), dir)))

        val iso = rom.copy(fsName = "A_Very_Long_Game_Name_That_Goes_On_(USA).iso", files = listOf(file(3, "A_Very_Long_Game_Name_That_Goes_On_(USA).iso", "roms/ps3/arcade-d")))
        assertFalse(Ps3Folders.needsShortName(iso, planInstall(iso, emptyList(), dir)))
    }

    @Test
    fun `param sfo is found at the top of a psn folder or in PS3_GAME of a disc folder`() {
        val base = "roms/ps3/arcade-d/${arkedo.fsName}"
        val psn = arkedo.copy(files = listOf(file(1, "PARAM.SFO", base), file(2, "PARAM.SFO", "$base/USRDIR/extra")))
        assertEquals(1, Ps3Folders.paramSfo(psn)?.id)
        val disc = arkedo.copy(files = listOf(file(3, "PARAM.SFO", "$base/PS3_GAME"), file(4, "EBOOT.BIN", "$base/PS3_GAME/USRDIR")))
        assertEquals(3, Ps3Folders.paramSfo(disc)?.id)
        assertNull(Ps3Folders.paramSfo(arkedo.copy(files = listOf(file(5, "EBOOT.BIN", "$base/USRDIR")))))
    }

    private fun file(id: Int, name: String, path: String) =
        RomFileDto(id = id, fileName = name, filePath = path, fileSizeBytes = 1)

    companion object {
        /** A PARAM.SFO (PSF 1.1) holding [entries] as UTF-8 strings. */
        fun sfo(vararg entries: Pair<String, String>): ByteArray {
            val keys = ByteArrayOutputStream()
            val data = ByteArrayOutputStream()
            val index = ByteArrayOutputStream()
            fun le16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
            fun le32(out: ByteArrayOutputStream, v: Int) { le16(out, v and 0xffff); le16(out, (v ushr 16) and 0xffff) }
            for ((key, value) in entries) {
                val bytes = value.toByteArray() + 0
                val max = (bytes.size + 3) / 4 * 4
                le16(index, keys.size())
                le16(index, 0x0204)
                le32(index, bytes.size)
                le32(index, max)
                le32(index, data.size())
                keys.write(key.toByteArray() + 0)
                data.write(bytes + ByteArray(max - bytes.size))
            }
            while (keys.size() % 4 != 0) keys.write(0)
            val keyTable = 20 + index.size()
            val header = ByteArrayOutputStream()
            header.write(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
            le32(header, 0x0101)
            le32(header, keyTable)
            le32(header, keyTable + keys.size())
            le32(header, entries.size)
            return header.toByteArray() + index.toByteArray() + keys.toByteArray() + data.toByteArray()
        }
    }
}
