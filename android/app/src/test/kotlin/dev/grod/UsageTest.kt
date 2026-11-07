package dev.grod

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.grod.UsageEvent.Kind.BACKGROUND
import dev.grod.UsageEvent.Kind.FOREGROUND
import dev.grod.UsageEvent.Kind.SCREEN_OFF

class UsageTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private fun at(text: String) = ZonedDateTime.parse("${text}+03:00").toInstant().toEpochMilli()

    @Test
    fun sumsForegroundTimePerLocalDay() {
        val ig = "com.instagram.android"
        val events = listOf(
            UsageEvent(ig, at("2026-11-05T23:50:00"), FOREGROUND),
            UsageEvent(ig, at("2026-11-06T00:20:00"), BACKGROUND),
            UsageEvent("x", at("2026-11-06T01:00:00"), BACKGROUND),
            UsageEvent(ig, at("2026-11-06T09:00:00"), FOREGROUND),
        )
        val days = Usage.foregroundByDay(events, at("2026-11-06T09:05:00"), zone)
        assertEquals(10 * 60_000L, days[LocalDate.parse("2026-11-05")]!![ig])
        assertEquals(25 * 60_000L, days[LocalDate.parse("2026-11-06")]!![ig])
        assertEquals(null, days[LocalDate.parse("2026-11-06")]!!["x"])
        val json = JSONObject(Usage.toJson(1L, days))
        assertEquals(25 * 60_000L, json.getJSONObject("days").getJSONObject("2026-11-06").getLong(ig))
    }

    @Test
    fun anAppWithoutBackgroundStopsWhenAnotherComesForwardOrTheScreenTurnsOff() {
        val yt = "com.google.android.youtube"
        val ig = "com.instagram.android"
        val events = listOf(
            UsageEvent(yt, at("2026-11-06T23:00:00"), FOREGROUND),
            // Force-stopped: no BACKGROUND for YouTube.
            UsageEvent(ig, at("2026-11-06T23:05:00"), FOREGROUND),
            UsageEvent("", at("2026-11-06T23:15:00"), SCREEN_OFF),
            UsageEvent(ig, at("2026-11-06T23:20:00"), BACKGROUND),
        )
        val day = Usage.foregroundByDay(events, at("2026-11-07T10:00:00"), zone)[LocalDate.parse("2026-11-06")]!!
        assertEquals(5 * 60_000L, day[yt])
        assertEquals(10 * 60_000L, day[ig])
    }
}
