package es.davidrg.rommsync.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** ES-DE's settings file has no root element; this is the shape found on the Steam Deck. */
    private fun settings(romDirectory: String?): File = tmp.newFile("es_settings.xml").apply {
        writeText(
            buildString {
                appendLine("<?xml version=\"1.0\"?>")
                appendLine("<bool name=\"AlternativeEmulatorPerGame\" value=\"true\" />")
                appendLine("<string name=\"MediaDirectory\" value=\"/run/media/deck/arcade/Emulation/tools/downloaded_media\" />")
                if (romDirectory != null) appendLine("<string name=\"ROMDirectory\" value=\"$romDirectory\" />")
                appendLine("<string name=\"ThemeSet\" value=\"epic-noir\" />")
            },
        )
    }

    @Test
    fun `absolute ROMDirectory is used as is`() {
        val file = settings("/run/media/deck/arcade/Emulation/roms")
        assertEquals("/run/media/deck/arcade/Emulation/roms", DesktopConfig.esdeRomDirectory(file, "/home/deck"))
    }

    @Test
    fun `tilde and placeholders expand against home`() {
        assertEquals("/home/deck/Emulation/roms", DesktopConfig.esdeRomDirectory(settings("~/Emulation/roms"), "/home/deck"))
        tmp.delete(); tmp.create()
        assertEquals("/home/deck/ROMs", DesktopConfig.esdeRomDirectory(settings("%ROMPATH%"), "/home/deck"))
        tmp.delete(); tmp.create()
        assertEquals("/home/deck/roms", DesktopConfig.esdeRomDirectory(settings("%HOMEPATH%/roms"), "/home/deck"))
    }

    @Test
    fun `missing or empty ROMDirectory gives null`() {
        assertNull(DesktopConfig.esdeRomDirectory(settings(null), "/home/deck"))
        tmp.delete(); tmp.create()
        assertNull(DesktopConfig.esdeRomDirectory(settings(""), "/home/deck"))
        assertNull(DesktopConfig.esdeRomDirectory(File(tmp.root, "absent.xml"), "/home/deck"))
    }

    @Test
    fun `MediaDirectory is read from the same settings file`() {
        assertEquals(
            "/run/media/deck/arcade/Emulation/tools/downloaded_media",
            DesktopConfig.esdeMediaDirectory(settings(null), "/home/deck"),
        )
    }

    @Test
    fun `empty MediaDirectory gives null`() {
        val file = tmp.newFile("empty_media.xml").apply { writeText("<string name=\"MediaDirectory\" value=\"\" />\n") }
        assertNull(DesktopConfig.esdeMediaDirectory(file, "/home/deck"))
    }
}
