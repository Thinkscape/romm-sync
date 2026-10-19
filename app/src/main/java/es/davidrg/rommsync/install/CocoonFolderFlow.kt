package es.davidrg.rommsync.install

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import es.davidrg.rommsync.install.CocoonFolderLedger.Applied
import es.davidrg.rommsync.install.CocoonFolderLedger.Desired
import es.davidrg.rommsync.install.CocoonFolderLedger.Work
import es.davidrg.rommsync.install.CocoonRescanService.Companion.clickableAncestor
import es.davidrg.rommsync.install.CocoonRescanService.Companion.findNode
import es.davidrg.rommsync.install.CocoonRescanService.Companion.findText
import es.davidrg.rommsync.install.CocoonRescanService.FlowAborted
import es.davidrg.rommsync.install.CocoonRescanService.FlowFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Applies [CocoonFolderLedger.pendingWork] through Cocoon 3.07's UI: one
 * home-screen folder per RomM collection, as a "Custom (Regex)" smart folder.
 * Runs inside [CocoonRescanService] under the same guards as the rescan
 * (Cocoon home in front on every display, screen on and unlocked); the grid may
 * sit on either of the Thor's screens. Matched by visible text and content
 * descriptions only (Cocoon's Compose UI has no resource ids):
 *
 * - **Create**: focus a visible "Empty grid slot" → Y → New → New Folder →
 *   "Create Folder": Folder Name (Cocoon's own on-screen keyboard: clear with
 *   its backspace key, then its Paste key with the name on the clipboard, else
 *   key by key) → Smart Folder Settings → Custom (Regex) → set the pattern
 *   EditText → Y → Y. The cover is uploaded afterwards through Edit (an icon
 *   uploaded inside the Create dialog does not stick).
 * - **Update**: folder tile → Y → Edit → read back the name and "Regex: …";
 *   [CocoonFolderLedger.plan] decides what may be rewritten (user edits win) →
 *   rename / pattern / icon → Y.
 * - **Icon**: Edit Folder Artwork → Upload Icon → system picker → the cover the
 *   writer published under `$SD/Cocoon/romm-covers/` → Y. Cocoon copies it to
 *   `downloaded_media/folders/icon/folder_<id>_<uuid>.<ext>`; that file name is
 *   recorded so a later change of the icon is recognised as the user's.
 * - **Remove**: Edit → Delete Folder → Delete; our icon file is deleted too.
 *
 * Any mismatch or time-out backs out to Cocoon's home screen, counts a failure
 * for that folder (it stops being retried after [CocoonFolderLedger.MAX_FAILURES])
 * and holds further passes for a while ([CocoonCollections.isDue]).
 */
internal class CocoonFolderFlow(private val s: CocoonRescanService) {

    private val ledger = CocoonCollections.ledger(s)

    suspend fun run() {
        val work = ledger.pendingWork()
        if (work.isEmpty()) return
        val version = CocoonAutoImport.cocoonVersion(s)
        Log.i(TAG, "folder pass start (Cocoon $version): ${work.joinToString { "${label(it)} ${it.key}" }}")
        s.setContentEvents(true)
        s.chooseHomeDisplay()
        var current: String? = null
        try {
            if (version !in CocoonAutoImport.SUPPORTED_COCOON_VERSIONS) throw FlowFailure("unsupported Cocoon version $version")
            for (item in work) {
                current = item.key
                val started = SystemClock.elapsedRealtime()
                withTimeout(ITEM_TIMEOUT_MS) { apply(item) }
                Log.i(TAG, "${label(item)} ${item.key} done in ${SystemClock.elapsedRealtime() - started} ms")
            }
            CocoonCollections.clearPassFailure(s)
        } catch (e: FlowAborted) {
            Log.i(TAG, "folder pass aborted: ${e.message}; work stays pending")
            s.allowedForeignPackage = null
        } catch (e: TimeoutCancellationException) {
            fail(current, "timed out after ${ITEM_TIMEOUT_MS / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: FlowFailure) {
            fail(current, e.message ?: "failure")
        } catch (e: Exception) {
            fail(current, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            s.allowedForeignPackage = null
            s.setContentEvents(false)
        }
    }

    private suspend fun fail(key: String?, reason: String) {
        Log.w(TAG, "folder pass failed on $key ($reason); screen was:")
        describeScreen().lineSequence().filter { it.isNotEmpty() }.forEach { Log.w(TAG, it) }
        runCatching { backOut() }
        key?.let(ledger::recordFailure)
        CocoonCollections.recordPassFailure(s)
    }

    /** True while one of Cocoon's folder dialogs (or its keyboard, or a save prompt) is open: the user is busy there. */
    fun dialogOpen(): Boolean = s.cocoonWindows().any { w ->
        w.root?.let { r -> (DIALOG_MARKERS + UNSAVED_TITLE).any { findText(r, it) != null } } == true
    }

    /** Texts and descriptions on every display, for the failure log. */
    fun describeScreen(): String = buildString {
        for (window in s.topAppWindows()) {
            val root = window.root ?: continue
            append("[display ").append(with(s) { window.displayIdCompat() }).append(' ').append(root.packageName).append("]\n")
            var lines = 0
            fun walk(node: AccessibilityNodeInfo) {
                node.refresh()
                if (lines >= MAX_DUMP_LINES) return
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    val b = Rect().also { node.getBoundsInScreen(it) }
                    append("  '").append(text.take(80)).append("' '").append(desc.take(60)).append("' ")
                        .append(node.className?.toString()?.substringAfterLast('.')).append(if (node.isClickable) " c" else "")
                        .append(if (node.isFocused) " f" else "").append(' ').append(b.toShortString()).append('\n')
                    lines++
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            walk(root)
        }
    }

    private fun label(item: Work) = when (item) {
        is Work.Create -> "create"
        is Work.Update -> "update"
        is Work.Remove -> "remove"
    }

    private suspend fun apply(item: Work) = when (item) {
        is Work.Create -> create(item.key, item.desired)
        is Work.Update -> update(item.key, item.applied, item.desired)
        is Work.Remove -> remove(item.key, item.applied)
    }

    // ── Work items ──────────────────────────────────────────────────────

    private suspend fun create(key: String, desired: Desired) {
        openGridMenu()
        s.step("open New → New Folder") {
            s.clickText("New")
            s.clickText("New Folder")
            awaitText(CREATE_TITLE)
        }
        val name = setName(desired.name)
        setPattern(desired.pattern)
        saveDialog(CREATE_TITLE)
        s.step("check the new tile '$name'") {
            s.waitFor(UI_TIMEOUT_MS) { findTile(it, name) } ?: throw FlowFailure("folder tile '$name' not on the grid after Create")
        }
        val applied = Applied(name = name, requestedName = desired.name.takeIf { it != name }, pattern = desired.pattern)
        ledger.markApplied(key, applied)
        Log.i(TAG, "created folder '$name' for $key")
        val cover = desired.coverPath?.let(::File)?.takeIf { it.isFile }
        if (cover != null && desired.coverSha1 != null) {
            if (!openEditFolder(name)) throw FlowFailure("new folder '$name' not found for its icon")
            uploadIconAndSave(key, applied, cover, desired.coverSha1)
        }
    }

    private suspend fun update(key: String, applied: Applied, desired: Desired) {
        if (!openEditFolder(applied.name)) {
            Log.i(TAG, "folder '${applied.name}' of $key is gone from the grid (deleted or renamed by the user): detached")
            ledger.markDetached(key)
            return
        }
        val observedPattern = readRegex()
        val observedIcon = applied.iconFile?.let { CocoonCollections.currentIconFile(s, it) }
        val plan = CocoonFolderLedger.plan(applied, desired, observedPattern, observedIcon)
        Log.i(TAG, "update $key: pattern in Cocoon ${if (observedPattern == applied.pattern) "ours" else "changed by the user"}, " +
            "icon ${if (observedIcon == applied.iconFile) "ours" else "changed by the user ($observedIcon)"}; " +
            "rename=${plan.rename != null} pattern=${plan.pattern != null} icon=${plan.uploadCover}")
        if (plan.isNoop) {
            closeDialog(EDIT_TITLE)
            ledger.markApplied(key, plan.applied)
            return
        }
        var result = plan.applied
        plan.rename?.let { wanted ->
            val typed = setName(wanted)
            result = result.copy(name = typed, requestedName = wanted.takeIf { it != typed })
        }
        plan.pattern?.let { setPattern(it) }
        val cover = desired.coverPath?.let(::File)?.takeIf { it.isFile }
        if (plan.uploadCover && cover != null && desired.coverSha1 != null) {
            uploadIconAndSave(key, result, cover, desired.coverSha1)
        } else {
            saveDialog(EDIT_TITLE)
            ledger.markApplied(key, result)
        }
    }

    private suspend fun remove(key: String, applied: Applied) {
        if (!openEditFolder(applied.name)) {
            Log.i(TAG, "folder '${applied.name}' of $key already gone")
            deleteIconFiles(applied)
            ledger.markRemoved(key)
            return
        }
        s.step("delete folder '${applied.name}'") {
            tapText("Delete Folder")
            s.waitFor(UI_TIMEOUT_MS) { root -> findText(root, DELETE_TITLE)?.let { findNode(root) { n -> n.text?.toString() == "Delete" } } }
                ?: throw FlowFailure("Delete Folder dialog did not open")
            // The "Delete" row (last row) ignores touches and accessibility clicks; it takes the
            // controller: move the focus down onto it, then press the D-pad centre. Tried once
            // only: a centre press on a wrong row would toggle "Delete Sub-Folders"/"Remove Contents".
            delay(SCROLL_SETTLE_MS)
            repeat(DELETE_ROW_INDEX) { s.dpad(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_DOWN) }
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
            val closed = waitGone(DELETE_TITLE, UI_TIMEOUT_MS)
            if (!closed || !waitGone(EDIT_TITLE, UI_TIMEOUT_MS)) throw FlowFailure("Delete Folder did not close")
            if (s.waitFor(SHORT_WAIT_MS) { findTile(it, applied.name) } != null) throw FlowFailure("folder '${applied.name}' still on the grid")
        }
        deleteIconFiles(applied)
        ledger.markRemoved(key)
        Log.i(TAG, "deleted folder '${applied.name}' of $key")
    }

    /** Cocoon leaves a deleted folder's uploaded icon behind: remove the one(s) of our folder. */
    private fun deleteIconFiles(applied: Applied) {
        val id = applied.iconFile?.let(CocoonCollections::folderIdOf) ?: return
        CocoonCollections.iconDir(s)?.listFiles()?.filter { it.name.startsWith("folder_${id}_") }?.forEach { it.delete() }
    }

    // ── Grid ────────────────────────────────────────────────────────────

    /** The grid tile labelled [name] (its clickable tile node when it has one; image tiles are touched by bounds). */
    private fun findTile(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? =
        findNode(root) { it.contentDescription?.toString() == name }?.let { if (it.isClickable) it else clickableAncestor(it) ?: it }

    /** The empty slot least cut off by the screen edge (a clipped slot may not take a tap). */
    private fun widestEmptySlot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val slots = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isClickable && node.contentDescription?.toString() == EMPTY_SLOT) slots += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        return slots.maxByOrNull { Rect().also { b -> it.getBoundsInScreen(b) }.width() }
    }

    /**
     * Long-presses a visible empty grid slot (scrolling the grid forward when none
     * is visible): Cocoon opens its grid menu (New, Sort, Search, Edit Grid, …).
     * Any other menu means a tile was hit: close it and fail rather than act on it.
     */
    private suspend fun openGridMenu() = s.step("open the grid menu on an empty slot") {
        var slot = s.waitFor(SHORT_WAIT_MS) { widestEmptySlot(it) }
        var scrolls = 0
        while (slot == null && scrolls < MAX_GRID_SCROLLS) {
            val grid = s.mainAppWindow()?.root?.let { r -> findNode(r) { it.isScrollable } }
                ?: throw FlowFailure("no empty grid slot and no scrollable grid")
            if (!grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            scrolls++
            delay(SCROLL_SETTLE_MS)
            slot = s.waitFor(SHORT_WAIT_MS) { widestEmptySlot(it) }
        }
        longPress(slot ?: throw FlowFailure("no empty grid slot for a new folder"))
        val menu = s.waitFor(UI_TIMEOUT_MS) { r -> findText(r, GRID_MENU_MARKER) ?: findText(r, CocoonRescanService.MENU_MARKER) }
            ?: throw FlowFailure("long press opened no menu")
        if (menu.text?.toString() != GRID_MENU_MARKER) {
            s.closeContextMenu()
            throw FlowFailure("long press hit a tile, not an empty grid slot")
        }
    }

    /**
     * Opens the context menu of the folder tile [name] → Edit; false when no such
     * tile exists. A visible tile is long-pressed (a click would open the folder);
     * otherwise the grid is searched with the D-pad.
     */
    private suspend fun openEditFolder(name: String): Boolean {
        val found = s.step("open the menu of folder '$name'") {
            val tile = s.waitFor(SHORT_WAIT_MS) { findTile(it, name) }
            if (tile != null) {
                longPress(tile)
                true
            } else {
                if (!s.focusTile(name)) return@step false
                s.openContextMenu() ?: throw FlowFailure("context menu of '$name' did not open")
                true
            }
        }
        if (!found) return false
        s.step("open Edit Folder of '$name'") {
            val menu = s.waitFor(UI_TIMEOUT_MS) { r -> if (findText(r, CocoonRescanService.MENU_MARKER) != null) r else null }
                ?: throw FlowFailure("context menu did not open")
            val title = s.contextMenuTitle(menu)
            if (title != name) {
                s.closeContextMenu()
                throw FlowFailure("menu is for '$title', not '$name'")
            }
            // The first touch only moves the menu focus onto "Edit"; the second activates it.
            val edit = findText(menu, CocoonRescanService.MENU_MARKER) ?: throw FlowFailure("'Edit' not in the menu")
            for (attempt in 0 until 3) {
                tapNode(edit)
                if (s.waitFor(SHORT_WAIT_MS) { findText(it, EDIT_TITLE) } != null) return@step
            }
            throw FlowFailure("'$EDIT_TITLE' did not open")
        }
        return true
    }

    private suspend fun longPress(node: AccessibilityNodeInfo) {
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (!s.tap(b.exactCenterX(), b.exactCenterY(), LONG_PRESS_MS)) throw FlowFailure("long press on '${node.contentDescription}' rejected")
    }

    // ── Folder dialog ───────────────────────────────────────────────────

    /** Value row under [label] in the Create/Edit Folder dialog. */
    private fun dialogValue(root: AccessibilityNodeInfo, label: String): String? {
        val node = findText(root, label) ?: return null
        val parent = node.parent ?: return null
        val index = (0 until parent.childCount).firstOrNull { parent.getChild(it) == node } ?: return null
        return (index + 1 until parent.childCount).asSequence()
            .mapNotNull { parent.getChild(it)?.text?.toString() }
            .firstOrNull()
    }

    private suspend fun readRegex(): String? = s.waitFor(SHORT_WAIT_MS) { root ->
        dialogValue(root, SMART_LABEL)
    }?.takeIf { it.startsWith(REGEX_PREFIX) }?.removePrefix(REGEX_PREFIX)

    /** Types [wanted] into Folder Name; returns the name Cocoon shows afterwards. */
    private suspend fun setName(wanted: String): String = s.step("set folder name") {
        tapText(NAME_LABEL)
        val keyboard = s.waitFor(UI_TIMEOUT_MS) { root -> if (findText(root, KEY_BACKSPACE) != null && findText(root, KEY_PASTE) != null) root else null }
            ?: throw FlowFailure("Cocoon keyboard did not open")
        clearField(keyboard)
        pasteName(wanted)
        if (readField() != wanted) {
            Log.i(TAG, "paste did not take ('${readField()}'); typing key by key")
            s.mainAppWindow()?.root?.let { clearField(it) }
            typeWithKeys(wanted)
        }
        val typed = readField()?.trim().orEmpty()
        if (typed.isEmpty()) throw FlowFailure("folder name '$wanted' could not be typed")
        tapKey(KEY_CONFIRM)
        val shown = s.waitFor(UI_TIMEOUT_MS) { root -> if (findText(root, KEY_BACKSPACE) == null) dialogValue(root, NAME_LABEL) else null }
            ?: throw FlowFailure("keyboard did not close")
        if (shown != wanted) Log.i(TAG, "Cocoon shows the name '$shown' for '$wanted'")
        shown
    }

    /** The text field at the top of Cocoon's keyboard screen: the first text below the "Folder Name" title. */
    private fun fieldNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val title = findText(root, NAME_LABEL) ?: return null
        val paste = findText(root, KEY_PASTE) ?: return null
        val top = Rect().also { title.getBoundsInScreen(it) }.bottom
        val bottom = Rect().also { paste.getBoundsInScreen(it) }.top
        return findNode(root) { node ->
            if (node == title) return@findNode false
            val b = Rect().also { node.getBoundsInScreen(it) }
            node.className?.toString() == "android.widget.TextView" && b.top >= top && b.bottom <= bottom
        }
    }

    /** The name being typed; the field shows a hint when empty. */
    private fun readField(): String? = s.mainAppWindow()?.root?.also { it.refresh() }?.let(::fieldNode)?.text?.toString()
        ?.let { if (it == FIELD_HINT) "" else it }

    private suspend fun clearField(root: AccessibilityNodeInfo) {
        val length = fieldNode(root)?.text?.length ?: 0
        repeat(length + 1) { tapKey(KEY_BACKSPACE, settle = KEY_DELAY_MS) }
        delay(CocoonRescanService.STEP_DELAY_MS)
        if (!readField().isNullOrEmpty()) throw FlowFailure("could not clear the folder name ('${readField()}')")
    }

    private suspend fun pasteName(name: String) {
        val clipboard = s.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("RomM collection", name))
        tapKey(KEY_PASTE)
        s.waitFor(SHORT_WAIT_MS) { if (readField() == name) true else null }
    }

    /**
     * Fallback when Paste does not work: taps Cocoon's keys one by one. Letters
     * (shift for capitals), digits and punctuation on the "?1" layer; characters
     * no layer offers are left out (the ledger records the name Cocoon shows).
     */
    private suspend fun typeWithKeys(text: String) {
        for (c in text) {
            if (c == ' ') {
                // The space bar has no label; it carries the "Y" button hint.
                val space = s.mainAppWindow()?.root?.let { r ->
                    val paste = findText(r, KEY_PASTE) ?: return@let null
                    val pasteTop = Rect().also { paste.getBoundsInScreen(it) }.top
                    findNode(r) { it.contentDescription?.toString() == "Y" && Rect().also { b -> it.getBoundsInScreen(b) }.top > pasteTop }
                }
                if (space != null) tapNode(space).also { delay(KEY_DELAY_MS) } else Log.i(TAG, "no space key; skipped")
                continue
            }
            if (tapKeyIfPresent(c.toString())) continue
            if (c.isUpperCase() && findKey(c.lowercaseChar().toString()) != null) {
                tapKey(KEY_SHIFT)
                tapKeyIfPresent(c.toString()) || tapKeyIfPresent(c.lowercaseChar().toString())
                continue
            }
            if (findKey(KEY_SYMBOLS) != null) {
                tapKey(KEY_SYMBOLS)
                val typed = tapKeyIfPresent(c.toString())
                findKey(KEY_LETTERS)?.let { tapKey(KEY_LETTERS) }
                if (typed) continue
            }
            Log.i(TAG, "Cocoon keyboard has no key for '$c'; skipped")
        }
    }

    private fun findKey(label: String): AccessibilityNodeInfo? =
        s.mainAppWindow()?.root?.also { it.refresh() }?.let { root -> findNode(root) { it.text?.toString() == label } }

    private suspend fun tapKeyIfPresent(label: String): Boolean {
        findKey(label) ?: return false
        tapKey(label, settle = KEY_DELAY_MS)
        return true
    }

    private suspend fun tapKey(label: String, settle: Long = CocoonRescanService.STEP_DELAY_MS) {
        val key = findKey(label) ?: throw FlowFailure("keyboard key '$label' not found")
        tapNode(key)
        delay(settle)
    }

    private suspend fun setPattern(pattern: String) = s.step("set Custom (Regex) pattern") {
        tapText(SMART_LABEL)
        awaitText(MANUAL_TYPE)
        var custom = s.waitFor(SHORT_WAIT_MS) { findText(it, CUSTOM_TYPE) }
        var scrolls = 0
        while (custom == null && scrolls < 4) {
            val list = s.mainAppWindow()?.root?.let { r -> findNode(r) { it.isScrollable } } ?: break
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrolls++
            custom = s.waitFor(SHORT_WAIT_MS) { findText(it, CUSTOM_TYPE) }
        }
        custom ?: throw FlowFailure("'$CUSTOM_TYPE' not found")
        // The list may still be scrolling: re-read the row's bounds after it settles, retry once more if the tap is lost.
        var edit: AccessibilityNodeInfo? = null
        for (attempt in 0 until 3) {
            delay(SCROLL_SETTLE_MS)
            val row = s.waitFor(SHORT_WAIT_MS) { findText(it, CUSTOM_TYPE) } ?: break
            tapNode(row)
            edit = s.waitFor(UI_TIMEOUT_MS / 2) { root ->
                if (findText(root, REGEX_TITLE) != null) findNode(root) { it.className?.toString() == "android.widget.EditText" } else null
            }
            if (edit != null) break
        }
        edit ?: throw FlowFailure("regex pattern field not found")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pattern) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw FlowFailure("could not set the pattern text")
        s.waitFor(SHORT_WAIT_MS) { root -> findNode(root) { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == pattern } }
            ?: throw FlowFailure("pattern field does not show the pattern")
        pressDialogY(REGEX_TITLE)
        val shown = readRegex()
        if (shown != pattern) throw FlowFailure("Cocoon shows the pattern '$shown', expected '$pattern'")
    }

    /** Taps the Y button of the dialog titled [title] (its pill is not clickable) and waits for the dialog to close. */
    private suspend fun pressDialogY(title: String) {
        for (attempt in 0 until 3) {
            delay(SCROLL_SETTLE_MS) // a tap during Cocoon's open/close animation is dropped
            // On a retry, a dialog that closed late (Cocoon can lag) is a success: never tap the grid behind it.
            val root = s.waitFor(if (attempt == 0) UI_TIMEOUT_MS else SHORT_WAIT_MS) { r -> if (findText(r, title) != null) r else null }
                ?: if (attempt == 0) throw FlowFailure("'$title' not open") else return
            val titleTop = Rect().also { findText(root, title)!!.getBoundsInScreen(it) }.top
            val y = findNode(root) { node ->
                node.contentDescription?.toString() == "Y" &&
                    Rect().also { node.getBoundsInScreen(it) }.let { b -> b.top <= titleTop + DIALOG_HEADER_SLACK_PX }
            } ?: throw FlowFailure("Y button of '$title' not found")
            tapNode(y)
            if (waitGone(title, UI_TIMEOUT_MS / 2)) return
            Log.i(TAG, "'$title' still open after Y; retrying")
        }
        throw FlowFailure("'$title' did not close on Y")
    }

    /** True once no Cocoon window on any display shows [text] (checked twice to skip animation frames). */
    private suspend fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var absentOnce = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val present = s.cocoonWindows().any { w -> w.root?.also { it.refresh() }?.let { findText(it, text) } != null }
            if (!present && absentOnce) return true
            absentOnce = !present
            delay(CocoonRescanService.POLL_MS * 2)
        }
        return false
    }

    private suspend fun saveDialog(title: String) = s.step("save '$title'") {
        pressDialogY(title)
        s.waitFor(UI_TIMEOUT_MS) { if (s.cocoonAtHome()) true else null } ?: throw FlowFailure("not back on the home screen after '$title'")
    }

    /** Leaves the dialog titled [title] without changes through its "B ←" header pill. */
    private suspend fun closeDialog(title: String) = s.step("close '$title'") {
        val back = s.waitFor(UI_TIMEOUT_MS) { r -> if (findText(r, title) != null) headerControl(r, "B") else null }
            ?: throw FlowFailure("'$title' has no back control")
        tapNode(back)
        if (!waitGone(title, UI_TIMEOUT_MS)) throw FlowFailure("'$title' did not close")
    }

    /** A dialog header's button hint ([desc] = "B", "Back", "X", …) in the top band of the screen. */
    private fun headerControl(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? =
        findNode(root) { n -> n.contentDescription?.toString() == desc && Rect().also { n.getBoundsInScreen(it) }.top < KEYBOARD_HEADER_PX }

    // ── Folder icon ─────────────────────────────────────────────────────

    /**
     * In Edit Folder: uploads [cover] as the icon and saves the dialog. The
     * ledger gets [saved] (the dialog's other changes) as soon as Cocoon saved
     * it, then the cover fields once Cocoon's icon file is found.
     */
    private suspend fun uploadIconAndSave(key: String, saved: Applied, cover: File, coverSha1: String) {
        val iconDir = CocoonCollections.iconDir(s) ?: throw FlowFailure("Cocoon icon folder not found")
        val before = iconDir.list()?.toSet().orEmpty()
        s.step("open Edit Folder Artwork") {
            tapText(ARTWORK_LABEL)
            awaitText(UPLOAD_ICON)
        }
        s.step("Upload Icon") {
            s.allowedForeignPackage = DOCUMENTS_UI
            // A touch during the artwork screen's opening animation is dropped: retry while the picker is not up.
            for (attempt in 0 until 3) {
                delay(SCROLL_SETTLE_MS)
                if (docsWindow() != null) break
                tapText(UPLOAD_ICON)
                if (waitForDocs(PICKER_TIMEOUT_MS / 2) { it } != null) break
            }
        }
        pickInDocumentsUi(cover)
        s.step("apply the icon") { pressDialogY(ARTWORK_TITLE) }
        saveDialog(EDIT_TITLE)
        ledger.markApplied(key, saved)
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var stored: String? = null
        while (stored == null && SystemClock.elapsedRealtime() < deadline) {
            stored = iconDir.list()?.filter { it !in before && it.startsWith("folder_") }?.maxByOrNull { File(iconDir, it).lastModified() }
            if (stored == null) delay(CocoonRescanService.STEP_DELAY_MS)
        }
        stored ?: throw FlowFailure("Cocoon stored no icon file for $key")
        Log.i(TAG, "icon for $key stored as $stored")
        saved.iconFile?.takeIf { it != stored }?.let { File(iconDir, it).delete() }
        ledger.markApplied(key, saved.copy(coverSha1 = coverSha1, iconFile = stored))
    }

    private fun docsWindow(): AccessibilityWindowInfo? =
        s.topAppWindows().firstOrNull { it.root?.packageName?.toString() == DOCUMENTS_UI }

    private suspend fun <T : Any> waitForDocs(timeoutMs: Long, probe: (AccessibilityNodeInfo) -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val window = docsWindow()
            val root = window?.root
            if (root != null) {
                root.refresh()
                probe(root)?.let {
                    with(s) { s.activeDisplay = window.displayIdCompat() }
                    return it
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(CocoonRescanService.POLL_MS)
        }
    }

    /**
     * Picks [cover] in the system picker. DocumentsUI reopens Cocoon's last
     * folder, so the file is usually visible at once; otherwise: roots drawer →
     * the volume holding it → each folder down to it.
     */
    private suspend fun pickInDocumentsUi(cover: File) {
        s.allowedForeignPackage = DOCUMENTS_UI
        val volume = s.getSystemService(StorageManager::class.java)?.getStorageVolume(cover)
            ?: throw FlowFailure("no storage volume for $cover")
        val volumeDir = volume.directory ?: throw FlowFailure("volume of $cover not mounted")
        val volumeTitle = volume.getDescription(s)
        val segments = cover.parentFile!!.relativeTo(volumeDir).path.split('/').filter { it.isNotEmpty() }
        waitForDocs(PICKER_TIMEOUT_MS) { it } ?: throw FlowFailure("system file picker did not open")
        Log.i(TAG, "picker open; looking for ${cover.name} (volume '$volumeTitle', path $segments)")
        if (findDocsItem(cover.name) == null) {
            val showRoots = waitForDocs(UI_TIMEOUT_MS) { r -> findNode(r) { it.contentDescription?.toString() == "Show roots" } }
                ?: throw FlowFailure("picker has no roots button")
            activate(showRoots)
            val root = waitForDocs(UI_TIMEOUT_MS) { r -> findText(r, volumeTitle) }
                ?: throw FlowFailure("picker has no root '$volumeTitle'")
            activate(root)
            delay(PICKER_STEP_MS)
            for (segment in segments) {
                val dir = findDocsItem(segment) ?: throw FlowFailure("picker: folder '$segment' not found")
                activate(dir)
                delay(PICKER_STEP_MS)
            }
        }
        val item = findDocsItem(cover.name) ?: throw FlowFailure("picker: ${cover.name} not found")
        activate(item)
        s.waitFor(PICKER_TIMEOUT_MS) { if (docsWindow() == null) true else null } ?: throw FlowFailure("picker did not close")
        s.allowedForeignPackage = null
        delay(CocoonRescanService.STEP_DELAY_MS)
    }

    /** A picker entry titled [name] (its label, or the content description "name, size, date"), scrolling as needed. */
    private suspend fun findDocsItem(name: String): AccessibilityNodeInfo? {
        repeat(MAX_PICKER_SCROLLS) {
            val hit = waitForDocs(SHORT_WAIT_MS) { root ->
                findNode(root) { n ->
                    n.text?.toString() == name && n.className?.toString() == "android.widget.TextView" &&
                        n.viewIdResourceName?.endsWith(":id/title") != false ||
                        n.contentDescription?.toString()?.startsWith("$name, ") == true
                }
            }
            if (hit != null) return hit
            val list = docsWindow()?.root?.let { r -> findNode(r) { it.isScrollable && it.className?.toString()?.endsWith("RecyclerView") == true } }
                ?: return null
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return null
            delay(CocoonRescanService.STEP_DELAY_MS)
        }
        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Taps [text]'s bounds. Rows of Cocoon's folder dialogs only react to a real
     * touch (an accessibility click on their clickable ancestor does nothing).
     */
    private suspend fun tapText(text: String) {
        val node = s.waitFor(UI_TIMEOUT_MS) { findText(it, text) } ?: throw FlowFailure("'$text' not found")
        tapNode(node)
        delay(CocoonRescanService.STEP_DELAY_MS)
    }

    private suspend fun awaitText(text: String) {
        s.waitFor(UI_TIMEOUT_MS) { findText(it, text) } ?: throw FlowFailure("'$text' did not show")
    }

    /** Clicks the node's clickable (or focusable) ancestor; taps its bounds when no ancestor takes the click. */
    private suspend fun activate(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = clickableAncestor(node)
        if (target == null) {
            var current: AccessibilityNodeInfo? = node
            while (current != null && !current.isFocusable) current = current.parent
            target = current
        }
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapNode(node)
        delay(CocoonRescanService.STEP_DELAY_MS)
    }

    private suspend fun tapNode(node: AccessibilityNodeInfo) {
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (!s.tap(b.exactCenterX(), b.exactCenterY())) throw FlowFailure("tap on '${node.text ?: node.contentDescription}' rejected")
    }

    /** After a failure: leave the picker, then close dialogs/menus until Cocoon's home screen shows. */
    private suspend fun backOut() {
        s.allowedForeignPackage = DOCUMENTS_UI
        for (attempt in 0 until 10) {
            if (docsWindow() == null) {
                val windows = s.cocoonWindows().mapNotNull { w -> w.root?.let { w to it } }
                // Cocoon's dialogs ignore a global Back on the Thor's second screen; use their own controls:
                // "Discard" in the save prompt (never keep a half-applied change), the keyboard's "X"
                // pill, else the dialog header's "B ←" pill.
                val target = windows.firstNotNullOfOrNull { (w, r) ->
                    val node = if (findText(r, UNSAVED_TITLE) != null) {
                        findText(r, DISCARD)
                    } else if (findText(r, KEY_BACKSPACE) != null) {
                        headerControl(r, "X")
                    } else if (DIALOG_MARKERS.any { findText(r, it) != null }) {
                        headerControl(r, "B") ?: headerControl(r, "Back")
                    } else {
                        null
                    }
                    node?.let { w to it }
                }
                if (target != null) {
                    s.activeDisplay = with(s) { target.first.displayIdCompat() }
                    tapNode(target.second)
                    delay(CocoonRescanService.STEP_DELAY_MS * 3)
                    continue
                }
                if (windows.none { (_, r) -> DIALOG_MARKERS.any { findText(r, it) != null } }) break
            }
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(CocoonRescanService.STEP_DELAY_MS * 3)
        }
        s.allowedForeignPackage = null
        s.backOut()
    }

    companion object {
        private const val TAG = "CocoonCollections"
        private const val DOCUMENTS_UI = "com.android.documentsui"

        private const val EMPTY_SLOT = "Empty grid slot"
        private const val GRID_MENU_MARKER = "Edit Grid"
        private const val CREATE_TITLE = "Create Folder"
        private const val EDIT_TITLE = "Edit Folder"
        private const val DELETE_TITLE = "Delete Folder"
        private const val NAME_LABEL = "Folder Name"
        private const val SMART_LABEL = "Smart Folder Settings"
        private const val MANUAL_TYPE = "Manual (Off)"
        private const val CUSTOM_TYPE = "Custom (Regex)"
        private const val REGEX_TITLE = "Regex Pattern"
        private const val REGEX_PREFIX = "Regex: "
        private const val ARTWORK_LABEL = "Edit Folder Artwork"
        private const val ARTWORK_TITLE = "Folder artwork"
        private const val UPLOAD_ICON = "Upload Icon"

        private const val KEY_BACKSPACE = "backspace"
        private const val KEY_PASTE = "Paste"
        private const val KEY_CONFIRM = "check"
        private const val KEY_SHIFT = "shift"
        private const val FIELD_HINT = "Enter text..."
        private const val KEY_SYMBOLS = "?1"
        private const val KEY_LETTERS = "ABC"

        private const val UI_TIMEOUT_MS = CocoonRescanService.UI_TIMEOUT_MS
        private const val SHORT_WAIT_MS = 1_000L
        private const val SCROLL_SETTLE_MS = 600L
        private const val LONG_PRESS_MS = 900L
        /** D-pad downs from the Delete Folder dialog's initial focus to its "Delete" row. */
        private const val DELETE_ROW_INDEX = 3
        private const val KEY_DELAY_MS = 120L
        private const val PICKER_TIMEOUT_MS = 8_000L
        private const val PICKER_STEP_MS = 800L
        private const val ITEM_TIMEOUT_MS = 2 * 60_000L
        private const val MAX_GRID_SCROLLS = 6
        private const val MAX_PICKER_SCROLLS = 8
        private const val DIALOG_HEADER_SLACK_PX = 80
        private const val MAX_DUMP_LINES = 120
        /** Cocoon's keyboard header (its "X" cancel pill) sits above this y. */
        private const val KEYBOARD_HEADER_PX = 200
        private const val UNSAVED_TITLE = "Unsaved changes"
        private const val DISCARD = "Discard"
        /** Texts that only show while one of the folder dialogs (or Cocoon's keyboard) is open. */
        private val DIALOG_MARKERS = listOf(
            CREATE_TITLE, EDIT_TITLE, DELETE_TITLE, NAME_LABEL, SMART_LABEL, MANUAL_TYPE, CUSTOM_TYPE, REGEX_TITLE, ARTWORK_TITLE,
            UPLOAD_ICON, KEY_BACKSPACE, "New Folder",
        )
    }
}
