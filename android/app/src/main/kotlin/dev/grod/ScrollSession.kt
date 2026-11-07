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

    private var sincePauseMs = 0L
    private var pauses = 0
    private var lastScrollAt: Long? = null

    /** Counts one scroll event; returns the pause length in seconds when a pause is due. */
    fun scroll(now: Long, settings: ScrollSettings): Int? {
        if (paused) return null
        val last = resetIfIdle(now, settings)
        // The first scroll of a session adds nothing; a swipe after watching for a while adds only the cap,
        // so watching does not count as scrolling.
        val added = if (last == null) 0L else min(now - last, MAX_STEP_MS).coerceAtLeast(0)
        lastScrollAt = now
        sessionMs += added
        sincePauseMs += added
        if (sincePauseMs < settings.freeSeconds * 1000L) return null
        return nextPause(settings)
    }

    /**
     * A pause due now whatever the scroll time, when a quick opening's window is over (docs/rules.md,
     * "Opening check"). It counts like any other pause, so the next one is longer.
     */
    fun pauseNow(now: Long, settings: ScrollSettings): Int {
        resetIfIdle(now, settings)
        lastScrollAt = now
        return nextPause(settings)
    }

    /** Starts the session over after resetMinutes without scrolling; returns the last scroll time that still counts. */
    private fun resetIfIdle(now: Long, settings: ScrollSettings): Long? {
        val last = lastScrollAt
        if (last == null || now - last < settings.resetMinutes * 60_000L) return last
        sessionMs = 0
        sincePauseMs = 0
        pauses = 0
        return null
    }

    private fun nextPause(settings: ScrollSettings): Int {
        sincePauseMs = 0
        val seconds = min(settings.firstPauseSeconds * settings.growth.pow(pauses), settings.maxPauseSeconds.toDouble())
        pauses++
        return seconds.roundToInt()
    }

    companion object {
        const val MAX_STEP_MS = 5_000L

        /** "40 s" under a minute, whole minutes after. */
        fun label(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${(ms + 30_000) / 60_000} min"
    }
}
