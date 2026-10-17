package es.davidrg.rommsync.core.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DevicePairingTest {

    private lateinit var server: MockWebServer
    private val pairing = DevicePairing(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    private fun initResponse(expiresIn: Int = 600, interval: Int = 5) = MockResponse()
        .setResponseCode(201)
        .setBody(
            """{"device_code":"dc-secret","user_code":"12345678","verification_path":"/pair/device",""" +
                """"verification_path_complete":"/pair/device?user_code=12345678","expires_in":$expiresIn,"interval":$interval}""",
        )

    private fun detail(code: Int, detail: String) =
        MockResponse().setResponseCode(code).setBody("""{"detail":"$detail"}""")

    private fun tokenResponse() = MockResponse().setBody(
        """{"access_token":"rmm_token","device_id":"dev-1","scopes":["devices.read"],"expires_at":null}""",
    )

    private fun deviceResponse() = MockResponse().setBody(
        """{"id":"dev-1","name":"Steam Deck","capabilities":{"remote_install":true},"sync_mode":"api","sync_enabled":true}""",
    )

    private suspend fun pair(onCode: (PairingCode) -> Unit = {}) =
        pairing.pair(baseUrl, "uuid-1", "Steam Deck", "linux", "1.0.0-thinkscape.1", onCode)

    @Test
    fun `pending then success returns token and advertises remote install`() = runTest {
        server.enqueue(initResponse())
        server.enqueue(detail(400, "authorization_pending"))
        server.enqueue(tokenResponse())
        server.enqueue(deviceResponse())

        var code: PairingCode? = null
        val device = pair { code = it }

        assertEquals(PairedDevice("rmm_token", "dev-1", null), device)
        assertEquals("12345678", code?.userCode)
        assertEquals("$baseUrl/pair/device?user_code=12345678", code?.verificationUrl)
        assertEquals(600, code?.expiresInSeconds)

        val init = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/device/init", init.path)
        val initBody = init.body.readUtf8()
        assertTrue(initBody.contains("\"client_device_identifier\":\"uuid-1\""))
        assertTrue(initBody.contains("\"requested_scopes\":["))
        assertTrue(initBody.contains("\"devices.write\""))

        val poll = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/device/token", poll.path)
        assertTrue(poll.body.readUtf8().contains("\"device_code\":\"dc-secret\""))
        server.takeRequest(1, TimeUnit.SECONDS)!!

        val put = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("/api/devices/dev-1", put.path)
        assertEquals("Bearer rmm_token", put.getHeader("Authorization"))
        val putBody = put.body.readUtf8()
        assertTrue(putBody, putBody.contains("\"capabilities\":{\"remote_install\":true}"))
        assertTrue(putBody.contains("\"sync_mode\":\"api\""))
        assertTrue(putBody.contains("\"client\":\"romm-sync\""))
        assertTrue(putBody.contains("\"client_version\":\"1.0.0-thinkscape.1\""))

        // Two polls at the 5 s interval.
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun `code that is never approved expires`() = runTest {
        server.enqueue(initResponse(expiresIn = 10, interval = 5))
        server.enqueue(detail(400, "authorization_pending"))
        server.enqueue(detail(400, "authorization_pending"))

        try {
            pair()
            fail("expected PairingExpiredException")
        } catch (e: PairingExpiredException) {
            // expected
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `expired_token from server raises expired`() = runTest {
        server.enqueue(initResponse())
        server.enqueue(detail(400, "expired_token"))

        try {
            pair()
            fail("expected PairingExpiredException")
        } catch (e: PairingExpiredException) {
            // expected
        }
    }

    @Test
    fun `denied pairing raises denied`() = runTest {
        server.enqueue(initResponse())
        server.enqueue(detail(400, "access_denied"))

        try {
            pair()
            fail("expected PairingDeniedException")
        } catch (e: PairingDeniedException) {
            // expected
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http 429 doubles the poll wait`() = runTest {
        server.enqueue(initResponse(interval = 5))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"Too many polling attempts. Try again later."}"""))
        server.enqueue(detail(400, "authorization_pending"))
        server.enqueue(tokenResponse())
        server.enqueue(deviceResponse())

        val device = pair()

        assertEquals("dev-1", device.deviceId)
        // 5 s, then 429 doubles to 10 s for the next two polls.
        assertEquals(25_000L, testScheduler.currentTime)
    }
}
