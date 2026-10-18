package es.davidrg.rommsync.core.metadata

import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class EsdeMediaExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scraper: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    private lateinit var roms: File
    private lateinit var esde: File
    private lateinit var media: File

    private val files = mapOf(
        "/assets/romm/resources/roms/1/10/cover/big.png" to ("image/png" to "COVER-PNG"),
        "/assets/romm/resources/roms/1/10/screenshots/0.jpg" to ("image/jpeg" to "SHOT"),
        "/assets/romm/resources/roms/1/10/manual/10.pdf" to ("application/pdf" to "%PDF-manual"),
        "/logo" to ("image/jpeg" to "WHEEL"),
    )

    @Before
    fun setUp() {
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (type, body) = files[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setHeader("Content-Type", type).setBody(Buffer().writeUtf8(body))
            }
        }
        server = MockWebServer().apply { this.dispatcher = dispatcher; start() }
        scraper = MockWebServer().apply { this.dispatcher = dispatcher; start() }
        roms = tmp.newFolder("roms")
        esde = File(tmp.root, "ES-DE")
        media = File(esde, "downloaded_media")
    }

    @After
    fun tearDown() {
        server.shutdown()
        scraper.shutdown()
    }

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    /** Scraper on another host name than the RomM server (127.0.0.1 vs localhost). */
    private val scraperLogo: String
        get() {
            val host = if (server.hostName == "127.0.0.1") "localhost" else "127.0.0.1"
            return "http://$host:${scraper.port}/logo?media=wheel"
        }

    private fun rom(fsName: String = "Game (USA).sfc", multi: Boolean = false) = RomDetailDto(
        id = 10,
        platformSlug = "snes",
        fsName = fsName,
        name = "Game & Co",
        hasMultipleFiles = multi,
        summary = "A <great> game",
        pathCoverLarge = "/assets/romm/resources/roms/1/10/cover/big.png?ts=2026-10-15 19:18:21",
        mergedScreenshots = listOf("/assets/romm/resources/roms/1/10/screenshots/0.jpg"),
        pathManual = "roms/1/10/manual/10.pdf",
        metadatum = mapOf(
            "genres" to listOf("Adventure"),
            "developers" to listOf("Nintendo R&D4"),
            "publishers" to listOf("Nintendo", "Playtronic"),
            "player_count" to "1",
            "first_release_date" to 509328000000.0,
            "average_rating" to 85.56,
        ),
        ssMetadata = mapOf("logo_url" to scraperLogo, "fanart_url" to null),
    )

    private fun exporter() = EsdeMediaExporter(OkHttpClient(), baseUrl, "rmm_token")

    private fun export(rom: RomDetailDto, primary: File, options: EsdeMediaExporter.Options = EsdeMediaExporter.Options()) =
        runBlocking { exporter().exportFor(rom, primary, "snes", esde, media, options) }

    private fun snes(vararg path: String) = File(roms, "snes/" + path.joinToString("/"))

    @Test
    fun `single-file game gets media named after the rom stem and a gamelist entry`() {
        val primary = snes("Game (USA).sfc").apply { parentFile.mkdirs(); writeText("rom") }
        val result = export(rom(), primary)

        assertEquals(emptyList<String>(), result.failures)
        assertEquals("COVER-PNG", File(media, "snes/covers/Game (USA).png").readText())
        assertEquals("SHOT", File(media, "snes/screenshots/Game (USA).jpg").readText())
        assertEquals("%PDF-manual", File(media, "snes/manuals/Game (USA).pdf").readText())
        // Extension-less scraper URL: named from Content-Type.
        assertEquals("WHEEL", File(media, "snes/marquees/Game (USA).jpg").readText())
        assertEquals(4, result.written.size)

        val gamelist = File(esde, "gamelists/snes/gamelist.xml").readText()
        assertTrue(gamelist.contains("<path>./Game (USA).sfc</path>"))
        assertTrue(gamelist.contains("<name>Game &amp; Co</name>"))
        assertTrue(gamelist.contains("<desc>A &lt;great&gt; game</desc>"))
        assertTrue(gamelist.contains("<rating>0.86</rating>"))
        assertTrue(gamelist.contains("<releasedate>19860221T000000</releasedate>"))
        assertTrue(gamelist.contains("<developer>Nintendo R&amp;D4</developer>"))
        assertTrue(gamelist.contains("<publisher>Nintendo, Playtronic</publisher>"))
        assertTrue(gamelist.contains("<genre>Adventure</genre>"))
        assertTrue(gamelist.contains("<players>1</players>"))

        // The token goes to RomM only, never to the scraper host.
        requests.forEach { request ->
            val toServer = request.requestUrl!!.port == server.port
            assertEquals(if (toServer) "Bearer rmm_token" else null, request.getHeader("Authorization"))
        }
        assertTrue(File(media, "snes/covers").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `m3u game folder is named like ES-DE shows the directory`() {
        val dir = snes("Final Fantasy VII.m3u").apply { mkdirs() }
        File(dir, "Final Fantasy VII.m3u").writeText("Disc 1.chd\n")
        val ref = EsdeMetadata.gameRef(dir)!!
        assertEquals(EsdeGameRef("./Final Fantasy VII.m3u", "", "Final Fantasy VII"), ref)

        export(rom("Final Fantasy VII", multi = true), dir)
        assertEquals("COVER-PNG", File(media, "snes/covers/Final Fantasy VII.png").readText())
        assertTrue(File(esde, "gamelists/snes/gamelist.xml").readText().contains("<path>./Final Fantasy VII.m3u</path>"))
    }

    @Test
    fun `plain multi-file folder uses its launch file and mirrors the sub folder`() {
        val dir = snes("Game (USA)").apply { mkdirs() }
        File(dir, "Game (USA) (Track 1).bin").writeText("xxxxxxxxxxxx")
        File(dir, "Game (USA).cue").writeText("cue")
        assertEquals(EsdeGameRef("./Game (USA)/Game (USA).cue", "Game (USA)/", "Game (USA)"), EsdeMetadata.gameRef(dir))

        export(rom("Game (USA)", multi = true), dir)
        assertEquals("COVER-PNG", File(media, "snes/covers/Game (USA)/Game (USA).png").readText())
    }

    @Test
    fun `stem drops only the last extension`() {
        assertEquals(EsdeGameRef("./Celeste.p8.png", "", "Celeste.p8"), EsdeMetadata.gameRef(File(roms, "pico8/Celeste.p8.png")))
    }

    @Test
    fun `media already present with the same size is kept`() {
        val primary = snes("Game (USA).sfc").apply { parentFile.mkdirs(); writeText("rom") }
        // Same length as the served cover, different bytes: proves it was not rewritten.
        val cover = File(media, "snes/covers/Game (USA).png").apply { parentFile.mkdirs(); writeText("cover-png") }
        // Present under the other accepted extension: kept, and never requested.
        val shot = File(media, "snes/screenshots/Game (USA).png").apply { parentFile.mkdirs(); writeText("user scrape") }

        val result = export(rom(), primary, EsdeMediaExporter.Options(setOf(EsdeMediaType.COVERS, EsdeMediaType.SCREENSHOTS)))

        assertEquals("cover-png", cover.readText())
        assertEquals("user scrape", shot.readText())
        assertEquals(cover, result.present[EsdeMediaType.COVERS])
        assertEquals(shot, result.present[EsdeMediaType.SCREENSHOTS])
        assertTrue(result.written.isEmpty())
        assertTrue(requests.none { it.requestUrl!!.encodedPath.contains("screenshots") })
        assertNull(File(media, "snes/screenshots/Game (USA).jpg").takeIf { it.exists() })
    }

    @Test
    fun `different size is replaced and missing sources fall back`() {
        val primary = snes("Game (USA).sfc").apply { parentFile.mkdirs(); writeText("rom") }
        val cover = File(media, "snes/covers/Game (USA).png").apply { parentFile.mkdirs(); writeText("old") }
        val rom = rom().copy(pathCoverLarge = "/assets/romm/resources/missing.png", urlCover = server.url("/assets/romm/resources/roms/1/10/cover/big.png").toString())

        val result = export(rom, primary, EsdeMediaExporter.Options(setOf(EsdeMediaType.COVERS), writeGamelist = false))

        assertEquals("COVER-PNG", cover.readText())
        assertEquals(cover, result.written[EsdeMediaType.COVERS])
        assertTrue(!File(esde, "gamelists").exists())
        assertEquals("./Game (USA).sfc", result.entry!!.path)
    }

    @Test
    fun `server and resource relative paths resolve against the base url`() {
        assertEquals("https://r.example/assets/romm/resources/roms/1/2/manual/2.pdf", EsdeMetadata.serverUrl("https://r.example/", "roms/1/2/manual/2.pdf"))
        assertEquals("https://r.example/sub/assets/x.png", EsdeMetadata.serverUrl("https://r.example/sub", "/assets/x.png"))
    }
}
