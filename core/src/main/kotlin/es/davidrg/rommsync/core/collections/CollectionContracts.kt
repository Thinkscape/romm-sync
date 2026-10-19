package es.davidrg.rommsync.core.collections

import java.io.File

/** A RomM collection this device is subscribed to (`GET /api/devices/{id}/collections`). */
data class DeviceCollection(val kind: String, val id: Int, val name: String, val description: String?,
                            val romIds: List<Int>, val coverUrl: String?, val updatedAt: String) {
    val key: String get() = "$kind:$id"
}

/** An installed member: [files] are the rom's files on disk, under `<roms root>/<platformFolder>/`. */
data class CollectionMember(val romId: Int, val platformFolder: String, val files: List<java.io.File>)

interface LauncherCollectionWriter {
    /** Idempotent. cover = downloaded artwork or null. Must respect local overrides (see above). */
    fun write(collection: DeviceCollection, members: List<CollectionMember>, cover: java.io.File?)
    /** Remove a collection this client created (no-op otherwise). */
    fun remove(key: String, name: String)

    /**
     * Names the launchers this writer currently targets. Part of the reconciler's
     * signature, so a launcher that appears later (ES-DE installed next to Cocoon)
     * receives every collection once. Empty for single-launcher writers.
     */
    fun signatureTag(): String = ""
}

interface LauncherGameRemover {
    /** Drop launcher metadata (gamelist entry, media, collection refs) after the files are deleted; schedule a launcher refresh. */
    fun onRomRemoved(romId: Int, platformFolder: String, files: List<java.io.File>)
}

/**
 * Why a rom is on this device. A rom carries any number of markers; files are
 * removed by collection sync only when the last marker goes, and never while
 * [MANUAL] is present.
 */
object Provenance {
    /** Pushed from RomM ("Install on device"/push.py), downloaded by hand, or present before collection sync. */
    const val MANUAL = "manual"
    private const val COLLECTION_PREFIX = "collection:"

    /** Marker for [DeviceCollection.key] (`collection:<kind>:<id>`). */
    fun collection(key: String): String = COLLECTION_PREFIX + key

    fun isCollection(marker: String): Boolean = marker.startsWith(COLLECTION_PREFIX)

    /** The collection key of a collection marker, null for any other marker. */
    fun collectionKey(marker: String): String? =
        if (isCollection(marker)) marker.removePrefix(COLLECTION_PREFIX).takeIf { it.isNotEmpty() } else null
}

/**
 * Persistent provenance per rom id. An empty set means "no provenance" and must
 * be stored as such (not read back as [Provenance.MANUAL]). Implementations
 * migrate roms that were installed before provenance existed to `{manual}`
 * once, so collection sync never deletes a pre-existing game.
 */
interface RomProvenanceStore {
    fun markers(romId: Int): Set<String>
    fun setMarkers(romId: Int, markers: Set<String>)

    /** Every rom with at least one marker. */
    fun allMarkers(): Map<Int, Set<String>>
}

/** What this client last wrote to the launcher for a collection. */
data class KnownCollection(val name: String, val signature: String)

/** Collections this client has materialised in the launcher, by [DeviceCollection.key]. */
interface CollectionStateStore {
    fun known(): Map<String, KnownCollection>
    fun put(key: String, value: KnownCollection)
    fun remove(key: String)
}

/** Outcome of a local (not request-driven) install; [status] uses the install report statuses. */
data class LocalInstallResult(
    val status: String,
    val reason: String? = null,
    val files: List<File> = emptyList(),
)

/** Outcome of an uninstall: `done`, `not_installed` or `failed` + [reason]. */
data class LocalUninstallResult(
    val status: String,
    val reason: String? = null,
    val files: List<File> = emptyList(),
)

/** Server access + install pipeline the reconciler drives; implemented by `InstallClient`. */
interface CollectionBackend {
    suspend fun fetchCollections(): List<DeviceCollection>

    /** Downloads the collection's cover into [dir] (cached per cover URL); null when it has none or the fetch fails. */
    suspend fun downloadCover(collection: DeviceCollection, dir: File): File?

    /** Installs [romId] through the push pipeline (placement, hash checks, library record) and adds [marker]. */
    suspend fun installRom(romId: Int, marker: String): LocalInstallResult

    /** Removes the rom's game files (never saves/states), its launcher entry, library record and provenance. */
    suspend fun uninstallRom(romId: Int): LocalUninstallResult
}
