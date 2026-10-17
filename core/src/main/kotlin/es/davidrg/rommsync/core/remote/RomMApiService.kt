package es.davidrg.rommsync.core.remote

import es.davidrg.rommsync.core.remote.dto.PlatformDto
import es.davidrg.rommsync.core.remote.dto.RomResponse
import es.davidrg.rommsync.core.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.core.remote.dto.DeviceRegistrationResponse
import es.davidrg.rommsync.core.remote.dto.NegotiateRequest
import es.davidrg.rommsync.core.remote.dto.NegotiateResponse
import es.davidrg.rommsync.core.remote.dto.SessionCompleteRequest
import es.davidrg.rommsync.core.remote.dto.DeviceAuthInitRequest
import es.davidrg.rommsync.core.remote.dto.DeviceAuthInitResponse
import es.davidrg.rommsync.core.remote.dto.DeviceAuthTokenRequest
import es.davidrg.rommsync.core.remote.dto.DeviceAuthTokenResponse
import es.davidrg.rommsync.core.remote.dto.DeviceCapabilitiesUpdate
import es.davidrg.rommsync.core.remote.dto.DeviceDto
import es.davidrg.rommsync.core.remote.dto.InstallReportRequest
import es.davidrg.rommsync.core.remote.dto.InstallRequestDto
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

interface RomMApiService {

    /**
     * Get all platforms available on the RomM server.
     * GET /api/platforms
     *
     * RomM returns a bare JSON array, NOT a wrapped object.
     */
    @GET("api/platforms")
    suspend fun getPlatforms(): List<PlatformDto>

    /**
     * Get ROMs filtered by platform with pagination.
     * GET /api/roms?platform_ids=[id]&limit=50&offset=0
     *
     * RomM uses fastapi-pagination → response is {items, total, page, size, pages}.
     * IMPORTANT: Use platform_ids (plural), NOT platform_id (singular).
     */
    @GET("api/roms")
    suspend fun getRoms(
        @QueryMap params: Map<String, String>,
    ): RomResponse

    /**
     * Download ROM content — returns a streaming response body.
     * GET /api/roms/{id}/content/{file_name}
     *
     * For multi-file ROMs, the server may use mod_zip (Content-Length = -1)
     * and return a dynamically zipped stream.
     */
    @Streaming
    @GET("api/roms/{id}/content/{file_name}")
    suspend fun downloadRom(
        @Path("id") romId: Int,
        @Path("file_name", encoded = true) fileName: String,
        /** Cabecera Range para reanudar descargas parciales (p.ej. "bytes=1048576-"). */
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    // ── Save Sync endpoints ──────────────────────────────────────────────

    /**
     * Register this device with the RomM server for save sync.
     * POST /api/devices
     */
    @POST("api/devices")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest,
    ): DeviceRegistrationResponse

    /**
     * Negotiate sync operations with the server.
     * POST /api/sync/negotiate
     */
    @POST("api/sync/negotiate")
    suspend fun negotiateSync(
        @Body request: NegotiateRequest,
    ): NegotiateResponse

    /**
     * Upload a save file to the server.
     * POST /api/saves?rom_id=...&emulator=...&device_id=...
     */
    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Part file: MultipartBody.Part,
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String? = null,
        @Query("device_id") deviceId: String? = null,
        /** Pairing key for negotiate: saves are matched on (rom_id, slot). */
        @Query("slot") slot: String? = null,
        @Query("content_hash") contentHash: String? = null,
        @Query("overwrite") overwrite: Boolean = true,
    ): ResponseBody

    /**
     * Download a save file from the server.
     * GET /api/saves/{id}/content
     */
    @Streaming
    @GET("api/saves/{id}/content")
    suspend fun downloadSave(
        @Path("id") saveId: Int,
        @Query("device_id") deviceId: String? = null,
    ): ResponseBody

    /**
     * Complete a sync session.
     * POST /api/sync/sessions/{session_id}/complete
     */
    @POST("api/sync/sessions/{session_id}/complete")
    suspend fun completeSession(
        @Path("session_id") sessionId: Int,
        @Body request: SessionCompleteRequest,
    ): ResponseBody

    // ── Device pairing (unauthenticated; RomM device authorization flow) ──

    /** POST /api/auth/device/init — start pairing; returns the user code to approve in the web UI. */
    @POST("api/auth/device/init")
    suspend fun deviceAuthInit(
        @Body request: DeviceAuthInitRequest,
    ): DeviceAuthInitResponse

    /**
     * POST /api/auth/device/token — poll for the bound token. Kept as a raw
     * Response: HTTP 400 carries `detail` (authorization_pending, slow_down,
     * access_denied, expired_token) and 429 means rate limited.
     */
    @POST("api/auth/device/token")
    suspend fun deviceAuthToken(
        @Body request: DeviceAuthTokenRequest,
    ): Response<DeviceAuthTokenResponse>

    // ── Devices and remote installs (device-bound token) ──────────────────

    /** GET /api/devices/{device_id} */
    @GET("api/devices/{deviceId}")
    suspend fun getDevice(
        @Path("deviceId") deviceId: String,
    ): DeviceDto

    /** PUT /api/devices/{device_id} — advertise capabilities (remote_install) and metadata. */
    @PUT("api/devices/{deviceId}")
    suspend fun updateDevice(
        @Path("deviceId") deviceId: String,
        @Body update: DeviceCapabilitiesUpdate,
    ): DeviceDto

    /**
     * POST /api/devices/{device_id}/installs/claim — takes every pending request
     * and returns all requests the device holds taken; refreshes presence for 90 s.
     */
    @POST("api/devices/{deviceId}/installs/claim")
    suspend fun claimInstalls(
        @Path("deviceId") deviceId: String,
    ): List<InstallRequestDto>

    /** PUT /api/devices/{device_id}/installs/{request_id} — report done/already_installed/failed. */
    @PUT("api/devices/{deviceId}/installs/{requestId}")
    suspend fun reportInstall(
        @Path("deviceId") deviceId: String,
        @Path("requestId") requestId: String,
        @Body report: InstallReportRequest,
    ): Unit

    /** GET /api/roms/{id} — detailed rom with its files (ids, names, paths, sizes, hashes). */
    @GET("api/roms/{romId}")
    suspend fun getRomDetail(
        @Path("romId") romId: Int,
    ): RomDetailDto

    /** GET /api/roms/{file_id}/files/content/{file_name} — streams one RomFile by its id. */
    @Streaming
    @GET("api/roms/{fileId}/files/content/{fileName}")
    suspend fun downloadRomFile(
        @Path("fileId") fileId: Int,
        @Path("fileName") fileName: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    // ── Saves / states listing and state transfer ─────────────────────────
    // /api/sync/negotiate covers saves only; states use these endpoints directly.

    /** GET /api/saves?rom_id=…[&device_id=…] */
    @GET("api/saves")
    suspend fun listSaves(
        @Query("rom_id") romId: Int,
        @Query("device_id") deviceId: String? = null,
    ): List<ServerAssetDto>

    /** GET /api/states[?rom_id=…] — without rom_id, every state of the user. */
    @GET("api/states")
    suspend fun listStates(
        @Query("rom_id") romId: Int? = null,
    ): List<ServerAssetDto>

    /** POST /api/states?rom_id=…&emulator=… — multipart part name must be `stateFile`. */
    @Multipart
    @POST("api/states")
    suspend fun uploadState(
        @Part file: MultipartBody.Part,
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String? = null,
    ): ServerAssetDto

    /** GET /api/states/{id}/content */
    @Streaming
    @GET("api/states/{id}/content")
    suspend fun downloadState(
        @Path("id") stateId: Int,
    ): ResponseBody
}
