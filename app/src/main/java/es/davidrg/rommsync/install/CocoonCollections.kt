package es.davidrg.rommsync.install

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import es.davidrg.rommsync.RomMSyncApplication
import java.io.File

/**
 * Shared state of the Cocoon collection folders: the persistent
 * [CocoonFolderLedger] and the hand-off to [CocoonRescanService], which applies
 * pending folder work through Cocoon's UI ([CocoonFolderFlow]) under the same
 * rules as the rescan (screen on, unlocked, Cocoon's home screen in front on
 * every display, after any settling rescan).
 */
object CocoonCollections {
    private const val TAG = "CocoonCollections"
    private const val PREFS = "cocoon_collections"
    private const val KEY_FAILED_AT = "folder_pass_failed_at"
    /** A failed folder pass is not retried within this window (each try drives Cocoon's UI). */
    private const val FAILURE_BACKOFF_MS = 10 * 60_000L

    /** Cocoon's data folder name on the volume holding the games (`$SD/Cocoon`). */
    private const val COCOON_DIR = "Cocoon"
    /** Covers handed to Cocoon's "Upload Icon" picker live here (the picker cannot see app-private files). */
    const val COVERS_DIR = "romm-covers"

    @Volatile
    private var ledgerInstance: CocoonFolderLedger? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ledger(context: Context): CocoonFolderLedger {
        ledgerInstance?.let { return it }
        val prefs = prefs(context)
        val store = object : StringStore {
            override fun getString(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String?) {
                // commit: the flow reads the ledger right after a UI step and must never redo one.
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
            }
        }
        return CocoonFolderLedger(store).also { ledgerInstance = it }
    }

    /** Folder work exists, the feature (the "Auto-import into Cocoon" toggle) is on, and no failure backoff runs. */
    fun isDue(context: Context): Boolean {
        val settings = (context.applicationContext as? RomMSyncApplication)?.container?.settingsDataStore ?: return false
        if (!settings.getCocoonAutoImportEnabledBlocking()) return false
        val failedAt = prefs(context).getLong(KEY_FAILED_AT, 0L)
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < FAILURE_BACKOFF_MS) return false
        return ledger(context).pendingWork().isNotEmpty()
    }

    fun recordPassFailure(context: Context) {
        prefs(context).edit().putLong(KEY_FAILED_AT, System.currentTimeMillis()).apply()
    }

    fun clearPassFailure(context: Context) {
        prefs(context).edit().remove(KEY_FAILED_AT).apply()
    }

    /** Called by [CocoonCollectionWriter] after recording new folder work: the service runs it when Cocoon is idle. */
    fun onFolderWorkPending(context: Context) {
        val pending = ledger(context).pendingWork().size
        clearPassFailure(context)
        val service = CocoonRescanService.instance
        if (service == null && !CocoonAutoImport.ensureServiceEnabled(context)) {
            Log.w(TAG, "Cocoon folder work pending ($pending item(s)) but the accessibility service is off")
            return
        }
        Log.i(TAG, "Cocoon folder work pending: $pending item(s); waiting for Cocoon home in the foreground")
        service?.onFolderWorkPending()
    }

    /** `$SD/Cocoon` (Cocoon's data folder) on whichever mounted volume has it, or null. */
    fun cocoonDataDir(context: Context): File? {
        val storage = context.getSystemService(StorageManager::class.java) ?: return null
        return storage.storageVolumes
            .mapNotNull { it.directory }
            .map { File(it, COCOON_DIR) }
            .firstOrNull { File(it, "downloaded_media").isDirectory }
    }

    /** Where Cocoon stores uploaded folder icons (`folder_<id>_<uuid>.<ext>`). */
    fun iconDir(context: Context): File? = cocoonDataDir(context)?.let { File(it, "downloaded_media/folders/icon") }

    /** Folder id Cocoon encodes in an icon file name, or null. */
    fun folderIdOf(iconFile: String): String? =
        Regex("^folder_(\\d+)_").find(iconFile)?.groupValues?.get(1)

    /** Cocoon's current icon file for the folder that had [iconFile] (newest wins), or null. */
    fun currentIconFile(context: Context, iconFile: String): String? {
        val id = folderIdOf(iconFile) ?: return null
        return iconDir(context)?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("folder_${id}_") }
            ?.maxByOrNull { it.lastModified() }
            ?.name
    }
}
