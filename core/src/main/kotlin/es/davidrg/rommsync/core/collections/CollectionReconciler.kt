package es.davidrg.rommsync.core.collections

import es.davidrg.rommsync.core.install.InstallClient
import es.davidrg.rommsync.core.install.InstalledRomStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Mirrors the RomM collections this device is subscribed to ("Sync to device")
 * onto local storage and the launcher.
 *
 * Every rom carries provenance markers ([Provenance]): `manual` for pushes,
 * hand downloads and anything present before collection sync, and
 * `collection:<kind>:<id>` per synced collection. One [reconcile]:
 *
 * 1. fetches the subscribed collections (a failed fetch changes nothing);
 * 2. drops the marker of every rom that left a collection, or whose collection
 *    is no longer listed; a rom left without markers is uninstalled (files only,
 *    never saves/states), a rom still marked `manual` or by another collection
 *    stays;
 * 3. removes launcher collections no longer listed ([LauncherCollectionWriter.remove]
 *    is a no-op for collections the writer did not create);
 * 4. writes every listed collection with the members installed now;
 * 5. installs missing members one at a time through the push pipeline (rom
 *    already on disk and recorded: marker only), then rewrites that collection.
 *
 * Launcher writes are skipped while a collection's signature (name, description,
 * cover, `updated_at`, installed members) equals the last one written.
 * Runs are serialised; [request] coalesces triggers into one pending run.
 */
class CollectionReconciler(
    private val backend: CollectionBackend,
    private val installed: InstalledRomStore,
    private val provenance: RomProvenanceStore,
    private val state: CollectionStateStore,
    private val writer: LauncherCollectionWriter,
    private val coverDir: File,
    private val logger: (String) -> Unit = { println("[romm-sync] $it") },
) {
    private val mutex = Mutex()
    private val requests = Channel<String>(Channel.CONFLATED)
    private var loop: Job? = null

    /** Starts the worker consuming [request]s in [scope]. No-op when already started. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            for (reason in requests) {
                try {
                    reconcile(reason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Asks for a run (socket connect, `collections:changed`, periodic cycle). Never blocks. */
    fun request(reason: String) {
        requests.trySend(reason)
    }

    /** Result counters of one run, for logs and tests. */
    data class Summary(
        val collections: Int = 0,
        val installed: Int = 0,
        val uninstalled: Int = 0,
        val failed: Int = 0,
        val launcherWrites: Int = 0,
        val launcherRemovals: Int = 0,
    )

    suspend fun reconcile(reason: String): Summary = mutex.withLock { reconcileLocked(reason) }

    private suspend fun reconcileLocked(reason: String): Summary {
        val collections = try {
            backend.fetchCollections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cannot fetch collections ($reason): ${e.javaClass.simpleName}: ${e.message}")
            return Summary()
        }
        log("reconcile ($reason): ${collections.size} collections")
        var summary = Summary(collections = collections.size)
        val listed = collections.associateBy { it.key }
        val wanted: Map<String, Set<Int>> = collections.associate { it.key to it.romIds.toSet() }

        // 2. Membership removals.
        for ((romId, markers) in provenance.allMarkers()) {
            val stale = markers.filter { marker ->
                val key = Provenance.collectionKey(marker) ?: return@filter false
                wanted[key]?.contains(romId) != true
            }.toSet()
            if (stale.isEmpty()) continue
            val remaining = markers - stale
            if (remaining.isNotEmpty()) {
                provenance.setMarkers(romId, remaining)
                continue
            }
            // Keep the stale markers until the files are really gone, so a failed uninstall retries.
            val result = backend.uninstallRom(romId)
            when (result.status) {
                InstallClient.STATUS_DONE, InstallClient.STATUS_NOT_INSTALLED -> {
                    provenance.setMarkers(romId, emptySet())
                    summary = summary.copy(uninstalled = summary.uninstalled + 1)
                }
                else -> {
                    log("uninstall of rom $romId failed: ${result.reason}")
                    summary = summary.copy(failed = summary.failed + 1)
                }
            }
        }

        // 3. Collections no longer listed.
        for ((key, known) in state.known()) {
            if (key in listed) continue
            try {
                writer.remove(key, known.name)
                summary = summary.copy(launcherRemovals = summary.launcherRemovals + 1)
            } catch (e: Exception) {
                log("cannot remove launcher collection ${known.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
            state.remove(key)
        }

        // 4. Launcher collections with what is installed now.
        for (collection in collections) {
            if (writeCollection(collection)) summary = summary.copy(launcherWrites = summary.launcherWrites + 1)
        }

        // 5. Missing members, sequentially, one collection at a time.
        for (collection in collections) {
            val marker = Provenance.collection(collection.key)
            var changed = false
            for (romId in collection.romIds.distinct()) {
                val markers = provenance.markers(romId)
                val record = installed.installed(romId)
                if (record != null && record.primary.exists()) {
                    if (marker !in markers) {
                        // Already on disk: a rom recorded before provenance existed is the user's.
                        val pre = if (markers.isEmpty()) setOf(Provenance.MANUAL) else emptySet()
                        provenance.setMarkers(romId, markers + marker + pre)
                        changed = true
                    }
                    continue
                }
                val result = backend.installRom(romId, marker)
                when (result.status) {
                    InstallClient.STATUS_DONE, InstallClient.STATUS_ALREADY_INSTALLED -> {
                        summary = summary.copy(installed = summary.installed + 1)
                        changed = true
                    }
                    else -> {
                        log("install of rom $romId for ${collection.name} failed: ${result.reason}")
                        summary = summary.copy(failed = summary.failed + 1)
                    }
                }
            }
            if (changed && writeCollection(collection)) {
                summary = summary.copy(launcherWrites = summary.launcherWrites + 1)
            }
        }
        log("reconcile ($reason) done: $summary")
        return summary
    }

    /** Writes [collection] when its signature changed; true when the writer ran. */
    private suspend fun writeCollection(collection: DeviceCollection): Boolean {
        val marker = Provenance.collection(collection.key)
        val members = collection.romIds.distinct().mapNotNull { romId ->
            if (marker !in provenance.markers(romId)) return@mapNotNull null
            val record = installed.installed(romId) ?: return@mapNotNull null
            val folder = record.primary.absoluteFile.parentFile?.name ?: return@mapNotNull null
            CollectionMember(romId, folder, record.files.ifEmpty { listOf(record.primary) })
        }
        val signature = signature(collection, members, writer.signatureTag())
        if (state.known()[collection.key]?.signature == signature) return false
        val cover = try {
            backend.downloadCover(collection, coverDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cover of ${collection.name}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        try {
            writer.write(collection, members, cover)
        } catch (e: Exception) {
            log("cannot write launcher collection ${collection.name}: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        state.put(collection.key, KnownCollection(collection.name, signature))
        return true
    }

    private fun log(message: String) = logger("collections: $message")

    companion object {
        /** Stable digest of everything a launcher write depends on ([launchers]: [LauncherCollectionWriter.signatureTag]). */
        internal fun signature(collection: DeviceCollection, members: List<CollectionMember>, launchers: String = ""): String {
            val text = buildString {
                append(collection.name).append('\u0000')
                append(collection.description.orEmpty()).append('\u0000')
                append(collection.coverUrl.orEmpty()).append('\u0000')
                append(collection.updatedAt).append('\u0000')
                for (m in members.sortedBy { it.romId }) {
                    append(m.romId).append('|').append(m.platformFolder)
                    m.files.map { it.path }.sorted().forEach { append('|').append(it) }
                    append('\u0000')
                }
                // Absent for single-launcher writers, so their existing signatures stay valid.
                if (launchers.isNotEmpty()) append("launchers=").append(launchers)
            }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
