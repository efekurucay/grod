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
 * `adb shell am broadcast -a dev.grod.EXPORT -n dev.grod/.ExportReceiver` writes files/export/usage.json
 * (docs/data.md). Guarded by the DUMP permission in the manifest, so only adb and the system can call it.
 */
class ExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val keepDays = RuleStore.get(app).rules.keepDays
        val pending = goAsync()
        thread(name = "grod-export") {
            try {
                export(app, keepDays)
            } finally {
                pending.finish()
            }
        }
    }

    private fun export(context: Context, keepDays: Int) {
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
        val dir = File(context.filesDir, "export").apply { mkdirs() }
        val tmp = File(dir, "usage.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(File(dir, "usage.json"))
    }

    private companion object {
        const val ACTION = "dev.grod.EXPORT"
    }
}
