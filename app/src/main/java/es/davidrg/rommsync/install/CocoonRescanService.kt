package es.davidrg.rommsync.install

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import es.davidrg.rommsync.RomMSyncApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Runs Cocoon's "Rescan Games" for [CocoonAutoImport] by driving Cocoon's UI.
 *
 * Only acts while a rescan is pending, the screen is on and unlocked, and every
 * display shows Cocoon's home screen (any other app in front on either screen,
 * e.g. an emulator, blocks it). Cocoon's grid may sit on either of the Thor's
 * screens; the flow follows whichever window holds the next expected element. Flow, matched by visible text only (Cocoon's
 * Compose UI exposes no resource ids):
 *
 * 1. Click the visible "Settings" pod tile (content description) twice: focus,
 *    then launch. If it is scrolled off screen or not exposed, search the grid
 *    with D-pad global actions (API 33), reading each focused tile's name from
 *    its context menu (bottom-right "Y" button), cache its position, "Launch".
 * 2. Settings → "Library & data" → "Rescan Games".
 * 3. In the dialog (must read "All platforms") tap the start pill next to it,
 *    wait for "Scan complete", press "OK", then Back to the home screen.
 *
 * Any mismatch or timeout backs out to Cocoon's home screen and posts the
 * manual-rescan notification. Every step is logged under [TAG].
 */
class CocoonRescanService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var evaluateJob: Job? = null
    private var flowJob: Job? = null
    private var receiverRegistered = false
    /** Display the flow currently works on (Cocoon can use both of the Thor's screens). */
    private var activeDisplay = Display.DEFAULT_DISPLAY

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scheduleEvaluate(FOREGROUND_SETTLE_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        setContentEvents(false)
        Log.i(TAG, "service connected (Cocoon ${CocoonAutoImport.cocoonVersion(this)})")
        if (CocoonAutoImport.isPending(this)) scheduleEvaluate(FOREGROUND_SETTLE_MS)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (flowJob?.isActive != true && CocoonAutoImport.isPending(this)) {
                scheduleEvaluate(FOREGROUND_SETTLE_MS)
            }
        }
    }

    /** Called by [CocoonAutoImport] when installs have settled. */
    fun onRescanPending() {
        scope.launch { scheduleEvaluate(0L) }
    }

    private fun scheduleEvaluate(delayMs: Long) {
        if (flowJob?.isActive == true) return
        evaluateJob?.cancel()
        evaluateJob = scope.launch {
            delay(delayMs)
            evaluate()
        }
    }

    private fun evaluate() {
        if (!CocoonAutoImport.isPending(this) || flowJob?.isActive == true) return
        val settings = (application as RomMSyncApplication).container.settingsDataStore
        if (!settings.getCocoonAutoImportEnabledBlocking()) {
            CocoonAutoImport.clearPending(this)
            return
        }
        val blocker = foregroundBlocker()
        if (blocker != null) {
            Log.i(TAG, "rescan pending, waiting: $blocker")
            return
        }
        flowJob = scope.launch { runFlow() }
    }

    /** Why the flow cannot start now, or null when Cocoon's home screen is in front. */
    private fun foregroundBlocker(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "needs Android 13 (D-pad accessibility actions)"
        if (!getSystemService(PowerManager::class.java).isInteractive) return "screen off"
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return "device locked"
        busyApp()?.let { return "foreground app is $it" }
        val cocoon = cocoonWindows()
        if (cocoon.isEmpty()) return "no Cocoon window on any display"
        cocoon.firstOrNull { it.title?.toString() != COCOON_HOME_TITLE }?.let {
            return "Cocoon shows '${it.title}' on display ${it.displayIdCompat()}, not its home screen"
        }
        return null
    }

    // ── The flow ────────────────────────────────────────────────────────

    private class FlowFailure(message: String) : Exception(message)
    private class FlowAborted(message: String) : Exception(message)

    private suspend fun runFlow() {
        val pendingSince = CocoonAutoImport.pendingSince(this)
        val started = SystemClock.elapsedRealtime()
        val version = CocoonAutoImport.cocoonVersion(this)
        Log.i(TAG, "flow start (Cocoon $version, pending for ${System.currentTimeMillis() - pendingSince} ms)")
        setContentEvents(true)
        chooseHomeDisplay()
        try {
            if (version !in CocoonAutoImport.SUPPORTED_COCOON_VERSIONS) {
                throw FlowFailure("unsupported Cocoon version $version")
            }
            withTimeout(FLOW_TIMEOUT_MS) {
                openSettingsFromHome()
                step("open Library & data") { clickText("Library & data") }
                step("open Rescan Games") {
                    waitFor(UI_TIMEOUT_MS) { findText(it, "Rescan Games") } ?: throw FlowFailure("'Rescan Games' not found")
                    clickText("Rescan Games")
                }
                startScan()
                val newGames = awaitScanResult()
                step("dismiss result") { clickText("OK") }
                returnHome()
                Log.i(TAG, "flow done in ${SystemClock.elapsedRealtime() - started} ms: $newGames new games " +
                    "(${System.currentTimeMillis() - pendingSince} ms after the last install settled)")
            }
            CocoonAutoImport.clearPending(this)
            CocoonAutoImport.cancelFallback(this)
        } catch (e: FlowAborted) {
            Log.i(TAG, "flow aborted: ${e.message}; rescan stays pending")
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                fail("timed out after ${FLOW_TIMEOUT_MS / 1000} s")
            } else {
                throw e
            }
        } catch (e: FlowFailure) {
            fail(e.message ?: "failure")
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            setContentEvents(false)
        }
    }

    private suspend fun fail(reason: String) {
        Log.w(TAG, "flow failed ($reason); backing out to Cocoon home and posting the manual-rescan notification")
        runCatching { backOut() }
        CocoonAutoImport.clearPending(this)
        CocoonAutoImport.postFallback(this)
    }

    private suspend inline fun <T> step(name: String, block: () -> T): T {
        ensureCocoonInFront()
        Log.i(TAG, "step: $name")
        return block()
    }

    /** Stops (without touching the UI) when the user left Cocoon or the screen went off. */
    private fun ensureCocoonInFront() {
        if (!getSystemService(PowerManager::class.java).isInteractive) throw FlowAborted("screen turned off")
        busyApp()?.let { throw FlowAborted("foreground changed to $it") }
        if (cocoonWindows().isEmpty()) throw FlowAborted("Cocoon is no longer on screen")
    }

    /**
     * Cocoon may show its home grid on either screen (the Thor's bottom screen runs
     * ExternalDisplayActivity): work on the window that holds the Settings tile,
     * else the focused Cocoon window.
     */
    private fun chooseHomeDisplay() {
        val cocoon = cocoonWindows()
        val window = cocoon.firstOrNull { w -> w.root?.let { findSettingsTile(it) } != null }
            ?: cocoon.firstOrNull { it.isFocused }
            ?: cocoon.firstOrNull()
        activeDisplay = window?.displayIdCompat() ?: Display.DEFAULT_DISPLAY
        Log.i(TAG, "Cocoon windows on displays ${cocoon.map { it.displayIdCompat() }}; working on display $activeDisplay")
    }

    /** True once a Cocoon Settings window shows on any display (and follows it). */
    private fun settingsOpen(): Boolean {
        val window = cocoonWindows().firstOrNull { it.title?.toString() == SETTINGS_TITLE } ?: return false
        activeDisplay = window.displayIdCompat()
        return true
    }

    private fun cocoonAtHome(): Boolean {
        val cocoon = cocoonWindows()
        return cocoon.isNotEmpty() && cocoon.all { it.title?.toString() == COCOON_HOME_TITLE }
    }

    private suspend fun openSettingsFromHome() {
        // Visible grid tiles expose their name as content description ("Settings", "DOS", "Empty grid slot").
        // A click focuses a tile and a second click on the focused tile launches it.
        val viaTile = step("click the Settings pod tile") {
            var opened = false
            repeat(2) {
                if (opened) return@repeat
                val tile = mainAppWindow()?.root?.let(::findSettingsTile) ?: return@repeat
                tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                opened = waitFor(TILE_LAUNCH_WAIT_MS) { if (settingsOpen()) true else null } == true
            }
            opened
        }
        if (viaTile) return
        Log.i(TAG, "Settings tile not visible; searching the grid with the D-pad")
        step("focus the Settings pod") {
            if (!focusSettingsPod()) throw FlowFailure("Settings pod not found on the home screen")
        }
        step("launch Settings") {
            openContextMenu() ?: throw FlowFailure("context menu did not open")
            clickText("Launch")
            waitFor(UI_TIMEOUT_MS) { if (settingsOpen()) true else null }
                ?: throw FlowFailure("Settings did not open")
        }
    }

    private suspend fun startScan() = step("start the scan of all platforms") {
        val label = waitFor(UI_TIMEOUT_MS) { root ->
            findText(root, "All platforms") ?: findNode(root) { it.text?.endsWith("platform(s) selected") == true }
        } ?: throw FlowFailure("Rescan dialog did not open")
        if (label.text?.toString() != "All platforms") throw FlowFailure("dialog preselects '${label.text}', not all platforms")
        // The start pill (the "Y ▶" button right of the label) has no click semantics; tap its bounds.
        val parent = label.parent ?: throw FlowFailure("dialog header has no parent")
        val pill = (0 until parent.childCount).mapNotNull { parent.getChild(it) }
            .firstOrNull { child -> child != label && findNode(child) { it.contentDescription?.toString() == "Y" } != null }
            ?: throw FlowFailure("start button not found")
        val bounds = Rect().also { pill.getBoundsInScreen(it) }
        if (!tap(bounds.exactCenterX(), bounds.exactCenterY())) throw FlowFailure("tap on start button was rejected")
    }

    private suspend fun awaitScanResult(): String = step("wait for the scan") {
        val done = waitFor(SCAN_TIMEOUT_MS) { findText(it, "Scan complete") }
            ?: throw FlowFailure("scan did not complete within ${SCAN_TIMEOUT_MS / 1000} s")
        val root = done.window?.root ?: mainAppWindow()?.root
        val countLabel = root?.let { r -> findNode(r) { it.text?.toString()?.trim() == "new games" } }
        val count = countLabel?.parent?.let { p ->
            val index = (0 until p.childCount).firstOrNull { p.getChild(it) == countLabel } ?: -1
            if (index > 0) p.getChild(index - 1)?.text?.toString() else null
        } ?: "?"
        Log.i(TAG, "scan complete: $count new games")
        count
    }

    private suspend fun returnHome() = step("return to the home screen") {
        repeat(3) {
            if (cocoonAtHome()) return@step
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(STEP_DELAY_MS * 3)
        }
        if (!cocoonAtHome()) throw FlowFailure("could not return to the home screen")
    }

    /** Best effort after a failure: close menus/dialogs/Settings until Cocoon's home screen shows. */
    private suspend fun backOut() {
        repeat(5) {
            if (busyApp() != null) return
            val cocoon = cocoonWindows()
            if (cocoon.isEmpty()) return
            if (!cocoonAtHome()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(STEP_DELAY_MS * 3)
                return@repeat
            }
            val menu = cocoon.firstOrNull { w -> w.root?.let { findText(it, MENU_MARKER) } != null } ?: return
            activeDisplay = menu.displayIdCompat()
            closeContextMenu()
        }
    }

    // ── Settings pod search on the home grid ───────────────────────────

    private suspend fun focusSettingsPod(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val first = readFocusedName() ?: return false
        Log.i(TAG, "focused tile: $first")
        if (first == SETTINGS_POD) return true

        var topLeft = goTopLeft()
        val cachedRow = prefs.getInt(KEY_ROW, -1)
        val cachedCol = prefs.getInt(KEY_COL, -1)
        if (cachedRow >= 0 && cachedCol >= 0) {
            repeat(cachedRow) { dpad(GLOBAL_ACTION_DPAD_DOWN) }
            repeat(cachedCol) { dpad(GLOBAL_ACTION_DPAD_RIGHT) }
            val name = readFocusedName()
            Log.i(TAG, "cached position ($cachedRow,$cachedCol): $name")
            if (name == SETTINGS_POD) return true
            topLeft = goTopLeft()
        }
        var rowStart = topLeft ?: return false
        for (row in 0 until MAX_ROWS) {
            var previous: String? = null
            var name: String? = rowStart
            var col = 0
            while (col < MAX_COLS) {
                if (name == null) return false
                if (name == SETTINGS_POD) {
                    prefs.edit().putInt(KEY_ROW, row).putInt(KEY_COL, col).apply()
                    Log.i(TAG, "Settings pod at ($row,$col)")
                    return true
                }
                if (name == previous) break
                previous = name
                dpad(GLOBAL_ACTION_DPAD_RIGHT)
                col++
                name = readFocusedName()
            }
            dpad(GLOBAL_ACTION_DPAD_DOWN)
            repeat(col + 1) { dpad(GLOBAL_ACTION_DPAD_LEFT) }
            val next = readFocusedName() ?: return false
            if (next == rowStart) return false
            rowStart = next
        }
        return false
    }

    /** Moves focus to the first tile of the first row; returns its name. */
    private suspend fun goTopLeft(): String? {
        repeat(MAX_ROWS) { dpad(GLOBAL_ACTION_DPAD_UP) }
        var previous: String? = null
        repeat(MAX_COLS / LEFT_BATCH + 1) {
            repeat(LEFT_BATCH) { dpad(GLOBAL_ACTION_DPAD_LEFT) }
            val name = readFocusedName() ?: return null
            if (name == previous) return name
            previous = name
        }
        return previous
    }

    private suspend fun dpad(action: Int) {
        performGlobalAction(action)
        delay(DPAD_DELAY_MS)
    }

    /** Opens the focused tile's context menu, reads its title, closes it again. */
    private suspend fun readFocusedName(): String? {
        val root = openContextMenu() ?: run { Log.w(TAG, "context menu did not open"); return null }
        val edit = findText(root, MENU_MARKER) ?: return null
        val menuLeft = Rect().also { edit.getBoundsInScreen(it) }.left
        val title = findNode(root) { node ->
            val text = node.text?.toString()
            if (text.isNullOrBlank() || text in MENU_ENTRIES || node.className?.toString() != "android.widget.TextView") {
                false
            } else {
                val b = Rect().also { node.getBoundsInScreen(it) }
                b.right <= menuLeft
            }
        }?.text?.toString()
        if (title == null) Log.w(TAG, "context menu has no title")
        closeContextMenu()
        return title
    }

    private suspend fun openContextMenu(): AccessibilityNodeInfo? {
        val root = mainAppWindow()?.root ?: return null
        findText(root, MENU_MARKER)?.let { return root }
        val menuButton = findNode(root) { it.contentDescription?.toString() == "Y" }?.let(::clickableAncestor)
            ?: run { Log.w(TAG, "home menu button (Y) not found"); return null }
        menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return waitFor(UI_TIMEOUT_MS) { r -> if (findText(r, MENU_MARKER) != null) r else null }
    }

    private suspend fun closeContextMenu() {
        val window = mainAppWindow() ?: return
        val root = window.root ?: return
        val windowBounds = Rect().also { window.getBoundsInScreen(it) }
        val scrim = findNode(root) { node ->
            node.isClickable && Rect().also { node.getBoundsInScreen(it) } == windowBounds
        }
        if (scrim == null || !scrim.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        waitFor(UI_TIMEOUT_MS) { r -> if (findText(r, MENU_MARKER) == null) true else null }
    }

    // ── Accessibility helpers ───────────────────────────────────────────

    /** Topmost application window of each display. */
    private fun topAppWindows(): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOfNotNull(windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION })
        }
        val all = windowsOnAllDisplays
        return (0 until all.size()).mapNotNull { i ->
            all.valueAt(i).firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        }
    }

    private fun cocoonWindows(): List<AccessibilityWindowInfo> =
        topAppWindows().filter { it.root?.packageName?.toString() == CocoonAutoImport.COCOON_PACKAGE }

    /** A non-Cocoon app (an emulator, …) in front on any display, or null. */
    private fun busyApp(): String? = topAppWindows()
        .mapNotNull { it.root?.packageName?.toString() }
        .firstOrNull { it != CocoonAutoImport.COCOON_PACKAGE }

    /** Topmost application window on the display the flow currently works on. */
    private fun mainAppWindow(): AccessibilityWindowInfo? {
        val top = topAppWindows()
        return top.firstOrNull { it.displayIdCompat() == activeDisplay } ?: top.firstOrNull()
    }

    private fun AccessibilityWindowInfo.displayIdCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) displayId else Display.DEFAULT_DISPLAY

    private suspend fun clickText(text: String) {
        val node = waitFor(UI_TIMEOUT_MS) { findText(it, text) } ?: throw FlowFailure("'$text' not found")
        val target = clickableAncestor(node) ?: throw FlowFailure("'$text' is not clickable")
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw FlowFailure("click on '$text' failed")
        delay(STEP_DELAY_MS)
    }

    /**
     * Polls the active display's window first, then every other Cocoon window; a
     * match on another display moves the flow there (Settings and its dialogs can
     * open on either screen).
     */
    private suspend fun <T : Any> waitFor(timeoutMs: Long, probe: (AccessibilityNodeInfo) -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val active = mainAppWindow()
            val candidates = listOfNotNull(active) + cocoonWindows().filter { it.displayIdCompat() != active?.displayIdCompat() }
            for (window in candidates) {
                val root = window.root ?: continue
                root.refresh()
                probe(root)?.let {
                    activeDisplay = window.displayIdCompat()
                    return it
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(POLL_MS)
        }
    }

    private suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_MS))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(activeDisplay) }
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }

    /** Content-change events keep the node cache fresh; only needed while the flow runs. */
    private fun setContentEvents(enabled: Boolean) {
        val info = serviceInfo ?: return
        val base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.eventTypes = if (enabled) base or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else base
        serviceInfo = info
    }

    companion object {
        private const val TAG = "CocoonAutoImport"
        private const val PREFS = "cocoon_auto_import"
        private const val KEY_ROW = "settings_pod_row"
        private const val KEY_COL = "settings_pod_col"

        private const val COCOON_HOME_TITLE = "Cocoon"
        private const val SETTINGS_TITLE = "Settings"
        private const val SETTINGS_POD = "Settings"
        /** Present in every tile context menu (games and pods show "Launch", platform folders "Open"). */
        private const val MENU_MARKER = "Edit"
        private val MENU_ENTRIES = setOf("Launch", "Open", "Edit", "Hide", "Unhide", "Favorite", "Unfavorite", "Remove")

        private const val MAX_ROWS = 6
        private const val MAX_COLS = 40
        private const val LEFT_BATCH = 8
        private const val DPAD_DELAY_MS = 300L
        private const val STEP_DELAY_MS = 300L
        private const val POLL_MS = 100L
        private const val TAP_MS = 60L
        private const val UI_TIMEOUT_MS = 4_000L
        private const val TILE_LAUNCH_WAIT_MS = 1_500L
        private const val FOREGROUND_SETTLE_MS = 2_000L
        private const val SCAN_TIMEOUT_MS = 5 * 60_000L
        private const val FLOW_TIMEOUT_MS = 7 * 60_000L

        @Volatile
        var instance: CocoonRescanService? = null
            private set

        private fun findSettingsTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            findNode(root) { it.isClickable && it.contentDescription?.toString() == SETTINGS_POD }

        private fun findText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
            findNode(root) { it.text?.toString() == text }

        private fun findNode(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (match(root)) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                findNode(child, match)?.let { return it }
            }
            return null
        }

        private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            while (current != null && !current.isClickable) current = current.parent
            return current
        }
    }
}
