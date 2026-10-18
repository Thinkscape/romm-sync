package es.davidrg.rommsync.data.metadata

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.davidrg.rommsync.core.download.PlatformFolders
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.metadata.EsdeGameEntry
import es.davidrg.rommsync.core.metadata.EsdeGamelist
import es.davidrg.rommsync.core.metadata.EsdeMediaExporter
import es.davidrg.rommsync.core.metadata.EsdeMediaType
import es.davidrg.rommsync.core.metadata.EsdeMetadata
import es.davidrg.rommsync.core.remote.NetworkModule
import es.davidrg.rommsync.data.local.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Exports one platform's RomM media and metadata in ES-DE's layout through the
 * shared [EsdeMediaExporter] (the same code push installs use):
 * ```
 * <metadata dir>/downloaded_media/<system>/<type>/<rom stem>.<ext>
 * <metadata dir>/gamelists/<system>/gamelist.xml
 * ```
 * `<metadata dir>` is the "ES-DE / Cocoon metadata folder" setting and
 * `<system>` the platform's launcher folder ([PlatformFolders]). Optionally
 * copies covers/screenshots into RetroHRAI's structure.
 */
class MetadataExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val platformId = inputData.getInt(KEY_PLATFORM_ID, -1)
        val platformSlug = inputData.getString(KEY_PLATFORM_SLUG)
            ?: return@withContext Result.failure()

        if (platformId < 0) return@withContext Result.failure()

        val dataStore = SettingsDataStore(applicationContext)
        val serverUrl = dataStore.getServerUrlBlocking()
        val apiKey = dataStore.getApiKeyBlocking()
        val esdeDir = File(dataStore.getEsdeMetadataDirBlocking())
        val mediaDir = File(esdeDir, "downloaded_media")
        val retroHraiMediaPath = dataStore.getRetroHraiMediaPathBlocking()

        if (apiKey.isEmpty() || serverUrl.isEmpty()) {
            return@withContext Result.failure()
        }
        val roots = buildList {
            add(dataStore.getRomsRootPathBlocking())
            if (dataStore.getDualRomsPathsEnabledBlocking()) add(dataStore.getSecondaryRomsPathBlocking())
        }
        val folder = PlatformFolders.folderFor(platformSlug)
        if (folder == null) {
            Log.w(TAG, "platform $platformSlug has no launcher folder; nothing to export")
            return@withContext Result.failure()
        }

        val mediaTypes = buildSet {
            if (inputData.getBoolean(KEY_COVERS, true)) {
                addAll(listOf(
                    EsdeMediaType.COVERS, EsdeMediaType.MARQUEES, EsdeMediaType.FANART, EsdeMediaType.BOXES_3D,
                    EsdeMediaType.BACKCOVERS, EsdeMediaType.PHYSICAL_MEDIA, EsdeMediaType.MIXIMAGES,
                ))
            }
            if (inputData.getBoolean(KEY_SCREENSHOTS, true)) {
                add(EsdeMediaType.SCREENSHOTS)
                add(EsdeMediaType.TITLESCREENS)
            }
            if (inputData.getBoolean(KEY_VIDEOS, true)) add(EsdeMediaType.VIDEOS)
            if (inputData.getBoolean(KEY_MANUALS, true)) add(EsdeMediaType.MANUALS)
        }
        val exportGamelist = inputData.getBoolean(KEY_GAMELIST, true)
        val exportRetroHrai = inputData.getBoolean(KEY_RETROHRAI, false)

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo(platformSlug, 0, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground", e)
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)
        val exporter = EsdeMediaExporter(OkHttpClient(), serverUrl, apiKey) { Log.w(TAG, it) }
        val options = EsdeMediaExporter.Options(mediaTypes = mediaTypes, writeGamelist = false)

        val entries = mutableListOf<EsdeGameEntry>()
        var processed = 0
        var offset = 0
        val limit = 100
        var hasMore = true
        var mediaDownloaded = 0
        var mediaSkipped = 0

        while (hasMore) {
            val params = buildMap {
                put("platform_ids", platformId.toString())
                put("limit", limit.toString())
                put("offset", offset.toString())
                put("order_by", "name")
                put("order_dir", "asc")
            }

            val response = try {
                api.getRomDetailsPage(params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching ROMs at offset $offset", e)
                return@withContext Result.retry()
            }

            if (response.items.isEmpty()) break

            for (rom in response.items) {
                processed++
                // The root where the game is on disk, else the main root (gamelists ignore missing games).
                val primary = roots.mapNotNull { EsdeMetadata.primaryFor(rom, File(it, folder)) }
                    .let { candidates -> candidates.firstOrNull { it.exists() } ?: candidates.firstOrNull() }
                    ?: continue
                val result = exporter.exportFor(rom, primary, folder, esdeDir, mediaDir, options)
                mediaDownloaded += result.written.size
                mediaSkipped += result.present.size
                result.entry?.let { entries += it }

                if (exportRetroHrai) {
                    val gameName = (rom.name ?: rom.fsName).replace(Regex("[^a-zA-Z0-9._ -]"), "").trim()
                    result.files[EsdeMediaType.COVERS]?.let {
                        copyToRetroHrai(it, retroHraiMediaPath, platformSlug, "covers", "${gameName}_cover_0.jpg")
                    }
                    result.files[EsdeMediaType.SCREENSHOTS]?.let {
                        // Screenshot_0 + fanart_0 (first screenshot doubles as fanart)
                        copyToRetroHrai(it, retroHraiMediaPath, platformSlug, "screenshots", "${gameName}_screenshot_0.jpg")
                        copyToRetroHrai(it, retroHraiMediaPath, platformSlug, "fanart", "${gameName}_fanart_0.jpg")
                    }
                }

                val progress = (processed * 100 / response.total.coerceAtLeast(1)).coerceIn(0, 100)
                try {
                    setProgress(workDataOf(KEY_MEDIA_DOWNLOADED to mediaDownloaded))
                    setForeground(createForegroundInfo(platformSlug, progress, mediaDownloaded))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }

            offset += limit
            hasMore = offset < response.total
        }

        if (exportGamelist && entries.isNotEmpty()) {
            val gamelistFile = EsdeMediaExporter.gamelistFile(esdeDir, folder)
            try {
                EsdeGamelist.upsertFile(gamelistFile, entries)
                Log.i(TAG, "Gamelist written: ${gamelistFile.absolutePath} (${entries.size} entries)")
            } catch (e: Exception) {
                Log.w(TAG, "Gamelist not written: ${gamelistFile.absolutePath}", e)
                return@withContext Result.failure()
            }
        }

        Result.success(workDataOf(
            KEY_MEDIA_DOWNLOADED to mediaDownloaded,
            KEY_MEDIA_SKIPPED to mediaSkipped,
            KEY_ENTRIES to entries.size,
        ))
    }

    /**
     * Copies an exported file into RetroHRAI's structure
     * `{retroHraiMediaPath}/{slug}/{type}/{fileName}`; skips existing targets.
     */
    private fun copyToRetroHrai(
        source: File,
        retroHraiMediaPath: String,
        platformSlug: String,
        type: String,
        fileName: String,
    ) {
        val targetDir = File("$retroHraiMediaPath/$platformSlug/$type")
        targetDir.mkdirs()
        val target = File(targetDir, fileName)
        if (target.exists()) return
        try {
            source.copyTo(target, overwrite = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy to RetroHRAI: $fileName", e)
        }
    }

    private fun createForegroundInfo(slug: String, progress: Int, mediaCount: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(tr("android.metadata.notification_title", slug))
            .setContentText(tr("android.metadata.notification_text", mediaCount))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                tr("android.metadata.channel_name"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = tr("android.metadata.channel_description") }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TAG = "MetadataExportWorker"
        const val WORK_NAME_PREFIX = "metadata_export_"

        const val KEY_PLATFORM_ID = "platform_id"
        const val KEY_PLATFORM_SLUG = "platform_slug"
        const val KEY_COVERS = "export_covers"
        const val KEY_SCREENSHOTS = "export_screenshots"
        const val KEY_VIDEOS = "export_videos"
        const val KEY_MANUALS = "export_manuals"
        const val KEY_GAMELIST = "export_gamelist"
        const val KEY_RETROHRAI = "export_retrohrai"

        const val KEY_MEDIA_DOWNLOADED = "media_downloaded"
        const val KEY_MEDIA_SKIPPED = "media_skipped"
        const val KEY_ENTRIES = "entries_count"

        private const val CHANNEL_ID = "metadata_export"
        private const val NOTIFICATION_ID = 3001
    }
}
