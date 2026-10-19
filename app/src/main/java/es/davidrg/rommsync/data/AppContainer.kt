package es.davidrg.rommsync.data

import android.content.Context
import es.davidrg.rommsync.data.local.RomSyncDatabase
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.metadata.MetadataExportManager
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.SaveSyncManager
import es.davidrg.rommsync.data.update.AppUpdateChecker
import es.davidrg.rommsync.data.update.ApkInstallHelper
import es.davidrg.rommsync.download.DownloadManager

/**
 * Manual dependency injection container.
 *
 * Initialized once in [es.davidrg.rommsync.RomMSyncApplication.onCreate].
 * All ViewModels receive their dependencies from here via ViewModelFactory.
 *
 * This avoids the annotation-processing complexity of Hilt while keeping
 * a clean, testable architecture.
 */
class AppContainer(private val appContext: Context) {

    private val database: RomSyncDatabase = RomSyncDatabase.getDatabase(appContext)

    val settingsDataStore = SettingsDataStore(appContext)

    val settingsRepository = SettingsRepository(settingsDataStore)

    val romRepository = RomRepository(
        platformDao = database.platformDao(),
        romDao = database.romDao(),
        collectionSyncDao = database.collectionSyncDao(),
    )

    val downloadManager = DownloadManager(appContext)

    val saveSyncManager = SaveSyncManager(appContext)

    /** Versionado local de saves (restaurable desde el detalle de juego). */
    val saveBackupManager = es.davidrg.rommsync.core.sync.SaveBackupManager(
        java.io.File(appContext.filesDir, "save-backups"),
    )

    val saveBackupRestorer = es.davidrg.rommsync.data.sync.SaveBackupRestorer(
        context = appContext,
        settingsDataStore = settingsDataStore,
        romDao = database.romDao(),
        platformDao = database.platformDao(),
        backupManager = saveBackupManager,
    )

    val metadataExportManager = MetadataExportManager(appContext)

    val appUpdateChecker = AppUpdateChecker()

    val apkInstallHelper = ApkInstallHelper(appContext)

    /** Records push-installed roms in the downloaded-rom table so library and save sync see them. */
    val installedRomStore: es.davidrg.rommsync.core.install.InstalledRomStore by lazy {
        es.davidrg.rommsync.data.install.RoomInstalledRomStore(database.romDao())
    }

    /** Provenance markers + collection state for collection sync (RomM "Sync to device"). */
    val collectionSyncStore: es.davidrg.rommsync.data.install.RoomCollectionSyncStore by lazy {
        es.davidrg.rommsync.data.install.RoomCollectionSyncStore(database.collectionSyncDao())
    }

    /** RomM device pairing; its client carries no Authorization header (init/token are open endpoints). */
    val devicePairing: es.davidrg.rommsync.core.auth.DevicePairing by lazy {
        es.davidrg.rommsync.core.auth.DevicePairing(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * Called when server settings change to refresh the API client.
     */
    fun configureApi(serverUrl: String, apiKey: String) {
        romRepository.configureApi(serverUrl, apiKey)
    }
}
