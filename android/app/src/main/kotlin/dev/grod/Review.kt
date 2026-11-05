package dev.grod

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The top-level `review` settings (docs/rules.md, "Weekly review"): ISO [weekday] and local [minute] of the day. */
data class ReviewSettings(val weekday: Int, val minute: Int) {
    /** "Sundays at 20:00". */
    val label: String
        get() = DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s at %02d:%02d".format(minute / 60, minute % 60)
}

object Review {
    /** First reminder strictly after [now], at the local wall time; DST handled as in [wallInstants]. */
    fun next(now: Long, review: ReviewSettings, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (-1L..7L).map(today::plusDays)
            .filter { it.dayOfWeek.value == review.weekday }
            .flatMap { wallInstants(it, review.minute, zone) }
            .filter { it > now }
            .min()
    }
}
