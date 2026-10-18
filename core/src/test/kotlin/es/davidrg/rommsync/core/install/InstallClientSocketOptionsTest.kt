package es.davidrg.rommsync.core.install

import io.socket.engineio.client.transports.WebSocket
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallClientSocketOptionsTest {

    private val http = OkHttpClient()

    @Test
    fun `socket uses websocket only with token auth and bearer header`() {
        val options = InstallClient.socketOptions("https://romm.example/", "rmm_tok", http)

        assertArrayEquals(arrayOf(WebSocket.NAME), options.transports)
        assertEquals("/ws/socket.io", options.path)
        assertEquals(mapOf("token" to "rmm_tok"), options.auth)
        assertEquals(listOf("Bearer rmm_tok"), options.extraHeaders["Authorization"])
        assertTrue(options.reconnection)
        assertEquals(5_000L, options.reconnectionDelay)
        assertEquals(300_000L, options.reconnectionDelayMax)
        assertSame(http, options.webSocketFactory)
    }

    @Test
    fun `socket path keeps a base url prefix`() {
        val options = InstallClient.socketOptions("https://host/romm", "rmm_tok", http)
        assertEquals("/romm/ws/socket.io", options.path)
    }
}
