package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.collections.CollectionMember
import es.davidrg.rommsync.core.collections.DeviceCollection
import es.davidrg.rommsync.core.metadata.EsdeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeCollectionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var roms: File
    private lateinit var esde: File
    private lateinit var ledger: EsdeCollectionLedger
    private lateinit var writer: EsdeCollectionWriter
    private val submitted = mutableListOf<String>()

    /** Runs edits inline, as when ES-DE is not running. */
    private val editor = EsdeEditor { description, edit ->
        submitted += description
        edit()
    }

    @Before
    fun setUp() {
        roms = tmp.newFolder("roms")
        esde = tmp.newFolder("ES-DE")
        File(esde, "settings").mkdirs()
        settingsFile().writeText(
            """
            <?xml version="1.0"?>
            <string name="CollectionSystemsAuto" value="favorites,recent" />
            <string name="CollectionSystemsCustom" value="" />
            <string name="ROMDirectory" value="${roms.path}" />
            """.trimIndent() + "\n",
        )
        ledger = EsdeCollectionLedger(tmp.newFile("esde-collections.properties").also { it.delete() })
        writer = EsdeCollectionWriter({ esde }, { roms.path }, EsdeRomPaths { roms.path }, ledger, editor)
    }

    private fun settingsFile() = File(File(esde, "settings"), "es_settings.xml")
    private fun enabled() = EsdeSettings.customCollections(settingsFile().readText())
    private fun cfg(name: String) = File(File(esde, "collections"), "custom-$name.cfg")
    private fun collection(name: String, id: Int = 1, updatedAt: String = "t1") =
        DeviceCollection("regular", id, name, "A description ES-DE cannot show", emptyList(), "/cover.png", updatedAt)

    private fun file(path: String, text: String = "x"): File =
        File(roms, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun single(romId: Int, folder: String, name: String) = CollectionMember(romId, folder, listOf(file("$folder/$name")))

    @Test
    fun `writes rompath lines for files, m3u sets and launch files in game folders, and enables the collection`() {
        val smw = single(1, "snes", "Super Mario World (USA).sfc")
        val ff7 = CollectionMember(2, "psx", listOf(file("psx/Final Fantasy VII.m3u/FF7.m3u"), file("psx/Final Fantasy VII.m3u/FF7 (Disc 1).chd")))
        val crash = CollectionMember(3, "psx", listOf(file("psx/Crash/Crash (USA).cue"), file("psx/Crash/Crash (USA).bin", "bigger bin")))

        writer.write(collection("Favourite RPGs"), listOf(smw, ff7, crash), cover = null)

        assertEquals(
            listOf(
                "%ROMPATH%/psx/Crash/Crash (USA).cue",
                "%ROMPATH%/psx/Final Fantasy VII.m3u",
                "%ROMPATH%/snes/Super Mario World (USA).sfc",
            ),
            EsdeCollectionWriter.readLines(cfg("Favourite RPGs")),
        )
        assertEquals(listOf("Favourite RPGs"), enabled())
        val entry = ledger.get("regular:1")!!
        assertTrue(entry.created)
        assertEquals("Favourite RPGs", entry.name)
    }

    @Test
    fun `games the user added in es-de survive updates and member removal`() {
        val a = single(1, "snes", "A.sfc")
        val b = single(2, "snes", "B.sfc")
        writer.write(collection("Mix"), listOf(a, b), null)
        cfg("Mix").appendText("%ROMPATH%/gba/User Pick.gba\n")

        writer.write(collection("Mix", updatedAt = "t2"), listOf(a), null)

        assertEquals(
            listOf("%ROMPATH%/snes/A.sfc", "%ROMPATH%/gba/User Pick.gba").sorted(),
            EsdeCollectionWriter.readLines(cfg("Mix")).sorted(),
        )
    }

    @Test
    fun `a collection the user disabled in es-de is not enabled again`() {
        val a = single(1, "snes", "A.sfc")
        writer.write(collection("Mix"), listOf(a), null)
        EsdeSettings.update(esde) { emptyList() }

        writer.write(collection("Mix", updatedAt = "t2"), listOf(a, single(2, "snes", "B.sfc")), null)

        assertEquals(emptyList<String>(), enabled())
        assertEquals(2, EsdeCollectionWriter.readLines(cfg("Mix")).size)
    }

    @Test
    fun `an existing user collection with the same name is adopted and never deleted`() {
        File(esde, "collections").mkdirs()
        File(File(esde, "collections"), "custom-racing.cfg").writeText("%ROMPATH%/n64/Mario Kart 64 (USA).z64\n")
        EsdeSettings.update(esde) { listOf("racing") }
        val a = single(1, "snes", "Super Mario Kart (USA).sfc")

        writer.write(collection("Racing"), listOf(a), null)
        assertEquals(listOf("racing"), enabled())
        assertFalse("adopted, not created", ledger.get("regular:1")!!.created)
        assertEquals(2, EsdeCollectionWriter.readLines(cfg("racing")).size)

        writer.remove("regular:1", "Racing")

        assertEquals(listOf("%ROMPATH%/n64/Mario Kart 64 (USA).z64"), EsdeCollectionWriter.readLines(cfg("racing")))
        assertEquals(listOf("racing"), enabled())
    }

    @Test
    fun `removing a collection we created deletes the file and its settings entry`() {
        writer.write(collection("Mix"), listOf(single(1, "snes", "A.sfc")), null)

        writer.remove("regular:1", "Mix")

        assertFalse(cfg("Mix").exists())
        assertEquals(emptyList<String>(), enabled())
        assertEquals(null, ledger.get("regular:1"))
    }

    @Test
    fun `removing a collection we created keeps it when the user added games`() {
        writer.write(collection("Mix"), listOf(single(1, "snes", "A.sfc")), null)
        cfg("Mix").appendText("%ROMPATH%/gba/User Pick.gba\n")

        writer.remove("regular:1", "Mix")

        assertEquals(listOf("%ROMPATH%/gba/User Pick.gba"), EsdeCollectionWriter.readLines(cfg("Mix")))
        assertEquals(listOf("Mix"), enabled())
    }

    @Test
    fun `rename in romm moves the file and the enabled entry`() {
        val a = single(1, "snes", "A.sfc")
        writer.write(collection("Old Name"), listOf(a), null)
        EsdeSettings.update(esde) { it + "Other" }

        writer.write(collection("New Name", updatedAt = "t2"), listOf(a), null)

        assertFalse(cfg("Old Name").exists())
        assertEquals(listOf("%ROMPATH%/snes/A.sfc"), EsdeCollectionWriter.readLines(cfg("New Name")))
        assertEquals(listOf("New Name", "Other"), enabled())
    }

    @Test
    fun `names lose characters es-de forbids and avoid system folder names`() {
        assertEquals("Best of 90s Hits", EsdeCollectionWriter.esdeName(collection("Best of: 90s \"Hits\""), emptySet()))
        assertEquals("SNES (RomM)", EsdeCollectionWriter.esdeName(collection("SNES"), setOf("snes")))
        assertEquals("RomM regular 7", EsdeCollectionWriter.esdeName(collection("./", id = 7), emptySet()))
    }

    @Test
    fun `settings line is added when missing and values are escaped`() {
        val text = "<?xml version=\"1.0\"?>\n<string name=\"Theme\" value=\"linear\" />\n"
        val updated = EsdeSettings.withCustomCollections(text, listOf("Tom & Jerry", "Mix"))
        assertTrue(updated.endsWith("<string name=\"CollectionSystemsCustom\" value=\"Tom &amp; Jerry,Mix\" />\n"))
        assertEquals(listOf("Tom & Jerry", "Mix"), EsdeSettings.customCollections(updated))
    }

    @Test
    fun `remover drops gamelist entry, media and collection lines`() {
        val media = tmp.newFolder("media")
        val sfc = file("snes/A (USA).sfc")
        writer.write(collection("Mix"), listOf(CollectionMember(1, "snes", listOf(sfc)), single(2, "snes", "B.sfc")), null)
        File(esde, "collections/custom-User.cfg").writeText("%ROMPATH%/snes/A (USA).sfc\n%ROMPATH%/gba/C.gba\n")
        File(esde, "gamelists/snes").mkdirs()
        File(esde, "gamelists/snes/gamelist.xml").writeText(
            "<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./A (USA).sfc</path>\n\t\t<name>A</name>\n\t</game>\n" +
                "\t<game>\n\t\t<path>./B.sfc</path>\n\t\t<name>B</name>\n\t</game>\n</gameList>\n",
        )
        val cover = File(media, "snes/covers/A (USA).png").apply { parentFile.mkdirs(); writeText("png") }
        val other = File(media, "snes/covers/B.png").apply { writeText("png") }
        sfc.delete()

        val remover = EsdeGameRemover({ EsdeTarget(esde, media) }, EsdeRomPaths { roms.path }, ledger, editor)
        remover.onRomRemoved(1, "snes", listOf(sfc))

        val gamelist = File(esde, "gamelists/snes/gamelist.xml").readText()
        assertFalse(gamelist.contains("A (USA)"))
        assertTrue(gamelist.contains("./B.sfc"))
        assertFalse(cover.exists())
        assertTrue(other.exists())
        assertEquals(listOf("%ROMPATH%/snes/B.sfc"), EsdeCollectionWriter.readLines(cfg("Mix")))
        assertEquals(listOf("%ROMPATH%/gba/C.gba"), EsdeCollectionWriter.readLines(cfg("User")))
        assertEquals(setOf("%ROMPATH%/snes/B.sfc"), ledger.get("regular:1")!!.lines)
    }

    @Test
    fun `nothing is written while the editor holds the change`() {
        val queued = mutableListOf<() -> Unit>()
        val deferred = EsdeCollectionWriter({ esde }, { roms.path }, EsdeRomPaths { roms.path }, ledger, EsdeEditor { _, edit -> queued += edit })

        deferred.write(collection("Later"), listOf(single(1, "snes", "A.sfc")), null)
        assertFalse(cfg("Later").exists())

        queued.single()()
        assertTrue(cfg("Later").exists())
    }
}
