package es.davidrg.rommsync.data.install

import es.davidrg.rommsync.core.collections.CollectionStateStore
import es.davidrg.rommsync.core.collections.KnownCollection
import es.davidrg.rommsync.core.collections.RomProvenanceStore
import es.davidrg.rommsync.data.local.dao.CollectionSyncDao
import es.davidrg.rommsync.data.local.entity.CollectionStateEntity

/**
 * Room-backed provenance markers and collection state for the collection
 * reconciler. Rows predating provenance were migrated to `manual` by
 * `RomSyncDatabase.MIGRATION_7_8`. Called from background threads only.
 */
class RoomCollectionSyncStore(private val dao: CollectionSyncDao) : RomProvenanceStore, CollectionStateStore {

    override fun markers(romId: Int): Set<String> = dao.markers(romId).mapTo(LinkedHashSet()) { it.marker }

    override fun setMarkers(romId: Int, markers: Set<String>) = dao.replaceMarkers(romId, markers)

    override fun allMarkers(): Map<Int, Set<String>> =
        dao.allMarkers().groupBy({ it.romId }, { it.marker }).mapValues { (_, v) -> v.toSet() }

    override fun known(): Map<String, KnownCollection> =
        dao.collectionStates().associate { it.collectionKey to KnownCollection(it.name, it.signature) }

    override fun put(key: String, value: KnownCollection) =
        dao.putCollectionState(CollectionStateEntity(key, value.name, value.signature))

    override fun remove(key: String) = dao.removeCollectionState(key)
}
