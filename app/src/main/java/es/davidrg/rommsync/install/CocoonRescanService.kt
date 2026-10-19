package es.davidrg.rommsync.install

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
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
 *
 * The same service decides when ES-DE may be refreshed ([EsdeReloader], [evaluateEsde]).
 */
class CocoonRescanService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var evaluateJob: Job? = null
    private var flowJob: Job? = null
    private var receiverRegistered = false
    /** Display the flow currently works on (Cocoon can use both of the Thor's screens). */
    internal var activeDisplay = Display.DEFAULT_DISPLAY
    /** A non-Cocoon package the running flow opened itself (the system file picker); not a blocker. */
    internal var allowedForeignPackage: String? = null

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
        CocoonFolderDebugTrigger.register(this)
        Log.i(TAG, "service connected (Cocoon ${CocoonAutoImport.cocoonVersion(this)})")
        if (anyWorkDue()) scheduleEvaluate(FOREGROUND_SETTLE_MS)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        CocoonFolderDebugTrigger.unregister(this)
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
            trackEsdeFront()
            if (flowJob?.isActive != true && anyWorkDue()) {
                // A stale ES-DE is restarted right away, before the user starts browsing it; later
                // window events of its arrival must not push that check back.
                val esdeJustArrived = esdeFrontSince != 0L && System.currentTimeMillis() - esdeFrontSince < ESDE_FRONT_GRACE_MS
                scheduleEvaluate(if (esdeJustArrived && EsdeReloader.isDue(this)) ESDE_FRONT_CHECK_MS else FOREGROUND_SETTLE_MS)
            }
        }
    }

    /** Wall-clock time ES-DE's window came to the front (0 = not on screen). */
    @Volatile
    private var esdeFrontSince = 0L
    private var lastEsdeWait: String? = null

    /** Tracks [esdeFrontSince]; true when ES-DE's window just appeared. */
    private fun trackEsdeFront(): Boolean {
        val inFront = topAppWindows().any { it.root?.packageName?.toString() == EsdeReloader.ESDE_PACKAGE }
        if (!inFront) {
            esdeFrontSince = 0L
            return false
        }
        if (esdeFrontSince != 0L) return false
        esdeFrontSince = System.currentTimeMillis()
        return true
    }

    /** Called by [CocoonAutoImport] when installs have settled. */
    fun onRescanPending() {
        scope.launch { scheduleEvaluate(0L) }
    }

    /** Called by [CocoonCollections] when collection folder work was recorded. */
    fun onFolderWorkPending() {
        scope.launch { scheduleEvaluate(FOREGROUND_SETTLE_MS) }
    }

    /** Called by [EsdeReloader] when an ES-DE refresh became due. */
    fun onEsdeWorkPending() {
        scope.launch { scheduleEvaluate(0L) }
    }

    private fun anyWorkDue(): Boolean =
        CocoonAutoImport.isDue(this) || CocoonCollections.isDue(this) || EsdeReloader.isDue(this)

    private fun scheduleEvaluate(delayMs: Long) {
        if (flowJob?.isActive == true) return
        evaluateJob?.cancel()
        evaluateJob = scope.launch {
            delay(delayMs)
            evaluate()
        }
    }

    /**
     * Starts the rescan when it is due, else the collection folder pass. Folder
     * work waits while installs are still settling, so the rescan that brings the
     * collection's new games into Cocoon runs first.
     */
    private fun evaluate() {
        if (flowJob?.isActive == true) return
        if (evaluateEsde()) return
        val enabled = CocoonAutomation.isEnabled(this)
        val rescanDue = CocoonAutoImport.isDue(this)
        val step = CocoonAutomation.step(
            enabled = enabled,
            rescanDue = rescanDue,
            folderWorkDue = !rescanDue && CocoonCollections.isDue(this),
            installsSettling = CocoonAutoImport.isSettling(this),
        )
        when (step) {
            CocoonAutomation.Step.NONE -> {
                // A failed folder pass waits out its backoff; nothing else would wake the service then.
                if (enabled) CocoonCollections.backoffRemainingMs(this).takeIf { it > 0 }?.let { scheduleEvaluate(it + FOREGROUND_SETTLE_MS) }
                return
            }
            CocoonAutomation.Step.DISCARD_PENDING -> {
                Log.i(TAG, "Cocoon automation is off: pending rescan dropped")
                CocoonAutoImport.discardPending(this)
                return
            }
            CocoonAutomation.Step.WAIT_FOR_SETTLE -> {
                // Installs still settling: their rescan goes first (onRescanPending re-evaluates).
                scheduleEvaluate(FOREGROUND_SETTLE_MS)
                return
            }
            CocoonAutomation.Step.RESCAN, CocoonAutomation.Step.FOLDERS -> Unit
        }
        val rescan = step == CocoonAutomation.Step.RESCAN
        if (getSystemService(PowerManager::class.java).isInteractive && uncoverCocoonGrid()) {
            scheduleEvaluate(FOREGROUND_SETTLE_MS)
            return
        }
        val blocker = foregroundBlocker()
        if (blocker != null) {
            Log.i(TAG, "${if (rescan) "rescan" else "collection folder work"} pending, waiting: $blocker")
            // Closing a Cocoon dialog or menu raises no window event: look again later.
            if (blocker == DIALOG_BLOCKER) scheduleEvaluate(BLOCKED_RECHECK_MS)
            return
        }
        flowJob = scope.launch { if (rescan) runFlow() else CocoonFolderFlow(this@CocoonRescanService).run() }.also { job ->
            // Installs or folder work that arrived while the flow ran stay recorded: run again
            // once this flow is gone (isDue applies the settle and failure backoffs).
            job.invokeOnCompletion { cause ->
                if (cause == null) scope.launch { scheduleEvaluate(FOREGROUND_SETTLE_MS) }
            }
        }
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
        // Cocoon's folder dialogs and keyboard are drawn inside its home window.
        if (CocoonFolderFlow(this).dialogOpen()) return DIALOG_BLOCKER
        return null
    }

    /**
     * ES-DE Companion left on Cocoon's second screen after ES-DE went to the
     * background (it closes itself only when ES-DE quits, not when ES-DE is merely
     * left with Home): with ES-DE on no display and the companion not the window
     * the user is using, its context is stale and it covers Cocoon's home grid,
     * which would hold every Cocoon flow. Brings Cocoon's second-screen home back
     * in front of it on that display (the companion keeps running behind it and
     * comes back with ES-DE). Only when Cocoon automation is on and Cocoon is
     * the HOME app ([CocoonAutomation.mayUncoverCocoon]). Returns true when it did so.
     */
    private fun uncoverCocoonGrid(): Boolean {
        if (!CocoonAutomation.mayUncoverCocoon(CocoonAutomation.isEnabled(this), CocoonAutomation.homePackage(this))) return false
        val top = topAppWindows()
        if (top.any { it.root?.packageName?.toString() == EsdeReloader.ESDE_PACKAGE }) return false
        if (top.any { it.root?.packageName?.toString().let { p -> p != CocoonAutoImport.COCOON_PACKAGE && p != ESDE_COMPANION_PACKAGE } }) return false
        val companion = top.firstOrNull { it.root?.packageName?.toString() == ESDE_COMPANION_PACKAGE } ?: return false
        if (companion.isActive) return false
        val displayId = companion.displayIdCompat()
        Log.i(TAG, "ES-DE Companion covers display $displayId while ES-DE is not on screen: bringing Cocoon's home back there")
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setClassName(CocoonAutoImport.COCOON_PACKAGE, COCOON_SECOND_SCREEN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            true
        }.getOrElse {
            Log.w(TAG, "could not bring Cocoon's home to display $displayId: ${it.message}")
            false
        }
    }

    /**
     * Runs a due ES-DE refresh ([EsdeReloader]) unless a game or another app is in
     * front: ES-DE not on screen → edits applied (done when ES-DE is not running);
     * ES-DE idle and focused in front → [esdeRestartFlow], either within
     * [ESDE_FRONT_GRACE_MS] of ES-DE coming to the front (a stale copy resumed from
     * the background) or after a change that arrived while it was in front (5 s
     * debounce). A stale copy the user is already browsing is left alone until it
     * next comes to the front. Returns true when the restart flow took the screen.
     */
    private fun evaluateEsde(): Boolean {
        if (!EsdeReloader.isDue(this)) return false
        fun waiting(reason: String): Boolean {
            // Window events re-evaluate often; log each reason once.
            if (reason != lastEsdeWait) Log.i(TAG, "ES-DE refresh pending, waiting: $reason")
            lastEsdeWait = reason
            return false
        }
        if (!getSystemService(PowerManager::class.java).isInteractive) return waiting("screen off")
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return waiting("device locked")
        val top = topAppWindows().filterNot { isPassive(it) }.map { it to (it.root?.packageName?.toString() ?: "an unreadable window") }
        top.map { it.second }
            .firstOrNull { it != EsdeReloader.ESDE_PACKAGE && it != CocoonAutoImport.COCOON_PACKAGE }
            ?.let { return waiting("foreground app is $it") }
        val generation = EsdeReloader.pendingGeneration(this)
        val esde = top.firstOrNull { it.second == EsdeReloader.ESDE_PACKAGE }?.first
        if (esde == null) {
            esdeFrontSince = 0L
            EsdeReloader.refreshOffScreen(this, generation)
            return false
        }
        if (EsdeReloader.startedSinceChange(this)) {
            EsdeReloader.clear(this, generation, "es-de refreshed (ES-DE was started after the last change)")
            return false
        }
        val now = System.currentTimeMillis()
        val frontSince = esdeFrontSince.takeIf { it != 0L } ?: now.also { esdeFrontSince = it }
        val inGrace = now - frontSince < ESDE_FRONT_GRACE_MS
        if (EsdeReloader.lastChangeAt(this) < frontSince && !inGrace) {
            return waiting("ES-DE in use since it came to the front; restart when it next comes to the front")
        }
        // Back goes to the focused window; with Cocoon's bottom screen focused it would reach Cocoon.
        if (!esde.isFocused) {
            if (inGrace) scheduleEvaluate(ESDE_FRONT_CHECK_MS)
            return waiting("ES-DE is not the focused window")
        }
        lastEsdeWait = null
        Log.i(TAG, "ES-DE restart trigger ${now - frontSince} ms after ES-DE came to the front")
        val displayId = esde.displayIdCompat()
        flowJob = scope.launch { esdeRestartFlow(generation, displayId) }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause == null) scope.launch { scheduleEvaluate(FOREGROUND_SETTLE_MS) }
            }
        }
        return true
    }

    /**
     * Quits the idle ES-DE with Back (system view: "Back button exits app"; in a
     * gamelist or menu the first presses go back up), applies the queued edits and
     * starts ES-DE again on [displayId]. Confirmed by ES-DE's log starting over.
     * An ES-DE Companion shown on another display that closed itself with ES-DE's
     * quit is started again on its display first, so ES-DE keeps the input focus.
     */
    private suspend fun esdeRestartFlow(generation: Long, displayId: Int) {
        val started = SystemClock.elapsedRealtime()
        val headBefore = EsdeReloader.logHead(this)
        Log.i(TAG, "ES-DE idle in front on display $displayId: restarting it (generation $generation)")
        fun esdeWindow() = topAppWindows().firstOrNull { it.root?.packageName?.toString() == EsdeReloader.ESDE_PACKAGE }
        fun companionWindow() = topAppWindows().firstOrNull { it.root?.packageName?.toString() == ESDE_COMPANION_PACKAGE }
        val companionDisplay = companionWindow()?.displayIdCompat()
        var presses = 0
        while (esdeWindow() != null && presses < ESDE_MAX_BACK_PRESSES) {
            val front = busyApp(allowCompanion = true)
            if (front != null && front != EsdeReloader.ESDE_PACKAGE) {
                Log.i(TAG, "ES-DE restart aborted: foreground changed to $front; stays pending")
                return
            }
            if (esdeWindow()?.isFocused != true) {
                Log.i(TAG, "ES-DE restart aborted: ES-DE lost focus; stays pending")
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            presses++
            delay(ESDE_BACK_INTERVAL_MS)
        }
        if (esdeWindow() != null) {
            EsdeReloader.recordFailure(this, generation, "ES-DE still on screen after $presses Back presses " +
                "(is 'Back button exits app' off?)")
            return
        }
        // The window goes before the process: wait for ES-DE's clean shutdown.
        val quitBy = SystemClock.elapsedRealtime() + ESDE_QUIT_TIMEOUT_MS
        while (EsdeReloader.isRunning(this) && SystemClock.elapsedRealtime() < quitBy) delay(200)
        val applied = EsdeReloader.applyEdits(this)
        if (companionDisplay != null) {
            // "Close Companion App on ES-DE Quit" reacts to the same log line; give it a moment.
            val closedBy = SystemClock.elapsedRealtime() + COMPANION_CLOSE_WAIT_MS
            while (companionWindow() != null && SystemClock.elapsedRealtime() < closedBy) delay(200)
        }
        if (companionDisplay != null && companionWindow() == null) {
            Log.i(TAG, "ES-DE Companion closed with ES-DE; starting it on display $companionDisplay")
            runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ESDE_COMPANION_PACKAGE, ESDE_COMPANION_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(companionDisplay).toBundle())
            }.onFailure { Log.w(TAG, "ES-DE Companion start failed: ${it.message}") }
        }
        Log.i(TAG, "ES-DE quit after $presses Back presses; $applied edits applied; starting it on display $displayId")
        try {
            startActivity(EsdeReloader.launchIntent(), ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (e: Exception) {
            EsdeReloader.recordFailure(this, generation, "start: ${e.message}")
            return
        }
        val confirmBy = SystemClock.elapsedRealtime() + ESDE_START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < confirmBy) {
            val head = EsdeReloader.logHead(this)
            if (head != null && head != headBefore) {
                EsdeReloader.clear(this, generation,
                    "es-de refreshed (restart in ${SystemClock.elapsedRealtime() - started} ms, $presses Back presses)")
                return
            }
            delay(300)
        }
        EsdeReloader.recordFailure(this, generation, "ES-DE did not start again within ${ESDE_START_TIMEOUT_MS / 1000} s")
    }

    // ── The flow ────────────────────────────────────────────────────────

    internal class FlowFailure(message: String) : Exception(message)
    internal class FlowAborted(message: String) : Exception(message)

    private suspend fun runFlow() {
        // Only installs up to this generation are covered by this flow's rescan.
        val generation = CocoonAutoImport.pendingGeneration(this)
        val lastInstallAt = CocoonAutoImport.lastInstallAt(this)
        val started = SystemClock.elapsedRealtime()
        val version = CocoonAutoImport.cocoonVersion(this)
        Log.i(TAG, "flow start (Cocoon $version, generation $generation, last install ${System.currentTimeMillis() - lastInstallAt} ms ago)")
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
                    "(${System.currentTimeMillis() - lastInstallAt} ms after the last install it covers)")
            }
            CocoonAutoImport.clearPending(this, generation)
            CocoonAutoImport.cancelFallback(this)
        } catch (e: FlowAborted) {
            Log.i(TAG, "flow aborted: ${e.message}; rescan stays pending")
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                fail(generation, "timed out after ${FLOW_TIMEOUT_MS / 1000} s")
            } else {
                throw e
            }
        } catch (e: FlowFailure) {
            fail(generation, e.message ?: "failure")
        } catch (e: Exception) {
            fail(generation, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            setContentEvents(false)
        }
    }

    private suspend fun fail(generation: Long, reason: String) {
        Log.w(TAG, "flow failed ($reason); backing out to Cocoon home and posting the manual-rescan notification")
        runCatching { backOut() }
        // Keep the marker (a later install or the backoff expiry retries); never drop it here.
        CocoonAutoImport.recordFailure(this, generation)
        CocoonAutoImport.postFallback(this)
    }

    internal suspend fun <T> step(name: String, block: suspend () -> T): T {
        ensureCocoonInFront()
        Log.i(TAG, "step: $name")
        return block()
    }

    /** Stops (without touching the UI) when the user left Cocoon or the screen went off. */
    internal fun ensureCocoonInFront() {
        if (!getSystemService(PowerManager::class.java).isInteractive) throw FlowAborted("screen turned off")
        busyApp()?.let { throw FlowAborted("foreground changed to $it") }
        if (cocoonWindows().isEmpty()) throw FlowAborted("Cocoon is no longer on screen")
    }

    /**
     * Cocoon may show its home grid on either screen (the Thor's bottom screen runs
     * ExternalDisplayActivity): work on the window that holds the Settings tile,
     * else the one with the grid's "Y" hint, else the focused Cocoon window.
     */
    internal fun chooseHomeDisplay() {
        val cocoon = cocoonWindows()
        val window = cocoon.firstOrNull { w -> w.root?.let { findSettingsTile(it) } != null }
            ?: cocoon.firstOrNull { w -> w.root?.let(::hasGridBar) == true }
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

    internal fun cocoonAtHome(): Boolean {
        val cocoon = cocoonWindows()
        return cocoon.isNotEmpty() && cocoon.all { it.title?.toString() == COCOON_HOME_TITLE }
    }

    private suspend fun openSettingsFromHome() {
        if (step("click the Settings pod tile") { clickSettingsTile() }) return
        // Grid scrolled to a later page: drag it back toward its start (the pod sits in the second
        // column); a drag also rebuilds Cocoon's grid tree as clickable tiles.
        val viaDrag = step("drag the grid back to the Settings pod") {
            var previous: List<Pair<String, Rect>>? = null
            var opened = false
            for (drag in 0 until MAX_GRID_PAGES) {
                val tiles = dragGrid(towardStart = true)
                if (clickSettingsTile()) {
                    Log.i(TAG, "Settings pod reached after ${drag + 1} drags")
                    opened = true
                    break
                }
                if (tiles == previous) break
                previous = tiles
            }
            opened
        }
        if (viaDrag) return
        // Left out of the grid's tree: move the grid focus onto it with the D-pad (read back from
        // the hero view) and launch it from its menu (pods offer "Top Screen" / "Bottom Screen").
        step("focus the Settings pod") {
            if (!focusGridTile(SETTINGS_POD)) throw FlowFailure("Settings pod not found on the home grid")
        }
        step("launch Settings") {
            val menu = openFocusedMenu() ?: throw FlowFailure("context menu did not open")
            if (contextMenuTitle(menu) != SETTINGS_POD) {
                closeContextMenu()
                throw FlowFailure("menu is for '${contextMenuTitle(menu)}', not the Settings pod")
            }
            // Launch → "Top Screen" / "Bottom Screen" submenu → Top Screen. Each entry takes two
            // touches: the first moves the menu focus onto it, the second activates it.
            for (touch in 0 until 6) {
                if (settingsOpen()) return@step
                val root = mainAppWindow()?.root?.also { it.refresh() } ?: break
                val entry = findText(root, "Top Screen") ?: findText(root, "Launch") ?: break
                val b = Rect().also { entry.getBoundsInScreen(it) }
                tap(b.exactCenterX(), b.exactCenterY())
                delay(STEP_DELAY_MS * 2)
            }
            waitFor(UI_TIMEOUT_MS) { if (settingsOpen()) true else null } ?: throw FlowFailure("Settings did not open")
        }
    }

    /** Visible grid tiles expose their name as content description ("Settings", "DOS", "Empty grid slot").
     *  A click focuses a tile and a second click on the focused tile launches it. */
    private suspend fun clickSettingsTile(): Boolean {
        repeat(2) {
            val tile = gridWindow()?.root?.let(::findSettingsTile) ?: return false
            tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (waitFor(TILE_LAUNCH_WAIT_MS) { if (settingsOpen()) true else null } == true) return true
        }
        return false
    }

    // ── Home grid ───────────────────────────────────────────────────────

    /**
     * The Cocoon window that shows the home grid: the one with the bottom bar's
     * "Y" hint (the other screen shows the hero view). Makes it the active display.
     */
    internal fun gridWindow(): AccessibilityWindowInfo? {
        val window = cocoonWindows().firstOrNull { w -> w.root?.let(::hasGridBar) == true } ?: return null
        activeDisplay = window.displayIdCompat()
        return window
    }

    /** Visible clickable grid tiles of [root], left to right, top to bottom (only used to see whether a drag moved the grid). */
    private fun gridTiles(root: AccessibilityNodeInfo): List<Pair<String, Rect>> {
        val tiles = mutableListOf<Pair<String, Rect>>()
        root.refresh()
        fun collect(node: AccessibilityNodeInfo) {
            val desc = node.contentDescription?.toString()
            if (node.isClickable && !desc.isNullOrEmpty() && desc !in BAR_HINTS) {
                tiles += desc to Rect().also { node.getBoundsInScreen(it) }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        return tiles.sortedWith(compareBy({ it.second.left }, { it.second.top }))
    }

    /**
     * Drags the grid by about half a screen (slow, so it does not fling past tiles).
     * [towardStart] drags the content right, revealing earlier tiles. Returns the
     * visible tiles once the grid settled.
     */
    private suspend fun dragGrid(towardStart: Boolean): List<Pair<String, Rect>> {
        val window = gridWindow() ?: throw FlowFailure("Cocoon's home grid is not on screen")
        val b = Rect().also { window.getBoundsInScreen(it) }
        val y = b.top + b.height() * GRID_DRAG_Y
        val left = b.left + b.width() * GRID_DRAG_FROM
        val right = b.left + b.width() * (1 - GRID_DRAG_FROM)
        val (from, to) = if (towardStart) left to right else right to left
        if (!swipe(from, y, to, y, GRID_DRAG_MS)) throw FlowFailure("grid drag was rejected")
        delay(GRID_SETTLE_MS)
        val tiles = gridWindow()?.root?.let(::gridTiles).orEmpty()
        Log.i(TAG, "grid drag ${if (towardStart) "back" else "forward"}: ${tiles.size} tiles, " +
            "${tiles.firstOrNull()?.let { "'${it.first}'@${it.second.left}" }} … ${tiles.lastOrNull()?.let { "'${it.first}'@${it.second.left}" }}")
        return tiles
    }

    /**
     * An empty grid slot that takes a touch: on the visible page, else after
     * dragging the grid toward its end (Cocoon keeps empty slots after the last
     * tile). Empty slots are always exposed as buttons, whatever state the rest of
     * the grid's accessibility tree is in.
     */
    internal suspend fun emptyGridSlot(): AccessibilityNodeInfo? {
        var previous: List<Pair<String, Rect>>? = null
        repeat(MAX_GRID_PAGES) {
            visibleGridTile(EMPTY_SLOT)?.let { return it }
            val tiles = dragGrid(towardStart = false)
            if (tiles == previous) return visibleGridTile(EMPTY_SLOT)
            previous = tiles
        }
        return null
    }

    /**
     * The clickable grid tile named [name] if it is on screen and wide enough to
     * take a touch. Cocoon's grid tree is only trustworthy for clickable tiles:
     * after it returns from another screen it often exposes stale, non-clickable
     * images and leaves folders out entirely, so a miss here proves nothing (see
     * [focusGridTile]).
     */
    internal fun visibleGridTile(name: String): AccessibilityNodeInfo? {
        val window = gridWindow() ?: return null
        val root = window.root?.also { it.refresh() } ?: return null
        val tile = findNode(root) { it.isClickable && it.contentDescription?.toString() == name } ?: return null
        val b = Rect().also { tile.getBoundsInScreen(it) }
        return tile.takeIf { b.width() >= MIN_TILE_WIDTH_PX }
    }

    /**
     * Name of the grid tile that has the focus, as Cocoon's hero view on the other
     * screen shows it: the large hero image's description (platform folders, games)
     * or the title above the subtitle ("Folder", "Pod", …) for icon tiles; null when
     * the focus is on an empty slot. Throws when there is no hero view to read.
     */
    private fun heroTitle(): String? {
        val grid = gridWindow() ?: throw FlowFailure("Cocoon's home grid is not on screen")
        val hero = cocoonWindows().firstOrNull { it.displayIdCompat() != grid.displayIdCompat() }?.root
            ?: throw FlowFailure("no Cocoon hero view on the other screen to read the focused tile from")
        activeDisplay = grid.displayIdCompat()
        hero.refresh()
        val bounds = Rect().also { hero.getBoundsInScreen(it) }
        var image: String? = null
        val texts = mutableListOf<Pair<String, Rect>>()
        fun collect(node: AccessibilityNodeInfo) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            val desc = node.contentDescription?.toString()
            if (image == null && !desc.isNullOrBlank() && b.width() > bounds.width() / 3) image = desc
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && node.className?.toString() == "android.widget.TextView" && b.top > bounds.centerY()) {
                texts += text to b
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(hero)
        return texts.minByOrNull { it.second.top }?.first ?: image
    }

    /**
     * Moves the grid's input focus onto the tile named [name] with D-pad actions,
     * reading each focused tile's name from the hero view. Walks from the grid's
     * first column, column by column (top to bottom), until the tile, or until
     * [EMPTY_COLUMNS_AT_END] columns in a row hold nothing (the grid's end). False
     * only after that walk, so callers may act on absence. The grid's display gets
     * the input focus first (a drag on it), so the D-pad reaches it.
     */
    internal suspend fun focusGridTile(name: String): Boolean {
        dragGrid(towardStart = true)
        delay(STEP_DELAY_MS)
        heroTitle()?.let { if (it == name) return true }
        // To the first column: up to the top row, then left until the focus stops moving. Cocoon drops
        // the key after a bump against the grid's edge, so wait after each phase that ends in one.
        repeat(GRID_ROWS) { dpad(GLOBAL_ACTION_DPAD_UP) }
        delay(EDGE_SETTLE_MS)
        var title = heroTitle()
        for (step in 0 until MAX_COLS) {
            val next = move(GLOBAL_ACTION_DPAD_LEFT, title)
            Log.d(TAG, "grid walk to start: $next")
            if (next == title) break
            title = next
        }
        delay(EDGE_SETTLE_MS)
        var emptyColumns = 0
        val seen = mutableSetOf<String>()
        var current = heroTitle()
        for (col in 0 until MAX_COLS) {
            var columnEmpty = true
            for (row in 0 until GRID_ROWS) {
                if (row > 0) current = move(GLOBAL_ACTION_DPAD_DOWN, current)
                if (current == name) {
                    Log.i(TAG, "'$name' focused at column $col, row $row")
                    return true
                }
                current?.let {
                    columnEmpty = false
                    seen += it
                }
                Log.d(TAG, "grid walk ($col,$row): $current")
            }
            emptyColumns = if (columnEmpty) emptyColumns + 1 else 0
            if (emptyColumns >= EMPTY_COLUMNS_AT_END) {
                // Absence is acted on (a folder is taken as deleted by the user): only trust a walk that read the grid.
                if (SETTINGS_POD !in seen) throw FlowFailure("D-pad walk did not read the grid (saw ${seen.size} tiles, no Settings pod)")
                Log.i(TAG, "'$name' not on the grid (${seen.size} tiles in $col columns)")
                return false
            }
            repeat(GRID_ROWS - 1) { current = move(GLOBAL_ACTION_DPAD_UP, current) }
            current = move(GLOBAL_ACTION_DPAD_RIGHT, current)
        }
        throw FlowFailure("the home grid did not end within $MAX_COLS columns")
    }

    /**
     * One D-pad step on the grid; returns the focused tile's name once the hero view
     * shows a different one than [before] (its update can lag the focus by a few
     * hundred ms), or the same name after [HERO_SETTLE_MS] (an edge, or two empty slots).
     */
    private suspend fun move(action: Int, before: String?): String? {
        performGlobalAction(action)
        val deadline = SystemClock.elapsedRealtime() + HERO_SETTLE_MS
        delay(DPAD_DELAY_MS)
        while (true) {
            val now = heroTitle()
            if (SystemClock.elapsedRealtime() >= deadline) return now
            if (now != before) {
                // The hero cross-fades (a subtitle alone can show for a frame): take the name once it holds.
                delay(HERO_STABLE_MS)
                val settled = heroTitle()
                if (settled == now) return now
            }
            delay(POLL_MS)
        }
    }

    /** Opens the focused tile's context menu (or the grid menu on an empty slot) with the grid's "Y" hint. */
    internal suspend fun openFocusedMenu(): AccessibilityNodeInfo? {
        val root = gridWindow()?.root ?: throw FlowFailure("Cocoon's home grid is not on screen")
        val windowBottom = Rect().also { root.getBoundsInScreen(it) }.bottom
        val y = findNode(root) { n ->
            n.contentDescription?.toString() == "Y" && Rect().also { n.getBoundsInScreen(it) }.top > windowBottom - GRID_BAR_HEIGHT_PX
        } ?: throw FlowFailure("the grid's Y hint is not on screen")
        val b = Rect().also { y.getBoundsInScreen(it) }
        if (!tap(b.exactCenterX(), b.exactCenterY())) throw FlowFailure("tap on the Y hint rejected")
        return waitFor(UI_TIMEOUT_MS) { r -> if (menuOpen(r)) r else null }
    }

    private fun hasGridBar(root: AccessibilityNodeInfo): Boolean {
        val windowBottom = Rect().also { root.getBoundsInScreen(it) }.bottom
        return findNode(root) { n ->
            n.contentDescription?.toString() == "Y" && Rect().also { n.getBoundsInScreen(it) }.top > windowBottom - GRID_BAR_HEIGHT_PX
        } != null
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

    internal suspend fun returnHome() = step("return to the home screen") {
        repeat(3) {
            if (cocoonAtHome()) return@step
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(STEP_DELAY_MS * 3)
        }
        if (!cocoonAtHome()) throw FlowFailure("could not return to the home screen")
    }

    /**
     * Best effort after a failure: closes Cocoon's prompts, keyboard, menus, folder
     * and artwork screens and Settings on every display until only the home grid
     * (and the hero view) remain. Uses Cocoon's own on-screen controls ("Discard",
     * the keyboard's "X" pill, a header's "B ←" pill, the menu scrim): a global
     * Back only reaches the display that has the input focus.
     */
    internal suspend fun backOut() {
        repeat(BACK_OUT_STEPS) {
            if (busyApp() != null) return
            val cocoon = cocoonWindows().mapNotNull { w -> w.root?.let { w to it } }
            if (cocoon.isEmpty()) return
            val target = cocoon.firstNotNullOfOrNull { (w, r) ->
                when {
                    findText(r, UNSAVED_TITLE) != null -> findText(r, DISCARD)
                    findText(r, KEY_BACKSPACE) != null -> headerControl(r, "X")
                    menuOpen(r) -> null.also {
                        activeDisplay = w.displayIdCompat()
                        closeContextMenu()
                    }
                    else -> headerControl(r, "B") ?: headerControl(r, "Back")
                }?.let { w to it }
            }
            if (target != null) {
                activeDisplay = target.first.displayIdCompat()
                val b = Rect().also { target.second.getBoundsInScreen(it) }
                tap(b.exactCenterX(), b.exactCenterY())
                delay(STEP_DELAY_MS * 3)
                return@repeat
            }
            if (cocoonAtHome()) {
                if (cocoonWindows().none { w -> w.root?.let(::menuOpen) == true }) return
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(STEP_DELAY_MS * 3)
            }
        }
    }

    /** A screen header's button hint ([desc] = "B", "Back", "X", …) in the top band of its window. */
    internal fun headerControl(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val top = Rect().also { root.getBoundsInScreen(it) }.top
        return findNode(root) { n -> n.contentDescription?.toString() == desc && Rect().also { n.getBoundsInScreen(it) }.top < top + HEADER_BAND_PX }
    }

    /** Long-presses [tile] (opens its context menu, or the grid menu on an empty slot). */
    internal suspend fun longPress(tile: AccessibilityNodeInfo) {
        val b = Rect().also { tile.getBoundsInScreen(it) }
        if (!tap(b.exactCenterX(), b.exactCenterY(), LONG_PRESS_MS)) throw FlowFailure("long press on '${tile.contentDescription}' rejected")
    }

    internal suspend fun dpad(action: Int) {
        performGlobalAction(action)
        delay(DPAD_DELAY_MS)
    }

    /** Name of the tile whose context menu is open in [root] (the text left of the menu entries). */
    internal fun contextMenuTitle(root: AccessibilityNodeInfo): String? {
        val edit = findNode(root) { it.text?.toString() in MENU_ENTRIES } ?: return null
        val menuLeft = Rect().also { edit.getBoundsInScreen(it) }.left
        return findNode(root) { node ->
            val text = node.text?.toString()
            if (text.isNullOrBlank() || text in MENU_ENTRIES || node.className?.toString() != "android.widget.TextView") {
                false
            } else {
                val b = Rect().also { node.getBoundsInScreen(it) }
                b.right <= menuLeft
            }
        }?.text?.toString()
    }

    internal suspend fun closeContextMenu() {
        val window = mainAppWindow() ?: return
        val root = window.root ?: return
        val windowBounds = Rect().also { window.getBoundsInScreen(it) }
        val scrim = findNode(root) { node ->
            node.isClickable && Rect().also { node.getBoundsInScreen(it) } == windowBounds
        }
        if (scrim == null || !scrim.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        waitFor(UI_TIMEOUT_MS) { r -> if (!menuOpen(r)) true else null }
    }

    // ── Accessibility helpers ───────────────────────────────────────────

    /** Topmost application window of each display. */
    internal fun topAppWindows(): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOfNotNull(windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION })
        }
        val all = windowsOnAllDisplays
        return (0 until all.size()).mapNotNull { i ->
            all.valueAt(i).firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        }
    }

    internal fun cocoonWindows(): List<AccessibilityWindowInfo> =
        topAppWindows().filter { it.root?.packageName?.toString() == CocoonAutoImport.COCOON_PACKAGE }

    /**
     * A non-Cocoon app (an emulator, …) in front on any display, or null. ES-DE Companion
     * counts too (it covers Cocoon's grid) unless [allowCompanion] and it is passive.
     */
    internal fun busyApp(allowCompanion: Boolean = false): String? = topAppWindows()
        .filterNot { allowCompanion && isPassive(it) }
        .mapNotNull { it.root?.packageName?.toString() }
        .firstOrNull { it != CocoonAutoImport.COCOON_PACKAGE && it != allowedForeignPackage }

    /**
     * ES-DE Companion showing ES-DE's context on the other screen: no blocker for the ES-DE
     * restart unless it is the active window (global Back and D-pad actions would reach it).
     * isFocused is per display on the Thor, so it is true for the companion too.
     */
    private fun isPassive(window: AccessibilityWindowInfo): Boolean =
        !window.isActive && window.root?.packageName?.toString() == ESDE_COMPANION_PACKAGE

    /** Topmost application window on the display the flow currently works on. */
    internal fun mainAppWindow(): AccessibilityWindowInfo? {
        val top = topAppWindows()
        return top.firstOrNull { it.displayIdCompat() == activeDisplay } ?: top.firstOrNull()
    }

    internal fun AccessibilityWindowInfo.displayIdCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) displayId else Display.DEFAULT_DISPLAY

    internal suspend fun clickText(text: String) {
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
    internal suspend fun <T : Any> waitFor(timeoutMs: Long, probe: (AccessibilityNodeInfo) -> T?): T? {
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

    /** Touch at ([x], [y]) on the active display; a [durationMs] of ~1 s is a long press. */
    internal suspend fun tap(x: Float, y: Float, durationMs: Long = TAP_MS): Boolean =
        gesture(Path().apply { moveTo(x, y) }, durationMs)

    /** A straight drag on the active display. */
    internal suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
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
    internal fun setContentEvents(enabled: Boolean) {
        val info = serviceInfo ?: return
        val base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.eventTypes = if (enabled) base or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else base
        serviceInfo = info
    }

    companion object {
        private const val TAG = "CocoonAutoImport"
        private const val ESDE_MAX_BACK_PRESSES = 6
        private const val ESDE_BACK_INTERVAL_MS = 700L
        private const val ESDE_QUIT_TIMEOUT_MS = 5_000L
        private const val ESDE_START_TIMEOUT_MS = 30_000L
        /** Grid pages walked at most when searching (each drag moves about two columns). */
        private const val MAX_GRID_PAGES = 20
        /** Rows of Cocoon's home grid on the Thor. */
        private const val GRID_ROWS = 3
        private const val MAX_COLS = 60
        /** This many fully empty columns in a row end the D-pad walk (Cocoon keeps empty slots after the last tile). */
        private const val EMPTY_COLUMNS_AT_END = 3
        /** Cocoon ignores the next key for a moment after a bump against the grid's edge. */
        private const val EDGE_SETTLE_MS = 1_200L
        /** Longest wait for the hero view to show the newly focused tile after a D-pad step. */
        private const val HERO_SETTLE_MS = 1_500L
        private const val HERO_STABLE_MS = 200L
        internal const val EMPTY_SLOT = "Empty grid slot"
        private const val GRID_DRAG_Y = 0.42f
        private const val GRID_DRAG_FROM = 0.15f
        private const val GRID_DRAG_MS = 700L
        private const val GRID_SETTLE_MS = 700L
        /** Narrower tiles are clipped by the screen edge and may not take a touch. */
        private const val MIN_TILE_WIDTH_PX = 200
        /** The grid's bottom bar (X/Y hints, dock) sits in this band above the window's bottom. */
        private const val GRID_BAR_HEIGHT_PX = 200
        private const val LONG_PRESS_MS = 900L
        private val BAR_HINTS = setOf("X", "Y", "Select")
        /** A stale ES-DE is restarted only this soon after it comes to the front (before the user browses). */
        private const val ESDE_FRONT_GRACE_MS = 1_500L
        private const val ESDE_FRONT_CHECK_MS = 150L

        internal const val COCOON_HOME_TITLE = "Cocoon"
        internal const val ESDE_COMPANION_PACKAGE = "com.esde.companion"
        private const val ESDE_COMPANION_ACTIVITY = "com.esde.companion.ui.MainActivity"
        private const val COCOON_SECOND_SCREEN_ACTIVITY = "rip.moth.cocoonshell.ExternalDisplayActivity"
        private const val COMPANION_CLOSE_WAIT_MS = 1_500L
        private const val SETTINGS_TITLE = "Settings"
        private const val SETTINGS_POD = "Settings"
        /** Present in every tile context menu (games and pods show "Launch", platform folders "Open"). */
        internal const val MENU_MARKER = "Edit"
        /** Context-menu entries (pods offer "Top Screen"/"Bottom Screen" instead of Launch/Edit). */
        internal val MENU_ENTRIES = setOf(
            "Launch", "Open", "Edit", "Hide", "Unhide", "Favorite", "Unfavorite", "Remove", "Top Screen", "Bottom Screen",
        )

        /** A tile context menu or the grid menu is open in [root]. */
        internal fun menuOpen(root: AccessibilityNodeInfo): Boolean =
            findNode(root) { n -> n.text?.toString().let { it in MENU_ENTRIES || it == GRID_MENU_MARKER } } != null
        /** Last entry of the grid menu a long press on an empty slot opens (Apps, New, Sort, Search, Edit Grid, …). */
        internal const val GRID_MENU_MARKER = "Edit Grid"
        internal const val UNSAVED_TITLE = "Unsaved changes"
        internal const val DISCARD = "Discard"
        internal const val KEY_BACKSPACE = "backspace"
        /** Screen headers (the "B ←" pill, the keyboard's "X") sit in this band below a window's top. */
        private const val HEADER_BAND_PX = 200
        private const val BACK_OUT_STEPS = 10

        private const val DPAD_DELAY_MS = 300L
        internal const val POLL_MS = 100L
        internal const val STEP_DELAY_MS = 300L
        private const val TAP_MS = 60L
        internal const val UI_TIMEOUT_MS = 4_000L
        private const val TILE_LAUNCH_WAIT_MS = 1_500L
        private const val FOREGROUND_SETTLE_MS = 2_000L
        private const val DIALOG_BLOCKER = "a Cocoon dialog or menu is open"
        private const val BLOCKED_RECHECK_MS = 30_000L
        private const val SCAN_TIMEOUT_MS = 5 * 60_000L
        private const val FLOW_TIMEOUT_MS = 7 * 60_000L

        @Volatile
        var instance: CocoonRescanService? = null
            private set

        private fun findSettingsTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            findNode(root) { it.isClickable && it.contentDescription?.toString() == SETTINGS_POD }

        internal fun findText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
            findNode(root) { it.text?.toString() == text }

        internal fun findNode(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (match(root)) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                findNode(child, match)?.let { return it }
            }
            return null
        }

        internal fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            while (current != null && !current.isClickable) current = current.parent
            return current
        }
    }
}
