package es.davidrg.rommsync.core.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Collection device sync ──────────────────────────────────────────────
// GET /api/devices/{id}/collections -> List<DeviceCollectionDto>

/**
 * One RomM collection the device is subscribed to. [kind] is `regular` or
 * `smart`; [coverUrl] is a server-relative path fetched with the device token;
 * [updatedAt] changes on any change to name, description, cover, membership or
 * subscription.
 */
@JsonClass(generateAdapter = true)
data class DeviceCollectionDto(
    @Json(name = "collection_kind") val kind: String,
    @Json(name = "collection_id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "rom_ids") val romIds: List<Int> = emptyList(),
    @Json(name = "cover_url") val coverUrl: String? = null,
    @Json(name = "updated_at") val updatedAt: String = "",
)
