package es.davidrg.rommsync.data.local.entity

import androidx.room.Entity

/**
 * One provenance marker of an installed rom (`manual` or `collection:<kind>:<id>`,
 * see [es.davidrg.rommsync.core.collections.Provenance]). A rom without rows has
 * no provenance.
 */
@Entity(tableName = "rom_provenance", primaryKeys = ["romId", "marker"])
data class RomProvenanceEntity(
    val romId: Int,
    val marker: String,
)

/** What the collection reconciler last materialised in the launcher for a RomM collection. */
@Entity(tableName = "collection_state", primaryKeys = ["collectionKey"])
data class CollectionStateEntity(
    val collectionKey: String,
    val name: String,
    val signature: String,
)
