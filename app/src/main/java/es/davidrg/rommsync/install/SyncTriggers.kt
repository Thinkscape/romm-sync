package es.davidrg.rommsync.install

import android.content.Context
import android.util.Log
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.data.sync.SaveSyncManager
import java.util.concurrent.atomic.AtomicLong

/**
 * Event-driven save sync (app foreground, screen on, QS tile).
 *
 * Every trigger enqueues the same one-time [es.davidrg.rommsync.data.sync.SaveSyncWorker]
 * the Sync screen uses; triggers within [DEBOUNCE_MS] of the last accepted one
 * collapse into it. The worker itself honours the save-sync toggle.
 */
object SyncTriggers {
    private const val TAG = "SyncTriggers"
    const val DEBOUNCE_MS = 60_000L

    private val lastAccepted = AtomicLong(0L)

    /** Returns true when a sync was enqueued, false when debounced. */
    fun requestFullSync(context: Context, source: String): Boolean {
        val settings = (context.applicationContext as? RomMSyncApplication)?.container?.settingsDataStore
        if (settings != null &&
            (settings.getServerUrlBlocking().isEmpty() || settings.getApiKeyBlocking().isEmpty() ||
                !settings.getSaveSyncEnabledBlocking())
        ) {
            return false
        }
        val now = System.currentTimeMillis()
        val previous = lastAccepted.get()
        if (now - previous < DEBOUNCE_MS || !lastAccepted.compareAndSet(previous, now)) {
            Log.d(TAG, "sync trigger '$source' debounced")
            return false
        }
        Log.i(TAG, "sync triggered by $source")
        val manager = (context.applicationContext as? RomMSyncApplication)?.container?.saveSyncManager
            ?: SaveSyncManager(context.applicationContext)
        manager.triggerSync()
        return true
    }
}
