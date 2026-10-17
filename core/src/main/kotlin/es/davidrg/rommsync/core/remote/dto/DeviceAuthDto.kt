package es.davidrg.rommsync.core.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Device pairing (RomM 5.4 device authorization flow) ────────────────
// POST /api/auth/device/init  -> DeviceAuthInitResponse (201)
// POST /api/auth/device/token -> DeviceAuthTokenResponse once approved;
//   HTTP 400 {"detail": "authorization_pending" | "slow_down" | "access_denied" | "expired_token"}
//   HTTP 429 when rate limited.

@JsonClass(generateAdapter = true)
data class DeviceAuthInitRequest(
    @Json(name = "client_device_identifier") val clientDeviceIdentifier: String,
    @Json(name = "name") val name: String,
    @Json(name = "client") val client: String = "romm-sync",
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "client_version") val clientVersion: String? = null,
    @Json(name = "requested_scopes") val requestedScopes: List<String>,
)

@JsonClass(generateAdapter = true)
data class DeviceAuthInitResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_path") val verificationPath: String,
    @Json(name = "verification_path_complete") val verificationPathComplete: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int,
)

@JsonClass(generateAdapter = true)
data class DeviceAuthTokenRequest(
    @Json(name = "device_code") val deviceCode: String,
)

@JsonClass(generateAdapter = true)
data class DeviceAuthTokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "scopes") val scopes: List<String> = emptyList(),
    @Json(name = "expires_at") val expiresAt: String? = null,
)

/** Body of PUT /api/devices/{id}; null fields are omitted and left unchanged server-side. */
@JsonClass(generateAdapter = true)
data class DeviceCapabilitiesUpdate(
    @Json(name = "capabilities") val capabilities: Map<String, Boolean>,
    @Json(name = "sync_mode") val syncMode: String = "api",
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "client") val client: String = "romm-sync",
    @Json(name = "client_version") val clientVersion: String,
)

/** GET/PUT /api/devices/{id} response (subset of RomM's DeviceSchema). */
@JsonClass(generateAdapter = true)
data class DeviceDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "client") val client: String? = null,
    @Json(name = "client_version") val clientVersion: String? = null,
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "client_device_identifier") val clientDeviceIdentifier: String? = null,
    @Json(name = "sync_mode") val syncMode: String? = null,
    @Json(name = "sync_enabled") val syncEnabled: Boolean = true,
    @Json(name = "capabilities") val capabilities: Map<String, Boolean>? = null,
    @Json(name = "last_seen") val lastSeen: String? = null,
)
