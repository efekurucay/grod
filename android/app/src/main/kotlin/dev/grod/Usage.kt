package dev.grod

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/**
 * From UsageStatsManager: an app coming to the front, leaving it, or the screen turning off ([pkg]
 * unused). [cls] is the activity, where the event names one.
 */
data class UsageEvent(val pkg: String, val time: Long, val kind: Kind, val cls: String? = null) {
    enum class Kind { FOREGROUND, BACKGROUND, SCREEN_OFF }
}

/** Foreground time per app per local day (docs/data.md, "Export"); pure, so it is unit tested. */
object Usage {
    /**
     * [events] in time order. One app is in front at a time: another app coming to the front or the screen
     * turning off ends the current one, since a force-stopped app or a locked screen sends no BACKGROUND.
     * An app still in front at [end] counts until [end].
     */
    fun foregroundByDay(events: List<UsageEvent>, end: Long, zone: ZoneId): Map<LocalDate, Map<String, Long>> {
        val days = sortedMapOf<LocalDate, MutableMap<String, Long>>()
        var current: Pair<String, Long>? = null
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
        fun close(at: Long) {
            current?.let { (pkg, from) -> add(pkg, from, at) }
            current = null
        }
        for (e in events) {
            when (e.kind) {
                UsageEvent.Kind.FOREGROUND -> if (current?.first != e.pkg) {
                    close(e.time)
                    current = e.pkg to e.time
                }
                UsageEvent.Kind.BACKGROUND -> if (current?.first == e.pkg) close(e.time)
                UsageEvent.Kind.SCREEN_OFF -> close(e.time)
            }
        }
        close(end)
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
