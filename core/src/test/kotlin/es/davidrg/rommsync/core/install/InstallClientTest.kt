package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.metadata.EsdeGameRef
import es.davidrg.rommsync.core.metadata.EsdeMetadata
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

    private fun romJson(
        id: Int, slug: String, fsName: String, fsPath: String, files: List<String>,
        cover: String? = null, fsSlug: String? = null,
    ) =
        """{"id":$id,"platform_slug":"$slug","platform_fs_slug":${fsSlug?.let { "\"$it\"" } ?: "null"},""" +
            """"fs_name":"$fsName","fs_path":"$fsPath","name":"x",""" +
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
    fun `arcade roms land in the folder of their library fs_slug`() = runBlocking {
        val naomi = "naomi".toByteArray()
        val mame = "mame".toByteArray()
        contents[12] = naomi
        contents[13] = mame
        roms[12] = romJson(12, "arcade", "18wheels.zip", "roms/naomi/usb31", listOf(fileJson(12, "18wheels.zip", "roms/naomi/usb31", naomi)), fsSlug = "naomi")
        roms[13] = romJson(13, "arcade", "pacman.zip", "roms/arcade/usb31", listOf(fileJson(13, "pacman.zip", "roms/arcade/usb31", mame)), fsSlug = "arcade")
        claim("r12" to (12 to listOf(12)), "r13" to (13 to listOf(13)))

        client().drainNow()

        assertArrayEquals(naomi, File(root, "naomi/18wheels.zip").readBytes())
        assertArrayEquals(mame, File(root, "arcade/pacman.zip").readBytes())
        assertFalse(File(root, "mame").exists())
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

            override fun allInstalled() = records.values.toList()
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
    fun `uninstall fallback uses the fs_slug folder`() = runBlocking {
        val bytes = "aw".toByteArray()
        roms[14] = romJson(14, "arcade", "dolphin.zip", "roms/atomiswave/usb31", listOf(fileJson(141, "dolphin.zip", "roms/atomiswave/usb31", bytes)), fsSlug = "atomiswave")
        val placed = File(root, "atomiswave/dolphin.zip").apply { parentFile.mkdirs(); writeBytes(bytes) }
        uninstallClaim("u14", 14)
        trackingClient().drainNow()
        assertFalse(placed.exists())
        assertEquals(listOf("u14" to """{"status":"done"}"""), reports.toList())
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

    // ── Files shared by platforms that map to one folder ────────────────

    @Test
    fun `identical file of two platforms is recorded for both and deleted only by the last uninstall`() = runBlocking {
        val bytes = "alleyway".toByteArray()
        contents[670] = bytes
        contents[1130] = bytes
        roms[67079] = romJson(67079, "gb", "Alleyway (World).gb", "roms/gb", listOf(fileJson(670, "Alleyway (World).gb", "roms/gb", bytes)), fsSlug = "gb")
        roms[113776] = romJson(113776, "gb", "Alleyway (World).gb", "roms/sgb", listOf(fileJson(1130, "Alleyway (World).gb", "roms/sgb", bytes)), fsSlug = "sgb")
        val shared = File(root, "gb/Alleyway (World).gb")
        val client = trackingClient()

        claim("i1" to (67079 to listOf(670)))
        client.drainNow()
        claim("i2" to (113776 to listOf(1130)))
        client.drainNow()

        assertEquals(listOf("i1" to """{"status":"done"}""", "i2" to """{"status":"already_installed"}"""), reports.toList())
        assertEquals(1, downloadRequests().size)
        assertEquals(shared, records[67079]!!.primary)
        assertEquals(shared, records[113776]!!.primary)

        reports.clear()
        uninstallClaim("u2", 113776)
        client.drainNow()
        assertEquals(listOf("u2" to """{"status":"done"}"""), reports.toList())
        assertArrayEquals(bytes, shared.readBytes())
        assertEquals(null, records[113776])
        assertTrue(records.containsKey(67079))
        assertTrue(removed.isEmpty())
        assertTrue(logs.toString(), logs.contains("uninstall rom 113776: kept ${shared.path} (rom 67079 uses it)"))

        reports.clear()
        uninstallClaim("u1", 67079)
        client.drainNow()
        assertEquals(listOf("u1" to """{"status":"done"}"""), reports.toList())
        assertFalse(shared.exists())
        assertEquals(listOf(67079 to "gb"), removed)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `different file at a path another rom recorded is refused, not overwritten`() = runBlocking {
        val mine = "genesis-dump".toByteArray()
        val theirs = "megadrive-dump".toByteArray()
        contents[201] = mine
        contents[202] = theirs
        roms[20] = romJson(20, "genesis", "Sonic.md", "roms/genesis", listOf(fileJson(201, "Sonic.md", "roms/genesis", mine)))
        roms[21] = romJson(21, "genesis", "Sonic.md", "roms/megadrive", listOf(fileJson(202, "Sonic.md", "roms/megadrive", theirs)), fsSlug = "megadrive")
        val target = File(root, "genesis/Sonic.md")
        val client = trackingClient()

        claim("i20" to (20 to listOf(201)))
        client.drainNow()
        claim("i21" to (21 to listOf(202)))
        client.drainNow()

        assertEquals("i20" to """{"status":"done"}""", reports.first())
        val (id, body) = reports.last()
        assertEquals("i21", id)
        assertTrue(body, body.contains("\"status\":\"failed\""))
        assertTrue(body, body.contains("${target.path} is installed for rom 20"))
        assertArrayEquals(mine, target.readBytes())
        assertEquals(1, downloadRequests().size)
        assertEquals(null, records[21])
        assertTrue(File(root, "genesis").listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `shared archive without a usable hash is compared byte for byte`() = runBlocking {
        // Composite (decompressed) sha1: unusable for the served bytes, so the download decides.
        val composite = "ac3922" + "0".repeat(34)
        val zip = "pacman-zip".toByteArray()
        val other = "pacman-zjp".toByteArray()
        contents[301] = zip
        contents[302] = zip
        contents[303] = other
        roms[30] = romJson(30, "arcade", "pacman.zip", "roms/arcade", listOf(fileJson(301, "pacman.zip", "roms/arcade", zip, sha1 = composite)), fsSlug = "arcade")
        roms[31] = romJson(31, "arcade", "pacman.zip", "roms/mame", listOf(fileJson(302, "pacman.zip", "roms/mame", zip, sha1 = composite)), fsSlug = "mame")
        roms[32] = romJson(32, "arcade", "pacman.zip", "roms/fbneo", listOf(fileJson(303, "pacman.zip", "roms/fbneo", other, sha1 = composite)), fsSlug = "fbneo")
        val target = File(root, "arcade/pacman.zip")
        val client = trackingClient()

        claim("i30" to (30 to listOf(301)))
        client.drainNow()
        claim("i31" to (31 to listOf(302)))
        client.drainNow()
        claim("i32" to (32 to listOf(303)))
        client.drainNow()

        assertEquals("i31" to """{"status":"already_installed"}""", reports[1])
        assertTrue(records.containsKey(31))
        val body = reports[2].second
        assertTrue(body, body.contains("\"status\":\"failed\""))
        assertTrue(body, body.contains("${target.path} is installed for rom 30"))
        assertEquals(null, records[32])
        assertArrayEquals(zip, target.readBytes())
        assertTrue(File(root, "arcade").listFiles().orEmpty().none { it.name.endsWith(".part") })

        // A re-push of a co-owner trusts its own record: no second compare download.
        val before = downloadRequests().size
        claim("i33" to (31 to listOf(302)))
        client.drainNow()
        assertEquals("i33" to """{"status":"already_installed"}""", reports.last())
        assertEquals(before, downloadRequests().size)
    }

    @Test
    fun `shared owner covers game folders both ways`() {
        val gameDir = File(root, "psx/Game.m3u")
        val disc = File(gameDir, "Game (Disc 1).chd")
        val folderRecord = InstalledRom(1, "psx", gameDir, listOf(gameDir))
        val fileRecord = InstalledRom(2, "psx", gameDir, listOf(disc))
        assertEquals(1, sharedOwner(disc, listOf(folderRecord))?.romId)
        assertEquals(2, sharedOwner(gameDir, listOf(fileRecord))?.romId)
        assertEquals(null, sharedOwner(File(root, "psx/Other.chd"), listOf(folderRecord, fileRecord)))
        assertEquals(null, sharedOwner(File(root, "psx/Game.m3u.bak"), listOf(folderRecord)))
    }

    // ── PS3 folder names (RPCS3 rejects 32+ characters) ─────────────────

    private fun ps3Rom(id: Int, fsName: String, sfo: ByteArray, eboot: ByteArray): String {
        contents[id * 10 + 1] = sfo
        contents[id * 10 + 2] = eboot
        return romJson(
            id, "ps3", fsName, "roms/ps3/arcade-d",
            listOf(
                fileJson(id * 10 + 1, "PARAM.SFO", "roms/ps3/arcade-d/$fsName", sfo, sha1 = null),
                fileJson(id * 10 + 2, "EBOOT.BIN", "roms/ps3/arcade-d/$fsName/USRDIR", eboot, sha1 = null),
            ),
        )
    }

    @Test
    fun `long ps3 game folder installs under its title id, stays there on re-push and uninstalls from there`() = runBlocking {
        val long = "ARKEDO_SERIES_-_01_JUMP!_(Europe).ps3"
        val sfo = Ps3FoldersTest.sfo("CATEGORY" to "HG", "TITLE_ID" to "NPEB00598")
        roms[16] = ps3Rom(16, long, sfo, "eboot-eu".toByteArray())
        roms[17] = ps3Rom(17, "ARKEDO_SERIES_-_01_JUMP!_(Europe)_(Rev_1).ps3", sfo, "eboot-rev1".toByteArray())
        roms[18] = ps3Rom(18, "Bejeweled_2_(USA).ps3", Ps3FoldersTest.sfo("TITLE_ID" to "NPUA30002"), "bejeweled".toByteArray())
        val client = trackingClient()

        claim("p16" to (16 to listOf(161, 162)), "p17" to (17 to listOf(171, 172)), "p18" to (18 to listOf(181, 182)))
        client.drainNow()

        val dir = File(root, "ps3/NPEB00598.ps3")
        assertEquals(listOf("p16", "p17", "p18").map { it to """{"status":"done"}""" }, reports.toList())
        assertArrayEquals(sfo, File(dir, "PARAM.SFO").readBytes())
        assertArrayEquals("eboot-eu".toByteArray(), File(dir, "USRDIR/EBOOT.BIN").readBytes())
        assertFalse(File(root, "ps3/$long").exists())
        assertEquals(dir, records[16]!!.primary)
        // Same title id, other dump: tagged with its rom id instead of clobbering rom 16.
        assertEquals(File(root, "ps3/NPEB00598_17.ps3"), records[17]!!.primary)
        assertArrayEquals("eboot-rev1".toByteArray(), File(root, "ps3/NPEB00598_17.ps3/USRDIR/EBOOT.BIN").readBytes())
        // Short names are left alone.
        assertEquals(File(root, "ps3/Bejeweled_2_(USA).ps3"), records[18]!!.primary)
        // ES-DE shows the .ps3 folder as one game.
        assertEquals(EsdeGameRef("./NPEB00598.ps3", "", "NPEB00598"), EsdeMetadata.gameRef(dir))

        reports.clear()
        claim("p16b" to (16 to listOf(161, 162)))
        client.drainNow()
        assertEquals(listOf("p16b" to """{"status":"already_installed"}"""), reports.toList())
        assertEquals(dir, records[16]!!.primary)

        reports.clear()
        uninstallClaim("u16", 16)
        client.drainNow()
        assertEquals(listOf("u16" to """{"status":"done"}"""), reports.toList())
        assertFalse(dir.exists())
        assertTrue(File(root, "ps3/NPEB00598_17.ps3/USRDIR/EBOOT.BIN").exists())
    }

    @Test
    fun `long ps3 folder without a readable title id falls back to a cut name with the rom id`() = runBlocking {
        val long = "ARKEDO_SERIES_-_01_JUMP!_(Europe).ps3"
        roms[16] = ps3Rom(16, long, "garbage".toByteArray(), "eboot".toByteArray())
        claim("p16" to (16 to listOf(161, 162)))

        trackingClient().drainNow()

        assertEquals(listOf("p16" to """{"status":"done"}"""), reports.toList())
        assertEquals(File(root, "ps3/ARKEDO_SERIES_-_01_JUMP!_16.ps3"), records[16]!!.primary)
    }

    companion object {
        private const val DEVICE = "dev-1"
    }
}
