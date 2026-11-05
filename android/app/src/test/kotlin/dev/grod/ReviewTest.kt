package dev.grod

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val sunday8pm = ReviewSettings(7, 20 * 60)

    private fun at(text: String, zone: ZoneId = istanbul) = ZonedDateTime.of(LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test
    fun nextWeeklyReminder() {
        // 2026-11-08 is a Sunday.
        assertEquals(at("2026-11-08T20:00:00"), Review.next(at("2026-11-06T02:00:00"), sunday8pm, istanbul))
        assertEquals(at("2026-11-08T20:00:00"), Review.next(at("2026-11-08T19:59:00"), sunday8pm, istanbul))
        // At or after the time, the reminder is next week's.
        assertEquals(at("2026-11-15T20:00:00"), Review.next(at("2026-11-08T20:00:00"), sunday8pm, istanbul))
        assertEquals(at("2026-11-16T09:30:00"), Review.next(at("2026-11-09T10:00:00"), ReviewSettings(1, 9 * 60 + 30), istanbul))
        assertEquals("Sundays at 20:00", sunday8pm.label)
    }

    @Test
    fun dstSafe() {
        // Berlin, Sunday 2026-03-29: 02:30 does not exist; the reminder comes when the clock jumps to 03:00.
        val skipped = ReviewSettings(7, 2 * 60 + 30)
        val due = Review.next(at("2026-03-28T12:00:00", berlin), skipped, berlin)
        assertEquals(at("2026-03-29T03:00:00", berlin), due)
        // Berlin, Sunday 2026-10-25: 02:30 happens twice. The first pass is the reminder; from the
        // first pass itself, the next instant after it is the second pass.
        val first = ZonedDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00"), berlin).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        assertEquals(first, Review.next(at("2026-10-24T12:00:00", berlin), skipped, berlin))
        assertEquals(first + 3_600_000, Review.next(first, skipped, berlin))
    }
}
