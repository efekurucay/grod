package dev.grod

import dev.grod.UsageEvent.Kind.BACKGROUND
import dev.grod.UsageEvent.Kind.FOREGROUND
import dev.grod.UsageEvent.Kind.SCREEN_OFF
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningTest {
    private val ig = "com.instagram.android"
    private val wa = "com.whatsapp"
    private val home = "com.huawei.android.launcher"
    private val settings = OpeningSettings()
    private val away = settings.awayMs
    private val scroll = ScrollSettings()
    private val min = 60_000L
    private val day = 24 * 60 * min

    private fun fg(pkg: String, t: Long, cls: String? = null) = UsageEvent(pkg, t, FOREGROUND, cls)
    private fun bg(pkg: String, t: Long, cls: String? = null) = UsageEvent(pkg, t, BACKGROUND, cls)
    private fun off(t: Long) = UsageEvent("", t, SCREEN_OFF)
    private fun live(startedAt: Long = 0, windowMs: Long? = 2 * min, seenAt: Long = startedAt) =
        LiveSession(ig, Purpose.MESSAGE, startedAt, windowMs, seenAt)

    // Leaves and returns, from usage events.

    @Test
    fun movingBetweenItsOwnActivitiesIsNoLeave() {
        val trace = Opening.trace(listOf(bg(ig, 10_000), fg(ig, 10_050), bg(ig, 20_000), fg(ig, 20_030)), ig, 0)
        assertEquals(FrontTrace(emptyList(), null), trace)
    }

    @Test
    fun anotherAppAfterItsPauseOrTheScreenTurningOffIsALeave() {
        val events = listOf(bg(ig, 10_000), fg(wa, 10_100), fg(home, 20_000), fg(ig, 40_000), off(90_000))
        assertEquals(FrontTrace(listOf(10_100L to 40_000L), 90_000L), Opening.trace(events, ig, 0))
        // Events before the time the app was last seen in front are already accounted for.
        assertEquals(FrontTrace(emptyList(), 90_000L), Opening.trace(events, ig, 40_000))
    }

    @Test
    fun anotherAppResumingWhileItStaysResumedIsNoLeave() {
        // Touching a picture-in-picture window resumes the system's PiP menu; the app in front is not paused.
        val pip = "com.android.systemui"
        val events = listOf(fg(pip, 10_000), bg(pip, 12_000), fg(pip, 30_000), bg(pip, 31_000))
        assertEquals(FrontTrace(emptyList(), null), Opening.trace(events, ig, 0))
    }

    @Test
    fun onlyThePauseOfTheActivityLastResumedCounts() {
        // B resumed before A's pause was logged: the app is still in front when another app shows up beside it.
        val events = listOf(fg(ig, 10_000, "B"), bg(ig, 10_010, "A"), fg("com.android.systemui", 20_000))
        assertEquals(FrontTrace(emptyList(), null), Opening.trace(events, ig, 0))
        val left = listOf(fg(ig, 10_000, "B"), bg(ig, 50_000, "B"), fg(home, 50_100))
        assertEquals(FrontTrace(emptyList(), 50_100L), Opening.trace(left, ig, 0))
    }

    // Opening decision: same session or a new question.

    @Test
    fun comingBackSoonerContinuesTheSession() {
        val trace = Opening.trace(listOf(bg(ig, 60_000), fg(wa, 60_000), fg(ig, 60_000 + away - 1)), ig, 0)
        val follow = Opening.follow(live(), trace, 60_000 + away, away, inFront = true)
        assertEquals(Opening.Follow.Live(live().copy(seenAt = 60_000 + away)), follow)
    }

    @Test
    fun awayForAwayMinutesEndsTheSessionAtTheLeave() {
        val trace = Opening.trace(listOf(bg(ig, 3 * min), fg(home, 3 * min), fg(ig, 3 * min + away)), ig, 0)
        assertEquals(Opening.Follow.Ended(3 * min), Opening.follow(live(), trace, 3 * min + away + 500, away, inFront = true))
    }

    @Test
    fun aReturnNotYetLoggedIsClosedAtTheEvent() {
        // The window event can arrive before the app's own MOVE_TO_FOREGROUND is logged.
        val trace = Opening.trace(listOf(bg(ig, min), fg(home, min)), ig, 0)
        assertEquals(Opening.Follow.Ended(min), Opening.follow(live(), trace, min + away, away, inFront = true))
        assertEquals(Opening.Follow.Live(live().copy(seenAt = 2 * min)), Opening.follow(live(), trace, 2 * min, away, inFront = true))
    }

    @Test
    fun theFirstLongAbsenceEndsTheSession() {
        val events = listOf(bg(ig, min), fg(wa, min), fg(ig, 2 * min), off(4 * min), fg(ig, 4 * min + away), bg(ig, 20 * min), fg(home, 20 * min))
        val follow = Opening.follow(live(), Opening.trace(events, ig, 0), 21 * min, away, inFront = true)
        assertEquals(Opening.Follow.Ended(4 * min), follow)
    }

    @Test
    fun theMinuteCheckClosesASessionOnlyOnceAwayMinutesPassed() {
        val trace = Opening.trace(listOf(bg(ig, min), fg(home, min)), ig, 0)
        // Still within awayMinutes: the session stays, and seenAt stays before the leave so it is found again.
        assertEquals(Opening.Follow.Live(live()), Opening.follow(live(), trace, min + away - 1, away, inFront = false))
        assertEquals(Opening.Follow.Ended(min), Opening.follow(live(), trace, min + away, away, inFront = false))
        // Still in front: seenAt moves on.
        val inFront = Opening.trace(listOf(bg(ig, min), fg(ig, min + 10)), ig, 0)
        assertEquals(Opening.Follow.Live(live().copy(seenAt = 9 * min)), Opening.follow(live(), inFront, 9 * min, away, inFront = false))
    }

    @Test
    fun whilePausedUnderGrodsScreenSeenAtStaysSoTheLeaveIsFound() {
        // Instagram paused under the scroll pause (grod's screens are dropped from the events), then Leave went home.
        val paused = Opening.trace(listOf(bg(ig, min, "Main")), ig, 0)
        assertEquals(FrontTrace(emptyList(), null, paused = true), paused)
        val check = Opening.follow(live(), paused, 2 * min, away, inFront = false)
        assertEquals(Opening.Follow.Live(live()), check)
        val session = (check as Opening.Follow.Live).session
        val events = listOf(bg(ig, min, "Main"), fg(home, 3 * min))
        val trace = Opening.trace(events, ig, session.seenAt)
        assertEquals(Opening.Follow.Ended(3 * min), Opening.follow(session, trace, 3 * min + away, away, inFront = false))
        // Coming back resumes it, and seenAt moves on again.
        val back = Opening.trace(listOf(bg(ig, min, "Main"), fg(ig, 3 * min, "Main")), ig, 0)
        assertEquals(Opening.Follow.Live(live().copy(seenAt = 4 * min)), Opening.follow(live(), back, 4 * min, away, inFront = false))
    }

    @Test
    fun sessionLengthAndOverrun() {
        val s = live(startedAt = 1_000, windowMs = 2 * min)
        assertEquals(PastSession(1_000 + 2 * min, 2 * min, false), s.end(1_000 + 2 * min))
        assertEquals(PastSession(1_000 + 3 * min, 3 * min, true), s.end(1_000 + 3 * min))
        assertFalse(s.copy(purpose = Purpose.BROWSE, windowMs = null).end(1_000 + 30 * min).overran)
    }

    @Test
    fun windowOverOncePerSession() {
        val s = live(startedAt = 0, windowMs = 2 * min)
        assertFalse(s.windowOver(2 * min))
        assertTrue(s.windowOver(2 * min + 1))
        assertFalse(s.copy(windowPaused = true).windowOver(10 * min))
        assertFalse(s.copy(purpose = Purpose.BROWSE, windowMs = null).windowOver(10 * min))
        assertEquals("Opened for a message 3 min ago", Purpose.MESSAGE.openedAgo(3 * min + 10_000))
        assertEquals("Opened to look something up 1 min ago", Purpose.LOOKUP.openedAgo(5_000))
    }

    // Learning: windows, overrun, waits.

    private fun sessions(vararg minutes: Double, now: Long = 30 * day, window: Long = 2 * min) =
        minutes.mapIndexed { i, m -> (m * min).toLong().let { PastSession(now - i * 1_000L, it, it > window) } }

    @Test
    fun windowIsQuickMinutesUntilFiveSessions() {
        val now = 30 * day
        assertEquals(2 * min, Opening.windowMs(emptyList(), now, settings))
        assertEquals(2 * min, Opening.windowMs(sessions(5.0, 5.0, 5.0, 5.0), now, settings))
        assertEquals(3 * min, Opening.windowMs(sessions(1.0, 1.0, 1.0, 1.0), now, OpeningSettings(quickMinutes = 3)))
    }

    @Test
    fun windowIsTheSeventyFifthPercentileRoundedUpAndClamped() {
        val now = 30 * day
        // Nearest rank: the 4th of 5 sorted lengths, 2.5 min, rounded up to 3.
        assertEquals(3 * min, Opening.windowMs(sessions(0.5, 1.0, 9.0, 2.5, 2.0), now, settings))
        // 8 lengths: the 6th.
        assertEquals(4 * min, Opening.windowMs(sessions(1.0, 1.0, 2.0, 2.0, 3.0, 3.2, 8.0, 9.0), now, settings))
        assertEquals(min, Opening.windowMs(sessions(0.1, 0.1, 0.2, 0.2, 0.3), now, settings))
        assertEquals(10 * min, Opening.windowMs(sessions(30.0, 30.0, 30.0, 30.0, 30.0), now, settings))
    }

    @Test
    fun onlyTheLastFourteenDaysCount() {
        val now = 30 * day
        val old = (1..5).map { PastSession(now - 15 * day, 9 * min, true) }
        assertEquals(2 * min, Opening.windowMs(old, now, settings))
        assertEquals(Opening.Overrun(0, 0), Opening.overrun(old, now))
        // Adding a session drops the ones past 14 days.
        val memory = OpeningMemory(past = mapOf(Purpose.MESSAGE to old)).withPast(Purpose.MESSAGE, PastSession(now, min, false), now)
        assertEquals(listOf(PastSession(now, min, false)), memory.past(Purpose.MESSAGE))
    }

    @Test
    fun browseWaitGrowsWithEachBrowseTodayUpToTheCap() {
        assertEquals(listOf(10, 20, 40, 80, 160, 300, 300), (0..6).map { Opening.waitSeconds(it, scroll) })
        val answer = Opening.answer(Purpose.BROWSE, emptyList(), 2, 0, settings, scroll)
        assertEquals(Opening.Answer(Purpose.BROWSE, 40, null), answer)
    }

    @Test
    fun nightHoursRunAcrossMidnightAndEqualTimesTurnThemOff() {
        val t = { s: String -> java.time.LocalTime.parse(s) }
        val d = OpeningSettings()
        assertTrue(d.night(t("00:00")) && d.night(t("05:59")))
        assertFalse(d.night(t("06:00")) || d.night(t("23:59")))
        val late = OpeningSettings(nightFrom = 23 * 60, nightTo = 7 * 60)
        assertTrue(late.night(t("23:00")) && late.night(t("03:00")))
        assertFalse(late.night(t("07:00")) || late.night(t("22:59")))
        assertFalse(OpeningSettings(nightFrom = 0, nightTo = 0).night(t("03:00")))
        assertEquals("23:00–07:00", late.nightLabel)
    }

    @Test
    fun atNightAQuickPurposeWaitsLikeBrowseAndKeepsItsWindow() {
        val night = Opening.answer(Purpose.MESSAGE, emptyList(), 2, 30 * day, settings, scroll, night = true)
        assertEquals(Opening.Answer(Purpose.MESSAGE, 40, 2 * min), night)
    }

    @Test
    fun browseCountResetsAtLocalMidnight() {
        val today = LocalDate.parse("2026-11-07")
        val m = OpeningMemory().withBrowse(today).withBrowse(today)
        assertEquals(2, m.browsesOn(today))
        assertEquals(0, m.browsesOn(today.plusDays(1)))
        assertEquals(1, m.withBrowse(today.plusDays(1)).browsesOn(today.plusDays(1)))
    }

    @Test
    fun quickPurposeOpensAtOnceUntilMoreThanHalfOverran() {
        val now = 30 * day
        val fresh = Opening.answer(Purpose.MESSAGE, emptyList(), 3, now, settings, scroll)
        assertEquals(Opening.Answer(Purpose.MESSAGE, null, 2 * min), fresh)

        // 3 of 4 overran, but fewer than 5 sessions: still quick.
        var past = sessions(3.0, 3.0, 3.0, 1.0)
        assertNull(Opening.answer(Purpose.MESSAGE, past, 0, now, settings, scroll).waitSeconds)

        // 4 of 5: more than half, the quick open is lost and the Browse wait applies (n = Browse answers today).
        past = past + PastSession(now - 10_000, 4 * min, true)
        val overrun = Opening.overrun(past, now)
        assertEquals(Opening.Overrun(4, 5), overrun)
        assertTrue(overrun.lost)
        assertEquals("4 of your last 5 ran over", overrun.label)
        val lost = Opening.answer(Purpose.MESSAGE, past, 1, now, settings, scroll)
        assertEquals(20, lost.waitSeconds)
        assertEquals(Opening.windowMs(past, now, settings), lost.windowMs)

        // Sessions that hold bring it back as soon as the share is half or less: 4 of 8.
        past = past + (1..3).map { PastSession(now - 20_000 - it, min, false) }
        assertEquals(Opening.Overrun(4, 8), Opening.overrun(past, now))
        assertNull(Opening.answer(Purpose.MESSAGE, past, 1, now, settings, scroll).waitSeconds)

        // One more overrun and it is lost again: 5 of 9.
        past = past + PastSession(now - 30_000, 6 * min, true)
        assertEquals(20, Opening.answer(Purpose.MESSAGE, past, 1, now, settings, scroll).waitSeconds)
    }

    // What survives process death.

    @Test
    fun memoryRoundTripsThroughJson() {
        val memory = OpeningMemory(
            past = mapOf(Purpose.MESSAGE to listOf(PastSession(5, 120_000, false)), Purpose.BROWSE to listOf(PastSession(9, 600_000, false))),
            live = mapOf(ig to live(startedAt = 100, seenAt = 200).copy(windowPaused = true),
                wa to LiveSession(wa, Purpose.BROWSE, 300, null, 400)),
            last = mapOf(ig to Purpose.LOOKUP),
            browseDay = LocalDate.parse("2026-11-07"),
            browseCount = 3,
        )
        assertEquals(memory, OpeningMemory.parse(memory.toJson()))
        assertEquals(OpeningMemory(), OpeningMemory.parse(null))
        assertEquals(OpeningMemory(), OpeningMemory.parse("{broken"))
    }
}
