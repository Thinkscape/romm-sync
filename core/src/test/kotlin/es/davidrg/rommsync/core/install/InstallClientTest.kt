package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.metadata.EsdeTarget
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InstallClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var root: File

    /** rom id -> rom detail JSON */
    private val roms = mutableMapOf<Int, String>()
    /** file id -> content */
    private val contents = mutableMapOf<Int, ByteArray>()
    private var claimBody = "[]"

    private val reports: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val recorded = mutableListOf<Triple<RomDetailDto, File, List<File>>>()
    private val finished = mutableListOf<Pair<String, String>>()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Holds every media (`/assets/…`) response until released. */
    private val mediaGate = CountDownLatch(1)

    @Before
    fun setUp() {
        root = tmp.newFolder("roms")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val segments = request.requestUrl!!.pathSegments
                return when {
                    request.method == "POST" && segments == listOf("api", "devices", DEVICE, "installs", "claim") ->
                        MockResponse().setBody(claimBody)
                    request.method == "PUT" && segments.size == 5 && segments.take(4) == listOf("api", "devices", DEVICE, "installs") -> {
                        reports += segments[4] to request.body.readUtf8()
                        MockResponse().setBody("{}")
                    }
                    request.method == "GET" && request.requestUrl!!.encodedPath.startsWith("/assets/") -> {
                        mediaGate.await(15, TimeUnit.SECONDS)
                        MockResponse().setBody("cover")
                    }
                    request.method == "GET" && segments.size == 6 && segments[0] == "api" && segments[1] == "roms" &&
                        segments[3] == "files" && segments[4] == "content" -> {
                        // /api/roms/{file_id}/files/content/{file_name}
                        val bytes = contents[segments[2].toInt()]
                        if (bytes == null) MockResponse().setResponseCode(404).setBody("""{"detail":"File not found"}""")
                        else MockResponse().setBody(Buffer().write(bytes))
                    }
                    request.method == "GET" && segments.size == 3 && segments[0] == "api" && segments[1] == "roms" ->
                        roms[segments[2].toInt()]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        mediaGate.countDown()
        server.shutdown()
    }

    private fun client(downloadsAllowed: Boolean = true, esdeTarget: () -> EsdeTarget? = { null }) = InstallClient(
        baseUrl = server.url("/").toString(),
        token = "rmm_device",
        deviceId = DEVICE,
        romsRoot = { root.path },
        downloadsAllowed = { downloadsAllowed },
        store = object : InstalledRomStore {
            override fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>) {
                recorded += Triple(rom, primaryFile, files)
            }
        },
        listener = object : InstallListener {
            override fun onInstallFinished(requestId: String, romId: Int, status: String, reason: String?, files: List<File>) {
                finished += requestId to status
            }
        },
        httpClient = OkHttpClient(),
        logger = { logs += it },
        esdeTarget = esdeTarget,
    )

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileJson(id: Int, name: String, path: String, bytes: ByteArray, sha1: String? = sha1(bytes)) =
        """{"id":$id,"rom_id":0,"file_name":"$name","file_path":"$path","file_size_bytes":${bytes.size},""" +
            """"sha1_hash":${sha1?.let { "\"$it\"" } ?: "null"},"md5_hash":null,"crc_hash":null,"category":"game"}"""

    private fun romJson(id: Int, slug: String, fsName: String, fsPath: String, files: List<String>, cover: String? = null) =
        """{"id":$id,"platform_slug":"$slug","fs_name":"$fsName","fs_path":"$fsPath","name":"x",""" +
            """"path_cover_large":${cover?.let { "\"$it\"" } ?: "null"},"files":[${files.joinToString(",")}]}"""

    private fun claim(vararg entries: Pair<String, Pair<Int, List<Int>>>) {
        claimBody = entries.joinToString(",", "[", "]") { (id, rom) ->
            """{"id":"$id","user_id":1,"device_id":"$DEVICE","rom_id":${rom.first},"file_ids":${rom.second},""" +
                """"status":"taken","reason":null,"created_at":"2026-10-18T00:00:00Z","updated_at":"2026-10-18T00:00:00Z"}"""
        }
    }

    private fun downloadRequests() = requests.filter { it.requestUrl!!.pathSegments.getOrNull(3) == "files" }

    @Test
    fun `single file lands in the mapped platform folder`() = runBlocking {
        val bytes = "nes-rom-bytes".toByteArray()
        contents[11] = bytes
        roms[1] = romJson(1, "nes", "Mario (USA).nes", "roms/nes", listOf(fileJson(11, "Mario (USA).nes", "roms/nes", bytes)))
        claim("r1" to (1 to listOf(11)))

        client().drainNow()

        val target = File(root, "nes/Mario (USA).nes")
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(listOf("r1" to """{"status":"done"}"""), reports.toList())
        assertEquals(target, recorded.single().second)
        assertEquals(listOf("r1" to "done"), finished)
        assertTrue(logs.contains("claim: 1 pending"))
        assertEquals("/api/roms/11/files/content/Mario%20(USA).nes", downloadRequests().single().path)
        assertEquals("Bearer rmm_device", downloadRequests().single().getHeader("Authorization"))
    }

    @Test
    fun `multi file rom goes into a game folder renamed to m3u`() = runBlocking {
        val m3u = "Game (Disc 1).chd\nGame (Disc 2).chd\n".toByteArray()
        val d1 = "disc-one".toByteArray()
        val d2 = "disc-two".toByteArray()
        val extra = "notes".toByteArray()
        contents.putAll(mapOf(21 to m3u, 22 to d1, 23 to d2, 24 to extra))
        roms[2] = romJson(
            2, "dc", "Game", "roms/dc",
            listOf(
                fileJson(21, "Game.m3u", "roms/dc/Game", m3u),
                fileJson(22, "Game (Disc 1).chd", "roms/dc/Game", d1),
                fileJson(23, "Game (Disc 2).chd", "roms/dc/Game", d2),
                fileJson(24, "readme.txt", "roms/dc/Game/extras", extra),
            ),
        )
        claim("r2" to (2 to listOf(21, 22, 23, 24)))

        client().drainNow()

        val gameDir = File(root, "dreamcast/Game.m3u")
        assertArrayEquals(m3u, File(gameDir, "Game.m3u").readBytes())
        assertArrayEquals(d1, File(gameDir, "Game (Disc 1).chd").readBytes())
        assertArrayEquals(d2, File(gameDir, "Game (Disc 2).chd").readBytes())
        assertArrayEquals(extra, File(gameDir, "extras/readme.txt").readBytes())
        assertFalse(File(root, "dreamcast/Game").exists())
        assertEquals(gameDir, recorded.single().second)
        assertEquals(4, recorded.single().third.size)
        assertEquals(listOf("r2" to """{"status":"done"}"""), reports.toList())
    }

    @Test
    fun `existing file with matching size and sha1 is already installed`() = runBlocking {
        val bytes = "gba-rom".toByteArray()
        contents[31] = bytes
        roms[3] = romJson(3, "gba", "Game.gba", "roms/gba", listOf(fileJson(31, "Game.gba", "roms/gba", bytes)))
        File(root, "gba").mkdirs()
        File(root, "gba/Game.gba").writeBytes(bytes)
        claim("r3" to (3 to listOf(31)))

        client().drainNow()

        assertEquals(listOf("r3" to """{"status":"already_installed"}"""), reports.toList())
        assertTrue(downloadRequests().isEmpty())
        assertEquals(1, recorded.size)
    }

    @Test
    fun `sha1 mismatch fails and leaves no part file`() = runBlocking {
        val bytes = "genesis-rom".toByteArray()
        contents[41] = bytes
        roms[4] = romJson(
            4, "genesis", "Sonic.md", "roms/genesis",
            listOf(fileJson(41, "Sonic.md", "roms/genesis", bytes, sha1 = "0".repeat(40))),
        )
        claim("r4" to (4 to listOf(41)))

        client().drainNow()

        val (id, body) = reports.single()
        assertEquals("r4", id)
        assertTrue(body, body.contains("\"status\":\"failed\""))
        assertTrue(body, body.contains("sha1 mismatch for Sonic.md"))
        val platformDir = File(root, "genesis")
        assertFalse(File(platformDir, "Sonic.md").exists())
        assertTrue(platformDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `zip with composite sha1 installs and is then already installed`() = runBlocking {
        // RomM's sha1 for archives covers the decompressed members, never the served bytes.
        val bytes = "raw-zip-bytes".toByteArray()
        contents[81] = bytes
        roms[8] = romJson(
            8, "dos", "Keen 1.zip", "roms/dos",
            listOf(fileJson(81, "Keen 1.zip", "roms/dos", bytes, sha1 = "ac3922" + "0".repeat(34))),
        )
        claim("r8" to (8 to listOf(81)))
        val client = client()

        client.drainNow()
        assertArrayEquals(bytes, File(root, "dos/Keen 1.zip").readBytes())
        assertEquals(listOf("r8" to """{"status":"done"}"""), reports.toList())

        claim("r9" to (8 to listOf(81)))
        client.drainNow()
        assertEquals("r9" to """{"status":"already_installed"}""", reports.last())
        assertEquals(1, downloadRequests().size)
    }

    @Test
    fun `raw nes file with wrong sha1 still fails`() = runBlocking {
        val bytes = "nes-bytes".toByteArray()
        contents[91] = bytes
        roms[9] = romJson(9, "nes", "Zelda.nes", "roms/nes", listOf(fileJson(91, "Zelda.nes", "roms/nes", bytes, sha1 = "f".repeat(40))))
        claim("r10" to (9 to listOf(91)))

        client().drainNow()

        val body = reports.single().second
        assertTrue(body, body.contains("\"status\":\"failed\""))
        assertTrue(body, body.contains("sha1 mismatch for Zelda.nes"))
        assertFalse(File(root, "nes/Zelda.nes").exists())
        assertTrue(File(root, "nes").listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `unsupported platform fails with its slug`() = runBlocking {
        roms[5] = romJson(5, "zzz-unknown", "Thing.bin", "roms/zzz", listOf(fileJson(51, "Thing.bin", "roms/zzz", ByteArray(1))))
        claim("r5" to (5 to listOf(51)))

        client().drainNow()

        assertEquals(listOf("r5" to """{"status":"failed","reason":"unsupported platform zzz-unknown"}"""), reports.toList())
        assertTrue(downloadRequests().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `failure reason is truncated to 500 chars`() = runBlocking {
        val longSlug = "x".repeat(600)
        roms[6] = romJson(6, longSlug, "Thing.bin", "roms/x", listOf(fileJson(61, "Thing.bin", "roms/x", ByteArray(1))))
        claim("r6" to (6 to listOf(61)))

        client().drainNow()

        val body = reports.single().second
        val reason = Regex("\"reason\":\"([^\"]*)\"").find(body)!!.groupValues[1]
        assertEquals(500, reason.length)
    }

    @Test
    fun `no claim while downloads are not allowed`() = runBlocking {
        claim("r7" to (7 to listOf(71)))

        client(downloadsAllowed = false).drainNow()

        assertTrue(requests.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `slow media export does not delay the next install or its report`() = runBlocking {
        val a = "rom-a".toByteArray()
        val b = "rom-b".toByteArray()
        contents[101] = a
        contents[102] = b
        roms[10] = romJson(10, "nes", "A.nes", "roms/nes", listOf(fileJson(101, "A.nes", "roms/nes", a)), cover = "/assets/romm/resources/roms/1/10/cover/big.png")
        roms[11] = romJson(11, "nes", "B.nes", "roms/nes", listOf(fileJson(102, "B.nes", "roms/nes", b)), cover = "/assets/romm/resources/roms/1/11/cover/big.png")
        claim("r10" to (10 to listOf(101)), "r11" to (11 to listOf(102)))
        val esde = File(tmp.root, "ES-DE")
        val client = client(esdeTarget = { EsdeTarget(esde, File(esde, "downloaded_media")) })

        try {
            // Inline export would block on the gated cover of r10 before r11 is even downloaded.
            withTimeout(10_000) { client.drainNow() }

            assertEquals(listOf("r10" to """{"status":"done"}""", "r11" to """{"status":"done"}"""), reports.toList())
            assertArrayEquals(b, File(root, "nes/B.nes").readBytes())
            // The export for r10 is running (its cover request is held) while both reports are in.
            val deadline = System.currentTimeMillis() + 5_000
            while (requests.none { it.requestUrl!!.encodedPath.startsWith("/assets/") } && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(requests.any { it.requestUrl!!.encodedPath == "/assets/romm/resources/roms/1/10/cover/big.png" })
        } finally {
            mediaGate.countDown()
            client.stop()
        }
    }

    // ── Uninstall ───────────────────────────────────────────────────────

    private val records = mutableMapOf<Int, InstalledRom>()
    private val markers = mutableMapOf<Int, Set<String>>()
    private val removed = mutableListOf<Pair<Int, String>>()
    private val uninstalled = mutableListOf<Triple<String?, Int, String>>()

    private fun trackingClient() = InstallClient(
        baseUrl = server.url("/").toString(),
        token = "rmm_device",
        deviceId = DEVICE,
        romsRoot = { root.path },
        downloadsAllowed = { true },
        store = object : InstalledRomStore {
            override fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>) {
                records[rom.id] = InstalledRom(rom.id, rom.platformSlug, primaryFile, files)
            }

            override fun installed(romId: Int) = records[romId]
            override fun forgetInstalled(romId: Int) {
                records.remove(romId)
            }
        },
        listener = object : InstallListener {
            override fun onUninstallFinished(requestId: String?, romId: Int, status: String, reason: String?, files: List<File>) {
                uninstalled += Triple(requestId, romId, status)
            }
        },
        httpClient = OkHttpClient(),
        logger = { logs += it },
        provenance = object : es.davidrg.rommsync.core.collections.RomProvenanceStore {
            override fun markers(romId: Int) = markers[romId].orEmpty()
            override fun setMarkers(romId: Int, markers: Set<String>) {
                this@InstallClientTest.markers[romId] = markers
            }

            override fun allMarkers() = markers.filterValues { it.isNotEmpty() }
        },
        gameRemover = object : es.davidrg.rommsync.core.collections.LauncherGameRemover {
            override fun onRomRemoved(romId: Int, platformFolder: String, files: List<File>) {
                removed += romId to platformFolder
            }
        },
    )

    private fun uninstallClaim(id: String, romId: Int) {
        claimBody = """[{"id":"$id","rom_id":$romId,"file_ids":[],"status":"taken","reason":null,"action":"uninstall"}]"""
    }

    @Test
    fun `push records manual provenance`() = runBlocking {
        val bytes = "nes".toByteArray()
        contents[11] = bytes
        roms[1] = romJson(1, "nes", "Mario (USA).nes", "roms/nes", listOf(fileJson(11, "Mario (USA).nes", "roms/nes", bytes)))
        claim("r1" to (1 to listOf(11)))

        trackingClient().drainNow()

        assertEquals(setOf("manual"), markers[1])
    }

    @Test
    fun `uninstall deletes recorded files and empty folders but keeps foreign files`() = runBlocking {
        val gameDir = File(root, "psx/Game.m3u").apply { mkdirs() }
        val disc = File(gameDir, "Game (Disc 1).chd").apply { writeText("d1") }
        val m3u = File(gameDir, "Game.m3u").apply { writeText("Game (Disc 1).chd") }
        val other = File(root, "psx/Other.chd").apply { writeText("other") }
        val saves = File(tmp.root, "saves/duckstation/Game.mcd").apply { parentFile.mkdirs(); writeText("save") }
        records[5] = InstalledRom(5, "psx", gameDir, listOf(m3u, disc))
        markers[5] = setOf("manual")
        uninstallClaim("u5", 5)

        trackingClient().drainNow()

        assertFalse(gameDir.exists())
        assertTrue(other.exists())
        assertTrue(saves.exists())
        assertEquals(listOf("u5" to """{"status":"done"}"""), reports.toList())
        assertEquals(listOf(5 to "psx"), removed)
        assertEquals(null, records[5])
        assertEquals(emptySet<String>(), markers[5])
        assertEquals(listOf(Triple<String?, Int, String>("u5", 5, "done")), uninstalled)
    }

    @Test
    fun `uninstall keeps a game folder that holds files the install did not write`() = runBlocking {
        val gameDir = File(root, "dos/Keen").apply { mkdirs() }
        val exe = File(gameDir, "KEEN1.EXE").apply { writeText("exe") }
        val userSave = File(gameDir, "SAVEGAM0.CK1").apply { writeText("save") }
        records[6] = InstalledRom(6, "dos", gameDir, listOf(exe))
        uninstallClaim("u6", 6)

        trackingClient().drainNow()

        assertFalse(exe.exists())
        assertTrue(userSave.exists())
        assertEquals(listOf("u6" to """{"status":"done"}"""), reports.toList())
    }

    @Test
    fun `uninstall without a record falls back to romm's file list and reports not_installed when absent`() = runBlocking {
        val bytes = "nes".toByteArray()
        roms[7] = romJson(7, "nes", "Zelda.nes", "roms/nes", listOf(fileJson(71, "Zelda.nes", "roms/nes", bytes)))
        val placed = File(root, "nes/Zelda.nes").apply { parentFile.mkdirs(); writeBytes(bytes) }
        uninstallClaim("u7", 7)
        trackingClient().drainNow()
        assertFalse(placed.exists())
        assertEquals(listOf("u7" to """{"status":"done"}"""), reports.toList())

        reports.clear()
        uninstallClaim("u8", 7)
        trackingClient().drainNow()
        assertTrue(reports.single().second.contains("\"status\":\"not_installed\""))
    }

    @Test
    fun `uninstall refuses recorded paths outside the platform folder`() = runBlocking {
        val dir = File(root, "snes").apply { mkdirs() }
        val outside = File(tmp.root, "elsewhere.sfc").apply { writeText("x") }
        records[9] = InstalledRom(9, "snes", File(dir, "Game.sfc"), listOf(outside))
        uninstallClaim("u9", 9)

        trackingClient().drainNow()

        assertTrue(outside.exists())
        assertTrue(reports.single().second.contains("\"status\":\"failed\""))
    }

    companion object {
        private const val DEVICE = "dev-1"
    }
}
