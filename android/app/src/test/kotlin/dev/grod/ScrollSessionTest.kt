package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollSessionTest {
    private val defaults = ScrollSettings()

    /** Scrolls steadily, one event per second, until a pause is due; returns its length and the time it came. */
    private fun scrollUntilPause(session: ScrollSession, from: Long, settings: ScrollSettings = defaults): Pair<Int, Long> {
        var t = from
        while (true) {
            session.scroll(t, settings)?.let { return it to t }
            t += 1_000
        }
    }

    @Test
    fun steadyScrollingPausesAfterFreeSeconds() {
        val session = ScrollSession()
        // The first event has no previous one, so the minute is full after 60 more seconds.
        val (seconds, at) = scrollUntilPause(session, 0)
        assertEquals(10, seconds)
        assertEquals(60_000L, at)
        assertEquals(60_000L, session.sessionMs)
    }

    @Test
    fun pausesDoubleUpToTheCap() {
        val session = ScrollSession()
        var t = 0L
        val lengths = (1..8).map {
            val (seconds, at) = scrollUntilPause(session, t)
            t = at + 1_000
            seconds
        }
        assertEquals(listOf(10, 20, 40, 80, 160, 300, 300, 300), lengths)
    }

    @Test
    fun growthAndCapFollowTheSettings() {
        val session = ScrollSession()
        val settings = ScrollSettings(freeSeconds = 10, firstPauseSeconds = 3, growth = 1.5, maxPauseSeconds = 6)
        var t = 0L
        val lengths = (1..4).map {
            val (seconds, at) = scrollUntilPause(session, t, settings)
            t = at + 1_000
            seconds
        }
        assertEquals(listOf(3, 5, 6, 6), lengths)
    }

    @Test
    fun eachEventAddsAtMostFiveSeconds() {
        val session = ScrollSession()
        session.scroll(0, defaults)
        // A swipe after watching a video for two minutes adds only 5 s.
        session.scroll(120_000, defaults)
        assertEquals(5_000L, session.sessionMs)
        session.scroll(123_000, defaults)
        assertEquals(8_000L, session.sessionMs)
    }

    @Test
    fun watchingBetweenSwipesTakesTwelveSwipesToPause() {
        val session = ScrollSession()
        session.scroll(0, defaults)
        val pauses = (1..12).map { session.scroll(it * 30_000L, defaults) }
        assertEquals(List(11) { null } + 10, pauses)
    }

    @Test
    fun resetsAfterResetMinutesWithoutScrolling() {
        val session = ScrollSession()
        val (_, at) = scrollUntilPause(session, 0)
        val (second, at2) = scrollUntilPause(session, at + 1_000)
        assertEquals(20, second)
        session.scroll(at2 + 30_000, defaults)

        // 15 minutes without a scroll event: scroll time and pause count start over; that first scroll adds nothing.
        val back = at2 + 30_000 + 15 * 60_000
        assertNull(session.scroll(back, defaults))
        assertEquals(0L, session.sessionMs)
        val (third, _) = scrollUntilPause(session, back + 1_000)
        assertEquals(10, third)
    }

    @Test
    fun noResetJustBeforeResetMinutes() {
        val session = ScrollSession()
        val (_, at) = scrollUntilPause(session, 0)
        val back = at + 15 * 60_000 - 1
        session.scroll(back, defaults)
        val (second, _) = scrollUntilPause(session, back + 1_000)
        assertEquals(20, second)
    }

    @Test
    fun eventsWhilePausedAreIgnored() {
        val session = ScrollSession()
        val (_, at) = scrollUntilPause(session, 0)
        session.paused = true
        for (i in 1..400) assertNull(session.scroll(at + i * 1_000L, defaults))
        assertEquals(60_000L, session.sessionMs)

        // Ignored events do not count as scrolling either, so the gap since the last counted one applies.
        session.paused = false
        session.scroll(at + 401_000, defaults)
        assertEquals(65_000L, session.sessionMs)
    }

    @Test
    fun pauseNowTakesTheNextLengthAndCounts() {
        val session = ScrollSession()
        session.scroll(0, defaults)
        session.scroll(20_000, defaults)
        // A quick window ran out: the pause comes at once, at the session's next length.
        assertEquals(10, session.pauseNow(21_000, defaults))
        // Scroll time starts again from 0 and the next pause is longer.
        val (next, at) = scrollUntilPause(session, 22_000)
        assertEquals(20, next)
        assertEquals(81_000L, at)
        assertEquals(40, session.pauseNow(at + 1_000, defaults))
        // After resetMinutes without scrolling it starts over like any pause.
        assertEquals(10, session.pauseNow(at + 1_000 + 15 * 60_000, defaults))
    }

    @Test
    fun labelsScrollTime() {
        assertEquals("40 s", ScrollSession.label(40_000))
        assertEquals("1 min", ScrollSession.label(60_000))
        assertEquals("3 min", ScrollSession.label(150_000))
    }
}
