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
            val events = ArrayList<UsageEvent>()
            val query = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
            val e = UsageEvents.Event()
            while (query.getNextEvent(e)) {
                @Suppress("DEPRECATION")
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> events.add(UsageEvent(e.packageName, e.timeStamp, true))
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> events.add(UsageEvent(e.packageName, e.timeStamp, false))
                }
            }
            Usage.toJson(now, Usage.foregroundByDay(events, now, zone))
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
