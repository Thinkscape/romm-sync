package es.davidrg.rommsync.core.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Remote install (push from RomM to a paired device) ─────────────────
// POST /api/devices/{id}/installs/claim        -> List<InstallRequestDto> (all taken requests)
// PUT  /api/devices/{id}/installs/{request_id} <- InstallReportRequest

/**
 * One install request as served by RomM. The id is a string on the server
 * (`InstallRequestSchema.id: str`). [action] is `install` (default; servers
 * without uninstall support omit it) or `uninstall`.
 */
@JsonClass(generateAdapter = true)
data class InstallRequestDto(
    @Json(name = "id") val id: String,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_ids") val fileIds: List<Int> = emptyList(),
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "action") val action: String = "install",
)

/**
 * status: `done`, `already_installed`, `not_installed` (uninstall found nothing
 * to remove) or `failed`; reason at most 500 chars.
 */
@JsonClass(generateAdapter = true)
data class InstallReportRequest(
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String? = null,
)

/**
 * GET /api/roms/{id} subset: what places a rom's files on disk plus the scraped
 * media and metadata exported to ES-DE (also parsed from `GET /api/roms` list
 * items, where `files` is empty). Provider metadata stays as loose maps: their
 * shapes vary per provider and RomM version, and a type mismatch there must
 * never fail an install.
 */
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
    @Json(name = "has_multiple_files") val hasMultipleFiles: Boolean? = null,
    @Json(name = "has_nested_single_file") val hasNestedSingleFile: Boolean? = null,
    // ── Scraped text and media (ES-DE export) ──────────────────────────
    @Json(name = "summary") val summary: String? = null,
    /** Server-relative (`/assets/romm/resources/…`). */
    @Json(name = "path_cover_large") val pathCoverLarge: String? = null,
    /** Provider URL of the cover (absolute). */
    @Json(name = "url_cover") val urlCover: String? = null,
    /** Server-relative screenshot paths. */
    @Json(name = "merged_screenshots") val mergedScreenshots: List<String>? = null,
    /** Resource-relative (`roms/<platform>/<rom>/manual/<id>.pdf`). */
    @Json(name = "path_manual") val pathManual: String? = null,
    @Json(name = "url_manual") val urlManual: String? = null,
    @Json(name = "path_video") val pathVideo: String? = null,
    /** RomM's merged view across providers (`first_release_date` in ms). */
    @Json(name = "metadatum") val metadatum: Map<String, Any?>? = null,
    @Json(name = "igdb_metadata") val igdbMetadata: Map<String, Any?>? = null,
    @Json(name = "ss_metadata") val ssMetadata: Map<String, Any?>? = null,
    @Json(name = "launchbox_metadata") val launchboxMetadata: Map<String, Any?>? = null,
    @Json(name = "gamelist_metadata") val gamelistMetadata: Map<String, Any?>? = null,
)

/** `GET /api/roms` page with items as [RomDetailDto] (list items carry no `files`). */
@JsonClass(generateAdapter = true)
data class RomDetailPage(
    @Json(name = "items") val items: List<RomDetailDto> = emptyList(),
    @Json(name = "total") val total: Int = 0,
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
