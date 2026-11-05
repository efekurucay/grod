package dev.grod

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches target apps and applies the Android part of docs/rules.md: covers nodes, and leaves the
 * feed with Back/Home. All callbacks run on the main thread.
 */
class GrodService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val engine = Engine()
    private lateinit var store: RuleStore
    private lateinit var overlays: Overlays
    private var rules: RuleSet = RuleSet.EMPTY

    /** Package filter last sent to the framework; "*" means every package (see [updateFilter]). */
    private var appliedFilter: String? = null
    private var foregroundPkg: String? = null

    /** The Back whose Home follow-up is still armed. One slot: only the front app can trigger an action. */
    private var pendingHome: PendingHome? = null
    private val homeCheck = Runnable { escalate() }
    private val recheck = Runnable { evaluate(null, recheckIfNoWindow = false) }

    private class PendingHome(val ruleId: String, val pkg: String)

    private val onStoreChanged: () -> Unit = {
        if (store.rules !== rules) {
            rules = store.rules
            cancelPendingHome()
            engine.resetSession()
            overlays.clear()
            updateFilter()
        }
    }

    override fun onServiceConnected() {
        store = RuleStore.get(this)
        overlays = Overlays(this)
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
            overlays.clear()
            updateFilter()
            if (recheckIfNoWindow) handler.postDelayed(recheck, RECHECK_MS)
            return
        }
        try {
            val pkg = root.packageName?.toString()
            if (pkg != foregroundPkg) {
                foregroundPkg = pkg
                engine.resetSession()
                cancelPendingHome()
            }
            val targets = pkg?.let(rules::rulesFor).orEmpty()
            if (pkg == null || targets.isEmpty()) {
                overlays.clear()
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
            val outcome = engine.evaluate(targets, pkg, A11yTree(root), scroll, SystemClock.uptimeMillis())
            overlays.show(outcome.covers)
            updateFilter()
            pendingHome?.let { if (it.ruleId !in outcome.onFeed) cancelPendingHome() }
            // A rule waiting for its Home follow-up escalates instead of pressing Back again.
            outcome.fire.filter { it.id != pendingHome?.ruleId }.forEach { fire(it, pkg) }
        } finally {
            A11yTree.recycle(root)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::store.isInitialized) store.removeListener(onStoreChanged)
        if (::overlays.isInitialized) overlays.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun fire(rule: Rule, pkg: String) {
        store.countFire(rule.id)
        // Engine fires a rule at most once per cooldown window, so this shows one toast per action.
        val message = listOfNotNull(rule.title, rule.why).joinToString(" — ")
        Toast.makeText(this, "grod: $message", Toast.LENGTH_SHORT).show()
        when (rule.android!!.action) {
            Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                cancelPendingHome()
                pendingHome = PendingHome(rule.id, pkg)
                handler.postDelayed(homeCheck, Engine.COOLDOWN_MS)
            }
        }
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
            if (root.packageName?.toString() == pending.pkg && engine.stillDue(rule, pending.pkg, A11yTree(root))) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                engine.markFired(rule.id, SystemClock.uptimeMillis())
            }
        } finally {
            A11yTree.recycle(root)
        }
    }

    /**
     * Events normally come only from target packages, which keeps the service idle elsewhere. While a
     * cover is on screen, the filter opens to every package: otherwise the service would never see the
     * user leave for the launcher and the cover would stay on top of it.
     */
    private fun updateFilter() {
        val targets = rules.targetPackages
        val names: Array<String>? = when {
            overlays.isShowing -> null
            // An empty list means "every package" to the framework, so listen to ourselves instead.
            targets.isEmpty() -> arrayOf(packageName)
            else -> targets.sorted().toTypedArray()
        }
        val key = names?.joinToString(",") ?: "*"
        if (key == appliedFilter) return
        // Reading and writing serviceInfo are IPC calls, hence the cached key above.
        val info = serviceInfo ?: return
        info.packageNames = names
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
    }
}
