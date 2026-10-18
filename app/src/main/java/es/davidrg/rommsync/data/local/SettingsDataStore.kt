package es.davidrg.rommsync.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import es.davidrg.rommsync.data.repository.PairedDeviceSettings
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "romsync_settings")

/**
 * Persistent app configuration.
 *
 * Server URL, root ROMs directory and max concurrent downloads are stored via
 * Jetpack DataStore (Preferences). The **API key** is stored separately using
 * [EncryptedSharedPreferences] (AES256-GCM) so that it is never persisted in
 * plaintext and is never passed through WorkManager `Data`.
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        // ── DataStore keys (non-sensitive settings) ────────────────────
        val SERVER_URL = stringPreferencesKey("server_url")
        val ROMS_ROOT_PATH = stringPreferencesKey("roms_root_path")
        val SECONDARY_ROMS_PATH = stringPreferencesKey("secondary_roms_path")
        val DUAL_ROMS_PATHS_ENABLED = booleanPreferencesKey("dual_roms_paths_enabled")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val RETROARCH_BASE_PATH = stringPreferencesKey("retroarch_base_path")
        val DEVICE_ID = intPreferencesKey("sync_device_id")
        val DEVICE_ID_STRING = stringPreferencesKey("sync_device_id_string")
        val SAVE_SYNC_ENABLED = stringPreferencesKey("save_sync_enabled")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_SYNC_SUMMARY = stringPreferencesKey("last_sync_summary")
        val LAST_SYNC_CONFLICTS_JSON = stringPreferencesKey("last_sync_conflicts_json")
        val LAST_SYNC_FAILED_JSON = stringPreferencesKey("last_sync_failed_json")
        val SAVE_SYNC_INTERVAL_MINUTES = intPreferencesKey("save_sync_interval_minutes")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val SYNC_CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val CONFLICT_POLICY = stringPreferencesKey("conflict_policy")
        val ESDE_DATA_DIR = stringPreferencesKey("esde_data_dir")
        /** ES-DE / Cocoon metadata folder (gamelists + downloaded_media); unset = derived default. */
        val ESDE_METADATA_DIR = stringPreferencesKey("esde_metadata_dir")
        /** Export RomM media + gamelist entry after each push install (absent = on). */
        val ESDE_PUSH_MEDIA_ENABLED = booleanPreferencesKey("esde_push_media_enabled")
        val RETROHRAI_MEDIA_PATH = stringPreferencesKey("retrohrai_media_path")

        // Toggles de funciones opcionales (opt-out: ausencia = activado)
        val ESDE_EXPORT_ENABLED = booleanPreferencesKey("esde_export_enabled")
        val RETROHRAI_EXPORT_ENABLED = booleanPreferencesKey("retrohrai_export_enabled")

        /**
         * Legacy DataStore key where the API key used to live (plaintext).
         * Kept only to support one-time migration; the value is deleted after
         * being copied into EncryptedSharedPreferences.
         */
        private val LEGACY_API_KEY = stringPreferencesKey("api_key")

        // ── RomM device pairing (fork) ─────────────────────────────────
        /** UUID generated once per install; RomM keys the Device row on it. */
        val DEVICE_IDENTIFIER = stringPreferencesKey("device_identifier")
        val PAIRED_DEVICE_ID = stringPreferencesKey("paired_device_id")
        val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")
        val PAIRED_BASE_URL = stringPreferencesKey("paired_base_url")

        // ── EncryptedSharedPreferences key ─────────────────────────────
        private const val SECURE_PREFS_FILE = "romsync_secure_prefs"
        private const val SECURE_KEY_API_KEY = "api_key"
        private const val SECURE_KEY_PAIRED_TOKEN = "paired_token"

        const val DEFAULT_ROMS_PATH = "/storage/emulated/0/ROMs"
        const val DEFAULT_SECONDARY_ROMS_PATH = "/storage/emulated/1/ROMs"
        const val DEFAULT_MAX_DOWNLOADS = 2
        const val DEFAULT_RETROARCH_PATH = "/storage/emulated/0/RetroArch"
        const val DEFAULT_ESDE_DATA_DIR = "/storage/emulated/0/ES-DE"
        const val DEFAULT_RETROHRAI_MEDIA_PATH = "/storage/emulated/0/RetroHrai/media"

        /**
         * Default metadata folder: `<roms root parent>/ES-DE` when the ROMs root is
         * on removable storage (Thor: `/storage/EBD7-FF2C/ES-DE`, next to
         * `/storage/EBD7-FF2C/ROMs`), else ES-DE's data dir [esdeDataDir].
         */
        fun defaultEsdeMetadataDir(romsRoot: String, esdeDataDir: String): String {
            val root = java.io.File(romsRoot)
            val removable = runCatching { android.os.Environment.isExternalStorageRemovable(root) }
                .getOrElse { romsRoot.startsWith("/storage/") && !romsRoot.startsWith("/storage/emulated/") }
            val parent = root.absoluteFile.parentFile
            return if (removable && parent != null) java.io.File(parent, "ES-DE").path else esdeDataDir
        }
    }

    /**
     * Scope for the one-time legacy migration launched from [init].
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Encrypted storage ─────────────────────────────────────────────

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Hot, reactive holder for the API key. Seeded synchronously from the
     * encrypted store on construction and updated whenever [setApiKey] runs.
     */
    private val _apiKeyFlow = MutableStateFlow(securePrefs.getString(SECURE_KEY_API_KEY, "") ?: "")

    /** Device-bound `rmm_…` token from pairing ("" = not paired). */
    private val _pairedTokenFlow = MutableStateFlow(securePrefs.getString(SECURE_KEY_PAIRED_TOKEN, "") ?: "")

    init {
        // One-time migration: if a previous app version stored the API key in
        // the (plaintext) DataStore, move it to EncryptedSharedPreferences and
        // remove it from DataStore.
        migrationScope.launch { migrateApiKeyIfNeeded() }
    }

    private suspend fun migrateApiKeyIfNeeded() {
        val legacyKey = context.dataStore.data.first()[LEGACY_API_KEY]
        if (legacyKey.isNullOrEmpty()) return

        // Only copy if the encrypted store doesn't already have one.
        if (securePrefs.getString(SECURE_KEY_API_KEY, "").isNullOrEmpty()) {
            securePrefs.edit().putString(SECURE_KEY_API_KEY, legacyKey).apply()
            _apiKeyFlow.value = legacyKey
        }
        // Always wipe the plaintext copy.
        context.dataStore.edit { it.remove(LEGACY_API_KEY) }
    }

    // ── Reactive flows (Compose / ViewModel) ──────────────────────────

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    /** Upstream API key typed by the user (API-key mode). */
    val manualApiKey: Flow<String> = _apiKeyFlow

    /** Current pairing, or null in API-key mode. */
    val pairedDevice: Flow<PairedDeviceSettings?> = pairedDeviceFlow()

    /**
     * Credential the API clients send as Bearer: the paired device token when
     * paired, otherwise the manual API key.
     */
    val apiKey: Flow<String> = combine(_apiKeyFlow, pairedDevice) { key, paired -> paired?.token ?: key }
    val romsRootPath: Flow<String> = context.dataStore.data.map { it[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
    val secondaryRomsPath: Flow<String> = context.dataStore.data.map {
        it[SECONDARY_ROMS_PATH] ?: DEFAULT_SECONDARY_ROMS_PATH
    }
    val dualRomsPathsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[DUAL_ROMS_PATHS_ENABLED] ?: false
    }
    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map {
        it[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS
    }
    val retroArchBasePath: Flow<String> = context.dataStore.data.map {
        it[RETROARCH_BASE_PATH] ?: DEFAULT_RETROARCH_PATH
    }
    val syncDeviceId: Flow<Int?> = context.dataStore.data.map { it[DEVICE_ID] }
    val syncDeviceIdString: Flow<String?> = context.dataStore.data.map { it[DEVICE_ID_STRING] }
    val saveSyncEnabled: Flow<Boolean> = context.dataStore.data.map {
        // Ausencia de valor = activado (opt-out): nadie ha escrito esta clave
        // antes de que existiera el toggle, así que el comportamiento por
        // defecto conserva la función visible.
        (it[SAVE_SYNC_ENABLED] ?: "true") == "true"
    }
    val saveSyncIntervalMinutes: Flow<Int> = context.dataStore.data.map {
        it[SAVE_SYNC_INTERVAL_MINUTES] ?: 0
    }
    val lastSyncTimestamp: Flow<Long> = context.dataStore.data.map {
        it[LAST_SYNC_TIMESTAMP] ?: 0L
    }
    val lastSyncSummary: Flow<String> = context.dataStore.data.map {
        it[LAST_SYNC_SUMMARY] ?: ""
    }
    val lastSyncConflictsJson: Flow<String> = context.dataStore.data.map {
        it[LAST_SYNC_CONFLICTS_JSON] ?: "[]"
    }
    val lastSyncFailedJson: Flow<String> = context.dataStore.data.map {
        it[LAST_SYNC_FAILED_JSON] ?: "[]"
    }
    val esdeDataDir: Flow<String> = context.dataStore.data.map {
        it[ESDE_DATA_DIR] ?: DEFAULT_ESDE_DATA_DIR
    }
    /** Effective ES-DE / Cocoon metadata folder (explicit setting, else [defaultEsdeMetadataDir]). */
    val esdeMetadataDir: Flow<String> = context.dataStore.data.map { resolveEsdeMetadataDir(it) }
    val esdePushMediaEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[ESDE_PUSH_MEDIA_ENABLED] ?: true
    }

    private fun resolveEsdeMetadataDir(prefs: Preferences): String =
        prefs[ESDE_METADATA_DIR]?.takeIf { it.isNotBlank() }
            ?: defaultEsdeMetadataDir(
                prefs[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH,
                prefs[ESDE_DATA_DIR] ?: DEFAULT_ESDE_DATA_DIR,
            )
    val retroHraiMediaPath: Flow<String> = context.dataStore.data.map {
        it[RETROHRAI_MEDIA_PATH] ?: DEFAULT_RETROHRAI_MEDIA_PATH
    }
    val esdeExportEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[ESDE_EXPORT_ENABLED] ?: true
    }
    val retroHraiExportEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[RETROHRAI_EXPORT_ENABLED] ?: true
    }

    /** Combined settings snapshot */
    val settings: Flow<ServerConfig> = combine(
        context.dataStore.data,
        _apiKeyFlow,
        _pairedTokenFlow,
    ) { prefs, manualKey, token ->
        val paired = pairedFrom(prefs, token)
        val apiKey = paired?.token ?: manualKey
        ServerConfig(
            serverUrl = prefs[SERVER_URL] ?: "",
            apiKey = apiKey,
            romsRootPath = prefs[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH,
            maxConcurrentDownloads = prefs[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS,
            wifiOnlyDownloads = prefs[WIFI_ONLY_DOWNLOADS] ?: false,
            dualRomsPathsEnabled = prefs[DUAL_ROMS_PATHS_ENABLED] ?: false,
            secondaryRomsPath = prefs[SECONDARY_ROMS_PATH] ?: DEFAULT_SECONDARY_ROMS_PATH,
            pairedDeviceName = paired?.deviceName,
        )
    }

    // ── Writers ───────────────────────────────────────────────────────

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url.trimEnd('/') }
    }

    /**
     * Stores the API key in EncryptedSharedPreferences.
     * Also updates the reactive [_apiKeyFlow] so UI consumers refresh.
     */
    suspend fun setApiKey(key: String) {
        securePrefs.edit().putString(SECURE_KEY_API_KEY, key).apply()
        _apiKeyFlow.value = key
    }

    // ── Pairing ──────────────────────────────────────────────────────

    private fun pairedFrom(prefs: Preferences, token: String): PairedDeviceSettings? {
        val deviceId = prefs[PAIRED_DEVICE_ID]
        val baseUrl = prefs[PAIRED_BASE_URL]
        if (token.isEmpty() || deviceId.isNullOrEmpty() || baseUrl.isNullOrEmpty()) return null
        return PairedDeviceSettings(
            baseUrl = baseUrl,
            token = token,
            deviceId = deviceId,
            deviceName = prefs[PAIRED_DEVICE_NAME].orEmpty(),
        )
    }

    private fun pairedDeviceFlow(): Flow<PairedDeviceSettings?> =
        combine(context.dataStore.data, _pairedTokenFlow) { prefs, token -> pairedFrom(prefs, token) }

    /** Reads the token from the encrypted store, not the flow: other instances (workers) may have paired. */
    suspend fun getPairedDevice(): PairedDeviceSettings? =
        pairedFrom(context.dataStore.data.first(), securePrefs.getString(SECURE_KEY_PAIRED_TOKEN, "") ?: "")

    fun getPairedDeviceBlocking(): PairedDeviceSettings? =
        runCatching { runBlocking { getPairedDevice() } }.getOrNull()

    /**
     * Stores a successful pairing. The pairing URL also becomes the server URL
     * so every API client talks to the server that issued the token.
     */
    suspend fun savePairedDevice(device: PairedDeviceSettings) {
        securePrefs.edit().putString(SECURE_KEY_PAIRED_TOKEN, device.token).apply()
        context.dataStore.edit {
            it[PAIRED_DEVICE_ID] = device.deviceId
            it[PAIRED_DEVICE_NAME] = device.deviceName
            it[PAIRED_BASE_URL] = device.baseUrl.trimEnd('/')
            it[SERVER_URL] = device.baseUrl.trimEnd('/')
        }
        _pairedTokenFlow.value = device.token
    }

    /** Forgets the pairing locally; the device identifier is kept so re-pairing updates the same Device. */
    suspend fun clearPairedDevice() {
        securePrefs.edit().remove(SECURE_KEY_PAIRED_TOKEN).apply()
        _pairedTokenFlow.value = ""
        context.dataStore.edit {
            it.remove(PAIRED_DEVICE_ID)
            it.remove(PAIRED_DEVICE_NAME)
            it.remove(PAIRED_BASE_URL)
        }
    }

    /** Per-install UUID sent as `client_device_identifier`; generated on first use. */
    suspend fun deviceIdentifier(): String {
        context.dataStore.data.first()[DEVICE_IDENTIFIER]?.let { return it }
        var identifier = ""
        context.dataStore.edit { prefs ->
            identifier = prefs[DEVICE_IDENTIFIER] ?: java.util.UUID.randomUUID().toString().also {
                prefs[DEVICE_IDENTIFIER] = it
            }
        }
        return identifier
    }

    fun getWifiOnlyDownloadsBlocking(): Boolean =
        runCatching { runBlocking { context.dataStore.data.first()[WIFI_ONLY_DOWNLOADS] ?: false } }
            .getOrDefault(false)

    suspend fun setRomsRootPath(path: String) {
        context.dataStore.edit { it[ROMS_ROOT_PATH] = path }
    }

    suspend fun setSecondaryRomsPath(path: String) {
        context.dataStore.edit { it[SECONDARY_ROMS_PATH] = path.trimEnd('/') }
    }

    suspend fun setDualRomsPathsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DUAL_ROMS_PATHS_ENABLED] = enabled }
    }

    suspend fun setMaxConcurrentDownloads(max: Int) {
        context.dataStore.edit { it[MAX_CONCURRENT_DOWNLOADS] = max.coerceIn(1, 5) }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.dataStore.edit { it[WIFI_ONLY_DOWNLOADS] = enabled }
    }

    /** Auto-sync de saves solo con redes sin límite de datos (WiFi). */
    suspend fun setSyncWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[SYNC_WIFI_ONLY] = enabled }
    }

    /** Auto-sync de saves solo con el dispositivo cargando. */
    suspend fun setSyncChargingOnly(enabled: Boolean) {
        context.dataStore.edit { it[SYNC_CHARGING_ONLY] = enabled }
    }

    /** Versión de actualización omitida por el usuario ("" = ninguna). */
    suspend fun setSkippedVersion(version: String) {
        context.dataStore.edit { it[SKIPPED_VERSION] = version }
    }

    /** Política de conflictos de saves (id de ConflictPolicy del core). */
    suspend fun setConflictPolicy(policyId: String) {
        context.dataStore.edit { it[CONFLICT_POLICY] = policyId }
    }

    fun getSyncWifiOnlyBlocking(): Boolean = runBlocking {
        context.dataStore.data.first()[SYNC_WIFI_ONLY] ?: false
    }

    fun getSyncChargingOnlyBlocking(): Boolean = runBlocking {
        context.dataStore.data.first()[SYNC_CHARGING_ONLY] ?: false
    }

    fun getSkippedVersionBlocking(): String = runBlocking {
        context.dataStore.data.first()[SKIPPED_VERSION] ?: ""
    }

    fun getConflictPolicyBlocking(): String = runBlocking {
        context.dataStore.data.first()[CONFLICT_POLICY] ?: "ask"
    }

    // ── Flows reactivos de los nuevos ajustes ─────────────────────────

    val syncWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[SYNC_WIFI_ONLY] ?: false }
    val syncChargingOnly: Flow<Boolean> = context.dataStore.data.map { it[SYNC_CHARGING_ONLY] ?: false }
    val skippedVersion: Flow<String> = context.dataStore.data.map { it[SKIPPED_VERSION] ?: "" }
    val conflictPolicy: Flow<String> = context.dataStore.data.map { it[CONFLICT_POLICY] ?: "ask" }

    suspend fun setRetroArchBasePath(path: String) {
        context.dataStore.edit { it[RETROARCH_BASE_PATH] = path.trimEnd('/') }
    }

    suspend fun setSyncDeviceId(id: Int) {
        context.dataStore.edit { it[DEVICE_ID] = id }
    }

    suspend fun setSyncDeviceIdString(id: String) {
        context.dataStore.edit { it[DEVICE_ID_STRING] = id }
    }

    suspend fun setLastSync(timestamp: Long, summary: String) {
        context.dataStore.edit {
            it[LAST_SYNC_TIMESTAMP] = timestamp
            it[LAST_SYNC_SUMMARY] = summary
        }
    }

    /**
     * Persiste los conflictos pendientes como JSON para que la UI de
     * resolución sobreviva a reinicios de app/proceso.
     */
    suspend fun setLastSyncConflictsJson(json: String) {
        context.dataStore.edit { it[LAST_SYNC_CONFLICTS_JSON] = json }
    }

    suspend fun setLastSyncFailedJson(json: String) {
        context.dataStore.edit { it[LAST_SYNC_FAILED_JSON] = json }
    }

    fun getLastSyncConflictsJsonBlocking(): String =
        runBlocking {
            context.dataStore.data.first()[LAST_SYNC_CONFLICTS_JSON] ?: "[]"
        }

    suspend fun setSaveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SAVE_SYNC_ENABLED] = if (enabled) "true" else "false" }
    }

    suspend fun setSaveSyncIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[SAVE_SYNC_INTERVAL_MINUTES] = minutes }
    }

    suspend fun setEsdeDataDir(path: String) {
        context.dataStore.edit { it[ESDE_DATA_DIR] = path.trimEnd('/') }
    }

    /** Blank reverts to the derived default ([defaultEsdeMetadataDir]). */
    suspend fun setEsdeMetadataDir(path: String) {
        val value = path.trim().trimEnd('/')
        context.dataStore.edit { if (value.isEmpty()) it.remove(ESDE_METADATA_DIR) else it[ESDE_METADATA_DIR] = value }
    }

    suspend fun setEsdePushMediaEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ESDE_PUSH_MEDIA_ENABLED] = enabled }
    }

    suspend fun setEsdeExportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ESDE_EXPORT_ENABLED] = enabled }
    }

    suspend fun setRetroHraiExportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RETROHRAI_EXPORT_ENABLED] = enabled }
    }

    suspend fun setRetroHraiMediaPath(path: String) {
        context.dataStore.edit { it[RETROHRAI_MEDIA_PATH] = path.trimEnd('/') }
    }

    // ── Blocking readers (for WorkManager / non-coroutine callers) ────

    /**
     * Synchronous read of the ROMs root path for use in WorkManager.
     */
    fun getRomsRootPathBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
        }.getOrDefault(DEFAULT_ROMS_PATH)
    }

    /**
     * Synchronous read of the secondary ROMs path (SD card) for WorkManager.
     */
    fun getSecondaryRomsPathBlocking(): String {
        return runCatching {
            runBlocking {
                context.dataStore.data.first()[SECONDARY_ROMS_PATH] ?: DEFAULT_SECONDARY_ROMS_PATH
            }
        }.getOrDefault(DEFAULT_SECONDARY_ROMS_PATH)
    }

    /**
     * Synchronous read of the dual ROMs paths toggle. When true, downloads
     * ask the user which of the two configured roots to use.
     */
    fun getDualRomsPathsEnabledBlocking(): Boolean {
        return runCatching {
            runBlocking { context.dataStore.data.first()[DUAL_ROMS_PATHS_ENABLED] ?: false }
        }.getOrDefault(false)
    }

    /**
     * Synchronous read of the Bearer credential for WorkManager: the paired
     * device token when paired, otherwise the API key from
     * EncryptedSharedPreferences.
     */
    fun getApiKeyBlocking(): String {
        getPairedDeviceBlocking()?.let { return it.token }
        return runCatching {
            securePrefs.getString(SECURE_KEY_API_KEY, "") ?: ""
        }.getOrDefault("")
    }

    /**
     * Synchronous read of the server URL for use in WorkManager.
     */
    fun getServerUrlBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[SERVER_URL] ?: "" }
        }.getOrDefault("")
    }

    /**
     * Synchronous read of the max concurrent downloads setting for use in
     * WorkManager (the [DownloadWorker] reads this to size its concurrency
     * semaphore before each run).
     *
     * The value is always coerced to the valid 1-5 range.
     */
    fun getMaxConcurrentDownloadsBlocking(): Int {
        return runCatching {
            runBlocking {
                context.dataStore.data.first()[MAX_CONCURRENT_DOWNLOADS]
                    ?: DEFAULT_MAX_DOWNLOADS
            }
        }.getOrDefault(DEFAULT_MAX_DOWNLOADS).coerceIn(1, 5)
    }

    fun getRetroArchBasePathBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[RETROARCH_BASE_PATH] ?: DEFAULT_RETROARCH_PATH }
        }.getOrDefault(DEFAULT_RETROARCH_PATH)
    }

    fun getSyncIntervalMinutesBlocking(): Int {
        return runCatching {
            runBlocking { context.dataStore.data.first()[SAVE_SYNC_INTERVAL_MINUTES] ?: 0 }
        }.getOrDefault(0)
    }

    /** Lectura síncrona del toggle de sync de saves (para Workers y Application). */
    fun getSaveSyncEnabledBlocking(): Boolean {
        return runCatching {
            runBlocking { (context.dataStore.data.first()[SAVE_SYNC_ENABLED] ?: "true") == "true" }
        }.getOrDefault(true)
    }

    fun getSyncDeviceIdBlocking(): Int? {
        return runCatching {
            runBlocking { context.dataStore.data.first()[DEVICE_ID] }
        }.getOrNull()
    }

    fun getSyncDeviceIdStringBlocking(): String? {
        return runCatching {
            runBlocking { context.dataStore.data.first()[DEVICE_ID_STRING] }
        }.getOrNull()
    }

    /** Effective ES-DE / Cocoon metadata folder, for workers and the install service. */
    fun getEsdeMetadataDirBlocking(): String {
        return runCatching {
            runBlocking { resolveEsdeMetadataDir(context.dataStore.data.first()) }
        }.getOrDefault(DEFAULT_ESDE_DATA_DIR)
    }

    fun getEsdePushMediaEnabledBlocking(): Boolean {
        return runCatching {
            runBlocking { context.dataStore.data.first()[ESDE_PUSH_MEDIA_ENABLED] ?: true }
        }.getOrDefault(true)
    }

    fun getRetroHraiMediaPathBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[RETROHRAI_MEDIA_PATH] ?: DEFAULT_RETROHRAI_MEDIA_PATH }
        }.getOrDefault(DEFAULT_RETROHRAI_MEDIA_PATH)
    }
}

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val romsRootPath: String,
    val maxConcurrentDownloads: Int,
    /** Descargar ROMs solo por WiFi (saves se sincronizan por cualquier red). */
    val wifiOnlyDownloads: Boolean = false,
    /** Modo dos rutas de ROMs (memoria interna + tarjeta SD). */
    val dualRomsPathsEnabled: Boolean = false,
    /** Segunda ruta de ROMs (normalmente la tarjeta SD). Solo usa si dualRomsPathsEnabled. */
    val secondaryRomsPath: String = "",
    /** Name of the paired RomM device; null in API-key mode. */
    val pairedDeviceName: String? = null,
) {
    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && apiKey.isNotEmpty()
}
