package es.davidrg.rommsync.desktop

import java.io.File
import java.util.Properties

/**
 * Persiste el fingerprint de los saves de cada ROM tras un ciclo de sync
 * limpio (puerto del SyncedHashStore de Android). Si en el próximo ciclo el
 * fingerprint local coincide, se salta el zipeo+hash de ese ROM completo.
 *
 * Also keeps, per save state, the local sha1 and the server version marker
 * ([es.davidrg.rommsync.core.sync.SyncSlots.remoteMarker]) recorded at the last
 * successful sync: the common base for the state conflict rule (both sides
 * changed since it → conflict).
 *
 * Formato: properties en ~/.config/romm-sync/sync-hashes.properties
 *   fp.<romId> = fingerprint
 *   state.<romId>.<fileName>.local = sha1
 *   state.<romId>.<fileName>.remote = server marker
 */
class DesktopHashStore(private val file: File) {

    private val props = Properties()
    private var loadedStamp: Pair<Long, Long>? = null

    init {
        reload()
    }

    /** Picks up writes from the other process (daemon ↔ GUI) before reading or writing. */
    @Synchronized
    private fun reload() {
        val stamp = if (file.isFile) file.lastModified() to file.length() else null
        if (stamp == loadedStamp) return
        props.clear()
        if (stamp != null) runCatching { file.inputStream().use { props.load(it) } }
        loadedStamp = stamp
    }

    @Synchronized
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.outputStream().use { props.store(it, "RomM Sync desktop sync hashes") }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            loadedStamp = file.lastModified() to file.length()
        }
    }

    @Synchronized
    fun getFingerprint(romId: Int): String? {
        reload()
        return props.getProperty("fp.$romId")
    }

    @Synchronized
    fun setFingerprint(romId: Int, fingerprint: String) {
        reload()
        props["fp.$romId"] = fingerprint
        persist()
    }

    /** Drops the fingerprint shortcut so the next cycle rescans this rom (e.g. after a skipped conflict). */
    @Synchronized
    fun clearFingerprint(romId: Int) {
        reload()
        if (props.remove("fp.$romId") != null) persist()
    }

    /** Local sha1 of state [fileName] of [romId] at its last successful sync. */
    @Synchronized
    fun getStateLocalHash(romId: Int, fileName: String): String? {
        reload()
        return props.getProperty(stateKey(romId, fileName) + ".local")
    }

    /** Server version marker of state [fileName] of [romId] at its last successful sync. */
    @Synchronized
    fun getStateRemoteMarker(romId: Int, fileName: String): String? {
        reload()
        return props.getProperty(stateKey(romId, fileName) + ".remote")
    }

    /** Records a state as in sync: both sides now hold [localHash] / [remoteMarker]. */
    @Synchronized
    fun setStateSynced(romId: Int, fileName: String, localHash: String, remoteMarker: String) {
        reload()
        props[stateKey(romId, fileName) + ".local"] = localHash
        props[stateKey(romId, fileName) + ".remote"] = remoteMarker
        persist()
    }

    private fun stateKey(romId: Int, fileName: String) = "state.$romId.$fileName"
}
