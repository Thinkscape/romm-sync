package es.davidrg.rommsync.install

import android.content.Context
import android.content.Intent
import android.util.Log
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.core.metadata.EsdeCollectionLedger
import es.davidrg.rommsync.core.metadata.EsdeCollectionWriter
import es.davidrg.rommsync.core.metadata.EsdeEditor
import es.davidrg.rommsync.core.metadata.EsdeGameRemover
import es.davidrg.rommsync.core.metadata.EsdeRomPaths
import es.davidrg.rommsync.core.metadata.EsdeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Keeps ES-DE for Android (org.es_de.frontend) in step with pushes, uninstalls
 * and synced collections, next to Cocoon.
 *
 * ES-DE reads its ROM folders, gamelists and custom collections only at startup
 * (Utilities → Rescan ROM directory aside); it has no reload intent, IPC or file
 * watcher, and Android only lets an app kill its own background processes. A
 * refresh therefore means a new ES-DE process, decided by [CocoonRescanService]
 * from the windows on screen once a change has settled ([SETTLE_MS]):
 *
 * - ES-DE idle in front (and focused): the service presses Back until ES-DE quits
 *   ("Back button exits app", on by default; ES-DE logs "cleanly shutting down"),
 *   runs the queued edits of ES-DE's files and starts ES-DE again on the same
 *   display. ES-DE comes back on its system view after ~3 s.
 * - ES-DE not on screen: the queued edits run now. If ES-DE's log ends with its
 *   clean shutdown it is not running and the refresh is done; otherwise it may be
 *   alive in the background and is restarted as above when it comes to the front,
 *   unless it was started again after the change (its log restarts on every start).
 * - Any other app in front (an emulator launched from ES-DE or Cocoon), the screen
 *   off or locked: the refresh waits. A game is never interrupted.
 *
 * Files ES-DE rewrites itself (es_settings.xml, custom-*.cfg) change only through
 * [editor]; gamelists and media are written on push, as for Cocoon (ES-DE merges
 * its own changes into the existing gamelist file). Everything is gated on ES-DE
 * being installed and using the app's ES-DE folder (`<folder>/settings/es_settings.xml`).
 */
object EsdeReloader {
    const val ESDE_PACKAGE = "org.es_de.frontend"
    const val ESDE_ACTIVITY = "org.es_de.frontend.MainActivity"

    private const val TAG = "EsdeReload"
    private const val PREFS = "esde_reload"
    private const val KEY_HEAD_AT_CHANGE = "head_at_change"
    private const val SETTLE_MS = 5_000L
    private const val FAILURE_BACKOFF_MS = 2 * 60_000L
    private const val SHUTDOWN_MARK = "ES-DE cleanly shutting down"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val edits = ConcurrentLinkedQueue<Pair<String, () -> Unit>>()
    private var settleJob: Job? = null

    @Volatile
    private var markerInstance: RescanMarker? = null

    @Volatile
    private var ledgerInstance: EsdeCollectionLedger? = null

    /** Generation already reported as "waiting for ES-DE to come to the front" (log once). */
    @Volatile
    private var loggedBackgroundGeneration = -1L

    fun esdeVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(ESDE_PACKAGE, 0).versionName
    }.getOrNull()

    /** The ES-DE data folder, when ES-DE is installed and uses the app's ES-DE folder. */
    fun esdeDir(context: Context): File? {
        if (esdeVersion(context) == null) return null
        val settings = settings(context) ?: return null
        val dir = File(settings.getEsdeMetadataDirBlocking())
        return dir.takeIf { File(File(it, "settings"), "es_settings.xml").isFile }
    }

    /** Queues edits of ES-DE's own files; they run while ES-DE is stopped or off screen. */
    fun editor(context: Context): EsdeEditor {
        val app = context.applicationContext
        return EsdeEditor { description, edit ->
            edits.add(description to edit)
            Log.i(TAG, "queued: $description")
            onChanged(app)
        }
    }

    /** RomM collections as ES-DE custom collections (`<ES-DE>/collections/custom-<name>.cfg`). */
    fun collectionWriter(context: Context): EsdeCollectionWriter {
        val app = context.applicationContext
        return EsdeCollectionWriter(
            esdeDir = { esdeDir(app) },
            romsRoot = { settings(app)?.getRomsRootPathBlocking() },
            paths = romPaths(app),
            ledger = ledger(app),
            editor = editor(app),
            log = { Log.i(TAG, it) },
        )
    }

    /** Drops an uninstalled rom's gamelist entry, media and custom-collection lines. */
    fun gameRemover(context: Context): EsdeGameRemover {
        val app = context.applicationContext
        return EsdeGameRemover(
            target = { esdeDir(app)?.let { EsdeTarget(it, File(it, "downloaded_media")) } },
            paths = romPaths(app),
            ledger = ledger(app),
            editor = editor(app),
            log = { Log.i(TAG, it) },
        )
    }

    /** ES-DE's ROM directory is the app's ROM root (`%ROMPATH%` lines); ES-DE Android keeps its own copy of the path privately. */
    private fun romPaths(app: Context) = EsdeRomPaths { settings(app)?.getRomsRootPathBlocking() }

    @Synchronized
    private fun ledger(context: Context): EsdeCollectionLedger =
        ledgerInstance ?: EsdeCollectionLedger(File(context.applicationContext.filesDir, "esde-collections.properties"))
            .also { ledgerInstance = it }

    /**
     * Called after installs (once their media export finished), uninstalls, metadata
     * exports and queued edits. The refresh becomes due [SETTLE_MS] after the last call.
     */
    @Synchronized
    fun onChanged(context: Context) {
        val app = context.applicationContext
        if (esdeDir(app) == null) return
        rememberHead(app)
        marker(app).recordInstall()
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            notifyPending(app)
        }
    }

    private fun notifyPending(context: Context) {
        val service = CocoonRescanService.instance
        if (service == null && !CocoonAutoImport.ensureServiceEnabled(context)) {
            // Without the accessibility service nothing tells an idle ES-DE from a game in
            // front: the edits are applied, ES-DE shows them from its next start.
            val generation = pendingGeneration(context)
            val applied = applyEdits(context)
            marker(context).clear(generation)
            Log.w(TAG, "accessibility service off: $applied edits applied, ES-DE not restarted")
            return
        }
        Log.i(TAG, "refresh pending; waiting for ES-DE idle in front or off screen")
        service?.onEsdeWorkPending()
    }

    fun isDue(context: Context): Boolean = esdeVersion(context) != null && marker(context).isDue()

    fun pendingGeneration(context: Context): Long = marker(context).generation

    fun hasQueuedEdits(): Boolean = edits.isNotEmpty()

    /**
     * ES-DE is not on any screen and no game runs: apply the queued edits. Done when
     * ES-DE is not running; else the restart waits until ES-DE comes to the front.
     */
    fun refreshOffScreen(context: Context, generation: Long) {
        val applied = applyEdits(context)
        if (!isRunning(context)) {
            marker(context).clear(generation)
            Log.i(TAG, "es-de refreshed (not running; $applied edits applied; its next start loads them)")
            return
        }
        if (applied > 0 || loggedBackgroundGeneration != generation) {
            loggedBackgroundGeneration = generation
            Log.i(TAG, "es-de in the background ($applied edits applied): restart when it comes to the front")
        }
    }

    /** ES-DE in front was started after the latest change and has every edit: nothing to do. */
    fun startedSinceChange(context: Context): Boolean {
        if (edits.isNotEmpty()) return false
        val head = logHead(context) ?: return false
        val atChange = prefs(context).getString(KEY_HEAD_AT_CHANGE, null)
        return head != atChange
    }

    fun clear(context: Context, generation: Long, message: String) {
        marker(context).clear(generation)
        Log.i(TAG, message)
    }

    fun recordFailure(context: Context, generation: Long, reason: String) {
        marker(context).recordFailure(generation)
        Log.w(TAG, "es-de restart failed ($reason); retrying after a backoff")
    }

    /** Runs the queued edits (ES-DE stopped or off screen). Returns how many ran. */
    fun applyEdits(context: Context): Int {
        var count = 0
        while (true) {
            val (description, edit) = edits.poll() ?: break
            try {
                edit()
                count++
            } catch (e: Exception) {
                Log.w(TAG, "edit '$description' failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // ES-DE instances started before this point lack the edits.
        if (count > 0) rememberHead(context)
        return count
    }

    /** False when ES-DE's log ends with its clean shutdown (or does not exist). */
    fun isRunning(context: Context): Boolean {
        val log = logFile(context) ?: return false
        return !logTail(log).contains(SHUTDOWN_MARK)
    }

    /** First line of ES-DE's log; ES-DE truncates the log at every start, so a new line means a new process. */
    fun logHead(context: Context): String? =
        logFile(context)?.let { file -> runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull() }

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(ESDE_PACKAGE, ESDE_ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun logFile(context: Context): File? =
        esdeDir(context)?.let { File(File(it, "logs"), "es_log.txt") }?.takeIf { it.isFile }

    private fun logTail(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - 512).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun rememberHead(context: Context) {
        prefs(context).edit().putString(KEY_HEAD_AT_CHANGE, logHead(context)).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun marker(context: Context): RescanMarker {
        markerInstance?.let { return it }
        val prefs = prefs(context)
        val store = object : LongStore {
            override fun getLong(key: String): Long? =
                if (prefs.contains(key)) prefs.getLong(key, 0L) else null

            override fun putLongs(values: Map<String, Long?>) {
                val editor = prefs.edit()
                values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putLong(key, value) }
                editor.apply()
            }
        }
        return RescanMarker(store, System::currentTimeMillis, SETTLE_MS, FAILURE_BACKOFF_MS).also { markerInstance = it }
    }

    private fun settings(context: Context) =
        (context.applicationContext as? RomMSyncApplication)?.container?.settingsDataStore
}
