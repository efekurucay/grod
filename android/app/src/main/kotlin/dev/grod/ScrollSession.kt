package dev.grod

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The top-level `scroll` settings (docs/rules.md, "Scroll pause"). */
data class ScrollSettings(
    val freeSeconds: Int = 60,
    val firstPauseSeconds: Int = 10,
    val growth: Double = 2.0,
    val maxPauseSeconds: Int = 300,
    val resetMinutes: Int = 15,
)

/**
 * The phone's one scroll session, shared by every target app. Pure, so the timing is unit tested;
 * times are milliseconds on a clock that keeps running while the phone sleeps.
 */
class ScrollSession {
    /** Set while the pause screen is up; scroll events then count for nothing. */
    var paused = false

    /** Scroll time in this session, for the pause screen. */
    var sessionMs = 0L
        private set

    /**
     * Set by "Urgent" on the pause screen (docs/rules.md, "Scroll pause"): scrolling then brings a
     * check without a wait every [URGENT_CHECK_MS] instead of pauses, until Leave or the session resets.
     */
    var urgent = false
        private set

    /** Scroll time since "Urgent", for the check screen. */
    var urgentMs = 0L
        private set

    private var sincePauseMs = 0L
    private var pauses = 0
    private var lastScrollAt: Long? = null

    /** When the pause shown last ends. Leaving its screen does not end it, so coming back early shows it again. */
    private var pauseEndsAt = 0L

    /** Seconds left of the pause shown last, or null when it is over (or "Urgent" ended it). */
    fun pauseLeft(now: Long): Int? = ((pauseEndsAt - now + 999) / 1000).toInt().takeIf { it > 0 }

    /**
     * Counts one scroll event; returns the pause length in seconds when a pause is due. In urgent mode
     * it returns 0 when a check is due: a screen without a wait that does not count as a pause.
     */
    fun scroll(now: Long, settings: ScrollSettings): Int? {
        if (paused) return null
        val last = resetIfIdle(now, settings)
        // The first scroll of a session adds nothing; a swipe after watching for a while adds only the cap,
        // so watching does not count as scrolling.
        val added = if (last == null) 0L else min(now - last, MAX_STEP_MS).coerceAtLeast(0)
        lastScrollAt = now
        sessionMs += added
        sincePauseMs += added
        if (urgent) {
            urgentMs += added
            if (sincePauseMs < URGENT_CHECK_MS) return null
            sincePauseMs = 0
            return 0
        }
        if (sincePauseMs < settings.freeSeconds * 1000L) return null
        return nextPause(now, settings)
    }

    /**
     * A pause due now whatever the scroll time, when a quick opening's window is over (docs/rules.md,
     * "Opening check"). It counts like any other pause, so the next one is longer.
     */
    fun pauseNow(now: Long, settings: ScrollSettings): Int {
        resetIfIdle(now, settings)
        lastScrollAt = now
        return nextPause(now, settings)
    }

    /** "Urgent" on the pause screen: the pause ends now and checks take over from pauses. */
    fun startUrgent() {
        urgent = true
        urgentMs = 0
        sincePauseMs = 0
        pauseEndsAt = 0
    }

    fun endUrgent() {
        urgent = false
    }

    /** Starts the session over after resetMinutes without scrolling; returns the last scroll time that still counts. */
    private fun resetIfIdle(now: Long, settings: ScrollSettings): Long? {
        val last = lastScrollAt
        if (last == null || now - last < settings.resetMinutes * 60_000L) return last
        sessionMs = 0
        sincePauseMs = 0
        pauses = 0
        urgent = false
        return null
    }

    private fun nextPause(now: Long, settings: ScrollSettings): Int {
        sincePauseMs = 0
        val seconds = min(settings.firstPauseSeconds * settings.growth.pow(pauses), settings.maxPauseSeconds.toDouble()).roundToInt()
        pauses++
        pauseEndsAt = now + seconds * 1000L
        return seconds
    }

    companion object {
        const val MAX_STEP_MS = 5_000L
        const val URGENT_CHECK_MS = 30_000L

        /** "40 s" under a minute, whole minutes after. */
        fun label(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${(ms + 30_000) / 60_000} min"
    }
}
