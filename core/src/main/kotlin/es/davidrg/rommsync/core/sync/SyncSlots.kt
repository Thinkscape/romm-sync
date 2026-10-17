package es.davidrg.rommsync.core.sync

import es.davidrg.rommsync.core.remote.dto.ClientSaveState
import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RomM save-sync slot contract, shared by the Android and desktop coordinators.
 *
 * - `/api/sync/negotiate` pairs client and server saves on `(rom_id, slot)`; a
 *   null slot never pairs (always "upload"). Every client save therefore carries
 *   a stable slot = its canonical local file name, plus the emulator id.
 * - Slotted uploads are stored server-side under a datetime-tagged name
 *   (`Game [2026-10-18_10-00-00].srm`); downloads go back to the canonical
 *   local name, never to the tagged server name.
 * - Negotiate covers saves only: states ([SaveKind.STATE]) are excluded from it
 *   and reconciled with [planStates] against `GET /api/states`.
 */
object SyncSlots {

    /** RomM's `asset_store.DATETIME_TAG_PATTERN`. */
    private val DATETIME_TAG = Regex(" \\[\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}]")

    /** Server column limits (`models/assets.py`). */
    const val SLOT_MAX_LENGTH = 255
    const val EMULATOR_MAX_LENGTH = 50

    /** Upper bound of `rom_ids` per negotiate request (RomM caps the scope list). */
    const val MAX_ROM_IDS_PER_NEGOTIATE = 500

    fun stripDatetimeTag(fileName: String): String = DATETIME_TAG.replace(fileName, "")

    /** Slot for a local save: its canonical file name (identical on every device). */
    fun slotFor(save: LocalSave): String = save.fileName.take(SLOT_MAX_LENGTH)

    /**
     * Negotiate entries for [savesByRom] (rom id → handler results): saves only,
     * each with a non-null slot and emulator. [emulatorFor] gives the emulator id
     * used for a rom on this device.
     */
    fun clientSaveStates(
        savesByRom: Map<Int, List<LocalSave>>,
        emulatorFor: (romId: Int) -> String,
    ): List<ClientSaveState> = savesByRom.flatMap { (romId, saves) ->
        val emulator = emulatorFor(romId).take(EMULATOR_MAX_LENGTH)
        saves.filter { it.kind == SaveKind.SAVE }.map { save ->
            ClientSaveState(
                romId = romId,
                fileName = save.fileName,
                slot = slotFor(save),
                emulator = emulator,
                contentHash = save.sha1,
                updatedAt = formatIso8601(save.lastModified),
                fileSizeBytes = save.file.length().toInt(),
            )
        }
    }

    /**
     * Local file name a server save/state lands at. The slot wins when it is a
     * plain file name of the same type as the server file (our own uploads);
     * otherwise (foreign slots like "autosave", or no slot) the server name with
     * its datetime tag removed. Path components are never honoured.
     */
    fun localNameFor(serverFileName: String, slot: String?): String {
        val untagged = File(stripDatetimeTag(serverFileName)).name
        if (slot.isNullOrBlank() || !isPlainFileName(slot)) return untagged
        val slotExt = slot.substringAfterLast('.', "")
        val serverExt = untagged.substringAfterLast('.', "")
        return if (slotExt.isNotEmpty() && slotExt.equals(serverExt, ignoreCase = true)) slot else untagged
    }

    /** The local save a negotiate operation refers to (matched on slot, then on the untagged name). */
    fun localSaveFor(op: SyncOperation, saves: List<LocalSave>?): LocalSave? {
        if (saves.isNullOrEmpty()) return null
        val candidates = saves.filter { it.kind == SaveKind.SAVE }
        op.slot?.let { slot -> candidates.find { slotFor(it) == slot }?.let { return it } }
        val name = localNameFor(op.fileName, op.slot)
        return candidates.find { it.fileName == name }
    }

    fun formatIso8601(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }

    private fun isPlainFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

    // ── States ────────────────────────────────────────────────────────────

    enum class StateAction { UPLOAD, DOWNLOAD, CONFLICT, NOOP }

    /**
     * One state reconciliation step. [name] is the canonical local file name;
     * [remote] is the newest server state with that name.
     */
    data class StateOp(
        val action: StateAction,
        val name: String,
        val local: LocalSave?,
        val remote: ServerAssetDto?,
    )

    /**
     * Version marker of a server state as stored after a sync. `StateSchema`
     * has no content hash in RomM 5.4, so id + update time + size stand in.
     */
    fun remoteMarker(remote: ServerAssetDto): String =
        remote.contentHash ?: "${remote.id}:${remote.updatedAt}:${remote.fileSizeBytes}"

    /**
     * Plans state sync for one rom.
     *
     * - [localStates]: this device's state files (kind STATE).
     * - [remoteStates]: `GET /api/states?rom_id=` for the rom. Only states written
     *   by [emulator] (or with no emulator but a same-named local file) take part:
     *   another emulator's state is not loadable here.
     * - [lastLocalHash] / [lastRemoteMarker]: what this device recorded for a
     *   name at its last successful sync (null = never synced).
     *
     * Rules: local-only → upload; remote-only → download; both present → compare
     * against the last sync: only local changed → upload, only remote changed →
     * download, both changed (including first contact) → conflict, unless the
     * server reports the same content hash.
     */
    fun planStates(
        localStates: List<LocalSave>,
        remoteStates: List<ServerAssetDto>,
        emulator: String,
        lastLocalHash: (name: String) -> String?,
        lastRemoteMarker: (name: String) -> String?,
    ): List<StateOp> {
        val locals = localStates.filter { it.kind == SaveKind.STATE }.associateBy { it.fileName }
        val remotes = remoteStates
            .filter { it.emulator == emulator || (it.emulator == null && localNameFor(it.fileName, it.slot) in locals) }
            .groupBy { localNameFor(it.fileName, it.slot) }
            .mapValues { (_, list) -> list.maxWith(compareBy<ServerAssetDto>({ it.updatedAt ?: "" }, { it.id })) }

        return (locals.keys + remotes.keys).sorted().map { name ->
            val local = locals[name]
            val remote = remotes[name]
            val action = when {
                remote == null -> StateAction.UPLOAD
                local == null -> StateAction.DOWNLOAD
                remote.contentHash != null && remote.contentHash.equals(local.sha1, ignoreCase = true) ->
                    StateAction.NOOP
                else -> {
                    val localChanged = lastLocalHash(name) != local.sha1
                    val remoteChanged = lastRemoteMarker(name) != remoteMarker(remote)
                    when {
                        localChanged && remoteChanged -> StateAction.CONFLICT
                        localChanged -> StateAction.UPLOAD
                        remoteChanged -> StateAction.DOWNLOAD
                        else -> StateAction.NOOP
                    }
                }
            }
            StateOp(action, name, local, remote)
        }
    }
}
