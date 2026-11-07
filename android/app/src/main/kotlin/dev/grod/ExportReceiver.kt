package dev.grod

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * docs/data.md, "Export": `dev.grod.EXPORT` writes usage.json and a copy of the logs into the
 * external export dir, where `adb pull` reaches them without run-as (release builds are not
 * debuggable); `dev.grod.EXPORT_CLEAR` deletes that dir. The private logs stay where they are.
 * Guarded by the DUMP permission in the manifest, so only adb and the system can call it.
 */
class ExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir("export") ?: return
        val keepDays = RuleStore.get(app).rules.keepDays
        val pending = goAsync()
        thread(name = "grod-export") {
            try {
                when (intent.action) {
                    ACTION_EXPORT -> {
                        writeUsage(app, keepDays, dir)
                        LogWriter.get(app).copyTo(File(dir, "log"))
                    }
                    ACTION_CLEAR -> dir.deleteRecursively()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun writeUsage(context: Context, keepDays: Int, dir: File) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val from = LocalDate.now(zone).minusDays(maxOf(keepDays, 1) - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val json = if (!Permissions.usageAccess(context)) {
            Usage.toJson(now, emptyMap(), error = "usage access not granted")
        } else {
            Usage.toJson(now, Usage.foregroundByDay(context.usageEvents(from, now), now, zone))
        }
        dir.mkdirs()
        val tmp = File(dir, "usage.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(File(dir, "usage.json"))
    }

    private companion object {
        const val ACTION_EXPORT = "dev.grod.EXPORT"
        const val ACTION_CLEAR = "dev.grod.EXPORT_CLEAR"
    }
}

/** UsageEvents.Event.SCREEN_NON_INTERACTIVE, public from API 28; minSdk is 26, where it never appears. */
private const val SCREEN_NON_INTERACTIVE = 16

/** Apps coming to the front, leaving it and the screen turning off in [from]..[to], in time order. Needs usage access. */
fun Context.usageEvents(from: Long, to: Long): List<UsageEvent> {
    val events = ArrayList<UsageEvent>()
    val query = getSystemService(UsageStatsManager::class.java).queryEvents(from, to)
    val e = UsageEvents.Event()
    while (query.getNextEvent(e)) {
        @Suppress("DEPRECATION")
        when (e.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> events.add(UsageEvent(e.packageName, e.timeStamp, UsageEvent.Kind.FOREGROUND, e.className))
            UsageEvents.Event.MOVE_TO_BACKGROUND -> events.add(UsageEvent(e.packageName, e.timeStamp, UsageEvent.Kind.BACKGROUND, e.className))
            SCREEN_NON_INTERACTIVE -> events.add(UsageEvent(e.packageName.orEmpty(), e.timeStamp, UsageEvent.Kind.SCREEN_OFF))
        }
    }
    return events
}
