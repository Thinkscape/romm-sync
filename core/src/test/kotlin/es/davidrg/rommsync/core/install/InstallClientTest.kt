package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import kotlinx.coroutines.runBlocking
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
    private val logs = mutableListOf<String>()

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
        server.shutdown()
    }

    private fun client(downloadsAllowed: Boolean = true) = InstallClient(
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
    )

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileJson(id: Int, name: String, path: String, bytes: ByteArray, sha1: String? = sha1(bytes)) =
        """{"id":$id,"rom_id":0,"file_name":"$name","file_path":"$path","file_size_bytes":${bytes.size},""" +
            """"sha1_hash":${sha1?.let { "\"$it\"" } ?: "null"},"md5_hash":null,"crc_hash":null,"category":"game"}"""

    private fun romJson(id: Int, slug: String, fsName: String, fsPath: String, files: List<String>) =
        """{"id":$id,"platform_slug":"$slug","fs_name":"$fsName","fs_path":"$fsPath","name":"x","files":[${files.joinToString(",")}]}"""

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

    companion object {
        private const val DEVICE = "dev-1"
    }
}
