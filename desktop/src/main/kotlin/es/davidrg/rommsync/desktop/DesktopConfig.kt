package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.metadata.EsdeTarget
import es.davidrg.rommsync.core.sync.platform.SavePaths
import es.davidrg.rommsync.core.sync.platform.SaveProfile
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

/** Configuración persistente de la app desktop (java.util.prefs, sin Android). */
object DesktopConfig {
    private val prefs = Preferences.userNodeForPackage(DesktopConfig::class.java)

    var serverUrl: String
        get() = prefs.get("server_url", "")
        set(v) = prefs.put("server_url", v)

    var apiKey: String
        get() = prefs.get("api_key", "")
        set(v) = prefs.put("api_key", v)

    var romsRoot: String
        get() = prefs.get("roms_root", defaultRomsRoot())
        set(v) = prefs.put("roms_root", v)

    /** Device id registered through `POST /api/devices` (API-key mode only). */
    var deviceId: String?
        get() = prefs.get("device_id", null)
        set(v) = if (v == null) prefs.remove("device_id") else prefs.put("device_id", v)

    // ── Pairing (RomM device authorization) ─────────────────────────────

    /**
     * Stable per-install identifier sent as `client_device_identifier`; generated
     * once. Re-pairing with it updates the same Device row in RomM.
     */
    val deviceIdentifier: String
        @Synchronized get() = prefs.get(KEY_IDENTIFIER, null) ?: UUID.randomUUID().toString().also {
            prefs.put(KEY_IDENTIFIER, it)
            flush()
        }

    /** Device-bound `rmm_…` token obtained by pairing ("" = not paired). */
    val pairedToken: String get() = prefs.get(KEY_PAIRED_TOKEN, "")

    /** RomM device id the paired token is bound to ("" = not paired). */
    val pairedDeviceId: String get() = prefs.get(KEY_PAIRED_DEVICE_ID, "")

    /** Name the device was paired under (shown in settings). */
    val pairedDeviceName: String get() = prefs.get(KEY_PAIRED_NAME, "")

    /** Server the device was paired with. */
    val pairedBaseUrl: String get() = prefs.get(KEY_PAIRED_URL, "")

    val isPaired: Boolean get() = pairedToken.isNotBlank() && pairedDeviceId.isNotBlank() && pairedBaseUrl.isNotBlank()

    /** Server every API call goes to: the paired server when paired, else the API-key server. */
    val effectiveServerUrl: String get() = if (isPaired) pairedBaseUrl else serverUrl

    /** Bearer credential: the paired token when paired, else the API key. */
    val authToken: String get() = if (isPaired) pairedToken else apiKey

    fun savePairing(baseUrl: String, name: String, token: String, deviceId: String) {
        prefs.put(KEY_PAIRED_URL, baseUrl)
        prefs.put(KEY_PAIRED_NAME, name)
        prefs.put(KEY_PAIRED_TOKEN, token)
        prefs.put(KEY_PAIRED_DEVICE_ID, deviceId)
        prefs.put("server_url", baseUrl)
        flush()
    }

    /** Forgets the paired token locally (the device stays in RomM until deleted there). */
    fun clearPairing() {
        prefs.remove(KEY_PAIRED_TOKEN)
        prefs.remove(KEY_PAIRED_DEVICE_ID)
        prefs.remove(KEY_PAIRED_NAME)
        prefs.remove(KEY_PAIRED_URL)
        flush()
    }

    /** Writes pending preference changes to disk (the daemon exits right after pairing). */
    fun flush() {
        runCatching { prefs.flush() }
    }

    /** Re-reads preferences written by another process (GUI ↔ daemon). */
    fun sync() {
        runCatching { prefs.sync() }
    }

    val configDir: File
        get() = File(System.getProperty("user.home"), ".config/romm-sync").also { it.mkdirs() }

    val cacheDir: File
        get() = File(System.getProperty("user.home"), ".cache/romm-sync").also { it.mkdirs() }

    /** ES-DE's `ROMDirectory` when set, else `$HOME/Emulation/roms` (EmuDeck). */
    private fun defaultRomsRoot(): String {
        val home = System.getProperty("user.home", ".")
        return esdeRomDirectory(File(home, "ES-DE/settings/es_settings.xml"), home)
            ?: File(home, "Emulation/roms").absolutePath
    }

    /**
     * EmuDeck layout profile when `<romsRoot>/../saves` exists (the Steam Deck's
     * `Emulation/saves`), else null (upstream per-emulator defaults).
     */
    fun saveProfile(): SaveProfile? {
        val saves = File(romsRoot).absoluteFile.parentFile?.let { File(it, "saves") } ?: return null
        return if (saves.isDirectory) SavePaths.emudeck(saves.path) else null
    }

    /** Slugs de plataformas ocultas por el usuario (CSV). */
    var hiddenPlatforms: String
        get() = prefs.get("hidden_platforms", "")
        set(v) = prefs.put("hidden_platforms", v)

    /** Directorio de datos de ES-DE (donde vive gamelists/). */
    var esdeDataDir: String
        get() = prefs.get("esde_data_dir", File(System.getProperty("user.home", "."), "ES-DE").absolutePath)
        set(v) = prefs.put("esde_data_dir", v)

    /** Export RomM media + gamelist entries into ES-DE after each push install (daemon). */
    var esdeMediaOnInstall: Boolean
        get() = prefs.getBoolean(KEY_ESDE_MEDIA_ON_INSTALL, true)
        set(v) = prefs.putBoolean(KEY_ESDE_MEDIA_ON_INSTALL, v)

    /**
     * ES-DE target for media exports: [esdeDataDir], media into `MediaDirectory`
     * from its `settings/es_settings.xml` (default `<ES-DE dir>/downloaded_media`
     * when unset). Null when [requireExisting] and the ES-DE folder is absent.
     */
    fun esdeTarget(requireExisting: Boolean = true): EsdeTarget? {
        val esdeDir = File(esdeDataDir)
        if (requireExisting && !esdeDir.isDirectory) return null
        val home = System.getProperty("user.home", ".")
        val media = esdeMediaDirectory(File(esdeDir, "settings/es_settings.xml"), home)
            ?: File(esdeDir, "downloaded_media").absolutePath
        return EsdeTarget(esdeDir, File(media))
    }

    /** [esdeTarget] when export on install is enabled (the daemon's push installs). */
    fun esdeInstallTarget(): EsdeTarget? = if (esdeMediaOnInstall) esdeTarget() else null

    /**
     * Restart a running, idle ES-DE after push installs so new games show up without a
     * manual rescan (daemon; see [LauncherRefresher]). Never interrupts a running game.
     */
    var launcherRefreshOnInstall: Boolean
        get() = prefs.getBoolean(KEY_LAUNCHER_REFRESH, true)
        set(v) = prefs.putBoolean(KEY_LAUNCHER_REFRESH, v)

    /** Minutos entre sincronizaciones automáticas de saves (0 = desactivado). */
    var autoSyncMinutes: Int
        get() = prefs.getInt("auto_sync_minutes", 0)
        set(v) = prefs.putInt("auto_sync_minutes", v)

    /** Descargas simultáneas máximas (1-5, igual que Android). */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", 2).coerceIn(1, 5)
        set(v) = prefs.putInt("max_concurrent_downloads", v.coerceIn(1, 5))

    /** Timestamp (epoch ms) de la última sincronización de saves completada. */
    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(v) = prefs.putLong("last_sync_at", v)

    /** Resumen de la última sincronización de saves (legible, en español). */
    var lastSyncSummary: String
        get() = prefs.get("last_sync_summary", "")
        set(v) = prefs.put("last_sync_summary", v)

    /** Política de resolución de conflictos de saves (id de ConflictPolicy). */
    var conflictPolicy: String
        get() = prefs.get("conflict_policy", "ask")
        set(v) = prefs.put("conflict_policy", v)

    /** Cerrar a la bandeja del sistema en vez de salir (sync en background). */
    var closeToTray: Boolean
        get() = prefs.getBoolean("close_to_tray", false)
        set(v) = prefs.putBoolean("close_to_tray", v)

    /** Versión de actualización omitida por el usuario ("" = ninguna). */
    var skippedVersion: String
        get() = prefs.get("skipped_version", "")
        set(v) = prefs.put("skipped_version", v)

    /** Versión de la app desktop (para el comprobador de actualizaciones). Keep equal to app versionName. */
    const val appVersion: String = "1.0.0-thinkscape.1"

    private const val KEY_IDENTIFIER = "device.identifier"
    private const val KEY_PAIRED_TOKEN = "paired.token"
    private const val KEY_PAIRED_DEVICE_ID = "paired.device_id"
    private const val KEY_PAIRED_NAME = "paired.device_name"
    private const val KEY_PAIRED_URL = "paired.base_url"
    private const val KEY_ESDE_MEDIA_ON_INSTALL = "esde.media_on_install"
    private const val KEY_LAUNCHER_REFRESH = "launcher.refresh_on_install"

    private fun esdeSettingRegex(name: String) = Regex("""<string\s+name="$name"\s+value="([^"]*)"\s*/>""")

    /**
     * `ROMDirectory` from ES-DE's `es_settings.xml` (not well-formed XML: no root
     * element, so it is matched line-wise). `~` and `%HOMEPATH%` expand to [home];
     * `%ROMPATH%` to ES-DE's default `~/ROMs`. Null when unset or unreadable.
     */
    internal fun esdeRomDirectory(settings: File, home: String): String? = esdePathSetting(settings, "ROMDirectory", home)

    /** `MediaDirectory` from ES-DE's `es_settings.xml`, expanded like [esdeRomDirectory]; null when unset. */
    internal fun esdeMediaDirectory(settings: File, home: String): String? = esdePathSetting(settings, "MediaDirectory", home)

    private fun esdePathSetting(settings: File, name: String, home: String): String? {
        val text = runCatching { settings.readText() }.getOrNull() ?: return null
        val raw = esdeSettingRegex(name).find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val expanded = raw
            .replace("%ROMPATH%", File(home, "ROMs").path)
            .replace("%HOMEPATH%", home)
            .let { if (it == "~" || it.startsWith("~/")) home + it.removePrefix("~") else it }
        return File(expanded).absoluteFile.normalize().path
    }
}
