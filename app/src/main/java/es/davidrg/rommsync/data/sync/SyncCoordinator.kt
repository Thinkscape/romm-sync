package es.davidrg.rommsync.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.remote.NetworkModule
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.core.remote.dto.NegotiateRequest
import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import es.davidrg.rommsync.core.remote.dto.SessionCompleteRequest
import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.ConflictPolicy
import es.davidrg.rommsync.core.sync.SaveBackupManager
import es.davidrg.rommsync.core.sync.StagedPath
import es.davidrg.rommsync.core.sync.SyncSlots
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveHandler
import es.davidrg.rommsync.core.sync.platform.SaveKind
import es.davidrg.rommsync.core.sync.platform.SaveProfile
import es.davidrg.rommsync.core.sync.platform.SyncSupport
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.local.entity.PlatformEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orquesta el ciclo completo de sincronización de saves con el servidor RomM:
 * 1. Resolves the device id (paired device, else upstream registration).
 * 2. Escanea saves locales.
 * 3. Negocia con el servidor (qué subir, qué bajar, qué conflictos).
 * 4. Ejecuta las operaciones.
 * 5. Cierra la sesión.
 * 6. Reconciles save states, which negotiate does not cover.
 *
 * Follows the RomM slot contract ([SyncSlots]): every negotiated save carries
 * slot = canonical local file name and the emulator id; uploads never
 * overwrite unless the user chose "keep local"; downloads land at the
 * canonical local name; a `delete` operation moves the local file into the
 * backup area; conflicts are surfaced (or resolved by the user's policy).
 */
class SyncCoordinator(
    private val settingsDataStore: SettingsDataStore,
    private val romDao: RomDao,
    private val platformDao: PlatformDao,
    private val cacheDir: File,
    private val syncedHashStore: SyncedHashStore? = null,
    private val backupManager: SaveBackupManager? = null,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    private val appContext: Context,
) {

    /** Copias staged de rutas restringidas de esta corrida. */
    private val stagedPaths = mutableMapOf<String, StagedPath>()

    /**
     * Ruta usable por la API File para los handlers: si es /storage/... que
     * la app no puede leer (Android ≥13 bloquea Android/data incluso con
     * «Todos los archivos»), staging vía root o SAF (ver SavePathAccess).
     */
    private fun stagedFor(basePath: String): StagedPath =
        stagedPaths.getOrPut(basePath) {
            SavePathAccess.stage(basePath, File(cacheDir, "save_stage"), appContext)
        }

    private fun stageFor(basePath: String): String = stagedFor(basePath).dir.path

    /** Copia staged de vuelta a la ruta real (la staged se conserva para el resto de la corrida). */
    private fun commitStaged(basePath: String) {
        stagedPaths[basePath]?.let { SavePathAccess.commit(it, appContext) }
    }

    private fun cleanupStaged() {
        stagedPaths.values.forEach { SavePathAccess.cleanup(it, appContext) }
        stagedPaths.clear()
    }

    /** Everything save sync needs to know about one downloaded rom on this device. */
    private inner class RomContext(
        val rom: DownloadedRomEntity,
        val handler: SaveHandler,
        val emulator: String,
        val rawBasePath: String,
    ) {
        val basePath: String get() = stageFor(rawBasePath)
        val ref get() = SaveTransfer.RomRef(rom.romId, rom.fileName, rom.platformSlug)
        private var scanned: List<LocalSave>? = null

        /** Local saves and states (scanned once per run, on demand). */
        suspend fun localFiles(): List<LocalSave> = scanned ?: handler.findSaves(
            romId = rom.romId,
            romFileName = rom.fileName,
            platformSlug = rom.platformSlug,
            savesBasePath = basePath,
            romLocalPath = rom.localPath,
        ).also { scanned = it }

        fun invalidate() {
            scanned = null
        }

        suspend fun fingerprint(): String? = handler.savesFingerprint(
            romId = rom.romId,
            romFileName = rom.fileName,
            platformSlug = rom.platformSlug,
            savesBasePath = basePath,
            romLocalPath = rom.localPath,
        )
    }

    private fun romContext(
        rom: DownloadedRomEntity,
        config: PlatformEntity?,
        retroArchBase: String,
        profile: SaveProfile?,
    ) = RomContext(
        rom = rom,
        handler = SavePathResolver.handlerFor(rom.platformSlug, config, profile),
        emulator = SavePathResolver.emulatorFor(rom.platformSlug, config, profile),
        rawBasePath = SavePathResolver.romBasePath(rom, config, retroArchBase, profile),
    )

    /** Mutable tallies of one sync run. */
    private class RunState {
        var completed = 0
        var failed = 0
        var uploaded = 0
        var downloaded = 0
        var autoResolvedConflicts = 0
        val failedRomIds = mutableSetOf<Int>()
        val conflictRomIds = mutableSetOf<Int>()
        val failures = mutableListOf<FailedOpInfo>()
        val conflicts = mutableListOf<ConflictInfo>()
    }

    suspend fun runSync(): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = settingsDataStore.getServerUrlBlocking()
        val apiKey = settingsDataStore.getApiKeyBlocking()
        val retroArchBase = settingsDataStore.getRetroArchBasePathBlocking()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            return@withContext SyncResult(error = tr("common.server_not_configured"))
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)
        val transfer = SaveTransfer(api, cacheDir) { msg, e -> Log.w(TAG, msg, e) }

        // 1. Device id: the paired device, else upstream registration (API-key mode)
        val deviceId = resolveDeviceId(api)
            ?: return@withContext SyncResult(error = tr("sync.error.device_registration_detail"))

        // 2. Escanear saves locales de ROMs descargados
        val allDownloadedRoms = romDao.getAllDownloadedRomsBlocking()
        if (allDownloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = tr("sync.no_downloaded_roms"))
        }

        val downloadedRoms = allDownloadedRoms.filterNot { it.excludedFromSync }
        if (downloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = tr("sync.all_excluded"))
        }

        val profile = SavePathResolver.deviceProfile(settingsDataStore)
        val platformConfigs = platformDao.getAllPlatformsBlocking().associateBy { it.slug }
        val pathWarnings = mutableListOf<String>()

        // Platforms whose saves cannot be shared (shared VMU, HDD image, nvram…) are skipped.
        val contexts = downloadedRoms
            .filter { rom ->
                SavePathResolver.syncSupport(rom.platformSlug, platformConfigs[rom.platformSlug], profile) is
                    SyncSupport.Supported
            }
            .map { rom -> romContext(rom, platformConfigs[rom.platformSlug], retroArchBase, profile) }
            .associateBy { it.rom.romId }
        val localSavesMap = mutableMapOf<Int, List<LocalSave>>()
        val fingerprintHits = mutableSetOf<Int>()

        for (ctx in contexts.values) {
            val rom = ctx.rom
            val config = platformConfigs[rom.platformSlug]
            val effectiveBasePath = ctx.basePath

            // Una ruta configurada (por juego o por plataforma) que no se puede
            // leer haría que el handler devolviera 0 saves en silencio: el sync
            // diría "Todo sincronizado" sin pista alguna. Avisar explícitamente.
            val explicitOverride = !rom.savesPathOverride.isNullOrBlank() ||
                config?.savesPathOverride?.isNotBlank() == true
            if (explicitOverride && !isPathReadable(effectiveBasePath) &&
                pathWarnings.size < MAX_PATH_WARNINGS
            ) {
                Log.w(TAG, "Ruta de saves configurada ilegible: '${rom.name}' → $effectiveBasePath")
                pathWarnings.add("${rom.name}: $effectiveBasePath")
            }

            // ── Atajo por fingerprint: si los saves no cambiaron desde el
            // último sync, saltarse el zipeo+hash completo de este ROM. ──
            // The rom stays in the negotiate scope, so server-side changes still arrive.
            val cachedFp = syncedHashStore?.getFingerprint(rom.romId)
            if (cachedFp != null) {
                val currentFp = ctx.fingerprint()
                if (currentFp != null && currentFp == cachedFp) {
                    Log.d(TAG, "Skipping unchanged saves for '${rom.name}' (fingerprint hit)")
                    fingerprintHits.add(rom.romId)
                    continue
                }
            }

            val saves = ctx.localFiles()
            if (saves.isNotEmpty()) {
                localSavesMap[rom.romId] = saves
            }
        }

        // 3. Negociar con la API real de RomM (saves only; one request per rom-id chunk)
        val run = RunState()
        val requests = buildNegotiateRequests(
            deviceId = deviceId,
            scopeRomIds = contexts.keys.sorted(),
            savesByRom = localSavesMap,
            emulatorFor = { romId -> contexts.getValue(romId).emulator },
        )
        for (request in requests) {
            val negotiateResponse = try {
                api.negotiateSync(request)
            } catch (e: Exception) {
                Log.w(TAG, "Negotiate failed", e)
                cleanupStaged()
                return@withContext SyncResult(error = tr("sync.error.negotiation", e.message))
            }

            // 4. Ejecutar operaciones
            val before = run.completed to run.failed
            for (op in negotiateResponse.operations) {
                val ctx = contexts[op.romId]
                if (ctx == null) {
                    recordFailure(run, op.romId, op.fileName, downloadedRoms, "sync.failed.action.download", "sync.failed.rom_not_downloaded")
                    continue
                }
                executeSaveOperation(op, ctx, transfer, deviceId, run)
            }

            // 5. Cerrar sesión
            try {
                api.completeSession(
                    sessionId = negotiateResponse.sessionId,
                    request = SessionCompleteRequest(
                        operationsCompleted = run.completed - before.first,
                        operationsFailed = run.failed - before.second,
                    ),
                ).close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to complete session", e)
            }
        }

        // 6. States: listed and transferred directly (negotiate covers saves only)
        syncStates(api, transfer, contexts, fingerprintHits, run)

        // 7. Sellar fingerprints de los ROMs cuyo sync terminó sin fallos ni
        // conflictos pendientes: en el próximo ciclo, si el save no cambió, se
        // saltará el zipeo.
        for (ctx in contexts.values) {
            val romId = ctx.rom.romId
            if (romId in run.failedRomIds || romId in run.conflictRomIds) continue
            ctx.fingerprint()?.let { syncedHashStore?.setFingerprint(romId, it) }
        }

        cleanupStaged()

        SyncResult(
            uploaded = run.uploaded,
            downloaded = run.downloaded,
            conflicts = run.conflicts.size,
            autoResolvedConflicts = run.autoResolvedConflicts,
            conflictDetails = run.conflicts,
            failedDetails = run.failures,
            message = buildResultMessage(run.completed, run.failed, run.conflicts.size, pathWarnings),
            // Señales explícitas para la UI: el estado no debe deducirse
            // leyendo el texto del mensaje (que es traducible).
            allSynced = run.completed == 0 && run.failed == 0 &&
                run.conflicts.isEmpty() && pathWarnings.isEmpty(),
            hasWarnings = pathWarnings.isNotEmpty(),
        )
    }

    private suspend fun executeSaveOperation(
        op: SyncOperation,
        ctx: RomContext,
        transfer: SaveTransfer,
        deviceId: String,
        run: RunState,
    ) {
        val romId = op.romId
        when (op.action) {
            "upload" -> {
                val save = SyncSlots.localSaveFor(op, ctx.localFiles())
                if (save == null) {
                    recordFailure(run, romId, op.fileName, listOf(ctx.rom), "sync.failed.action.upload", "sync.failed.save_missing")
                    return
                }
                when (transfer.uploadSave(save, romId, deviceId, ctx.emulator, ctx.handler, overwrite = false)) {
                    SaveTransfer.UploadOutcome.OK -> {
                        run.completed++
                        run.uploaded++
                        syncedHashStore?.setSyncedHash(romId, save.fileName, save.sha1)
                    }
                    // The slot moved on the server since our last sync: a conflict, not a failure.
                    SaveTransfer.UploadOutcome.CONFLICT ->
                        handleSaveConflict(op, ctx, transfer, deviceId, run)
                    SaveTransfer.UploadOutcome.FAILED ->
                        recordFailure(run, romId, save.fileName, listOf(ctx.rom), "sync.failed.action.upload", "sync.failed.upload_failed")
                }
            }
            "download" -> {
                val saveId = op.saveId
                val target = SyncSlots.localNameFor(op.fileName, op.slot)
                if (saveId == null) {
                    recordFailure(run, romId, target, listOf(ctx.rom), "sync.failed.action.download", "sync.failed.download_failed")
                    return
                }
                // RomM may answer `download` although this device changed the save too
                // (another device's upload has no baseline for us): our own record decides.
                val local = SyncSlots.localSaveFor(op, ctx.localFiles())
                when (SyncSlots.guardDownload(local, local?.let { syncedHashStore?.getSyncedHash(romId, it.fileName) }, op.serverContentHash)) {
                    SyncSlots.DownloadGuard.NOOP -> {
                        local?.let { syncedHashStore?.setSyncedHash(romId, it.fileName, it.sha1) }
                        return
                    }
                    SyncSlots.DownloadGuard.CONFLICT -> {
                        handleSaveConflict(op.copy(reason = tr("sync.conflict.both_changed")), ctx, transfer, deviceId, run)
                        return
                    }
                    SyncSlots.DownloadGuard.DOWNLOAD -> Unit
                }
                if (downloadSaveInto(ctx, transfer, saveId, deviceId, target)) {
                    run.completed++
                    run.downloaded++
                } else {
                    recordFailure(run, romId, target, listOf(ctx.rom), "sync.failed.action.download", "sync.failed.download_failed")
                }
            }
            "conflict" -> handleSaveConflict(op, ctx, transfer, deviceId, run)
            "delete" -> {
                // The slot was emptied on the server: keep a backup, then remove the local copy.
                val save = SyncSlots.localSaveFor(op, ctx.localFiles())
                val name = save?.fileName ?: SyncSlots.localNameFor(op.fileName, op.slot)
                if (save == null || removeLocal(ctx, save)) {
                    run.completed++
                    syncedHashStore?.removeSyncedHash(romId, name)
                } else {
                    recordFailure(run, romId, name, listOf(ctx.rom), "android.sync.failed.action.delete", "android.sync.failed.delete_failed")
                }
            }
            "no_op" -> {
                // Ya sincronizado: registrar hash local para que el preview sepa
                SyncSlots.localSaveFor(op, ctx.localFiles())?.let { save ->
                    syncedHashStore?.setSyncedHash(romId, save.fileName, save.sha1)
                }
            }
            else -> Log.w(TAG, "Unknown sync action '${op.action}' for ${op.fileName}")
        }
    }

    /** Applies the conflict policy; with ASK (or when the policy fails) the conflict is surfaced. */
    private suspend fun handleSaveConflict(
        op: SyncOperation,
        ctx: RomContext,
        transfer: SaveTransfer,
        deviceId: String,
        run: RunState,
    ) {
        val local = SyncSlots.localSaveFor(op, ctx.localFiles())
        val name = local?.fileName ?: SyncSlots.localNameFor(op.fileName, op.slot)
        val resolved = when (conflictPolicy) {
            ConflictPolicy.PREFER_LOCAL -> local != null &&
                keepLocalSave(ctx, transfer, local, deviceId).also { if (it) run.uploaded++ }
            ConflictPolicy.PREFER_SERVER -> op.saveId?.let { saveId ->
                downloadSaveInto(ctx, transfer, saveId, deviceId, name).also { if (it) run.downloaded++ }
            } ?: false
            ConflictPolicy.ASK -> false
        }
        if (resolved) {
            run.completed++
            run.autoResolvedConflicts++
        } else {
            Log.w(TAG, "Conflicto sin resolver: $name para rom ${op.romId}: ${op.reason}")
            run.conflictRomIds.add(op.romId)
            run.conflicts.add(
                ConflictInfo(
                    romId = op.romId,
                    romName = ctx.rom.name,
                    fileName = name,
                    serverUpdatedAt = op.serverUpdatedAt,
                    reason = op.reason,
                    saveId = op.saveId,
                    kind = ConflictInfo.KIND_SAVE,
                ),
            )
        }
    }

    /** "Keep local": uploads [local] with overwrite=true and records it as synced. */
    private suspend fun keepLocalSave(
        ctx: RomContext,
        transfer: SaveTransfer,
        local: LocalSave,
        deviceId: String,
    ): Boolean {
        val outcome = transfer.uploadSave(local, ctx.rom.romId, deviceId, ctx.emulator, ctx.handler, overwrite = true)
        if (outcome != SaveTransfer.UploadOutcome.OK) return false
        syncedHashStore?.setSyncedHash(ctx.rom.romId, local.fileName, local.sha1)
        return true
    }

    /** Backs up the current local copy, downloads save [saveId] as [target] and records its hash. */
    private suspend fun downloadSaveInto(
        ctx: RomContext,
        transfer: SaveTransfer,
        saveId: Int,
        deviceId: String,
        target: String,
    ): Boolean {
        // Copia de seguridad de la copia local antes de pisarla
        backupLocalSave(ctx.rom.romId, target, ctx.localFiles(), ctx.handler)
        transfer.downloadSave(saveId, deviceId, target, ctx.ref, ctx.basePath, ctx.handler)
            ?: return false
        // Ruta restringida staged: devolver la descarga al original
        commitStaged(ctx.rawBasePath)
        ctx.invalidate()
        // Record the sha1 of the save as the handler now reports it (folder saves
        // are re-zipped locally, so the downloaded blob's hash would not match).
        val written = ctx.localFiles().find { it.fileName == target }
        if (written != null) syncedHashStore?.setSyncedHash(ctx.rom.romId, target, written.sha1)
        return true
    }

    /** Moves a local save into the backup area (never a bare delete). */
    private suspend fun removeLocal(ctx: RomContext, save: LocalSave): Boolean {
        val manager = backupManager ?: return false
        val backedUp = runCatching {
            manager.backup(ctx.rom.romId, save.fileName, ctx.handler.prepareForUpload(save))
        }.getOrNull() != null
        if (!backedUp) return false
        val ok = SavePathAccess.delete(stagedFor(ctx.rawBasePath), save.file, appContext)
        ctx.invalidate()
        return ok
    }

    // ── States ────────────────────────────────────────────────────────────

    private suspend fun syncStates(
        api: RomMApiService,
        transfer: SaveTransfer,
        contexts: Map<Int, RomContext>,
        fingerprintHits: Set<Int>,
        run: RunState,
    ) {
        if (contexts.isEmpty()) return
        val remoteByRom = try {
            api.listStates().groupBy { it.romId }
        } catch (e: Exception) {
            Log.w(TAG, "Listing states failed", e)
            return
        }
        for (ctx in contexts.values) {
            val romId = ctx.rom.romId
            val remote = remoteByRom[romId].orEmpty()
            // Unchanged locally and nothing new on the server: no need to hash the local states.
            if (romId in fingerprintHits && remote.none { remoteChangedSinceSync(romId, it) }) continue
            val local = ctx.localFiles().filter { it.kind == SaveKind.STATE }
            if (local.isEmpty() && remote.isEmpty()) continue

            val plan = SyncSlots.planStates(
                localStates = local,
                remoteStates = remote,
                emulator = ctx.emulator,
                lastLocalHash = { syncedHashStore?.getSyncedHash(romId, it) },
                lastRemoteMarker = { syncedHashStore?.getSyncedHash(romId, remoteMarkerKey(it)) },
            )
            for (step in plan) {
                executeStateStep(step, ctx, transfer, run)
            }
        }
    }

    private fun remoteChangedSinceSync(romId: Int, remote: ServerAssetDto): Boolean {
        val name = SyncSlots.localNameFor(remote.fileName, remote.slot)
        return !SyncSlots.sameRemoteVersion(syncedHashStore?.getSyncedHash(romId, remoteMarkerKey(name)), remote)
    }

    private suspend fun executeStateStep(
        step: SyncSlots.StateOp,
        ctx: RomContext,
        transfer: SaveTransfer,
        run: RunState,
    ) {
        val romId = ctx.rom.romId
        when (step.action) {
            SyncSlots.StateAction.UPLOAD -> {
                val local = step.local ?: return
                if (uploadStateFrom(ctx, transfer, local)) {
                    run.completed++
                    run.uploaded++
                } else {
                    recordFailure(run, romId, step.name, listOf(ctx.rom), "sync.failed.action.upload", "sync.failed.upload_failed")
                }
            }
            SyncSlots.StateAction.DOWNLOAD -> {
                val remote = step.remote ?: return
                if (downloadStateInto(ctx, transfer, remote, step.name)) {
                    run.completed++
                    run.downloaded++
                } else {
                    recordFailure(run, romId, step.name, listOf(ctx.rom), "sync.failed.action.download", "sync.failed.download_failed")
                }
            }
            SyncSlots.StateAction.CONFLICT -> {
                val local = step.local
                val remote = step.remote
                val resolved = when (conflictPolicy) {
                    ConflictPolicy.PREFER_LOCAL -> local != null && uploadStateFrom(ctx, transfer, local)
                        .also { if (it) run.uploaded++ }
                    ConflictPolicy.PREFER_SERVER -> remote != null && downloadStateInto(ctx, transfer, remote, step.name)
                        .also { if (it) run.downloaded++ }
                    ConflictPolicy.ASK -> false
                }
                if (resolved) {
                    run.completed++
                    run.autoResolvedConflicts++
                } else {
                    run.conflictRomIds.add(romId)
                    run.conflicts.add(
                        ConflictInfo(
                            romId = romId,
                            romName = ctx.rom.name,
                            fileName = step.name,
                            serverUpdatedAt = remote?.updatedAt,
                            reason = tr("android.sync.conflict.state_reason"),
                            saveId = remote?.id,
                            kind = ConflictInfo.KIND_STATE,
                        ),
                    )
                }
            }
            SyncSlots.StateAction.NOOP -> {
                // Same content on both sides (server hash): remember it as the synced version.
                val local = step.local
                val remote = step.remote
                if (local != null && remote != null) recordStateSynced(romId, step.name, local.sha1, remote)
            }
        }
    }

    private suspend fun uploadStateFrom(ctx: RomContext, transfer: SaveTransfer, local: LocalSave): Boolean {
        val stored = transfer.uploadState(local, ctx.rom.romId, ctx.emulator, ctx.handler) ?: return false
        recordStateSynced(ctx.rom.romId, local.fileName, local.sha1, stored)
        return true
    }

    private suspend fun downloadStateInto(
        ctx: RomContext,
        transfer: SaveTransfer,
        remote: ServerAssetDto,
        name: String,
    ): Boolean {
        backupLocalSave(ctx.rom.romId, name, ctx.localFiles(), ctx.handler)
        val sha1 = transfer.downloadState(remote.id, name, ctx.ref, ctx.basePath, ctx.handler) ?: return false
        commitStaged(ctx.rawBasePath)
        recordStateSynced(ctx.rom.romId, name, sha1, remote)
        ctx.invalidate()
        return true
    }

    private fun recordStateSynced(romId: Int, name: String, localSha1: String, remote: ServerAssetDto) {
        syncedHashStore?.setSyncedHash(romId, name, localSha1)
        syncedHashStore?.setSyncedHash(romId, remoteMarkerKey(name), SyncSlots.remoteMarker(remote))
    }

    // ── Conflict resolution ───────────────────────────────────────────────

    /**
     * true si el directorio es utilizable: API File, root (teléfonos
     * rooteados) o concesión SAF vigente (selector, Android 11/12).
     */
    private fun isPathReadable(path: String): Boolean =
        SavePathAccess.isUsable(path, appContext)

    /**
     * Resuelve un conflicto pendiente forzando la dirección elegida por el
     * usuario:
     * - "local": uploads the local version with overwrite=true (this device wins).
     * - "server": downloads the newest server version of that slot (or state)
     *   over the local copy, after backing it up.
     *
     * Works for saves and states; the kind is taken from the local file.
     *
     * @return mensaje descriptivo del resultado.
     */
    suspend fun runConflictResolution(
        romId: Int,
        fileName: String,
        resolution: String,
    ): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = settingsDataStore.getServerUrlBlocking()
        val apiKey = settingsDataStore.getApiKeyBlocking()
        val retroArchBase = settingsDataStore.getRetroArchBasePathBlocking()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            return@withContext SyncResult(error = tr("common.server_not_configured"))
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)
        val transfer = SaveTransfer(api, cacheDir) { msg, e -> Log.w(TAG, msg, e) }
        val deviceId = resolveDeviceId(api)
            ?: return@withContext SyncResult(error = tr("sync.error.device_registration"))

        val rom = romDao.getAllDownloadedRomsBlocking().find { it.romId == romId }
            ?: return@withContext SyncResult(error = tr("sync.error.rom_not_downloaded", romId))

        val config = platformDao.getAllPlatformsBlocking().find { it.slug == rom.platformSlug }
        val ctx = romContext(rom, config, retroArchBase, SavePathResolver.deviceProfile(settingsDataStore))
        val local = ctx.localFiles().find { it.fileName == fileName }

        try {
            when (resolution) {
                "local" -> {
                    local ?: return@withContext SyncResult(error = tr("sync.error.local_save_not_found", fileName))
                    val ok = if (local.kind == SaveKind.STATE) {
                        uploadStateFrom(ctx, transfer, local)
                    } else {
                        keepLocalSave(ctx, transfer, local, deviceId)
                    }
                    if (ok) {
                        SyncResult(uploaded = 1, message = tr("sync.conflict.local_uploaded", fileName))
                    } else {
                        SyncResult(error = tr("sync.error.upload_failed", fileName))
                    }
                }
                "server" -> {
                    val remoteState = if (local == null || local.kind == SaveKind.STATE) {
                        newestRemote(api.listStates(romId = romId), fileName)
                    } else {
                        null
                    }
                    val ok = if (remoteState != null) {
                        downloadStateInto(ctx, transfer, remoteState, fileName)
                    } else {
                        val remoteSave = newestRemote(api.listSaves(romId = romId).filter { it.slot != null }, fileName)
                            ?: return@withContext SyncResult(error = tr("sync.error.no_save_id", fileName))
                        downloadSaveInto(ctx, transfer, remoteSave.id, deviceId, fileName)
                    }
                    if (ok) {
                        SyncResult(downloaded = 1, message = tr("sync.conflict.server_restored", fileName))
                    } else {
                        SyncResult(error = tr("sync.error.download_failed", fileName))
                    }
                }
                else -> SyncResult(error = tr("sync.error.unknown_resolution", resolution))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Conflict resolution failed for $fileName", e)
            SyncResult(error = tr("sync.conflict.resolve_error", e.message))
        } finally {
            cleanupStaged()
        }
    }

    /** Newest server asset whose canonical local name is [fileName]. */
    private fun newestRemote(assets: List<ServerAssetDto>, fileName: String): ServerAssetDto? =
        assets.filter { SyncSlots.localNameFor(it.fileName, it.slot) == fileName }
            .maxWithOrNull(compareBy<ServerAssetDto>({ it.updatedAt ?: "" }, { it.id }))

    /**
     * Guarda una copia de seguridad de la copia local de un save justo antes
     * de que una descarga del servidor la sobrescriba (blob preparado por el
     * handler, restaurable vía extractDownload).
     */
    private suspend fun backupLocalSave(
        romId: Int,
        fileName: String,
        saves: List<LocalSave>?,
        handler: SaveHandler,
    ) {
        val manager = backupManager ?: return
        val local = saves?.find { it.fileName == fileName } ?: return
        runCatching { manager.backup(romId, fileName, handler.prepareForUpload(local)) }
    }

    /**
     * Restaura una copia de seguridad de save sobre su ubicación original
     * (para la UI de historial de copias del detalle de juego).
     */
    suspend fun restoreBackup(romId: Int, fileName: String, backupFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val rom = romDao.getAllDownloadedRomsBlocking().find { it.romId == romId }
                ?: return@withContext false
            val config = platformDao.getAllPlatformsBlocking().find { it.slug == rom.platformSlug }
            val profile = SavePathResolver.deviceProfile(settingsDataStore)
            val handler = SavePathResolver.handlerFor(rom.platformSlug, config, profile)
            val retroArchBase = settingsDataStore.getRetroArchBasePathBlocking()
            val rawBase = SavePathResolver.romBasePath(rom, config, retroArchBase, profile)
            val staged = SavePathAccess.stage(rawBase, File(cacheDir, "save_stage"), appContext)
            val ok = runCatching {
                handler.extractDownload(
                    tempFile = backupFile,
                    romFileName = rom.fileName,
                    platformSlug = rom.platformSlug,
                    savesBasePath = staged.dir.path,
                    targetFileName = fileName,
                )
            }.getOrDefault(false)
            if (ok) SavePathAccess.commit(staged, appContext)
            SavePathAccess.cleanup(staged, appContext)
            ok
        }

    /**
     * Device id for sync. A device paired through RomM's device flow uses its
     * own id and is never registered again through `POST /api/devices`; only
     * API-key mode keeps the upstream registration.
     */
    private suspend fun resolveDeviceId(api: RomMApiService): String? {
        settingsDataStore.getPairedDeviceBlocking()?.deviceId?.takeIf { it.isNotBlank() }?.let { return it }

        val cached = settingsDataStore.getSyncDeviceIdStringBlocking()
        if (!cached.isNullOrBlank()) return cached

        return try {
            val response = api.registerDevice(
                DeviceRegistrationRequest(
                    name = "${Build.MANUFACTURER} ${Build.MODEL}",
                    platform = "android",
                    hostname = Build.DEVICE,
                ),
            )
            settingsDataStore.setSyncDeviceIdString(response.deviceId)
            response.deviceId
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "Device registration HTTP ${e.code()}: ${e.message()}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Device registration failed", e)
            null
        }
    }

    private fun recordFailure(
        run: RunState,
        romId: Int,
        fileName: String,
        roms: List<DownloadedRomEntity>,
        actionKey: String,
        reasonKey: String,
    ) {
        run.failed++
        run.failedRomIds.add(romId)
        run.failures.add(
            FailedOpInfo(
                romId = romId,
                romName = roms.find { it.romId == romId }?.name ?: tr("sync.unknown_rom", romId),
                fileName = fileName,
                action = tr(actionKey),
                reason = tr(reasonKey),
            ),
        )
    }

    private fun buildResultMessage(
        completed: Int,
        failed: Int,
        conflicts: Int,
        pathWarnings: List<String> = emptyList(),
    ): String {
        val parts = mutableListOf<String>()
        if (completed > 0) parts.add(tr("sync.result.completed", completed))
        if (failed > 0) parts.add(tr("sync.result.failed", failed))
        if (conflicts > 0) parts.add(tr("sync.result.conflicts", conflicts))
        if (pathWarnings.isNotEmpty()) {
            val shown = pathWarnings.take(3).joinToString(", ") +
                if (pathWarnings.size > 3) {
                    " " + tr("android.sync.result.more_paths", pathWarnings.size - 3)
                } else {
                    ""
                }
            parts.add(tr("android.sync.result.path_warnings", pathWarnings.size, shown))
        }
        return if (parts.isEmpty()) tr("sync.result.all_synced") else parts.joinToString(" · ")
    }

    companion object {
        private const val TAG = "SyncCoordinator"

        /** Tope de rutas ilegibles detalladas en el mensaje de resultado. */
        private const val MAX_PATH_WARNINGS = 10

        /** SyncedHashStore key under which the server version of a state is remembered. */
        internal fun remoteMarkerKey(name: String) = "remote:$name"

        /**
         * Negotiate requests for one run: saves only (states are synced
         * separately), each with slot + emulator, scoped with `rom_ids` to the
         * roms installed here so the server never offers downloads for roms this
         * device does not have. Chunked to the server's rom-id cap.
         */
        internal fun buildNegotiateRequests(
            deviceId: String,
            scopeRomIds: List<Int>,
            savesByRom: Map<Int, List<LocalSave>>,
            emulatorFor: (romId: Int) -> String,
        ): List<NegotiateRequest> = scopeRomIds.distinct()
            .chunked(SyncSlots.MAX_ROM_IDS_PER_NEGOTIATE)
            .map { chunk ->
                NegotiateRequest(
                    deviceId = deviceId,
                    saves = SyncSlots.clientSaveStates(
                        chunk.mapNotNull { id -> savesByRom[id]?.let { id to it } }.toMap(),
                        emulatorFor,
                    ),
                    romIds = chunk,
                )
            }
    }
}

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val autoResolvedConflicts: Int = 0,
    val conflictDetails: List<ConflictInfo> = emptyList(),
    val failedDetails: List<FailedOpInfo> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** No hubo nada que hacer (ni subidas, ni bajadas, ni fallos ni avisos). */
    val allSynced: Boolean = false,
    /** El resultado trae avisos (p. ej. rutas de saves configuradas ilegibles). */
    val hasWarnings: Boolean = false,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Detalle de una operación de sync que falló, para mostrar en la UI.
 */
data class FailedOpInfo(
    val romId: Int,
    val romName: String,
    val fileName: String,
    val action: String,
    val reason: String,
)

/**
 * Detalle de un conflicto detectado durante la negociación: la copia local
 * y la del servidor difieren y ambas son más recientes que la última
 * sincronización conocida.
 *
 * [fileName] is the canonical local name (the slot); [kind] says whether it is
 * an in-game save or a save state.
 */
data class ConflictInfo(
    val romId: Int,
    val romName: String,
    val fileName: String,
    val serverUpdatedAt: String?,
    val reason: String?,
    val saveId: Int?,
    val kind: String = KIND_SAVE,
) {
    companion object {
        const val KIND_SAVE = "save"
        const val KIND_STATE = "state"
    }
}
