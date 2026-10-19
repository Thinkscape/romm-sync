package es.davidrg.rommsync.install

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import es.davidrg.rommsync.R
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.core.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gets pushed games into Cocoon's library.
 *
 * Cocoon (rip.moth.cocoonshell 3.07) has no exported rescan entry point and its
 * background discovery workers only post a "review" notification, so the only
 * automatic path is driving Settings → Library & data → Rescan Games through
 * [CocoonRescanService]. Installs mark a rescan as pending; after [SETTLE_MS]
 * without further installs the service runs it as soon as Cocoon's home screen
 * is in the foreground on the main display (never over a game). When the
 * service is off or the UI does not match, a notification asks the user to
 * rescan by hand.
 */
object CocoonAutoImport {
    const val COCOON_PACKAGE = "rip.moth.cocoonshell"
    /** Cocoon versions whose Settings UI the service has been verified against. */
    val SUPPORTED_COCOON_VERSIONS = setOf("3.07")

    private const val TAG = "CocoonAutoImport"
    private const val PREFS = "cocoon_auto_import"
    /** Pre-generation marker key; migrated by [marker]. */
    private const val KEY_LEGACY_PENDING_SINCE = "pending_since"
    private const val SETTLE_MS = 5_000L
    /** A failed rescan is not retried for the same install generation within this window. */
    private const val FAILURE_BACKOFF_MS = 10 * 60_000L
    private const val NOTIFICATION_ID_FALLBACK = 4202

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settleJob: Job? = null

    /** Called after every successful push install. */
    @Synchronized
    fun onGameInstalled(context: Context) {
        val app = context.applicationContext
        val settings = (app as? RomMSyncApplication)?.container?.settingsDataStore ?: return
        if (!settings.getCocoonAutoImportEnabledBlocking() || !isCocoonInstalled(app)) return
        // Recorded right away (persisted, bumps the generation on every install) so a
        // running flow cannot clear it; [RescanMarker.isDue] enforces the settle delay.
        marker(app).recordInstall()
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            notifyPending(app)
        }
    }

    private fun notifyPending(context: Context) {
        val service = CocoonRescanService.instance
        if (service == null && !ensureServiceEnabled(context)) {
            Log.w(TAG, "rescan pending but the accessibility service is off; notifying")
            postFallback(context)
            return
        }
        Log.i(TAG, "rescan pending; waiting for Cocoon home in the foreground")
        service?.onRescanPending()
    }

    @Volatile
    private var markerInstance: RescanMarker? = null

    @Synchronized
    private fun marker(context: Context): RescanMarker {
        markerInstance?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = object : LongStore {
            override fun getLong(key: String): Long? =
                if (prefs.contains(key)) prefs.getLong(key, 0L) else null

            override fun putLongs(values: Map<String, Long?>) {
                val editor = prefs.edit()
                values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putLong(key, value) }
                editor.apply()
            }
        }
        val created = RescanMarker(store, System::currentTimeMillis, SETTLE_MS, FAILURE_BACKOFF_MS)
        if (prefs.contains(KEY_LEGACY_PENDING_SINCE)) {
            if (prefs.getLong(KEY_LEGACY_PENDING_SINCE, 0L) != 0L) created.recordInstall()
            prefs.edit().remove(KEY_LEGACY_PENDING_SINCE).apply()
        }
        markerInstance = created
        return created
    }

    fun isPending(context: Context): Boolean = marker(context).isPending

    /** Pending, settled, and not backing off after a failure for the same generation. */
    fun isDue(context: Context): Boolean = marker(context).isDue()

    /** Generation a flow captures at start and hands to [clearPending] / [recordFailure]. */
    fun pendingGeneration(context: Context): Long = marker(context).generation

    fun lastInstallAt(context: Context): Long = marker(context).lastInstallAt

    /** The rescan flow that started at [startedGeneration] succeeded; later installs stay pending. */
    fun clearPending(context: Context, startedGeneration: Long) = marker(context).clear(startedGeneration)

    /** The rescan flow failed: the marker stays, retries for that generation back off. */
    fun recordFailure(context: Context, startedGeneration: Long) = marker(context).recordFailure(startedGeneration)

    /** Auto-import was switched off: forget every pending install. */
    fun discardPending(context: Context) = marker(context).discard()

    fun cocoonVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(COCOON_PACKAGE, 0).versionName
    }.getOrNull()

    private fun isCocoonInstalled(context: Context): Boolean = cocoonVersion(context) != null

    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = serviceComponent(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * Turns the service on (keeping other enabled services) when the app holds
     * WRITE_SECURE_SETTINGS (granted once over adb). Returns whether it is on.
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (isServiceEnabled(context)) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        val updated = (current + serviceComponent(context).flattenToString()).joinToString(":")
        return runCatching {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "accessibility service enabled via WRITE_SECURE_SETTINGS")
            true
        }.getOrElse {
            Log.w(TAG, "could not enable accessibility service: ${it.message}")
            false
        }
    }

    /** Removes only this app's service from the enabled list (toggle switched off). */
    fun disableService(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        val me = serviceComponent(context)
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return
        val updated = current.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        runCatching { Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated.joinToString(":")) }
    }

    private fun serviceComponent(context: Context) = ComponentName(context, CocoonRescanService::class.java)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun postFallback(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val launchCocoon = context.packageManager.getLaunchIntentForPackage(COCOON_PACKAGE)
        val notification = NotificationCompat.Builder(context, InstallListenerService.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(tr("android.cocoon.fallback_title"))
            .setContentText(tr("android.cocoon.fallback_text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(tr("android.cocoon.fallback_text")))
            .setAutoCancel(true)
            .apply {
                if (launchCocoon != null) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            NOTIFICATION_ID_FALLBACK,
                            launchCocoon,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(NOTIFICATION_ID_FALLBACK, notification)
    }

    fun cancelFallback(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID_FALLBACK)
    }
}
