package es.davidrg.rommsync.core.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Remote install (push from RomM to a paired device) ─────────────────
// POST /api/devices/{id}/installs/claim        -> List<InstallRequestDto> (all taken requests)
// PUT  /api/devices/{id}/installs/{request_id} <- InstallReportRequest

/**
 * One install request as served by RomM. The id is a string on the server
 * (`InstallRequestSchema.id: str`).
 */
@JsonClass(generateAdapter = true)
data class InstallRequestDto(
    @Json(name = "id") val id: String,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_ids") val fileIds: List<Int> = emptyList(),
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String? = null,
)

/** status: `done`, `already_installed` or `failed`; reason at most 500 chars. */
@JsonClass(generateAdapter = true)
data class InstallReportRequest(
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String? = null,
)

/** GET /api/roms/{id} subset needed to place a rom's files on disk. */
@JsonClass(generateAdapter = true)
data class RomDetailDto(
    @Json(name = "id") val id: Int,
    @Json(name = "platform_slug") val platformSlug: String,
    @Json(name = "fs_name") val fsName: String,
    @Json(name = "files") val files: List<RomFileDto> = emptyList(),
    /** Library-relative folder holding the rom (e.g. `roms/gba/usb31`). */
    @Json(name = "fs_path") val fsPath: String? = null,
    @Json(name = "platform_id") val platformId: Int = 0,
    @Json(name = "name") val name: String? = null,
)

/**
 * One file of a rom (RomM `RomFileSchema`). `file_path` is library-relative and,
 * for multi-file roms, includes `<fs_path>/<fs_name>[/<sub dir>]`.
 */
@JsonClass(generateAdapter = true)
data class RomFileDto(
    @Json(name = "id") val id: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long = 0,
    @Json(name = "sha1_hash") val sha1Hash: String? = null,
    @Json(name = "md5_hash") val md5Hash: String? = null,
    @Json(name = "crc_hash") val crcHash: String? = null,
)
