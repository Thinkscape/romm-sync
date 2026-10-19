package es.davidrg.rommsync.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import es.davidrg.rommsync.BuildConfig
import es.davidrg.rommsync.MainActivity
import es.davidrg.rommsync.R
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.core.auth.PairedDevice
import es.davidrg.rommsync.core.collections.CollectionReconciler
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.install.InstallClient
import es.davidrg.rommsync.core.install.InstallListener
import es.davidrg.rommsync.core.metadata.EsdeTarget
import es.davidrg.rommsync.data.repository.PairedDeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service (type `dataSync`) that keeps this device online in RomM
 * and executes push-install and uninstall requests through one [InstallClient].
 *
 * Also owns the [CollectionReconciler] for RomM collections synced to this
 * device (runs on socket connect, `collections:changed` and every periodic save
 * sync), and schedules a save sync two minutes after the screen turns on while
 * charging or on Wi-Fi (cancelled if the screen goes off again first).
 */
class InstallListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val container get() = (application as RomMSyncApplication).container

    private var client: InstallClient? = null
    private var clientDevice: PairedDeviceSettings? = null
    private var reconciler: CollectionReconciler? = null
    private var reconcilerJob: Job? = null
    private var screenOnSyncJob: Job? = null
    private var postInstallSyncJob: Job? = null
    private var screenReceiverRegistered = false

    /** Last progress percentage shown per request; avoids flooding the notification manager. */
    private val lastProgress = ConcurrentHashMap<String, Int>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> scheduleScreenOnSync()
                Intent.ACTION_SCREEN_OFF -> cancelScreenOnSync()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        // startForeground must happen promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_SERVICE,
            serviceNotification(tr("android.install.service.connecting")),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val paired = container.settingsDataStore.getPairedDevice()
            if (paired == null) {
                Log.i(TAG, "not paired; stopping")
                // stopSelf(startId): a start issued after pairing (newer startId) keeps the service alive.
                stopSelf(startId)
                return@launch
            }
            val running = ensureClient(paired)
            if (intent?.action == ACTION_DRAIN) {
                runCatching { running.drainNow() }
                    .onFailure { Log.w(TAG, "drain failed: ${it.message}") }
            }
            if (intent?.action == ACTION_RECONCILE) requestReconcile("periodic")
        }
        return START_STICKY
    }

    /** Android 15+ caps dataSync services; stop cleanly when the system says so. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout; stopping")
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        synchronized(this) {
            client?.stop()
            client = null
            clientDevice = null
            reconcilerJob?.cancel()
            reconcilerJob = null
            reconciler = null
        }
        scope.cancel()
        super.onDestroy()
    }

    /** Queues a collection reconcile (no-op until a paired client runs). */
    fun requestReconcile(reason: String) {
        reconciler?.request(reason)
    }

    @Synchronized
    private fun ensureClient(paired: PairedDeviceSettings): InstallClient {
        client?.let { if (clientDevice == paired) return it }
        client?.stop()
        reconcilerJob?.cancel()
        val store = container.settingsDataStore
        val collectionStore = container.collectionSyncStore
        val newClient = InstallClient(
            baseUrl = paired.baseUrl,
            token = paired.token,
            deviceId = paired.deviceId,
            romsRoot = { store.getRomsRootPathBlocking() },
            downloadsAllowed = { !store.getWifiOnlyDownloadsBlocking() || isOnUnmeteredNetwork() },
            store = container.installedRomStore,
            listener = Listener(hostOf(paired.baseUrl)),
            logger = { Log.i(TAG, it) },
            // Cocoon's ES-DE integration reads this folder's gamelists/ and downloaded_media/.
            esdeTarget = {
                if (store.getEsdePushMediaEnabledBlocking()) {
                    val dir = File(store.getEsdeMetadataDirBlocking())
                    EsdeTarget(dir, File(dir, "downloaded_media"))
                } else {
                    null
                }
            },
            provenance = collectionStore,
            gameRemover = CocoonGameRemover(applicationContext, store),
        )
        val newReconciler = CollectionReconciler(
            backend = newClient,
            installed = container.installedRomStore,
            provenance = collectionStore,
            state = collectionStore,
            writer = CocoonCollectionWriter(applicationContext),
            coverDir = File(cacheDir, "collection-covers"),
            logger = { Log.i(TAG, it) },
        )
        val job = SupervisorJob(scope.coroutineContext[Job])
        newReconciler.start(CoroutineScope(scope.coroutineContext + job))
        client = newClient
        clientDevice = paired
        reconciler = newReconciler
        reconcilerJob = job
        // Devices paired by an older build lack remote_uninstall/collection_sync; idempotent.
        scope.launch {
            runCatching {
                container.devicePairing.advertiseCapabilities(
                    paired.baseUrl,
                    PairedDevice(paired.token, paired.deviceId, null),
                    PAIRING_PLATFORM,
                    BuildConfig.VERSION_NAME,
                )
            }.onFailure { Log.w(TAG, "cannot advertise capabilities: ${it.message}") }
        }
        newClient.start()
        return newClient
    }

    // ── Install listener → notifications ───────────────────────────────

    private inner class Listener(private val host: String) : InstallListener {
        override fun onConnection(connected: Boolean, detail: String?) {
            val text = if (connected) {
                tr("android.install.service.connected", host)
            } else {
                tr("android.install.service.disconnected", host)
            }
            notificationManager.notify(NOTIFICATION_ID_SERVICE, serviceNotification(text))
        }

        override fun onInstallStarted(requestId: String, romId: Int) {
            lastProgress[requestId] = -1
            notify(requestId, progressNotification(romId, 0, 0, indeterminate = true))
        }

        override fun onInstallProgress(requestId: String, bytes: Long, total: Long) {
            if (total <= 0) return
            val percent = ((bytes * 100) / total).toInt().coerceIn(0, 100)
            if (lastProgress.put(requestId, percent) == percent) return
            notify(requestId, progressNotification(null, percent, 100, indeterminate = false))
        }

        override fun onInstallFinished(requestId: String, romId: Int, status: String, reason: String?, files: List<File>) {
            lastProgress.remove(requestId)
            val name = files.firstOrNull()?.name ?: tr("android.install.rom_fallback", romId)
            val (title, text) = when (status) {
                InstallClient.STATUS_DONE -> tr("android.install.done_title") to name
                InstallClient.STATUS_ALREADY_INSTALLED -> tr("android.install.already_title") to name
                else -> tr("android.install.failed_title") to (reason ?: name)
            }
            notify(
                requestId,
                NotificationCompat.Builder(this@InstallListenerService, CHANNEL_RESULTS)
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build(),
            )
            if (status == InstallClient.STATUS_DONE || status == InstallClient.STATUS_ALREADY_INSTALLED) {
                schedulePostInstallSync()
            }
            if (status == InstallClient.STATUS_DONE && files.isNotEmpty()) {
                // Updates MediaStore; Cocoon itself only imports through its Rescan Games UI.
                MediaScannerConnection.scanFile(
                    applicationContext,
                    files.map { it.absolutePath }.toTypedArray(),
                    null,
                    null,
                )
                CocoonAutoImport.onLibraryChanged(applicationContext)
            }
        }

        override fun onUninstallFinished(requestId: String?, romId: Int, status: String, reason: String?, files: List<File>) {
            // Collection-driven removals (no request id) stay silent; RomM-requested ones notify like installs.
            if (requestId == null) return
            val name = files.firstOrNull()?.name ?: tr("android.install.rom_fallback", romId)
            val (title, text) = when (status) {
                InstallClient.STATUS_DONE -> tr("android.install.uninstall_done_title") to name
                InstallClient.STATUS_NOT_INSTALLED -> tr("android.install.uninstall_none_title") to name
                else -> tr("android.install.uninstall_failed_title") to (reason ?: name)
            }
            notify(
                requestId,
                NotificationCompat.Builder(this@InstallListenerService, CHANNEL_RESULTS)
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build(),
            )
        }

        override fun onCollectionsChanged(reason: String) {
            requestReconcile(reason)
        }

        override fun onMetadataExported(requestId: String, romId: Int, mediaFiles: Int, failures: List<String>) {
            if (failures.isEmpty()) {
                Log.i(TAG, "install $requestId: ES-DE media ready for rom $romId ($mediaFiles files)")
            } else {
                Log.w(TAG, "install $requestId: ES-DE export for rom $romId: $mediaFiles files, problems: ${failures.joinToString("; ")}")
            }
        }

        private fun progressNotification(romId: Int?, progress: Int, max: Int, indeterminate: Boolean): Notification =
            NotificationCompat.Builder(this@InstallListenerService, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(tr("android.install.progress_title"))
                .apply { romId?.let { setContentText(tr("android.install.rom_fallback", it)) } }
                .setProgress(max, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .build()

        private fun notify(requestId: String, notification: Notification) {
            notificationManager.notify(NOTIFICATION_TAG_INSTALL, requestId.hashCode(), notification)
        }
    }

    // ── Screen-on sync trigger ──────────────────────────────────────────

    /**
     * Sync after installs so saves/states already in RomM arrive with the game.
     * Trailing debounce: each finished install restarts the settle timer (a batch
     * → one sync after the last), and a trigger that the 60 s debounce would
     * drop (a sync ran just before the install finished) is deferred, not lost.
     */
    @Synchronized
    private fun schedulePostInstallSync() {
        postInstallSyncJob?.cancel()
        postInstallSyncJob = scope.launch {
            delay(POST_INSTALL_SETTLE_MS)
            while (true) {
                val wait = SyncTriggers.millisUntilAllowed()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                if (SyncTriggers.requestFullSync(applicationContext, "install")) break
                // false: either not configured / disabled (give up) or lost a debounce race (retry).
                if (SyncTriggers.millisUntilAllowed() == 0L) break
            }
        }
    }

    private fun scheduleScreenOnSync() {
        if (!isCharging() && !isOnUnmeteredNetwork()) return
        screenOnSyncJob?.cancel()
        screenOnSyncJob = scope.launch {
            delay(SCREEN_ON_SYNC_DELAY_MS)
            SyncTriggers.requestFullSync(applicationContext, "screen on")
        }
    }

    private fun cancelScreenOnSync() {
        screenOnSyncJob?.cancel()
        screenOnSyncJob = null
    }

    private fun isCharging(): Boolean {
        val battery = getSystemService(BatteryManager::class.java) ?: return false
        return battery.isCharging
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ── Notifications ───────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                tr("android.install.channel.service"),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                tr("android.install.channel.results"),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "InstallListener"
        /** Same platform string ConfigViewModel sends when pairing. */
        private const val PAIRING_PLATFORM = "android"
        private const val ACTION_DRAIN = "es.davidrg.rommsync.install.DRAIN"
        private const val ACTION_RECONCILE = "es.davidrg.rommsync.install.RECONCILE"
        private const val CHANNEL_SERVICE = "romm_device"
        internal const val CHANNEL_RESULTS = "romm_installs"
        private const val NOTIFICATION_ID_SERVICE = 4201
        private const val NOTIFICATION_TAG_INSTALL = "romm_install"
        private const val SCREEN_ON_SYNC_DELAY_MS = 2 * 60_000L
        /** Quiet period after the last finished install before syncing (collapses push batches). */
        private const val POST_INSTALL_SETTLE_MS = 10_000L

        private fun hostOf(baseUrl: String): String =
            runCatching { URI(baseUrl).host }.getOrNull() ?: baseUrl

        @Volatile
        private var instance: InstallListenerService? = null

        /**
         * Periodic collection reconcile (called from the save-sync cycle): queued
         * on the running service, or delivered with a start intent otherwise.
         */
        fun requestReconcile(context: Context, reason: String) {
            instance?.let {
                it.requestReconcile(reason)
                return
            }
            startWith(context, ACTION_RECONCILE)
        }

        /**
         * Starts (or nudges) the service when a paired device exists. [drain]
         * also claims pending installs right away. Safe from any context: a
         * background-start refusal is logged, not thrown.
         */
        fun start(context: Context, drain: Boolean = false) = startWith(context, if (drain) ACTION_DRAIN else null)

        private fun startWith(context: Context, action: String?) {
            val app = context.applicationContext
            val paired = (app as? RomMSyncApplication)?.container?.settingsDataStore?.getPairedDeviceBlocking()
            if (paired == null) return
            val intent = Intent(app, InstallListenerService::class.java)
            if (action != null) intent.action = action
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
                Log.w(TAG, "cannot start install service from background: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot start install service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, InstallListenerService::class.java))
        }
    }
}
