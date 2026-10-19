package es.davidrg.rommsync.core.collections

import es.davidrg.rommsync.core.install.InstallClient
import es.davidrg.rommsync.core.install.InstalledRom
import es.davidrg.rommsync.core.install.InstalledRomStore
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CollectionReconcilerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val store = MemoryStore()
    private val state = MemoryState()
    private val writer = RecordingWriter()
    private lateinit var backend: FakeBackend
    private lateinit var reconciler: CollectionReconciler

    @Before
    fun setUp() {
        root = tmp.newFolder("roms")
        backend = FakeBackend(root, store)
        reconciler = CollectionReconciler(backend, store, store, state, writer, tmp.newFolder("covers"), logger = {})
    }

    private fun collection(id: Int, vararg roms: Int, name: String = "Coll $id", updatedAt: String = "t1") =
        DeviceCollection("regular", id, name, null, roms.toList(), null, updatedAt)

    private fun marker(id: Int) = Provenance.collection("regular:$id")

    @Test
    fun `installs missing members, marks them and writes the launcher collection`() = runTest {
        backend.collections = listOf(collection(1, 10, 11))

        val summary = reconciler.reconcile("test")

        assertEquals(2, summary.installed)
        assertEquals(listOf(10, 11), backend.installCalls.map { it.first })
        assertEquals(setOf(marker(1)), store.markers(10))
        assertEquals(setOf(marker(1)), store.markers(11))
        val last = writer.writes.last()
        assertEquals("regular:1", last.first.key)
        assertEquals(listOf(10, 11), last.second.map { it.romId }.sorted())
        assertEquals("snes", last.second.first().platformFolder)
        assertTrue(state.known().containsKey("regular:1"))
    }

    @Test
    fun `manual rom is never removed when it leaves a collection`() = runTest {
        backend.collections = listOf(collection(1, 10))
        reconciler.reconcile("first")
        store.setMarkers(10, store.markers(10) + Provenance.MANUAL)

        backend.collections = listOf(collection(1, updatedAt = "t2"))
        reconciler.reconcile("second")

        assertTrue(backend.uninstallCalls.isEmpty())
        assertEquals(setOf(Provenance.MANUAL), store.markers(10))
        assertTrue(store.installed(10) != null)
        assertEquals(emptyList<Int>(), writer.writes.last().second.map { it.romId })
    }

    @Test
    fun `rom already on disk without provenance is adopted as manual and survives unsubscribe`() = runTest {
        backend.placeExisting(20)
        backend.collections = listOf(collection(1, 20))

        reconciler.reconcile("first")

        assertTrue("no download for a recorded rom", backend.installCalls.isEmpty())
        assertEquals(setOf(Provenance.MANUAL, marker(1)), store.markers(20))

        backend.collections = emptyList()
        reconciler.reconcile("unsubscribe")

        assertTrue(backend.uninstallCalls.isEmpty())
        assertEquals(setOf(Provenance.MANUAL), store.markers(20))
    }

    @Test
    fun `shared member stays until the last collection lets go`() = runTest {
        backend.collections = listOf(collection(1, 30), collection(2, 30))
        reconciler.reconcile("first")
        assertEquals(1, backend.installCalls.size)
        assertEquals(setOf(marker(1), marker(2)), store.markers(30))

        backend.collections = listOf(collection(1, updatedAt = "t2"), collection(2, 30))
        reconciler.reconcile("removed from 1")
        assertTrue(backend.uninstallCalls.isEmpty())
        assertEquals(setOf(marker(2)), store.markers(30))

        backend.collections = listOf(collection(1, updatedAt = "t2"), collection(2, updatedAt = "t3"))
        reconciler.reconcile("removed from 2")
        assertEquals(listOf(30), backend.uninstallCalls)
        assertEquals(emptySet<String>(), store.markers(30))
        assertTrue(store.installed(30) == null)
    }

    @Test
    fun `unsubscribing removes the launcher collection and uninstalls collection-only roms`() = runTest {
        backend.collections = listOf(collection(1, 40, 41, name = "Racing"))
        reconciler.reconcile("first")
        store.setMarkers(41, store.markers(41) + Provenance.MANUAL)

        backend.collections = emptyList()
        val summary = reconciler.reconcile("unsubscribe")

        assertEquals(listOf("regular:1" to "Racing"), writer.removes)
        assertEquals(listOf(40), backend.uninstallCalls)
        assertEquals(1, summary.uninstalled)
        assertFalse(state.known().containsKey("regular:1"))
        assertEquals(setOf(Provenance.MANUAL), store.markers(41))
    }

    @Test
    fun `failed uninstall keeps the marker so the next run retries`() = runTest {
        backend.collections = listOf(collection(1, 50))
        reconciler.reconcile("first")
        backend.failUninstall = true

        backend.collections = listOf(collection(1, updatedAt = "t2"))
        reconciler.reconcile("second")
        assertEquals(setOf(marker(1)), store.markers(50))

        backend.failUninstall = false
        reconciler.reconcile("third")
        assertEquals(listOf(50, 50), backend.uninstallCalls)
        assertEquals(emptySet<String>(), store.markers(50))
    }

    @Test
    fun `unchanged collection is not rewritten`() = runTest {
        backend.collections = listOf(collection(1, 60))
        reconciler.reconcile("first")
        val writes = writer.writes.size

        reconciler.reconcile("periodic")

        assertEquals(writes, writer.writes.size)
        assertEquals(1, backend.installCalls.size)
    }

    @Test
    fun `fetch failure changes nothing`() = runTest {
        backend.collections = listOf(collection(1, 70))
        reconciler.reconcile("first")
        backend.fetchFails = true

        reconciler.reconcile("offline")

        assertTrue(backend.uninstallCalls.isEmpty())
        assertTrue(writer.removes.isEmpty())
        assertEquals(setOf(marker(1)), store.markers(70))
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    private class MemoryStore : InstalledRomStore, RomProvenanceStore {
        val records = mutableMapOf<Int, InstalledRom>()
        private val provenance = mutableMapOf<Int, Set<String>>()

        override fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>) {
            records[rom.id] = InstalledRom(rom.id, rom.platformSlug, primaryFile, files)
        }

        override fun installed(romId: Int): InstalledRom? = records[romId]
        override fun forgetInstalled(romId: Int) {
            records.remove(romId)
        }

        override fun markers(romId: Int): Set<String> = provenance[romId].orEmpty()
        override fun setMarkers(romId: Int, markers: Set<String>) {
            if (markers.isEmpty()) provenance.remove(romId) else provenance[romId] = markers
        }

        override fun allMarkers(): Map<Int, Set<String>> = provenance.toMap()
    }

    private class MemoryState : CollectionStateStore {
        private val map = mutableMapOf<String, KnownCollection>()
        override fun known(): Map<String, KnownCollection> = map.toMap()
        override fun put(key: String, value: KnownCollection) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private class RecordingWriter : LauncherCollectionWriter {
        val writes = mutableListOf<Triple<DeviceCollection, List<CollectionMember>, File?>>()
        val removes = mutableListOf<Pair<String, String>>()
        override fun write(collection: DeviceCollection, members: List<CollectionMember>, cover: File?) {
            writes += Triple(collection, members, cover)
        }

        override fun remove(key: String, name: String) {
            removes += key to name
        }
    }

    /** Mimics InstallClient: files under `<root>/snes/`, provenance before the record, uninstall clears both. */
    private class FakeBackend(private val root: File, private val store: MemoryStore) : CollectionBackend {
        var collections: List<DeviceCollection> = emptyList()
        var fetchFails = false
        var failUninstall = false
        val installCalls = mutableListOf<Pair<Int, String>>()
        val uninstallCalls = mutableListOf<Int>()

        override suspend fun fetchCollections(): List<DeviceCollection> {
            if (fetchFails) throw java.io.IOException("offline")
            return collections
        }

        override suspend fun downloadCover(collection: DeviceCollection, dir: File): File? = null

        fun placeExisting(romId: Int) {
            val file = place(romId)
            store.recordInstalled(rom(romId), file, listOf(file))
        }

        override suspend fun installRom(romId: Int, marker: String): LocalInstallResult {
            installCalls += romId to marker
            val file = place(romId)
            store.setMarkers(romId, store.markers(romId) + marker)
            store.recordInstalled(rom(romId), file, listOf(file))
            return LocalInstallResult(InstallClient.STATUS_DONE, null, listOf(file))
        }

        override suspend fun uninstallRom(romId: Int): LocalUninstallResult {
            uninstallCalls += romId
            if (failUninstall) return LocalUninstallResult(InstallClient.STATUS_FAILED, "busy")
            val record = store.installed(romId) ?: return LocalUninstallResult(InstallClient.STATUS_NOT_INSTALLED)
            record.files.forEach { it.delete() }
            store.forgetInstalled(romId)
            store.setMarkers(romId, emptySet())
            return LocalUninstallResult(InstallClient.STATUS_DONE, null, record.files)
        }

        private fun place(romId: Int): File =
            File(File(root, "snes").apply { mkdirs() }, "rom$romId.sfc").apply { writeText("rom $romId") }

        private fun rom(romId: Int) = RomDetailDto(id = romId, platformSlug = "snes", fsName = "rom$romId.sfc")
    }
}
