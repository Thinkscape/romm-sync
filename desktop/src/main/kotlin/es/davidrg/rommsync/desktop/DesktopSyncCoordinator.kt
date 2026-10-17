package es.davidrg.rommsync.desktop

import java.net.InetAddress
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.remote.NetworkModule
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.core.remote.dto.NegotiateRequest
import es.davidrg.rommsync.core.remote.dto.NegotiateResponse
import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import es.davidrg.rommsync.core.remote.dto.SessionCompleteRequest
import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.ConflictPolicy
import es.davidrg.rommsync.core.sync.SaveBackupManager
import es.davidrg.rommsync.core.sync.SyncSlots
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveHandler
import es.davidrg.rommsync.core.sync.platform.SaveHandlerRegistry
import es.davidrg.rommsync.core.sync.platform.SaveKind
import es.davidrg.rommsync.core.sync.platform.SaveProfile
import es.davidrg.rommsync.core.sync.platform.SyncSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Orquesta el ciclo completo de sincronización de saves con el servidor RomM:
 * 1. Resuelve el device id (el emparejado, o registro por API key).
 * 2. Escanea saves locales.
 * 3. Negocia con el servidor (qué subir, qué bajar, qué conflictos).
 * 4. Ejecuta las operaciones.
 * 5. Cierra la sesión.
 * 6. Sincroniza los save states (fuera de negotiate) contra /api/states.
 *
 * Slot contract (see [SyncSlots]): every negotiated save carries slot = its
 * canonical local file name and the emulator id; downloads always land at the
 * canonical local name; `delete` moves the local file into the backup area;
 * conflicts are never resolved unless the user chose a policy or a side.
 */
class DesktopSyncCoordinator(
    private val config: DesktopConfig,
    private val library: DesktopLibrary,
    private val cacheDir: File,
    private val backupManager: SaveBackupManager? = null,
    private val hashStore: DesktopHashStore? = null,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    private val logger: (String) -> Unit = { println(it) },
) {

    /** Per-rom sync context: the handler, emulator and saves path this device uses for it. */
    private class RomContext(
        val rom: DesktopLibrary.RomEntry,
        val handler: SaveHandler,
        val emulator: String,
        val basePath: String,
    )

    private class Session(val api: RomMApiService, val deviceId: String)

    /** Builds the API client and resolves the device id, or returns a translated error. */
    private suspend fun openSession(): Pair<Session?, String?> {
        val serverUrl = config.effectiveServerUrl
        val token = config.authToken
        if (serverUrl.isEmpty() || token.isEmpty()) return null to tr("common.server_not_configured")
        val api = NetworkModule.createApiService(serverUrl, token)
        val deviceId = resolveDeviceId(api) ?: return null to tr("sync.error.device_registration_detail")
        return Session(api, deviceId) to null
    }

    private fun contexts(roms: List<DesktopLibrary.RomEntry>): List<RomContext> {
        val profile = config.saveProfile()
        return roms.mapNotNull { rom ->
            val platform = library.platform(rom.platformSlug)
            if (SaveHandlerRegistry.syncSupport(rom.platformSlug, platform.emulatorId, profile) is SyncSupport.Unsupported) {
                return@mapNotNull null
            }
            val emulator = platform.emulatorId
                ?: SaveHandlerRegistry.getDefaultEmulator(rom.platformSlug, profile).id
            RomContext(
                rom = rom,
                handler = SaveHandlerRegistry.getHandler(rom.platformSlug, platform.emulatorId, profile),
                emulator = emulator,
                basePath = resolveSavesBasePath(rom, platform, emulator, profile),
            )
        }
    }

    private suspend fun findSaves(ctx: RomContext): List<LocalSave> = ctx.handler.findSaves(
        romId = ctx.rom.romId,
        romFileName = ctx.rom.fileName,
        platformSlug = ctx.rom.platformSlug,
        savesBasePath = ctx.basePath,
        romLocalPath = ctx.rom.localPath,
    )

    private suspend fun fingerprint(ctx: RomContext): String? = ctx.handler.savesFingerprint(
        romId = ctx.rom.romId,
        romFileName = ctx.rom.fileName,
        platformSlug = ctx.rom.platformSlug,
        savesBasePath = ctx.basePath,
        romLocalPath = ctx.rom.localPath,
    )

    /** One negotiate per chunk of [SyncSlots.MAX_ROM_IDS_PER_NEGOTIATE] roms; scoped to the installed roms. */
    private suspend fun negotiate(
        session: Session,
        contexts: List<RomContext>,
        localSaves: Map<Int, List<LocalSave>>,
    ): List<NegotiateResponse> {
        val emulatorByRom = contexts.associate { it.rom.romId to it.emulator }
        return contexts.chunked(SyncSlots.MAX_ROM_IDS_PER_NEGOTIATE).map { chunk ->
            val ids = chunk.map { it.rom.romId }
            session.api.negotiateSync(
                buildNegotiateRequest(session.deviceId, ids, localSaves.filterKeys { it in ids }) { emulatorByRom.getValue(it) },
            )
        }
    }

    suspend fun runSync(): SyncResult = withContext(Dispatchers.IO) {
        val (session, error) = openSession()
        if (session == null) return@withContext SyncResult(error = error)

        val allDownloadedRoms = library.roms()
        if (allDownloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = tr("sync.no_downloaded_roms"))
        }
        val downloadedRoms = allDownloadedRoms.filterNot { it.excludedFromSync }
        if (downloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = tr("sync.all_excluded"))
        }

        val contexts = contexts(downloadedRoms)
        val ctxByRom = contexts.associateBy { it.rom.romId }
        val localSavesMap = mutableMapOf<Int, List<LocalSave>>()
        val skippedByFingerprint = mutableSetOf<Int>()

        for (ctx in contexts) {
            // Fingerprint shortcut: saves unchanged since the last clean cycle are
            // not listed; the server then offers only remote changes for them.
            val cachedFp = hashStore?.getFingerprint(ctx.rom.romId)
            if (cachedFp != null && fingerprint(ctx) == cachedFp) {
                skippedByFingerprint += ctx.rom.romId
                continue
            }
            val saves = findSaves(ctx)
            if (saves.isNotEmpty()) localSavesMap[ctx.rom.romId] = saves
        }

        val responses = try {
            negotiate(session, contexts, localSavesMap)
        } catch (e: Exception) {
            log("sync: negotiate failed: ${describe(e)}")
            return@withContext SyncResult(error = tr("sync.error.negotiation", e.message))
        }

        var completed = 0
        var failed = 0
        var uploaded = 0
        var downloaded = 0
        var autoResolvedConflicts = 0
        val unsealedRomIds = mutableSetOf<Int>()
        val failures = mutableListOf<FailedOpInfo>()
        val conflicts = mutableListOf<ConflictInfo>()

        fun romName(romId: Int) = ctxByRom[romId]?.rom?.name ?: tr("sync.unknown_rom", romId)
        fun fail(romId: Int, fileName: String, action: String, reason: String) {
            failed++
            unsealedRomIds += romId
            failures += FailedOpInfo(romId, romName(romId), fileName, action, reason)
        }
        fun conflict(op: SyncOperation) {
            unsealedRomIds += op.romId
            conflicts += ConflictInfo(
                romId = op.romId,
                romName = romName(op.romId),
                fileName = SyncSlots.localNameFor(op.fileName, op.slot),
                serverUpdatedAt = op.serverUpdatedAt,
                reason = op.reason,
                saveId = op.saveId,
            )
            log("sync: conflict ${op.fileName} (rom ${op.romId}): ${op.reason ?: "both sides changed"}; skipped")
        }

        for (response in responses) {
            var sessionCompleted = 0
            var sessionFailed = 0
            for (op in response.operations) {
                val ctx = ctxByRom[op.romId]
                val localName = SyncSlots.localNameFor(op.fileName, op.slot)
                when (op.action) {
                    "upload" -> {
                        val save = SyncSlots.localSaveFor(op, localSavesMap[op.romId])
                        if (save == null || ctx == null) {
                            fail(op.romId, localName, tr("sync.failed.action.upload"), tr("sync.failed.save_missing"))
                            sessionFailed++
                            continue
                        }
                        when (executeUpload(session, save, ctx, overwrite = false)) {
                            UploadOutcome.OK -> { completed++; sessionCompleted++; uploaded++ }
                            UploadOutcome.CONFLICT -> conflict(op)
                            UploadOutcome.FAILED -> {
                                fail(op.romId, localName, tr("sync.failed.action.upload"), tr("sync.failed.upload_failed"))
                                sessionFailed++
                            }
                        }
                    }
                    "download" -> {
                        val saveId = op.saveId
                        if (ctx == null || saveId == null) {
                            fail(op.romId, localName, tr("sync.failed.action.download"), tr("sync.failed.rom_not_downloaded"))
                            sessionFailed++
                            continue
                        }
                        backupLocalSave(op.romId, localName, localSavesMap[op.romId])
                        if (executeDownload(session, saveId, ctx, localName)) {
                            completed++; sessionCompleted++; downloaded++
                        } else {
                            fail(op.romId, localName, tr("sync.failed.action.download"), tr("sync.failed.download_failed"))
                            sessionFailed++
                        }
                    }
                    "delete" -> {
                        // The slot was emptied on the server: move the local copy into the backup area.
                        val save = SyncSlots.localSaveFor(op, localSavesMap[op.romId])
                        if (save == null || ctx == null || moveToBackup(op.romId, save, ctx.handler)) {
                            completed++; sessionCompleted++
                            log("sync: ${save?.fileName ?: localName} (rom ${op.romId}) removed in RomM; local copy moved to backups")
                        } else {
                            fail(op.romId, localName, tr("desktop.sync.action_delete"), tr("desktop.sync.backup_failed"))
                            sessionFailed++
                        }
                    }
                    "conflict" -> {
                        when (conflictPolicy) {
                            ConflictPolicy.PREFER_LOCAL -> {
                                val save = SyncSlots.localSaveFor(op, localSavesMap[op.romId])
                                if (save != null && ctx != null &&
                                    executeUpload(session, save, ctx, overwrite = true) == UploadOutcome.OK
                                ) {
                                    completed++; sessionCompleted++; uploaded++; autoResolvedConflicts++
                                } else {
                                    conflict(op)
                                }
                            }
                            ConflictPolicy.PREFER_SERVER -> {
                                val saveId = op.saveId
                                if (ctx != null && saveId != null) {
                                    backupLocalSave(op.romId, localName, localSavesMap[op.romId])
                                    if (executeDownload(session, saveId, ctx, localName)) {
                                        completed++; sessionCompleted++; downloaded++; autoResolvedConflicts++
                                    } else {
                                        conflict(op)
                                    }
                                } else {
                                    conflict(op)
                                }
                            }
                            ConflictPolicy.ASK -> conflict(op)
                        }
                    }
                }
            }
            try {
                session.api.completeSession(
                    sessionId = response.sessionId,
                    request = SessionCompleteRequest(
                        operationsCompleted = sessionCompleted,
                        operationsFailed = sessionFailed,
                    ),
                )
            } catch (e: Exception) {
                log("sync: completing session ${response.sessionId} failed: ${describe(e)}")
            }
        }

        // States: outside negotiate, reconciled per rom against GET /api/states.
        val states = syncStates(session, contexts, localSavesMap, skippedByFingerprint, dryRun = false)
        uploaded += states.uploaded
        downloaded += states.downloaded
        completed += states.uploaded + states.downloaded
        states.failures.forEach { fail(it.romId, it.fileName, it.action, it.reason) }
        states.conflicts.forEach {
            unsealedRomIds += it.romId
            conflicts += it
            log("sync: state conflict ${it.fileName} (rom ${it.romId}); skipped")
        }
        unsealedRomIds += states.incompleteRomIds

        // Seal fingerprints of roms whose cycle ended without failures or open conflicts.
        if (hashStore != null) {
            for (ctx in contexts) {
                if (ctx.rom.romId in unsealedRomIds) {
                    hashStore.clearFingerprint(ctx.rom.romId)
                    continue
                }
                fingerprint(ctx)?.let { hashStore.setFingerprint(ctx.rom.romId, it) }
            }
        }

        SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            conflicts = conflicts.size,
            autoResolvedConflicts = autoResolvedConflicts,
            conflictDetails = conflicts,
            failedDetails = failures,
            message = buildResultMessage(completed, failed, conflicts.size),
        )
    }

    /**
     * Negociación sin efectos: lista qué subidas/bajadas haría un runSync y
     * qué conflictos hay (botón "Comprobar cambios" de la pestaña Saves).
     */
    suspend fun scanPendingSaves(): PendingSavesReport = withContext(Dispatchers.IO) {
        val (session, error) = openSession()
        if (session == null) return@withContext PendingSavesReport(error = error)

        val downloadedRoms = library.roms().filterNot { it.excludedFromSync }
        if (downloadedRoms.isEmpty()) return@withContext PendingSavesReport()

        val contexts = contexts(downloadedRoms)
        val localSavesMap = mutableMapOf<Int, List<LocalSave>>()
        for (ctx in contexts) {
            val saves = findSaves(ctx)
            if (saves.isNotEmpty()) localSavesMap[ctx.rom.romId] = saves
        }

        val responses = try {
            negotiate(session, contexts, localSavesMap)
        } catch (e: Exception) {
            return@withContext PendingSavesReport(error = tr("sync.error.negotiation", e.message))
        }
        val operations = responses.flatMap { it.operations }
        val romNameById = downloadedRoms.associate { it.romId to it.name }
        fun item(op: SyncOperation) = PendingSaveItem(
            op.romId,
            romNameById[op.romId] ?: tr("sync.unknown_rom", op.romId),
            SyncSlots.localNameFor(op.fileName, op.slot),
        )
        val states = syncStates(session, contexts, localSavesMap, emptySet(), dryRun = true)

        PendingSavesReport(
            uploads = operations.filter { it.action == "upload" }.map(::item) + states.pendingUploads,
            downloads = operations.filter { it.action == "download" }.map(::item) + states.pendingDownloads,
            conflicts = operations.filter { it.action == "conflict" }.map {
                ConflictInfo(
                    romId = it.romId,
                    romName = romNameById[it.romId] ?: tr("sync.unknown_rom", it.romId),
                    fileName = SyncSlots.localNameFor(it.fileName, it.slot),
                    serverUpdatedAt = it.serverUpdatedAt,
                    reason = it.reason,
                    saveId = it.saveId,
                )
            } + states.conflicts,
        )
    }

    data class PendingSaveItem(
        val romId: Int,
        val romName: String,
        val fileName: String,
    )

    data class PendingSavesReport(
        val uploads: List<PendingSaveItem> = emptyList(),
        val downloads: List<PendingSaveItem> = emptyList(),
        val conflicts: List<ConflictInfo> = emptyList(),
        val error: String? = null,
    )

    /**
     * Resuelve un conflicto pendiente forzando la dirección elegida por el
     * usuario:
     * - "local": sube la versión local con overwrite (gana este dispositivo).
     * - "server": descarga la versión del servidor sobrescribiendo la local
     *   (con copia de seguridad previa).
     *
     * [fileName] is the canonical local name; saves and states are both handled.
     */
    suspend fun runConflictResolution(
        romId: Int,
        fileName: String,
        resolution: String,
    ): SyncResult = withContext(Dispatchers.IO) {
        val (session, error) = openSession()
        if (session == null) return@withContext SyncResult(error = error)

        val rom = library.roms().find { it.romId == romId }
            ?: return@withContext SyncResult(error = tr("sync.error.rom_not_downloaded", romId))
        val ctx = contexts(listOf(rom)).firstOrNull()
            ?: return@withContext SyncResult(error = tr("sync.error.rom_not_downloaded", romId))
        val localSaves = findSaves(ctx)
        val local = localSaves.find { it.fileName == fileName }

        if (local?.kind == SaveKind.STATE) {
            return@withContext resolveStateConflict(session, ctx, local, resolution)
        }

        when (resolution) {
            "local" -> {
                val save = local ?: return@withContext SyncResult(error = tr("sync.error.local_save_not_found", fileName))
                if (executeUpload(session, save, ctx, overwrite = true) == UploadOutcome.OK) {
                    hashStore?.clearFingerprint(romId)
                    SyncResult(uploaded = 1, message = tr("sync.conflict.local_uploaded", fileName))
                } else {
                    SyncResult(error = tr("sync.error.upload_failed", fileName))
                }
            }
            "server" -> {
                // Negotiate this rom alone to learn the server save id for the slot.
                val response = try {
                    session.api.negotiateSync(
                        buildNegotiateRequest(session.deviceId, listOf(romId), mapOf(romId to localSaves)) { ctx.emulator },
                    )
                } catch (e: Exception) {
                    return@withContext SyncResult(error = tr("sync.error.negotiation", e.message))
                }
                val op = response.operations.find {
                    it.romId == romId && SyncSlots.localNameFor(it.fileName, it.slot) == fileName
                } ?: return@withContext SyncResult(error = tr("sync.error.no_server_operation", fileName))
                val saveId = op.saveId
                    ?: return@withContext SyncResult(error = tr("sync.error.no_save_id", fileName))

                backupLocalSave(romId, fileName, localSaves)
                val ok = executeDownload(session, saveId, ctx, fileName)
                runCatching {
                    session.api.completeSession(
                        sessionId = response.sessionId,
                        request = SessionCompleteRequest(
                            operationsCompleted = if (ok) 1 else 0,
                            operationsFailed = if (ok) 0 else 1,
                        ),
                    )
                }
                if (ok) {
                    hashStore?.clearFingerprint(romId)
                    SyncResult(downloaded = 1, message = tr("sync.conflict.server_restored", fileName))
                } else {
                    SyncResult(error = tr("sync.error.download_failed", fileName))
                }
            }
            else -> SyncResult(error = tr("sync.error.unknown_resolution", resolution))
        }
    }

    private suspend fun resolveStateConflict(
        session: Session,
        ctx: RomContext,
        local: LocalSave,
        resolution: String,
    ): SyncResult {
        val romId = ctx.rom.romId
        return when (resolution) {
            "local" -> {
                val remote = uploadState(session, ctx, local)
                if (remote != null) {
                    hashStore?.setStateSynced(romId, local.fileName, local.sha1, SyncSlots.remoteMarker(remote))
                    SyncResult(uploaded = 1, message = tr("sync.conflict.local_uploaded", local.fileName))
                } else {
                    SyncResult(error = tr("sync.error.upload_failed", local.fileName))
                }
            }
            "server" -> {
                val remote = try {
                    session.api.listStates(romId)
                        .filter { SyncSlots.localNameFor(it.fileName, it.slot) == local.fileName }
                        .maxWithOrNull(compareBy<ServerAssetDto>({ it.updatedAt ?: "" }, { it.id }))
                } catch (e: Exception) {
                    log("sync: listing states of rom $romId failed: ${describe(e)}")
                    null
                } ?: return SyncResult(error = tr("sync.error.no_server_operation", local.fileName))
                backupLocalSave(romId, local.fileName, listOf(local))
                if (downloadState(session, ctx, remote, local.fileName)) {
                    SyncResult(downloaded = 1, message = tr("sync.conflict.server_restored", local.fileName))
                } else {
                    SyncResult(error = tr("sync.error.download_failed", local.fileName))
                }
            }
            else -> SyncResult(error = tr("sync.error.unknown_resolution", resolution))
        }
    }

    // ── States ──────────────────────────────────────────────────────────

    private class StatesOutcome {
        var uploaded = 0
        var downloaded = 0
        val failures = mutableListOf<FailedOpInfo>()
        val conflicts = mutableListOf<ConflictInfo>()
        val incompleteRomIds = mutableSetOf<Int>()
        val pendingUploads = mutableListOf<PendingSaveItem>()
        val pendingDownloads = mutableListOf<PendingSaveItem>()
    }

    /**
     * Reconciles save states (excluded from negotiate) for every rom: lists
     * `GET /api/states?rom_id=`, plans with [SyncSlots.planStates] against the
     * last-synced base in [hashStore], and (unless [dryRun]) performs the plan.
     * Roms skipped by the fingerprint shortcut are only rescanned when the server
     * holds a state version this device has not seen.
     */
    private suspend fun syncStates(
        session: Session,
        contexts: List<RomContext>,
        localSaves: MutableMap<Int, List<LocalSave>>,
        skippedByFingerprint: Set<Int>,
        dryRun: Boolean,
    ): StatesOutcome {
        val out = StatesOutcome()
        for (ctx in contexts) {
            val romId = ctx.rom.romId
            val remote = try {
                session.api.listStates(romId)
            } catch (e: Exception) {
                log("sync: listing states of rom $romId failed: ${describe(e)}")
                out.incompleteRomIds += romId
                continue
            }
            val locals = if (romId in skippedByFingerprint) {
                val unseen = remote.any { r ->
                    val name = SyncSlots.localNameFor(r.fileName, r.slot)
                    (r.emulator == null || r.emulator == ctx.emulator) &&
                        !SyncSlots.sameRemoteVersion(hashStore?.getStateRemoteMarker(romId, name), r)
                }
                if (!unseen) continue
                findSaves(ctx).also { localSaves[romId] = it }
            } else {
                localSaves[romId].orEmpty()
            }
            val plan = SyncSlots.planStates(
                localStates = locals,
                remoteStates = remote,
                emulator = ctx.emulator,
                lastLocalHash = { hashStore?.getStateLocalHash(romId, it) },
                lastRemoteMarker = { hashStore?.getStateRemoteMarker(romId, it) },
            )
            for (op in plan) {
                val item = PendingSaveItem(romId, ctx.rom.name, op.name)
                when (op.action) {
                    SyncSlots.StateAction.NOOP -> {
                        val local = op.local
                        val remoteState = op.remote
                        if (!dryRun && local != null && remoteState != null) {
                            hashStore?.setStateSynced(romId, op.name, local.sha1, SyncSlots.remoteMarker(remoteState))
                        }
                    }
                    SyncSlots.StateAction.CONFLICT -> out.conflicts += ConflictInfo(
                        romId = romId,
                        romName = ctx.rom.name,
                        fileName = op.name,
                        serverUpdatedAt = op.remote?.updatedAt,
                        reason = tr("sync.state.conflict_reason"),
                        saveId = null,
                        isState = true,
                    )
                    SyncSlots.StateAction.UPLOAD -> {
                        val local = op.local ?: continue
                        if (dryRun) { out.pendingUploads += item; continue }
                        val stored = uploadState(session, ctx, local)
                        if (stored != null) {
                            out.uploaded++
                            hashStore?.setStateSynced(romId, op.name, local.sha1, SyncSlots.remoteMarker(stored))
                        } else {
                            out.incompleteRomIds += romId
                            out.failures += FailedOpInfo(romId, ctx.rom.name, op.name, tr("sync.failed.action.upload"), tr("sync.failed.upload_failed"))
                        }
                    }
                    SyncSlots.StateAction.DOWNLOAD -> {
                        val remoteState = op.remote ?: continue
                        if (dryRun) { out.pendingDownloads += item; continue }
                        backupLocalSave(romId, op.name, locals)
                        if (downloadState(session, ctx, remoteState, op.name)) {
                            out.downloaded++
                        } else {
                            out.incompleteRomIds += romId
                            out.failures += FailedOpInfo(romId, ctx.rom.name, op.name, tr("sync.failed.action.download"), tr("sync.failed.download_failed"))
                        }
                    }
                }
            }
        }
        return out
    }

    private suspend fun uploadState(session: Session, ctx: RomContext, local: LocalSave): ServerAssetDto? = try {
        val file = ctx.handler.prepareForUpload(local)
        val part = MultipartBody.Part.createFormData(
            "stateFile",
            local.fileName,
            file.asRequestBody("application/octet-stream".toMediaType()),
        )
        session.api.uploadState(part, ctx.rom.romId, ctx.emulator.take(SyncSlots.EMULATOR_MAX_LENGTH))
    } catch (e: Exception) {
        log("sync: state upload ${local.fileName} (rom ${ctx.rom.romId}) failed: ${describe(e)}")
        null
    }

    /** Downloads [remote] to the canonical local [localName] and records the new base. */
    private suspend fun downloadState(session: Session, ctx: RomContext, remote: ServerAssetDto, localName: String): Boolean {
        val tempFile = File(cacheDir, "state_dl_${ctx.rom.romId}_${remote.id}")
        return try {
            session.api.downloadState(remote.id).byteStream().use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            val sha1 = sha1(tempFile)
            val ok = ctx.handler.extractDownload(
                tempFile = tempFile,
                romFileName = ctx.rom.fileName,
                platformSlug = ctx.rom.platformSlug,
                savesBasePath = ctx.basePath,
                targetFileName = localName,
            )
            if (ok) hashStore?.setStateSynced(ctx.rom.romId, localName, sha1, SyncSlots.remoteMarker(remote))
            ok
        } catch (e: Exception) {
            log("sync: state download ${remote.fileName} (rom ${ctx.rom.romId}) failed: ${describe(e)}")
            false
        } finally {
            tempFile.delete()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun resolveSavesBasePath(
        rom: DesktopLibrary.RomEntry,
        config: DesktopLibrary.PlatformEntry,
        emulator: String,
        profile: SaveProfile?,
    ): String {
        rom.savesPathOverride?.takeIf { it.isNotBlank() }?.let { return it }
        config.savesPathOverride?.takeIf { it.isNotBlank() }?.let { return it }
        return SaveHandlerRegistry.getDefaultSavesPath(
            emulatorId = emulator,
            platformSlug = rom.platformSlug,
            retroArchBase = "",
            profile = profile,
        )
    }

    /**
     * Guarda una copia de seguridad de la copia local de un save justo antes
     * de que una descarga del servidor la sobrescriba. El blob es el fichero
     * preparado por el handler (zip para saves de directorio), restaurable
     * vía extractDownload.
     */
    private suspend fun backupLocalSave(romId: Int, fileName: String, saves: List<LocalSave>?) {
        val manager = backupManager ?: return
        val local = saves?.find { it.fileName == fileName } ?: return
        val handler = library.rom(romId)?.let { contexts(listOf(it)).firstOrNull()?.handler } ?: return
        runCatching { manager.backup(romId, fileName, handler.prepareForUpload(local)) }
    }

    /** `delete` op: back the local save up, then remove it. False (and nothing removed) when no backup could be made. */
    private suspend fun moveToBackup(romId: Int, save: LocalSave, handler: SaveHandler): Boolean {
        val manager = backupManager ?: return false
        val backup = runCatching { manager.backup(romId, save.fileName, handler.prepareForUpload(save)) }.getOrNull()
            ?: return false
        if (!backup.isFile) return false
        return if (save.file.isDirectory) save.file.deleteRecursively() else (!save.file.exists() || save.file.delete())
    }

    /**
     * Restaura una copia de seguridad sobre la ubicación del save (para la
     * UI de historial de copias). El blob vuelve a pasar por extractDownload
     * del handler, así que sirve para saves de fichero y de directorio.
     */
    suspend fun restoreBackup(romId: Int, fileName: String, backupFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val rom = library.roms().find { it.romId == romId } ?: return@withContext false
            val ctx = contexts(listOf(rom)).firstOrNull() ?: return@withContext false
            runCatching {
                ctx.handler.extractDownload(
                    tempFile = backupFile,
                    romFileName = rom.fileName,
                    platformSlug = rom.platformSlug,
                    savesBasePath = ctx.basePath,
                    targetFileName = fileName,
                )
            }.getOrDefault(false)
        }

    /**
     * Paired devices use their bound `device_id` and never register through
     * `POST /api/devices`; API-key mode keeps upstream's one-time registration.
     */
    private suspend fun resolveDeviceId(api: RomMApiService): String? {
        if (config.isPaired) return config.pairedDeviceId
        val cached = config.deviceId
        if (!cached.isNullOrBlank()) return cached

        return try {
            val response = api.registerDevice(
                DeviceRegistrationRequest(
                    name = "RomM Sync Desktop",
                    platform = "linux",
                    hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop"),
                ),
            )
            config.deviceId = response.deviceId
            response.deviceId
        } catch (e: Exception) {
            log("sync: device registration failed: ${describe(e)}")
            null
        }
    }

    private enum class UploadOutcome { OK, CONFLICT, FAILED }

    /**
     * Slotted upload: slot = canonical name, content hash and device id as sent
     * in negotiate. [overwrite] only for an explicit keep-local choice; without it
     * RomM answers 409 when the slot moved on since this device's last sync.
     */
    private suspend fun executeUpload(
        session: Session,
        save: LocalSave,
        ctx: RomContext,
        overwrite: Boolean,
    ): UploadOutcome = try {
        val fileToUpload = ctx.handler.prepareForUpload(save)
        val requestFile = fileToUpload.asRequestBody("application/octet-stream".toMediaType())
        val filePart = MultipartBody.Part.createFormData("saveFile", save.fileName, requestFile)
        session.api.uploadSave(
            file = filePart,
            romId = ctx.rom.romId,
            emulator = ctx.emulator.take(SyncSlots.EMULATOR_MAX_LENGTH),
            deviceId = session.deviceId,
            slot = SyncSlots.slotFor(save),
            contentHash = save.sha1,
            overwrite = overwrite,
        ).close()
        UploadOutcome.OK
    } catch (e: HttpException) {
        if (e.code() == 409) {
            UploadOutcome.CONFLICT
        } else {
            log("sync: upload ${save.fileName} (rom ${ctx.rom.romId}) failed: ${describe(e)}")
            UploadOutcome.FAILED
        }
    } catch (e: Exception) {
        log("sync: upload ${save.fileName} (rom ${ctx.rom.romId}) failed: ${describe(e)}")
        UploadOutcome.FAILED
    }

    /** Downloads save [saveId] to the canonical local [localName] (never the server's tagged name). */
    private suspend fun executeDownload(
        session: Session,
        saveId: Int,
        ctx: RomContext,
        localName: String,
    ): Boolean {
        val tempFile = File(cacheDir, "sync_dl_${ctx.rom.romId}_$saveId")
        return try {
            session.api.downloadSave(saveId, session.deviceId).byteStream().use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            ctx.handler.extractDownload(
                tempFile = tempFile,
                romFileName = ctx.rom.fileName,
                platformSlug = ctx.rom.platformSlug,
                savesBasePath = ctx.basePath,
                targetFileName = localName,
            )
        } catch (e: Exception) {
            log("sync: download save $saveId (rom ${ctx.rom.romId}) failed: ${describe(e)}")
            false
        } finally {
            tempFile.delete()
        }
    }

    private fun log(message: String) = logger(message)

    private fun buildResultMessage(completed: Int, failed: Int, conflicts: Int): String {
        val parts = mutableListOf<String>()
        if (completed > 0) parts.add(tr("sync.result.completed", completed))
        if (failed > 0) parts.add(tr("sync.result.failed", failed))
        if (conflicts > 0) parts.add(tr("sync.result.conflicts", conflicts))
        return if (parts.isEmpty()) tr("sync.result.all_synced") else parts.joinToString(", ")
    }

    companion object {
        /**
         * Negotiate request for [romIds] (download scope) with the saves found
         * locally: saves only (states are synced separately), each with a non-null
         * slot and emulator per the slot contract.
         */
        internal fun buildNegotiateRequest(
            deviceId: String,
            romIds: List<Int>,
            localSaves: Map<Int, List<LocalSave>>,
            emulatorFor: (Int) -> String,
        ): NegotiateRequest = NegotiateRequest(
            deviceId = deviceId,
            saves = SyncSlots.clientSaveStates(localSaves, emulatorFor),
            romIds = romIds,
        )

        private fun sha1(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun describe(e: Throwable): String = when (e) {
            is HttpException -> "HTTP ${e.code()}: ${e.message()}"
            else -> "${e.javaClass.simpleName}: ${e.message}"
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
 * sincronización conocida. [fileName] is the canonical local name; [isState]
 * marks a save state (reconciled outside negotiate).
 */
data class ConflictInfo(
    val romId: Int,
    val romName: String,
    val fileName: String,
    val serverUpdatedAt: String?,
    val reason: String?,
    val saveId: Int?,
    val isState: Boolean = false,
)
