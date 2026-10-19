package es.davidrg.rommsync.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeGamelistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val existing = """
        <?xml version="1.0"?>
        <gameList>
        	<!-- hand-made comment -->
        	<folder>
        		<path>./Hacks</path>
        		<name>Hacks</name>
        	</folder>
        	<game>
        		<path>./Other Game.zip</path>
        		<name>Other Game</name>
        		<playcount>4</playcount>
        	</game>
        	<game>
        		<path>./Zelda &amp; Friends (Europe).zip</path>
        		<name>Old Name</name>
        		<desc>Old desc</desc>
        		<favorite>true</favorite>
        		<lastplayed>20261017T120000</lastplayed>
        		<developer />
        	</game>
        </gameList>
    """.trimIndent() + "\n"

    @Test
    fun `upsert replaces managed fields of the matched game and keeps everything else`() {
        val entry = EsdeGameEntry(
            "./Zelda & Friends (Europe).zip",
            linkedMapOf("name" to "Zelda & Friends", "desc" to "New <desc>", "developer" to "Nintendo R&D4", "genre" to "Adventure"),
        )
        val merged = EsdeGamelist.upsert(existing, listOf(entry))

        // Other entries, the folder and the comment are untouched.
        assertTrue(merged.contains("<!-- hand-made comment -->"))
        assertTrue(merged.contains("<folder>\n\t\t<path>./Hacks</path>\n\t\t<name>Hacks</name>\n\t</folder>"))
        assertTrue(merged.contains("<path>./Other Game.zip</path>\n\t\t<name>Other Game</name>\n\t\t<playcount>4</playcount>"))
        // Matched entry: managed fields replaced/added (escaped), ES-DE's own fields kept.
        val block = Regex("""<game>\s*<path>./Zelda[\s\S]*?</game>""").find(merged)!!.value
        assertTrue(block.contains("<name>Zelda &amp; Friends</name>"))
        assertTrue(block.contains("<desc>New &lt;desc&gt;</desc>"))
        assertTrue(block.contains("<developer>Nintendo R&amp;D4</developer>"))
        assertTrue(block.contains("\t\t<genre>Adventure</genre>\n\t</game>"))
        assertTrue(block.contains("<favorite>true</favorite>"))
        assertTrue(block.contains("<lastplayed>20261017T120000</lastplayed>"))
        assertFalse(merged.contains("Old Name"))
        // No duplicate entry was appended.
        assertEquals(1, Regex("<path>./Zelda").findAll(merged).count())
    }

    @Test
    fun `new games are appended before the root close and existing text stays byte for byte`() {
        val merged = EsdeGamelist.upsert(existing, listOf(EsdeGameEntry("./New.sfc", linkedMapOf("name" to "New"))))
        assertTrue(merged.startsWith(existing.substringBefore("</gameList>")))
        assertTrue(merged.endsWith("\t<game>\n\t\t<path>./New.sfc</path>\n\t\t<name>New</name>\n\t</game>\n</gameList>\n"))
    }

    @Test
    fun `missing file is created with folders`() {
        val file = File(tmp.root, "ES-DE/gamelists/snes/gamelist.xml")
        EsdeGamelist.upsertFile(file, listOf(EsdeGameEntry("./Game.sfc", linkedMapOf("name" to "Game", "players" to "1-2"))))
        assertEquals(
            "<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./Game.sfc</path>\n\t\t<name>Game</name>\n" +
                "\t\t<players>1-2</players>\n\t</game>\n</gameList>\n",
            file.readText(),
        )
        assertFalse(File(file.parentFile, ".gamelist.xml.tmp").exists())
    }

    @Test
    fun `upsert is idempotent`() {
        val entry = EsdeGameEntry("./Other Game.zip", linkedMapOf("name" to "Other Game"))
        assertEquals(existing, EsdeGamelist.upsert(existing, listOf(entry)))
    }

    @Test
    fun `self-closing root is expanded in every whitespace form`() {
        for (root in listOf("<gameList />", "<gameList/>", "<gameList\n\t/>")) {
            val merged = EsdeGamelist.upsert("<?xml version=\"1.0\"?>\n$root\n", listOf(EsdeGameEntry("./a.nes", linkedMapOf("name" to "A"))))
            assertEquals(
                "<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./a.nes</path>\n\t\t<name>A</name>\n\t</game>\n</gameList>\n",
                merged,
            )
        }
    }

    @Test
    fun `ES-DE empty gamelist keeps its alternativeEmulator block byte for byte`() {
        // Shape ES-DE writes on the Deck (gamelists/n3ds/gamelist.xml), no trailing newline.
        val head = "<?xml version=\"1.0\"?>\n<alternativeEmulator>\n\t<label>Azahar (Standalone)</label>\n</alternativeEmulator>\n"
        val file = File(tmp.root, "ES-DE/gamelists/n3ds/gamelist.xml").apply { parentFile.mkdirs(); writeText("$head<gameList />") }

        EsdeGamelist.upsertFile(file, listOf(EsdeGameEntry("./Game.3ds", linkedMapOf("name" to "Game"))))

        assertEquals(
            head + "<gameList>\n\t<game>\n\t\t<path>./Game.3ds</path>\n\t\t<name>Game</name>\n\t</game>\n</gameList>",
            file.readText(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `unrecognised file is not overwritten`() {
        EsdeGamelist.upsert("not xml at all", listOf(EsdeGameEntry("./a.nes", emptyMap())))
    }

    @Test
    fun `remove drops the game, entries inside its folder and nothing else`() {
        val text = "<?xml version=\"1.0\"?>\n<gameList>\n" +
            "\t<game>\n\t\t<path>./Keep.nes</path>\n\t\t<playcount>3</playcount>\n\t</game>\n" +
            "\t<game>\n\t\t<path>./Drop &amp; Go.nes</path>\n\t</game>\n" +
            "\t<folder>\n\t\t<path>./Game</path>\n\t</folder>\n" +
            "\t<game>\n\t\t<path>./Game/disc1.cue</path>\n\t</game>\n" +
            "\t<game>\n\t\t<path>./Games/other.cue</path>\n\t</game>\n" +
            "</gameList>\n"

        val pruned = EsdeGamelist.remove(text, listOf("./Drop & Go.nes", "./Game"))

        assertEquals(
            "<?xml version=\"1.0\"?>\n<gameList>\n" +
                "\t<game>\n\t\t<path>./Keep.nes</path>\n\t\t<playcount>3</playcount>\n\t</game>\n" +
                "\t<game>\n\t\t<path>./Games/other.cue</path>\n\t</game>\n" +
                "</gameList>\n",
            pruned,
        )
    }
}
