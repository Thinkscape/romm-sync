package es.davidrg.rommsync.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonFolderPatternTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Cocoon tests the query with `Regex.containsMatchIn` against display name and file name. */
    private fun matches(pattern: String, name: String) = Regex(pattern).containsMatchIn(name)

    @Test
    fun `pattern matches exactly the member file names`() {
        val pattern = CocoonFolderPattern.build(listOf("Super Mario World (USA).sfc", "Doom (1993).zip"))
        assertEquals("^(?:Doom \\(1993\\)\\.zip|Super Mario World \\(USA\\)\\.sfc)$", pattern)
        assertTrue(matches(pattern, "Super Mario World (USA).sfc"))
        assertTrue(matches(pattern, "Doom (1993).zip"))
        assertFalse(matches(pattern, "Super Mario World (USA)"))
        assertFalse(matches(pattern, "Super Mario World (USA).sfc.bak"))
        assertFalse(matches(pattern, "Doom"))
    }

    @Test
    fun `regex metacharacters in names are literal`() {
        val name = "Q*bert [!] {v1.1} ^$ a+b|c? \\ (Rev 1).nes"
        val pattern = CocoonFolderPattern.build(listOf(name))
        assertTrue(matches(pattern, name))
        assertFalse(matches(pattern, "Qbert [!] {v1.1} ^$ a+b|c? \\ (Rev 1).nes"))
    }

    @Test
    fun `empty collection matches nothing`() {
        val pattern = CocoonFolderPattern.build(emptyList())
        assertEquals(CocoonFolderPattern.MATCH_NOTHING, pattern)
        assertFalse(matches(pattern, ""))
        assertFalse(matches(pattern, "anything.zip"))
    }

    @Test
    fun `pattern is stable regardless of order and duplicates`() {
        assertEquals(
            CocoonFolderPattern.build(listOf("b.zip", "a.zip", "b.zip")),
            CocoonFolderPattern.build(listOf("a.zip", "b.zip")),
        )
    }

    @Test
    fun `game folder contributes its own name and its direct files`() {
        val folder = tmp.newFolder("Final Fantasy VII (USA).m3u")
        listOf("Final Fantasy VII (USA).m3u", "Disc 1.chd", "Disc 2.chd").forEach { java.io.File(folder, it).writeText("x") }
        java.io.File(folder, "sub").mkdirs()
        val single = tmp.newFile("Tetris (World).gb")
        val names = CocoonFolderPattern.namesFor(listOf(folder, single))
        assertEquals(
            setOf("Final Fantasy VII (USA).m3u", "Disc 1.chd", "Disc 2.chd", "Tetris (World).gb"),
            names,
        )
    }
}
