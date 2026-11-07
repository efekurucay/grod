package dev.grod

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The Android part of the scroll pause and the opening check (docs/rules.md, "Scroll pause",
 * "Opening check" and "Android service"): counts scroll events from target apps, notices when a
 * distracting app comes to the front, and starts grod's screens when one is due. It never reads the
 * screen. All callbacks run on the main thread.
 */
class GrodService : AccessibilityService() {
    private lateinit var store: RuleStore
    private lateinit var launcherPrefs: LauncherPrefs
    private lateinit var watch: OpeningWatch
    private var rules: RuleSet = RuleSet.EMPTY

    /** Apps the opening check covers: enabled rules plus launcher.distracting, with the launcher's phone marks on top. */
    private var openingTargets: Set<String> = emptySet()

    /** Package filter last sent to the framework (see [updateFilter]). */
    private var appliedFilter: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val onStoreChanged: () -> Unit = {
        if (store.rules !== rules) {
            rules = store.rules
            updateFilter()
        }
    }

    /** Kept in a field: SharedPreferences holds its listeners weakly. */
    private val onMarksChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateFilter() }

    private val tick = object : Runnable {
        override fun run() {
            watch.tick(rules, System.currentTimeMillis())
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        store = RuleStore.get(this)
        launcherPrefs = LauncherPrefs(this)
        watch = OpeningWatch(this)
        store.addListener(onStoreChanged)
        launcherPrefs.listen(onMarksChanged)
        store.checkLocal()
        store.fetch()
        rules = store.rules
        updateFilter()
        main.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        store.onEvent()
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> watch.onFront(pkg, rules, openingTargets, now)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (watch.onScroll(pkg, rules, openingTargets, now) || OpeningCheckActivity.showing) return
                scrolled(pkg, now)
            }
        }
    }

    private fun scrolled(pkg: String, now: Long) {
        // The filter can lag a rules change by an event, so the package is checked against the rules too.
        val rule = rules.rulesFor(pkg).firstOrNull()
        if (!session.paused && pkg in openingTargets) {
            watch.windowOver(pkg, now)?.let { reason ->
                pause(pkg, rule, session.pauseNow(SystemClock.elapsedRealtime(), rules.scroll), reason)
                return
            }
        }
        if (rule == null) return
        val seconds = session.scroll(SystemClock.elapsedRealtime(), rules.scroll) ?: return
        pause(pkg, rule, seconds, null)
    }

    /** A package only in launcher.distracting has no rule; its pause shows the app's name and logs rule "launcher". */
    private fun pause(pkg: String, rule: Rule?, seconds: Int, reason: String?) {
        val ruleId = rule?.id ?: "launcher"
        if (rule != null) store.countFire(rule.id)
        LogWriter.get(this).append("events", LogLines.event(System.currentTimeMillis(), ruleId, pkg, "paused"), rules.keepDays)
        startActivity(
            Intent(this, ScrollPauseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ScrollPauseActivity.EXTRA_RULE, ruleId)
                .putExtra(ScrollPauseActivity.EXTRA_PKG, pkg)
                .putExtra(ScrollPauseActivity.EXTRA_TITLE, rule?.title ?: appLabel(pkg))
                .putExtra(ScrollPauseActivity.EXTRA_WHY, rule?.why)
                .putExtra(ScrollPauseActivity.EXTRA_REASON, reason)
                .putExtra(ScrollPauseActivity.EXTRA_SECONDS, seconds)
                .putExtra(ScrollPauseActivity.EXTRA_SCROLLED_MS, session.sessionMs),
        )
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (::store.isInitialized) store.removeListener(onStoreChanged)
        if (::launcherPrefs.isInitialized) launcherPrefs.unlisten(onMarksChanged)
        super.onDestroy()
    }

    /** Events come only from target packages, which keeps the service idle everywhere else. */
    private fun updateFilter() {
        openingTargets = Launcher.distracting(rules, launcherPrefs.marks)
        val targets = rules.targetPackages + openingTargets
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
        private const val TICK_MS = 60_000L

        /** One session for the whole phone; the pause screen marks it paused while it is up. */
        val session = ScrollSession()
    }
}
