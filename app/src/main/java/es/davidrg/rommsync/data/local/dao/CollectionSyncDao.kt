package es.davidrg.rommsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import es.davidrg.rommsync.data.local.entity.CollectionStateEntity
import es.davidrg.rommsync.data.local.entity.RomProvenanceEntity

/** Provenance markers per installed rom and the reconciler's collection state. Non-suspend calls block: keep them off the main thread. */
@Dao
abstract class CollectionSyncDao {

    @Query("SELECT * FROM rom_provenance WHERE romId = :romId")
    abstract fun markers(romId: Int): List<RomProvenanceEntity>

    @Query("SELECT * FROM rom_provenance")
    abstract fun allMarkers(): List<RomProvenanceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertMarkers(rows: List<RomProvenanceEntity>)

    @Query("DELETE FROM rom_provenance WHERE romId = :romId")
    abstract fun clearMarkers(romId: Int)

    @Transaction
    open fun replaceMarkers(romId: Int, markers: Set<String>) {
        clearMarkers(romId)
        if (markers.isNotEmpty()) insertMarkers(markers.map { RomProvenanceEntity(romId, it) })
    }

    /** Adds one marker, keeping the others (manual downloads add `manual`). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addMarker(row: RomProvenanceEntity)

    /** The rom was deleted by hand in the app. */
    @Query("DELETE FROM rom_provenance WHERE romId = :romId")
    abstract suspend fun deleteMarkers(romId: Int)

    @Query("SELECT * FROM collection_state")
    abstract fun collectionStates(): List<CollectionStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putCollectionState(row: CollectionStateEntity)

    @Query("DELETE FROM collection_state WHERE collectionKey = :key")
    abstract fun removeCollectionState(key: String)
}
