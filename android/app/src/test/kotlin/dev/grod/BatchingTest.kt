package dev.grod

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchingTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = istanbul) = ZonedDateTime.of(
        java.time.LocalDateTime.parse(text), zone,
    ).toInstant().toEpochMilli()

    private fun local(ms: Long, zone: ZoneId = istanbul) = java.time.Instant.ofEpochMilli(ms).atZone(zone)

    private fun every(minutes: Int) = NotificationSettings(true, minutes, emptyList(), 30)

    private fun next(now: Long, minutes: Int, zone: ZoneId = istanbul) = Batching.nextDelivery(now, every(minutes).slots, zone)

    @Test
    fun alignsToTheClock() {
        assertEquals(at("2026-11-06T11:00:00"), next(at("2026-11-06T10:37:12"), 60))
        assertEquals(at("2026-11-06T10:00:00"), next(at("2026-11-06T09:10:00"), 120))
        // Exactly on a delivery: the next one, never now.
        assertEquals(at("2026-11-06T12:00:00"), next(at("2026-11-06T10:00:00"), 120))
        assertEquals(at("2026-11-07T00:00:00"), next(at("2026-11-06T23:30:00"), 60))
        // 45 does not divide a day: the last slot is 23:15, then midnight.
        assertEquals(at("2026-11-07T00:00:00"), next(at("2026-11-06T23:20:00"), 45))
        assertEquals(at("2026-11-06T00:45:00"), next(at("2026-11-06T00:00:00"), 45))
    }

    @Test
    fun fixedTimesWrapToTomorrow() {
        val s = NotificationSettings(true, 60, emptyList(), 30, deliverAt = listOf(9 * 60, 13 * 60, 18 * 60))
        assertEquals(at("2026-11-06T13:00:00"), Batching.nextDelivery(at("2026-11-06T09:00:00"), s.slots, istanbul))
        assertEquals(at("2026-11-06T09:00:00"), Batching.nextDelivery(at("2026-11-06T02:00:00"), s.slots, istanbul))
        assertEquals(at("2026-11-07T09:00:00"), Batching.nextDelivery(at("2026-11-06T18:30:00"), s.slots, istanbul))
    }

    @Test
    fun skippedHourDeliversWhenTheClockJumps() {
        // Berlin, 2026-03-29: 02:00 jumps to 03:00.
        val now = at("2026-03-29T01:30:00", berlin)
        val due = next(now, 60, berlin)
        assertEquals(3, local(due, berlin).hour)
        assertEquals(30 * 60 * 1000L, due - now)
    }

    @Test
    fun repeatedHourPicksTheNextInstant() {
        // Berlin, 2026-10-25: 03:00 falls back to 02:00, so 02:00–03:00 happens twice.
        val firstPass = ZonedDateTime.of(java.time.LocalDateTime.parse("2026-10-25T02:10:00"), berlin)
        val secondPass = firstPass.withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val due = next(secondPass, 15, berlin)
        assertEquals(5 * 60 * 1000L, due - secondPass)
        val dueFirst = next(firstPass.withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), 15, berlin)
        assertEquals(5 * 60 * 1000L, dueFirst - firstPass.withEarlierOffsetAtOverlap().toInstant().toEpochMilli())
    }

    private val settings = NotificationSettings(
        true, 60,
        listOf(NotificationAllow(null, "event", null), NotificationAllow("com.whatsapp", null, "Annem")),
        30,
    )
    private val now = at("2026-11-06T10:20:00")
    private fun msg(pkg: String = "com.whatsapp", cat: String? = "msg", title: String? = "Ayşe", postTime: Long = now) =
        Posted(pkg, cat, title, ongoing = false, foregroundService = false, postTime = postTime)

    @Test
    fun allowMatchingNeedsEveryField() {
        assertTrue(Batching.allows(NotificationAllow("COM.WHATSAPP", null, "annem"), msg(title = "Annem (2 messages)")))
        assertFalse(Batching.allows(NotificationAllow("com.whatsapp", null, "annem"), msg(title = "Ayşe")))
        assertFalse(Batching.allows(NotificationAllow("com.whatsapp", "call", null), msg()))
        assertTrue(Batching.allows(NotificationAllow(null, "EVENT", null), msg(cat = "event")))
    }

    @Test
    fun holdsOrdinaryNotifications() {
        assertEquals(Verdict.HOLD, Batching.verdict(settings, msg(), "dev.grod", null, now))
        assertEquals(Verdict.HOLD, Batching.verdict(settings, msg(pkg = "com.android.shell", cat = null, title = "grod test"), "dev.grod", null, now))
    }

    @Test
    fun neverHolds() {
        fun pass(n: Posted, s: NotificationSettings? = settings) = assertEquals(Verdict.PASS, Batching.verdict(s, n, "dev.grod", null, now))
        pass(msg(), s = null)
        pass(msg(), s = settings.copy(enabled = false))
        pass(msg(pkg = "dev.grod"))
        pass(msg(cat = "call"))
        pass(msg(cat = "alarm"))
        pass(msg(cat = "event"))
        pass(msg(title = "Annem"))
        pass(msg().copy(ongoing = true))
        pass(msg().copy(foregroundService = true))
    }

    @Test
    fun deliveriesAreNotHeldAgain() {
        val due = at("2026-11-06T11:00:00")
        // Back from snooze at the boundary (a little early or late): leave it.
        assertEquals(Verdict.DELIVERED, Batching.verdict(settings, msg(postTime = now), "dev.grod", due, due - 30_000))
        assertEquals(Verdict.DELIVERED, Batching.verdict(settings, msg(postTime = now), "dev.grod", due, due + 120_000))
        // Without grod's record, an old post time still gives the repost away.
        assertEquals(Verdict.DELIVERED, Batching.verdict(settings, msg(postTime = now), "dev.grod", null, due + 5_000))
        // A fresh post just after the boundary is new and waits for the next one.
        assertEquals(Verdict.HOLD, Batching.verdict(settings, msg(postTime = due + 5_000), "dev.grod", null, due + 5_000))
    }

    @Test
    fun updatesWaitForTheSameDelivery() {
        val due = at("2026-11-06T11:00:00")
        val later = at("2026-11-06T10:50:00")
        assertEquals(Verdict.HOLD, Batching.verdict(settings, msg(postTime = later), "dev.grod", due, later))
        assertEquals(due, Batching.holdUntil(settings, due, later, istanbul))
        assertEquals(due, Batching.holdUntil(settings, null, now, istanbul))
    }
}
