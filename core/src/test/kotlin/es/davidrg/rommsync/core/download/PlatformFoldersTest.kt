package es.davidrg.rommsync.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlatformFoldersTest {

    @Test
    fun `maps RomM slugs to launcher folders`() {
        assertEquals("n3ds", PlatformFolders.folderFor("3ds"))
        assertEquals("dreamcast", PlatformFolders.folderFor("dc"))
        assertEquals("nes", PlatformFolders.folderFor("nes"))
    }

    @Test
    fun `normalises case and whitespace`() {
        assertEquals("n3ds", PlatformFolders.folderFor(" 3DS "))
    }

    @Test
    fun `unknown slug has no folder`() {
        assertNull(PlatformFolders.folderFor("definitely-not-a-platform"))
        assertNull(PlatformFolders.platformDir("/roms", "definitely-not-a-platform"))
    }

    @Test
    fun `platformDir joins root and folder`() {
        assertEquals(File("/roms", "n3ds"), PlatformFolders.platformDir("/roms", "3ds"))
    }
}
