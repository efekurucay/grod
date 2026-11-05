package dev.grod

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches target apps and applies the Android part of docs/rules.md: covers nodes, and leaves the
 * feed with Back/Home. All callbacks run on the main thread.
 */
class GrodService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val engine by lazy {
        // Remembered across restarts, so YouTube's tiles are walked from the first event.
        val prefs = getSharedPreferences("grod.scan", MODE_PRIVATE)
        val scanner = Scanner(
            learned = prefs.getStringSet(K_WALK, null).orEmpty(),
            onLearned = { prefs.edit().putStringSet(K_WALK, it).apply() },
        )
        Engine(scanner = scanner)
    }
    private lateinit var store: RuleStore
    private lateinit var covers: Overlays
    private lateinit var masks: Overlays
    private var rules: RuleSet = RuleSet.EMPTY

    /** Package filter and timeout last sent to the framework; "*" means every package (see [updateFilter]). */
    private var appliedFilter: String? = null
    private var foregroundPkg: String? = null

    /** The Back whose follow-up (Home, or Back for channel rules) is still armed. One slot: only the front app can act. */
    private var pendingHome: PendingHome? = null
    private val homeCheck = Runnable { escalate() }
    private val recheck = Runnable { evaluate(null, recheckIfNoWindow = false) }

    private class PendingHome(val ruleId: String, val pkg: String)

    /** Controls still to click after an action, until [until] (uptime). */
    private class PendingDismiss(val pkg: String, val ids: List<String>, val until: Long)

    private var pendingDismiss: PendingDismiss? = null
    private val dismissTick = Runnable { tickDismiss() }

    private val onStoreChanged: () -> Unit = {
        if (store.rules !== rules) {
            rules = store.rules
            cancelPendingHome()
            engine.resetSession()
            clearOverlays()
            updateFilter()
        }
    }

    override fun onServiceConnected() {
        store = RuleStore.get(this)
        covers = Overlays(this, touchable = true)
        masks = Overlays(this, touchable = false)
        store.addListener(onStoreChanged)
        store.checkLocal()
        store.fetch()
        rules = store.rules
        updateFilter()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        store.onEvent()
        // With no cover up, events come only from target apps, so a return to the app after leaving it
        // shows up as a new window rather than as a package change. Swiping inside a viewer emits none.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) engine.resetSession()
        evaluate(event, recheckIfNoWindow = true)
    }

    /** [event] is null for the delayed recheck after the active window could not be read. */
    private fun evaluate(event: AccessibilityEvent?, recheckIfNoWindow: Boolean) {
        handler.removeCallbacks(recheck)
        val root = rootInActiveWindow
        if (root == null) {
            // Covers swallow taps, so none may stay up while the screen cannot be read. Look once more
            // shortly, since an app in transition often has no readable window for a moment.
            clearOverlays()
            updateFilter()
            if (recheckIfNoWindow) handler.postDelayed(recheck, RECHECK_MS)
            return
        }
        try {
            val pkg = root.packageName?.toString()
            if (pkg != foregroundPkg) {
                foregroundPkg = pkg
                engine.leaveApp()
                cancelPendingHome()
            }
            val targets = pkg?.let(rules::rulesFor).orEmpty()
            if (pkg == null || targets.isEmpty()) {
                clearOverlays()
                updateFilter()
                return
            }
            val scroll = if (
                event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.packageName?.toString() == pkg
            ) {
                Scroll(event.source?.let(::boundsOf))
            } else {
                null
            }
            val now = SystemClock.uptimeMillis()
            val tree = A11yTree(root)
            val outcome = engine.evaluate(targets, pkg, tree, scroll, now)
            covers.show(outcome.covers)
            masks.show(outcome.masks)
            updateFilter()
            pendingHome?.let { if (it.ruleId !in outcome.onFeed) cancelPendingHome() }
            // A rule waiting for its follow-up escalates through it instead of firing again.
            outcome.fire.filter { it.id != pendingHome?.ruleId }.forEach { fire(it, pkg) }
        } finally {
            A11yTree.recycle(root)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::store.isInitialized) store.removeListener(onStoreChanged)
        if (::covers.isInitialized) clearOverlays()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun fire(rule: Rule, pkg: String) {
        store.countFire(rule.countAs)
        // Engine fires a rule at most once per cooldown window, so this shows one toast per action.
        val message = listOfNotNull(rule.title, rule.why).joinToString(" — ")
        Toast.makeText(this, "grod: $message", Toast.LENGTH_SHORT).show()
        when (rule.android!!.action) {
            Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.BACK -> {
                stopPlayback(rule, pkg)
                performGlobalAction(GLOBAL_ACTION_BACK)
                cancelPendingHome()
                pendingHome = PendingHome(rule.id, pkg)
                handler.postDelayed(homeCheck, Engine.COOLDOWN_MS)
            }
        }
    }

    /**
     * For rules with [AndroidRule.dismiss]: the blocked video must not keep playing. A media pause
     * stops it at once; then the miniplayer that Back leaves is closed. Playing video sends almost no
     * accessibility events, so the close is looked for on a timer rather than on events.
     */
    private fun stopPlayback(rule: Rule, pkg: String) {
        val ids = rule.android!!.dismiss.mapNotNull { m -> m.viewId?.let { "$pkg:id/$it" } }
        if (ids.isEmpty()) return
        getSystemService(AudioManager::class.java).run {
            dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        pendingDismiss = PendingDismiss(pkg, ids, SystemClock.uptimeMillis() + DISMISS_MS)
        handler.removeCallbacks(dismissTick)
        handler.postDelayed(dismissTick, DISMISS_TICK_MS)
    }

    private fun tickDismiss() {
        val pending = pendingDismiss ?: return
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val tree = A11yTree(root)
                if (root.packageName?.toString() == pending.pkg && pending.ids.any { tree.click(it, ::tap) }) {
                    pendingDismiss = null
                    return
                }
            } finally {
                A11yTree.recycle(root)
            }
        }
        if (SystemClock.uptimeMillis() < pending.until) handler.postDelayed(dismissTick, DISMISS_TICK_MS) else pendingDismiss = null
    }

    private fun tap(box: Box) {
        val path = Path().apply { moveTo(((box.left + box.right) / 2).toFloat(), ((box.top + box.bottom) / 2).toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(), null, null)
    }

    private fun clearOverlays() {
        covers.clear()
        masks.clear()
    }

    private fun cancelPendingHome() {
        pendingHome = null
        handler.removeCallbacks(homeCheck)
    }

    private fun escalate() {
        val pending = pendingHome ?: return
        pendingHome = null
        // Look the rule up again: it may have been disabled or removed since Back was pressed.
        val rule = rules.rulesFor(pending.pkg).firstOrNull { it.id == pending.ruleId } ?: return
        val root = rootInActiveWindow ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (root.packageName?.toString() == pending.pkg && engine.stillDue(rule, pending.pkg, A11yTree(root), now)) {
                val action = if (rule.android!!.escalate == Action.BACK) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
                stopPlayback(rule, pending.pkg)
                performGlobalAction(action)
                engine.markFired(rule, now)
            }
        } finally {
            A11yTree.recycle(root)
        }
    }

    /**
     * Events normally come only from target packages, which keeps the service idle elsewhere. While an
     * overlay is on screen, the filter opens to every package: otherwise the service would never see the
     * user leave for the launcher and the overlay would stay on top of it.
     * Masks sit on scrolling content, so while one is up events are batched for less time and the masks
     * keep up with the scroll; the longer batching returns when none is shown.
     */
    private fun updateFilter() {
        val targets = rules.targetPackages
        val names: Array<String>? = when {
            covers.isShowing || masks.isShowing -> null
            // An empty list means "every package" to the framework, so listen to ourselves instead.
            targets.isEmpty() -> arrayOf(packageName)
            else -> targets.sorted().toTypedArray()
        }
        val timeout = if (masks.isShowing) SCROLL_TIMEOUT_MS else IDLE_TIMEOUT_MS
        val key = (names?.joinToString(",") ?: "*") + "@" + timeout
        if (key == appliedFilter) return
        // Reading and writing serviceInfo are IPC calls, hence the cached key above.
        val info = serviceInfo ?: return
        info.packageNames = names
        info.notificationTimeout = timeout
        serviceInfo = info
        appliedFilter = key
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Box {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        A11yTree.recycle(node)
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }

    private companion object {
        const val RECHECK_MS = 300L
        const val K_WALK = "walkPackages"
        const val DISMISS_MS = 5_000L
        const val DISMISS_TICK_MS = 300L
        const val TAP_MS = 50L
        const val SCROLL_TIMEOUT_MS = 50L

        /** Matches android:notificationTimeout in res/xml/grod_service.xml. */
        const val IDLE_TIMEOUT_MS = 200L
    }
}
