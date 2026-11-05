package dev.grod

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** An app coming to the front ([foreground]) or leaving it, from UsageStatsManager events. */
data class UsageEvent(val pkg: String, val time: Long, val foreground: Boolean)

/** Foreground time per app per local day (docs/data.md, "Export"); pure, so it is unit tested. */
object Usage {
    /** [events] in time order. An app still in front at [end] counts until [end]. */
    fun foregroundByDay(events: List<UsageEvent>, end: Long, zone: ZoneId): Map<LocalDate, Map<String, Long>> {
        val days = sortedMapOf<LocalDate, MutableMap<String, Long>>()
        val since = HashMap<String, Long>()
        fun add(pkg: String, from: Long, to: Long) {
            var start = from
            while (start < to) {
                val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stop = minOf(to, midnight)
                val day = days.getOrPut(date) { sortedMapOf() }
                day[pkg] = (day[pkg] ?: 0L) + (stop - start)
                start = stop
            }
        }
        for (e in events) {
            if (e.foreground) {
                since.putIfAbsent(e.pkg, e.time)
            } else {
                since.remove(e.pkg)?.let { add(e.pkg, it, e.time) }
            }
        }
        for ((pkg, from) in since) add(pkg, from, end)
        return days
    }

    fun toJson(generated: Long, days: Map<LocalDate, Map<String, Long>>, error: String? = null): String {
        val byDay = JSONObject()
        for ((date, apps) in days) {
            val day = JSONObject()
            for ((pkg, ms) in apps) day.put(pkg, ms)
            byDay.put(date.toString(), day)
        }
        return JSONObject().put("generated", generated).put("days", byDay).putOpt("error", error).toString(2) + "\n"
    }
}
