package dev.grod

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The Android part of the scroll pause (docs/rules.md, "Scroll pause" and "Android service"): counts
 * scroll events from target apps and starts the pause screen when one is due. It never reads the
 * screen. All callbacks run on the main thread.
 */
class GrodService : AccessibilityService() {
    private lateinit var store: RuleStore
    private var rules: RuleSet = RuleSet.EMPTY

    /** Package filter last sent to the framework (see [updateFilter]). */
    private var appliedFilter: String? = null

    private val onStoreChanged: () -> Unit = {
        if (store.rules !== rules) {
            rules = store.rules
            updateFilter()
        }
    }

    override fun onServiceConnected() {
        store = RuleStore.get(this)
        store.addListener(onStoreChanged)
        store.checkLocal()
        store.fetch()
        rules = store.rules
        updateFilter()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        store.onEvent()
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = event.packageName?.toString() ?: return
        // The filter can lag a rules change by an event, so the package is checked against the rules too.
        val rule = rules.rulesFor(pkg).firstOrNull() ?: return
        val seconds = session.scroll(SystemClock.elapsedRealtime(), rules.scroll) ?: return
        store.countFire(rule.id)
        LogWriter.get(this).append("events", LogLines.event(System.currentTimeMillis(), rule.id, pkg, "paused"), rules.keepDays)
        startActivity(
            Intent(this, ScrollPauseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ScrollPauseActivity.EXTRA_RULE, rule.id)
                .putExtra(ScrollPauseActivity.EXTRA_PKG, pkg)
                .putExtra(ScrollPauseActivity.EXTRA_TITLE, rule.title)
                .putExtra(ScrollPauseActivity.EXTRA_WHY, rule.why)
                .putExtra(ScrollPauseActivity.EXTRA_SECONDS, seconds)
                .putExtra(ScrollPauseActivity.EXTRA_SCROLLED_MS, session.sessionMs),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::store.isInitialized) store.removeListener(onStoreChanged)
        super.onDestroy()
    }

    /** Events come only from target packages, which keeps the service idle everywhere else. */
    private fun updateFilter() {
        val targets = rules.targetPackages
        // An empty list means "every package" to the framework, so listen to ourselves instead.
        val names = if (targets.isEmpty()) arrayOf(packageName) else targets.sorted().toTypedArray()
        val key = names.joinToString(",")
        if (key == appliedFilter) return
        // Reading and writing serviceInfo are IPC calls, hence the cached key above.
        val info = serviceInfo ?: return
        info.packageNames = names
        serviceInfo = info
        appliedFilter = key
    }

    companion object {
        /** One session for the whole phone; the pause screen marks it paused while it is up. */
        val session = ScrollSession()
    }
}
