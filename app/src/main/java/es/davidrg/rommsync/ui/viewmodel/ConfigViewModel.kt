package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.BuildConfig
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.SaveSyncManager
import es.davidrg.rommsync.data.update.ApkInstallHelper
import es.davidrg.rommsync.data.update.AppUpdateChecker
import es.davidrg.rommsync.data.update.InstallLaunchResult
import es.davidrg.rommsync.data.update.UpdateCheckResult
import es.davidrg.rommsync.data.update.UpdateDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import es.davidrg.rommsync.core.auth.DevicePairing
import es.davidrg.rommsync.core.auth.PairingCode
import es.davidrg.rommsync.core.auth.PairingDeniedException
import es.davidrg.rommsync.core.auth.PairingExpiredException
import es.davidrg.rommsync.data.repository.PairedDeviceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import retrofit2.HttpException

class ConfigViewModel(
    private val settingsRepository: SettingsRepository,
    private val romRepository: RomRepository? = null,
    private val saveSyncManager: SaveSyncManager? = null,
    private val updateChecker: AppUpdateChecker? = null,
    private val apkInstallHelper: ApkInstallHelper? = null,
    private val devicePairing: DevicePairing? = null,
) : ViewModel() {

    val settings: StateFlow<ServerConfig> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ServerConfig("", "", "", 2),
        )

    // ── RomM device pairing ────────────────────────────────────────────

    val pairedDevice: StateFlow<PairedDeviceSettings?> = settingsRepository.pairedDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Upstream API key (API-key mode), independent of the paired token. */
    val manualApiKey: StateFlow<String> = settingsRepository.manualApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var pairingJob: Job? = null

    /**
     * Runs the RomM device authorization flow: shows the user code, polls until
     * approved, then stores token + device id (which also switches every API
     * client to the paired token).
     */
    fun startPairing(serverUrl: String, deviceName: String) {
        val pairing = devicePairing ?: return
        val baseUrl = serverUrl.trim().trimEnd('/')
        val name = deviceName.trim()
        if (baseUrl.isEmpty() || name.isEmpty()) return
        pairingJob?.cancel()
        _pairingState.value = PairingState.Starting
        pairingJob = viewModelScope.launch {
            try {
                val identifier = settingsRepository.deviceIdentifier()
                val device = pairing.pair(
                    baseUrl = baseUrl,
                    identifier = identifier,
                    name = name,
                    platform = PAIRING_PLATFORM,
                    clientVersion = BuildConfig.VERSION_NAME,
                ) { code -> _pairingState.value = PairingState.WaitingForApproval(code) }
                settingsRepository.savePairedDevice(
                    PairedDeviceSettings(
                        baseUrl = baseUrl,
                        token = device.token,
                        deviceId = device.deviceId,
                        deviceName = name,
                    ),
                )
                _pairingState.value = PairingState.Paired
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingExpiredException) {
                _pairingState.value = PairingState.Failed(tr("android.pair.error_expired"))
            } catch (e: PairingDeniedException) {
                _pairingState.value = PairingState.Failed(tr("android.pair.error_denied", e.message ?: ""))
            } catch (e: HttpException) {
                _pairingState.value = PairingState.Failed(tr("android.pair.error_http", e.code()))
            } catch (e: Exception) {
                _pairingState.value = PairingState.Failed(
                    tr("android.pair.error_network", e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _pairingState.value = PairingState.Idle
    }

    /** Forgets the pairing locally (delete the device in RomM → Devices to remove it server-side). */
    fun unpair() {
        cancelPairing()
        viewModelScope.launch { settingsRepository.clearPairedDevice() }
    }

    // ── Actualizaciones desde GitHub Releases ──────────────────────────
    // null = aún no se ha comprobado nada en esta sesión.
    private val _updateCheckState = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckState: StateFlow<UpdateCheckResult?> = _updateCheckState.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    /** Mensaje de feedback del último intento de lanzar el instalador. */
    private val _installFeedback = MutableStateFlow<String?>(null)
    val installFeedback: StateFlow<String?> = _installFeedback.asStateFlow()

    val updateDownloadState: StateFlow<UpdateDownloadState> =
        apkInstallHelper?.downloadState ?: MutableStateFlow(UpdateDownloadState.Idle)

    /** Version currently installed on this device. */
    val currentVersion: String = BuildConfig.VERSION_NAME

    // ── Restricciones del auto-sync + política de conflictos + skip ────

    val syncWifiOnly: StateFlow<Boolean> = settingsRepository.syncWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val syncChargingOnly: StateFlow<Boolean> = settingsRepository.syncChargingOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val conflictPolicy: StateFlow<String> = settingsRepository.conflictPolicy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ask")
    val skippedVersion: StateFlow<String> = settingsRepository.skippedVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** Cambia una restricción y reprograma el trabajo periódico si toca. */
    fun setSyncConstraints(wifiOnly: Boolean? = null, chargingOnly: Boolean? = null) {
        viewModelScope.launch {
            wifiOnly?.let { settingsRepository.setSyncWifiOnly(it) }
            chargingOnly?.let { settingsRepository.setSyncChargingOnly(it) }
            val interval = settingsRepository.saveSyncIntervalMinutes.first()
            if (interval > 0) {
                saveSyncManager?.schedulePeriodicSync(
                    interval,
                    replace = true,
                    wifiOnly = wifiOnly ?: settingsRepository.syncWifiOnly.first(),
                    chargingOnly = chargingOnly ?: settingsRepository.syncChargingOnly.first(),
                )
            }
        }
    }

    fun setConflictPolicy(policyId: String) {
        viewModelScope.launch { settingsRepository.setConflictPolicy(policyId) }
    }

    /** Omite la versión disponible (deja de ofrecerse hasta la siguiente). */
    fun skipCurrentVersion() {
        val r = _updateCheckState.value as? UpdateCheckResult.UpdateAvailable ?: return
        viewModelScope.launch {
            settingsRepository.setSkippedVersion(r.latestVersion)
            _updateCheckState.value = UpdateCheckResult.UpToDate
        }
    }

    /** Limpia la versión omitida y vuelve a comprobar. */
    fun recheckSkippedVersion() {
        viewModelScope.launch {
            settingsRepository.setSkippedVersion("")
            checkForUpdates()
        }
    }

    /** Queries GitHub for the latest release and updates [updateCheckState]. */
    fun checkForUpdates() {
        val checker = updateChecker ?: return
        if (_isCheckingUpdate.value) return
        _isCheckingUpdate.value = true
        viewModelScope.launch {
            _updateCheckState.value = checker.check()
            _isCheckingUpdate.value = false
        }
    }

    /** Starts the APK download for the latest known update, if any. */
    fun downloadUpdate() {
        val helper = apkInstallHelper ?: return
        val update = _updateCheckState.value as? UpdateCheckResult.UpdateAvailable ?: return
        viewModelScope.launch { helper.download(update) }
    }

    /** Opens the system installer with the downloaded APK. */
    fun installUpdate() {
        val helper = apkInstallHelper ?: return
        val ready = helper.downloadState.value as? UpdateDownloadState.ReadyToInstall ?: return
        when (val result = helper.launchInstaller(ready.apkFile)) {
            is InstallLaunchResult.Started -> _installFeedback.value = null
            is InstallLaunchResult.NeedsPermission -> _installFeedback.value =
                tr("android.config.install_permission_hint")
            is InstallLaunchResult.Error -> _installFeedback.value = result.message
        }
    }

    /** Discards a downloaded update APK and resets the section. */
    fun cancelUpdate() {
        val helper = apkInstallHelper ?: return
        helper.cleanup()
    }

    // ── Escaneo de biblioteca ──────────────────────────────────────────
    private val _scanState = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

    /**
     * Recorre la biblioteca en disco y marca como descargados los juegos cuyo
     * fichero exista en la ruta esperada.
     */
    fun scanLibrary(serverUrl: String, apiKey: String) {
        val repo = romRepository ?: return
        if (_scanState.value is LibraryScanState.Scanning) return
        viewModelScope.launch {
            _scanState.value = LibraryScanState.Scanning(null)
            repo.configureApi(serverUrl, apiKey)
            val config = settingsRepository.settings.first()
            val romsRootPaths = buildList {
                add(config.romsRootPath)
                if (config.dualRomsPathsEnabled) add(config.secondaryRomsPath)
            }
            val result = repo.scanDownloadedLibrary(
                romsRootPaths = romsRootPaths,
                onProgress = { platformName ->
                    _scanState.value = LibraryScanState.Scanning(platformName)
                },
            )
            _scanState.value = if (result.isSuccess) {
                LibraryScanState.Done(detected = result.detected, scanned = result.scanned)
            } else {
                LibraryScanState.Error(result.error ?: tr("android.config.scan_error"))
            }
        }
    }

    fun dismissScanResult() {
        _scanState.value = LibraryScanState.Idle
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun setRomsRootPath(path: String) {
        viewModelScope.launch { settingsRepository.setRomsRootPath(path) }
    }

    fun setSecondaryRomsPath(path: String) {
        viewModelScope.launch { settingsRepository.setSecondaryRomsPath(path) }
    }

    fun setDualRomsPathsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDualRomsPathsEnabled(enabled) }
    }

    fun setMaxConcurrentDownloads(max: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(max) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnlyDownloads(enabled) }
    }

    fun setRetroArchBasePath(path: String) {
        viewModelScope.launch { settingsRepository.setRetroArchBasePath(path) }
    }

    /**
     * Activa/desactiva la función de sincronización de saves. Al desactivar
     * cancela cualquier trabajo de sync encolado; al reactivar restaura el
     * periodo configurado si existía.
     */
    fun setSaveSyncEnabled(enabled: Boolean) {
        val manager = saveSyncManager ?: return
        viewModelScope.launch {
            settingsRepository.setSaveSyncEnabled(enabled)
            if (enabled) {
                val interval = settingsRepository.saveSyncIntervalMinutes.first()
                if (interval > 0) {
                    manager.schedulePeriodicSync(interval)
                }
            } else {
                manager.cancelAllSyncWork()
            }
        }
    }

    fun setEsdeDataPath(path: String) {
        viewModelScope.launch { settingsRepository.setEsdeDataDir(path) }
    }

    fun setRetroHraiMediaPath(path: String) {
        viewModelScope.launch { settingsRepository.setRetroHraiMediaPath(path) }
    }

    /** Muestra/oculta la exportación de metadatos a ES-DE. */
    fun setEsdeExportEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEsdeExportEnabled(enabled) }
    }

    /** Muestra/oculta la exportación de metadatos a RetroHRAI. */
    fun setRetroHraiExportEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRetroHraiExportEnabled(enabled) }
    }

    private companion object {
        /** `platform` reported to RomM when pairing. */
        const val PAIRING_PLATFORM = "android"
    }
}

/** Progress of the RomM device authorization flow. */
sealed class PairingState {
    data object Idle : PairingState()
    data object Starting : PairingState()
    data class WaitingForApproval(val code: PairingCode) : PairingState()
    data object Paired : PairingState()
    data class Failed(val message: String) : PairingState()
}

/**
 * Estado del escaneo de biblioteca local.
 */
sealed class LibraryScanState {
    data object Idle : LibraryScanState()
    data class Scanning(val platformName: String?) : LibraryScanState()
    data class Done(val detected: Int, val scanned: Int) : LibraryScanState()
    data class Error(val message: String) : LibraryScanState()
}
