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

    @Test
    fun `arcade slug installs into the arcade folder`() {
        assertEquals("arcade", PlatformFolders.folderFor("arcade"))
        assertEquals("arcade", PlatformFolders.folderFor("arcade", "arcade"))
    }

    @Test
    fun `arcade fs_slugs with their own ES-DE system keep that folder`() {
        for (fs in listOf("naomi", "atomiswave", "daphne", "triforce")) {
            assertEquals(fs, PlatformFolders.folderFor("arcade", fs))
        }
        assertEquals("naomi", PlatformFolders.folderFor(" Arcade ", " NAOMI "))
        assertEquals(File("/roms", "naomi"), PlatformFolders.platformDir("/roms", "arcade", "naomi"))
    }

    @Test
    fun `unknown arcade fs_slug falls back to the arcade folder`() {
        assertEquals("arcade", PlatformFolders.folderFor("arcade", "cps3"))
        assertEquals("arcade", PlatformFolders.folderFor("arcade", ""))
    }

    @Test
    fun `fs_slug overrides are scoped to their slug`() {
        assertEquals("n3ds", PlatformFolders.folderFor("3ds", "naomi"))
        assertNull(PlatformFolders.folderFor("definitely-not-a-platform", "naomi"))
    }
}
