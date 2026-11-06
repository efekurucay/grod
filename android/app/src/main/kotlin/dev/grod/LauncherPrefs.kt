package dev.grod

import android.content.Context
import android.content.Intent

/** Long-press choices; they stay on this phone (docs/rules.md, "Launcher (Android)"). */
class LauncherPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("grod.launcher", Context.MODE_PRIVATE)

    var marks: LocalMarks
        get() = LocalMarks(set(K_DISTRACTING), set(K_NOT_DISTRACTING), set(K_HIDDEN), set(K_UNHIDDEN))
        set(value) = prefs.edit()
            .putStringSet(K_DISTRACTING, value.distracting)
            .putStringSet(K_NOT_DISTRACTING, value.notDistracting)
            .putStringSet(K_HIDDEN, value.hidden)
            .putStringSet(K_UNHIDDEN, value.unhidden)
            .apply()

    private fun set(key: String): Set<String> = prefs.getStringSet(key, null).orEmpty().toSet()

    private companion object {
        const val K_DISTRACTING = "distracting"
        const val K_NOT_DISTRACTING = "notDistracting"
        const val K_HIDDEN = "hidden"
        const val K_UNHIDDEN = "unhidden"
    }
}

/**
 * Word sizes for the launcher cloud, recomputed at most once a day so words do not jump around
 * between visits, and grod's own launch counts, which feed the sizes.
 */
class CloudSizes(context: Context) {
    private val app = context.applicationContext
    private val steps = app.getSharedPreferences("grod.cloud", Context.MODE_PRIVATE)
    /** Per package: times of launches from grod in the last 14 days, comma-separated. */
    private val launches = app.getSharedPreferences("grod.launchLog", Context.MODE_PRIVATE)

    fun step(pkg: String): Int = steps.getInt(pkg, 0)

    /**
     * Apps for the cloud, or null to show all (no usage access, so use is unknown). A launch from
     * grod counts at once; screen time comes from the daily pass.
     */
    fun recent(): Set<String>? {
        val used = steps.getStringSet(K_RECENT, null) ?: return null
        return used + Launcher.recent(emptyMap(), launchTimes(), System.currentTimeMillis())
    }

    fun countLaunch(pkg: String) {
        val now = System.currentTimeMillis()
        val kept = (launchTimes()[pkg].orEmpty() + now).filter { it > now - Launcher.RECENT_MS }
        launches.edit().putString(pkg, kept.joinToString(",")).apply()
    }

    private fun launchTimes(): Map<String, List<Long>> = launches.all.mapNotNull { (k, v) ->
        (v as? String)?.let { k to it.split(',').mapNotNull(String::toLongOrNull) }
    }.toMap()

    /** Blocking (reads usage stats); call off the main thread. */
    fun refreshIfStale(pkgs: Collection<String>, distracting: Set<String>) {
        val today = java.time.LocalDate.now().toString()
        // Also stale when usage access arrived after today's pass, which then had no use to go on.
        if (steps.getString(K_DAY, null) == today && (steps.contains(K_RECENT) || !Permissions.usageAccess(app))) return
        val now = System.currentTimeMillis()
        val access = Permissions.usageAccess(app)
        fun foreground(windowMs: Long): Map<String, Long> = if (!access) emptyMap() else
            app.getSystemService(android.app.usage.UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(now - windowMs, now)
                .mapValues { it.value.totalTimeInForeground }
        val times = launchTimes()
        val counts = times.mapValues { (_, t) -> t.count { it > now - Launcher.RECENT_MS } }
        val usage = foreground(Launcher.RECENT_MS)
        val computed = Launcher.sizeSteps(pkgs, usage, counts, distracting)
        steps.edit().clear().putString(K_DAY, today).apply {
            computed.forEach { (k, v) -> putInt(k, v) }
            if (access) putStringSet(K_RECENT, Launcher.recent(usage, times, now))
        }.apply()
    }

    private companion object {
        const val K_DAY = "_day"
        const val K_RECENT = "_inCloud"
    }
}

/** Starts an app's launcher activity in its own task, the way a home screen does. */
fun Context.launch(app: AppEntry) {
    CloudSizes(this).countLaunch(app.pkg)
    startActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.pkg, app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    )
}

/** Logs a launcher choice to events-*.jsonl (docs/data.md). */
fun Context.logLauncher(pkg: String, action: String) =
    LogWriter.get(this).append("events", LogLines.event(System.currentTimeMillis(), "launcher", pkg, action), RuleStore.get(this).rules.keepDays)
