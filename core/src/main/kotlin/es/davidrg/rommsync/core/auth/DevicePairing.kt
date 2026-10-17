package es.davidrg.rommsync.core.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import es.davidrg.rommsync.core.remote.AuthInterceptor
import es.davidrg.rommsync.core.remote.NetworkModule
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.DeviceAuthInitRequest
import es.davidrg.rommsync.core.remote.dto.DeviceAuthTokenRequest
import es.davidrg.rommsync.core.remote.dto.DeviceCapabilitiesUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress

/** Code the user approves in the RomM web UI. */
data class PairingCode(
    val userCode: String,
    /** Absolute URL of the approval page with the code pre-filled (QR-friendly). */
    val verificationUrl: String,
    val expiresInSeconds: Int,
)

/** Result of a successful pairing: a `rmm_…` token bound 1:1 to the RomM device. */
data class PairedDevice(
    val token: String,
    val deviceId: String,
    val expiresAt: String?,
)

class PairingExpiredException(message: String = "Pairing code expired before it was approved") : Exception(message)

class PairingDeniedException(message: String = "Pairing was denied in RomM") : Exception(message)

/**
 * RomM 5.4 device authorization flow.
 *
 * `POST /api/auth/device/init` returns a user code; the user approves it in
 * the web UI; this class polls `POST /api/auth/device/token` every `interval`
 * seconds (never faster; HTTP 429 doubles the wait, `slow_down` adds one
 * interval) until it gets the bound token, the code expires, or it is denied.
 * Then it advertises `remote_install` and the device metadata with
 * `PUT /api/devices/{id}`.
 *
 * Re-pairing with the same [identifier][pair] updates the same Device row on
 * the server; callers persist the identifier once per install.
 *
 * [client] must not add an Authorization header: init/token are open endpoints.
 */
class DevicePairing(private val client: OkHttpClient) {

    suspend fun pair(
        baseUrl: String,
        identifier: String,
        name: String,
        platform: String,
        clientVersion: String,
        onCode: (PairingCode) -> Unit,
    ): PairedDevice {
        val api = service(baseUrl, client)
        val init = api.deviceAuthInit(
            DeviceAuthInitRequest(
                clientDeviceIdentifier = identifier,
                name = name,
                client = CLIENT,
                platform = platform,
                clientVersion = clientVersion,
                requestedScopes = REQUESTED_SCOPES,
            ),
        )
        onCode(
            PairingCode(
                userCode = init.userCode,
                verificationUrl = baseUrl.trimEnd('/') + init.verificationPathComplete,
                expiresInSeconds = init.expiresIn,
            ),
        )

        val interval = init.interval.coerceAtLeast(1).toLong()
        var waitSeconds = interval
        var elapsedSeconds = 0L
        while (true) {
            delay(waitSeconds * 1000)
            elapsedSeconds += waitSeconds

            val response = try {
                api.deviceAuthToken(DeviceAuthTokenRequest(init.deviceCode))
            } catch (e: IOException) {
                null // Network hiccup: keep polling until the code expires.
            }
            if (response != null) {
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> {
                        val device = PairedDevice(body.accessToken, body.deviceId, body.expiresAt)
                        advertiseCapabilities(baseUrl, device, platform, clientVersion)
                        return device
                    }
                    response.code() == 429 -> {
                        response.errorBody()?.close()
                        waitSeconds = (waitSeconds * 2).coerceAtMost(MAX_WAIT_SECONDS)
                    }
                    response.code() == 400 -> when (val detail = errorDetail(response.errorBody()?.string())) {
                        "authorization_pending" -> Unit
                        "slow_down" -> waitSeconds = (waitSeconds + interval).coerceAtMost(MAX_WAIT_SECONDS)
                        "access_denied" -> throw PairingDeniedException()
                        "expired_token" -> throw PairingExpiredException()
                        else -> throw PairingDeniedException("Pairing rejected by RomM: ${detail ?: "HTTP 400"}")
                    }
                    else -> response.errorBody()?.close() // Transient server error: keep polling.
                }
            }
            if (elapsedSeconds >= init.expiresIn) throw PairingExpiredException()
        }
    }

    /**
     * `PUT /api/devices/{id}` with `remote_install=true`, `sync_mode=api` and the
     * device metadata. Without the capability RomM refuses install requests.
     */
    suspend fun advertiseCapabilities(
        baseUrl: String,
        device: PairedDevice,
        platform: String,
        clientVersion: String,
    ) {
        val authed = client.newBuilder().addInterceptor(AuthInterceptor { device.token }).build()
        service(baseUrl, authed).updateDevice(
            device.deviceId,
            DeviceCapabilitiesUpdate(
                capabilities = mapOf(CAPABILITY_REMOTE_INSTALL to true),
                syncMode = "api",
                hostname = localHostname(),
                platform = platform,
                client = CLIENT,
                clientVersion = clientVersion,
            ),
        )
    }

    private fun service(baseUrl: String, httpClient: OkHttpClient): RomMApiService =
        NetworkModule.createRetrofit(baseUrl, httpClient).create(RomMApiService::class.java)

    private suspend fun localHostname(): String? = withContext(Dispatchers.IO) {
        (System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.takeIf { it.isNotBlank() && it != "localhost" }
    }

    private fun errorDetail(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching { errorAdapter.fromJson(json)?.get("detail") as? String }.getOrNull()
    }

    companion object {
        const val CLIENT = "romm-sync"
        const val CAPABILITY_REMOTE_INSTALL = "remote_install"
        private const val MAX_WAIT_SECONDS = 60L

        /** Scope names from RomM `handler/auth/constants.py`. */
        val REQUESTED_SCOPES = listOf(
            "devices.read", "devices.write",
            "roms.read", "platforms.read",
            "assets.read", "assets.write",
            "me.read", "me.write",
            "collections.read", "firmware.read",
        )

        private val errorAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
    }
}
