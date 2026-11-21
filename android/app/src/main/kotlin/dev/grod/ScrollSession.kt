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
    /** Set while the pause screen is up. */
    private var screenUp = false

    /** When the service last started the pause screen, which takes a moment to come up. */
    private var requestedAt: Long? = null

    /** The service starts the pause screen: from now, a fling's further scroll events must not start it again. */
    fun screenRequested(now: Long) {
        requestedAt = now
    }

    fun screenShown() {
        screenUp = true
        requestedAt = null
    }

    fun screenGone() {
        screenUp = false
    }

    /**
     * True while the pause screen is up or on its way; scroll events then count for nothing. The
     * system can drop a start (one right as the previous screen closes never showed), so a screen that
     * has not come up within [SCREEN_START_MS] stops holding them, or scrolling would never pause again.
     */
    fun paused(now: Long): Boolean = screenUp || requestedAt?.let { now - it < SCREEN_START_MS } == true

    /** Scroll time in this session, for the pause screen. */
    var sessionMs = 0L
        private set

    /** When the pack taken on the pause screen ends (docs/rules.md, "Packs"), or null without one. */
    private var packEndsAt: Long? = null

    private var sincePauseMs = 0L
    private var pauses = 0
    private var lastScrollAt: Long? = null

    /** When the pause shown last ends. Leaving its screen does not end it, so coming back early shows it again. */
    private var pauseEndsAt = 0L

    /** Seconds left of the pause shown last, or null when it is over (or a pack ended it). */
    fun pauseLeft(now: Long): Int? = ((pauseEndsAt - now + 999) / 1000).toInt().takeIf { it > 0 }

    fun inPack(now: Long): Boolean = packEndsAt?.let { now < it } == true

    /** Counts one scroll event; returns the pause length in seconds when a pause is due. */
    fun scroll(now: Long, settings: ScrollSettings): Int? {
        if (paused(now)) return null
        packEndsAt?.let { end ->
            // Scrolling in a pack counts for nothing, but it keeps the session from going idle.
            if (now < end) {
                lastScrollAt = now
                return null
            }
            packEndsAt = null
            // Still at it when the pack is over: the next pause comes at once.
            val seen = lastScrollAt
            if (seen != null && now - seen < settings.resetMinutes * 60_000L) return pauseNow(now, settings)
        }
        val last = resetIfIdle(now, settings)
        // The first scroll of a session adds nothing; a swipe after watching for a while adds only the cap,
        // so watching does not count as scrolling.
        val added = if (last == null) 0L else min(now - last, MAX_STEP_MS).coerceAtLeast(0)
        lastScrollAt = now
        sessionMs += added
        sincePauseMs += added
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

    /** A pack taken on the pause screen: the pause ends now and none comes for [minutes]. */
    fun startPack(now: Long, minutes: Int) {
        packEndsAt = now + minutes * 60_000L
        sincePauseMs = 0
        pauseEndsAt = 0
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

    private fun nextPause(now: Long, settings: ScrollSettings): Int {
        sincePauseMs = 0
        // A pause right as a pack ends is the one its end would bring.
        packEndsAt = null
        val seconds = min(settings.firstPauseSeconds * settings.growth.pow(pauses), settings.maxPauseSeconds.toDouble()).roundToInt()
        pauses++
        pauseEndsAt = now + seconds * 1000L
        return seconds
    }

    companion object {
        const val MAX_STEP_MS = 5_000L

        /** Android holds background activity starts for up to 5 s after Home; a later screen never came. */
        const val SCREEN_START_MS = 5_000L

        /** "40 s" under a minute, whole minutes after. */
        fun label(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${(ms + 30_000) / 60_000} min"
    }
}
