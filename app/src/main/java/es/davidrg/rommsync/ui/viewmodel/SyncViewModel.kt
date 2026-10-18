package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.ConflictInfo
import es.davidrg.rommsync.data.sync.FailedOpInfo
import es.davidrg.rommsync.data.sync.SaveSyncManager
import es.davidrg.rommsync.data.sync.SyncState
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import es.davidrg.rommsync.data.sync.SyncedHashStore
import es.davidrg.rommsync.data.sync.SavePathAccess
import es.davidrg.rommsync.core.sync.platform.SaveHandlerRegistry
import es.davidrg.rommsync.core.sync.platform.SyncSupport
import es.davidrg.rommsync.data.sync.SavePathResolver
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modelo de datos para mostrar una partida local con cambios pendientes.
 */
data class SavePreviewItem(
    val romName: String,
    val platformSlug: String,
    val fileName: String,
    val lastModified: Long,
)

/**
 * Whether this device can read one emulator's save folder (direct, root or a
 * saved SAF grant). Unreadable folders need "Grant folder access".
 */
data class SaveAccessItem(
    val emulatorName: String,
    val platformSlugs: List<String>,
    val path: String,
    val usable: Boolean,
)

class SyncViewModel(
    private val settingsRepository: SettingsRepository,
    private val saveSyncManager: SaveSyncManager,
    private val romDao: RomDao,
    private val platformDao: PlatformDao,
    private val syncedHashStore: SyncedHashStore,
    private val appContext: Context,
) : ViewModel() {

    val retroArchBasePath: StateFlow<String> = settingsRepository.retroArchBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDataStore.DEFAULT_RETROARCH_PATH)

    /**
     * Last run's state. A run that left conflicts shows "conflicts resolved" once
     * the persisted conflict list is empty (all resolved), instead of its stale
     * "N conflicts" message.
     */
    val syncState: StateFlow<SyncState> = combine(
        saveSyncManager.observeSyncState(),
        settingsRepository.lastSyncConflictsJson,
    ) { state, conflictsJson ->
        if (state is SyncState.Success && state.conflicts > 0 && parseConflictsJson(conflictsJson).isEmpty()) {
            state.copy(message = tr("android.sync.result.conflicts_resolved"), conflicts = 0)
        } else {
            state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncState.Idle)

    /** RetroArch folder save sync actually uses, and whether it comes from the SD-card profile default. */
    data class EffectiveRetroArchPath(val path: String, val fromProfile: Boolean)

    val effectiveRetroArchPath: StateFlow<EffectiveRetroArchPath?> = combine(
        settingsRepository.retroArchBasePath,
        settingsRepository.settings,
    ) { base, config ->
        val profile = SavePathResolver.deviceProfile(config.romsRootPath)
        val path = SavePathResolver.platformBasePath(
            platformSlug = "snes",
            emulatorId = SaveHandlerRegistry.EmulatorId.RETROARCH.id,
            savesPathOverride = null,
            retroArchBase = base,
            profile = profile,
        )
        EffectiveRetroArchPath(path, fromProfile = profile != null && path != base)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastSyncTimestamp: StateFlow<Long> = settingsRepository.lastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastSyncSummary: StateFlow<String> = settingsRepository.lastSyncSummary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncIntervalMinutes: StateFlow<Int> = settingsRepository.saveSyncIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _localSaves = MutableStateFlow<List<SavePreviewItem>>(emptyList())
    val localSaves: StateFlow<List<SavePreviewItem>> = _localSaves.asStateFlow()

    /** Rutas de saves configuradas (juego/plataforma) que no se pueden leer. */
    private val _pathWarnings = MutableStateFlow<List<String>>(emptyList())
    val pathWarnings: StateFlow<List<String>> = _pathWarnings.asStateFlow()

    /** Juegos comprobados en el último escaneo (-1 = aún sin comprobar). */
    private val _scanCheckedCount = MutableStateFlow(-1)
    val scanCheckedCount: StateFlow<Int> = _scanCheckedCount.asStateFlow()

    /** Raíz para copias staged de rutas restringidas (Android/data + root). */
    private val saveStageRoot = File(System.getProperty("java.io.tmpdir"), "save_stage")

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Conflictos detectados en el último sync, para la UI de resolución. */
    private val _conflicts = MutableStateFlow<List<ConflictInfo>>(emptyList())
    val conflicts: StateFlow<List<ConflictInfo>> = _conflicts.asStateFlow()

    /** Operaciones fallidas en el último sync, para la UI de detalle. */
    private val _failedOps = MutableStateFlow<List<FailedOpInfo>>(emptyList())
    val failedOps: StateFlow<List<FailedOpInfo>> = _failedOps.asStateFlow()

    /** Resoluciones en curso (romId+fileName) para feedback en la UI. */
    private val _resolvingConflict = MutableStateFlow<String?>(null)
    val resolvingConflict: StateFlow<String?> = _resolvingConflict.asStateFlow()

    /** Save folders of the emulators in use (platforms with downloaded ROMs) and their access state. */
    private val _saveAccess = MutableStateFlow<List<SaveAccessItem>>(emptyList())
    val saveAccess: StateFlow<List<SaveAccessItem>> = _saveAccess.asStateFlow()

    /** Carga los conflictos y fallidos del último sync persistido. */
    init {
        viewModelScope.launch {
            settingsRepository.lastSyncConflictsJson.collect { json ->
                _conflicts.value = parseConflictsJson(json)
            }
        }
        viewModelScope.launch {
            settingsRepository.lastSyncFailedJson.collect { json ->
                _failedOps.value = parseFailuresJson(json)
            }
        }
        refreshSaveAccess()
    }

    /** Resuelve un conflicto forzando la dirección elegida. */
    fun resolveConflict(romId: Int, fileName: String, resolution: String) {
        viewModelScope.launch {
            _resolvingConflict.value = "${romId}_$fileName"
            try {
                saveSyncManager.triggerConflictResolution(romId, fileName, resolution)
            } finally {
                _resolvingConflict.value = null
            }
            // Refrescar la lista de conflictos tras resolver
            _conflicts.value = _conflicts.value.filterNot {
                it.romId == romId && it.fileName == fileName
            }
            settingsRepository.setLastSyncConflicts(serializeConflicts(_conflicts.value))
            if (_conflicts.value.isEmpty()) {
                // syncState switches to the "resolved" message from the emptied list; keep the stored summary in step.
                settingsRepository.setLastSync(System.currentTimeMillis(), tr("android.sync.result.conflicts_resolved"))
            }
            scanLocalSaves()
        }
    }

    private fun parseConflictsJson(json: String): List<ConflictInfo> = try {
        conflictMoshiAdapter.fromJson(json).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseFailuresJson(json: String): List<FailedOpInfo> = try {
        failuresMoshiAdapter.fromJson(json).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun serializeConflicts(conflicts: List<ConflictInfo>): String = try {
        conflictMoshiAdapter.toJson(conflicts)
    } catch (_: Exception) {
        "[]"
    }

    fun setRetroArchBasePath(path: String) {
        viewModelScope.launch {
            settingsRepository.setRetroArchBasePath(path)
            scanLocalSaves()
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setSaveSyncIntervalMinutes(minutes)
            saveSyncManager.schedulePeriodicSync(minutes, replace = true)
        }
    }

    fun triggerSync() {
        saveSyncManager.triggerSync()
    }

    fun scanLocalSaves() {
        viewModelScope.launch {
            _isScanning.value = true
            val saves = withContext(Dispatchers.IO) {
                scanSavesFromDisk()
            }
            _localSaves.value = saves
            _isScanning.value = false
            _saveAccess.value = withContext(Dispatchers.IO) { computeSaveAccess() }
        }
    }

    /** Re-checks save folder access (e.g. after a folder grant). */
    fun refreshSaveAccess() {
        viewModelScope.launch {
            _saveAccess.value = withContext(Dispatchers.IO) { computeSaveAccess() }
        }
    }

    private suspend fun computeSaveAccess(): List<SaveAccessItem> {
        val retroArchBase = settingsRepository.retroArchBasePath.first()
        val profile = SavePathResolver.deviceProfile(settingsRepository.settings.first().romsRootPath)
        val platformConfigs = platformDao.getAllPlatformsBlocking().associateBy { it.slug }
        data class Entry(val emulator: String, val slug: String, val path: String)
        val entries = romDao.getAllDownloadedRoms()
            .filterNot { it.excludedFromSync }
            .mapNotNull { rom ->
                val config = platformConfigs[rom.platformSlug]
                if (SavePathResolver.syncSupport(rom.platformSlug, config, profile) !is SyncSupport.Supported) {
                    return@mapNotNull null
                }
                Entry(
                    emulator = SavePathResolver.emulatorFor(rom.platformSlug, config, profile),
                    slug = rom.platformSlug,
                    path = SavePathResolver.romBasePath(rom, config, retroArchBase, profile),
                )
            }
        return entries.groupBy { it.emulator to it.path }
            .map { (key, list) ->
                val (emulator, path) = key
                SaveAccessItem(
                    emulatorName = SaveHandlerRegistry.EmulatorId.entries.find { it.id == emulator }?.displayName
                        ?: emulator,
                    platformSlugs = list.map { it.slug }.distinct().sorted(),
                    path = path,
                    usable = SavePathAccess.isUsable(path, appContext),
                )
            }
            .sortedWith(compareBy<SaveAccessItem>({ it.usable }, { it.emulatorName }))
    }

    private suspend fun scanSavesFromDisk(): List<SavePreviewItem> {
        // OJO: antes aquí había .stateIn(viewModelScope, Eagerly, DEFAULT).value —
        // la colecta es asíncrona, así que la lectura inmediata devolvía SIEMPRE
        // DEFAULT_RETROARCH_PATH: "Comprobar" escaneaba la ruta por defecto
        // mientras runSync leía la real → nunca veía cambios con ruta custom.
        val retroArchBase = settingsRepository.retroArchBasePath.first()

        val downloadedRoms = romDao.getAllDownloadedRoms()
        _scanCheckedCount.value = downloadedRoms.count { !it.excludedFromSync }
        if (downloadedRoms.isEmpty()) return emptyList()

        val platformConfigs = platformDao.getAllPlatformsBlocking().associateBy { it.slug }
        val profile = SavePathResolver.deviceProfile(settingsRepository.settings.first().romsRootPath)
        val results = mutableListOf<SavePreviewItem>()
        val warnings = mutableListOf<String>()

        for (rom in downloadedRoms) {
            if (rom.excludedFromSync) continue

            val config = platformConfigs[rom.platformSlug]
            // Same resolution as SyncCoordinator; unsupported platforms are never synced.
            if (SavePathResolver.syncSupport(rom.platformSlug, config, profile) !is SyncSupport.Supported) continue
            val handler = SavePathResolver.handlerFor(rom.platformSlug, config, profile)

            val effectiveBasePath = SavePathResolver.romBasePath(rom, config, retroArchBase, profile)

            // Ruta configurada explícitamente pero ilegible: el handler no
            // encontrará nada y el sync parecería "al día" sin buscar aquí.
            val explicitOverride = !rom.savesPathOverride.isNullOrBlank() ||
                config?.savesPathOverride?.isNotBlank() == true
            if (explicitOverride && warnings.size < 10) {
                if (!SavePathAccess.isUsable(effectiveBasePath, appContext)) {
                    val restricted = effectiveBasePath.contains("/Android/data") ||
                        effectiveBasePath.contains("/Android/obb") ||
                        effectiveBasePath.contains("/data/data")
                    val hint = if (restricted) {
                        " — " + tr("android.sync.path_warnings.no_root")
                    } else ""
                    warnings.add("${rom.name}: $effectiveBasePath$hint")
                }
            }

            // Ruta restringida (Android/data): staging temporal vía root o SAF
            // para que el handler trabaje con la API File tal cual.
            val staged = SavePathAccess.stage(effectiveBasePath, saveStageRoot, appContext)
            val saves = try {
                handler.findSaves(
                    romId = rom.romId,
                    romFileName = rom.fileName,
                    platformSlug = rom.platformSlug,
                    savesBasePath = staged.dir.path,
                    romLocalPath = rom.localPath,
                )
            } finally {
                SavePathAccess.cleanup(staged, appContext)
            }

            for (save in saves) {
                // Solo mostrar saves con cambios pendientes
                if (syncedHashStore.isAlreadySynced(rom.romId, save.fileName, save.sha1)) continue

                results.add(
                    SavePreviewItem(
                        romName = rom.name,
                        platformSlug = rom.platformSlug,
                        fileName = save.fileName,
                        lastModified = save.lastModified,
                    )
                )
            }
        }

        _pathWarnings.value = warnings
        return results.sortedByDescending { it.lastModified }
    }

    companion object {
        /** Adaptador Moshi para persistir conflictos como JSON. */
        private val conflictMoshiAdapter by lazy {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            moshi.adapter<List<ConflictInfo>>(
                Types.newParameterizedType(List::class.java, ConflictInfo::class.java),
            )
        }

        /** Adaptador Moshi para persistir operaciones fallidas como JSON. */
        private val failuresMoshiAdapter by lazy {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            moshi.adapter<List<FailedOpInfo>>(
                Types.newParameterizedType(List::class.java, FailedOpInfo::class.java),
            )
        }
    }
}
