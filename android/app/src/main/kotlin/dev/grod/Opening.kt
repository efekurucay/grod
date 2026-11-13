package dev.grod

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The top-level `opening` settings (docs/rules.md, "Opening check (Android)"); night times are minutes of the day. */
data class OpeningSettings(val awayMinutes: Int = 5, val quickMinutes: Int = 2, val nightFrom: Int = 0, val nightTo: Int = 6 * 60) {
    val awayMs: Long get() = awayMinutes * 60_000L

    /** Night runs from nightFrom up to nightTo, across midnight when nightFrom is later; equal times turn it off. */
    fun night(at: LocalTime): Boolean {
        val m = at.hour * 60 + at.minute
        return when {
            nightFrom == nightTo -> false
            nightFrom < nightTo -> m in nightFrom until nightTo
            else -> m >= nightFrom || m < nightTo
        }
    }

    /** "00:00–06:00" */
    val nightLabel: String get() = "${clock(nightFrom)}–${clock(nightTo)}"

    private fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}

/** An answer to the opening question; [id] is what the events log carries. */
enum class Purpose(val id: String, val label: String, val short: String, private val opened: String) {
    MESSAGE("message", "Message", "Message", "for a message"),
    LOOKUP("lookup", "Look something up", "Look up", "to look something up"),
    BROWSE("browse", "Browse", "Browse", "to browse");

    /** Message and Look something up open at once with a time window; Browse waits first. */
    val quick: Boolean get() = this != BROWSE

    /** "Opened for a message 3 min ago", on the pause that ends a quick window. */
    fun openedAgo(ms: Long): String = "Opened $opened ${maxOf(1L, (ms + 30_000) / 60_000)} min ago"

    companion object {
        fun of(id: String?): Purpose? = entries.firstOrNull { it.id == id }
    }
}

/** A finished session, kept for learning. */
data class PastSession(val endedAt: Long, val ms: Long, val overran: Boolean)

/**
 * A session in progress. It starts when the app opens after the answer (after the wait, if there
 * was one, so a wait never counts toward a quick window) and ends when the app leaves the front or
 * the screen turns off; it is only closed once the app stays away awayMinutes, because coming back
 * sooner continues it. Times are wall-clock milliseconds, the clock UsageStatsManager uses.
 */
data class LiveSession(
    val pkg: String,
    val purpose: Purpose,
    val startedAt: Long,
    /** The quick window; null for Browse. */
    val windowMs: Long?,
    /** Last time the app was known to be in front; usage events after it are checked for a leave. */
    val seenAt: Long,
    /** The window-over pause was shown, so it comes once per session. */
    val windowPaused: Boolean = false,
) {
    fun windowOver(now: Long): Boolean = windowMs != null && !windowPaused && now - startedAt > windowMs

    fun end(at: Long): PastSession {
        val ms = (at - startedAt).coerceAtLeast(0)
        return PastSession(at, ms, windowMs != null && ms > windowMs)
    }
}

/** What usage events say about one app after a time it was known to be in front. */
data class FrontTrace(
    /** Periods the app was away and came back, as (left, back). */
    val gaps: List<Pair<Long, Long>>,
    /** When the app left the front, if it has not come back. */
    val leftAt: Long?,
    /** Its activity is paused at the end without having left (grod's screen over it, so far). */
    val paused: Boolean = false,
)

/** Pure opening-check logic, unit tested without Android. */
object Opening {
    const val MIN_SESSIONS = 5
    const val DAYS_MS = 14 * 24 * 60 * 60 * 1000L
    private const val MIN_WINDOW_MS = 60_000L
    private const val MAX_WINDOW_MS = 10 * 60_000L

    /**
     * Follows [pkg] through [events] (time order) after [since], when it was in front. It leaves when
     * its activity is paused and another app comes to the front, or when the screen turns off. A pause
     * alone is no leave: moving between its own activities and grod's screens over it pause it too
     * (the caller drops grod's pause and question screens from [events]). Another app resuming while
     * it stays resumed is no leave either: that is the picture-in-picture menu or split screen.
     */
    fun trace(events: List<UsageEvent>, pkg: String, since: Long): FrontTrace {
        val gaps = ArrayList<Pair<Long, Long>>()
        var leftAt: Long? = null
        var paused = false
        // Activity B can resume before A's pause is logged; only the pause of the activity last resumed counts.
        var resumed: String? = null
        for (e in events) {
            if (e.time < since) continue
            when (e.kind) {
                UsageEvent.Kind.FOREGROUND -> if (e.pkg == pkg) {
                    leftAt?.let { gaps.add(it to e.time) }
                    leftAt = null
                    paused = false
                    resumed = e.cls
                } else if (leftAt == null && paused) {
                    leftAt = e.time
                }
                UsageEvent.Kind.BACKGROUND -> if (e.pkg == pkg && (resumed == null || resumed == e.cls)) paused = true
                UsageEvent.Kind.SCREEN_OFF -> if (leftAt == null) leftAt = e.time
            }
        }
        return FrontTrace(gaps, leftAt, paused && leftAt == null)
    }

    sealed interface Follow {
        /** The session ended at [at], when the app left for at least awayMinutes. */
        data class Ended(val at: Long) : Follow

        data class Live(val session: LiveSession) : Follow
    }

    /**
     * Whether [session] is still going at [now]. [inFront]: the app is in front now (it sent an
     * event), so a leave its return has not been logged for yet is closed at [now]. seenAt moves on
     * only while the app is resumed: a later trace must still see a pause before it to count a leave.
     */
    fun follow(session: LiveSession, trace: FrontTrace, now: Long, awayMs: Long, inFront: Boolean): Follow {
        val open = trace.leftAt
        val gaps = if (inFront && open != null) trace.gaps + (open to now) else trace.gaps
        gaps.firstOrNull { (left, back) -> back - left >= awayMs }?.let { return Follow.Ended(it.first) }
        if (!inFront && open != null) return if (now - open >= awayMs) Follow.Ended(open) else Follow.Live(session)
        return Follow.Live(if (trace.paused) session else session.copy(seenAt = now))
    }

    fun recent(sessions: List<PastSession>, now: Long): List<PastSession> = sessions.filter { it.endedAt > now - DAYS_MS }

    /**
     * The quick window: quickMinutes until the purpose has [MIN_SESSIONS] sessions in 14 days, then
     * their 75th percentile (nearest rank) rounded up to whole minutes, so it covers that share and
     * reads as minutes, clamped to 1–10 min.
     */
    fun windowMs(sessions: List<PastSession>, now: Long, settings: OpeningSettings): Long {
        val lengths = recent(sessions, now).map { it.ms }.sorted()
        if (lengths.size < MIN_SESSIONS) return settings.quickMinutes * 60_000L
        val p75 = lengths[ceil(0.75 * lengths.size).toInt() - 1]
        return (ceil(p75 / 60_000.0).toLong() * 60_000L).coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    data class Overrun(val overran: Int, val total: Int) {
        /** More than half of at least [MIN_SESSIONS] sessions overran: the quick open is lost until it is half or less. */
        val lost: Boolean get() = total >= MIN_SESSIONS && overran * 2 > total

        /** "6 of your last 10 ran over". */
        val label: String get() = "$overran of your last $total ran over"
    }

    fun overrun(sessions: List<PastSession>, now: Long): Overrun =
        recent(sessions, now).let { r -> Overrun(r.count { it.overran }, r.size) }

    /** scroll.firstPauseSeconds × growth ^ n, capped at maxPauseSeconds; n = Browse and night answers today before this one. */
    fun waitSeconds(n: Int, scroll: ScrollSettings): Int =
        min(scroll.firstPauseSeconds * scroll.growth.pow(n), scroll.maxPauseSeconds.toDouble()).roundToInt()

    /** What an answer does: open now ([waitSeconds] null) or after a wait; quick purposes get a window. */
    data class Answer(val purpose: Purpose, val waitSeconds: Int?, val windowMs: Long?)

    /** At [night] a quick purpose waits like Browse too: late openings are where "just a message" turns into browsing. */
    fun answer(
        purpose: Purpose,
        past: List<PastSession>,
        browsesToday: Int,
        now: Long,
        settings: OpeningSettings,
        scroll: ScrollSettings,
        night: Boolean = false,
    ): Answer {
        if (!purpose.quick) return Answer(purpose, waitSeconds(browsesToday, scroll), null)
        val wait = if (night || overrun(past, now).lost) waitSeconds(browsesToday, scroll) else null
        return Answer(purpose, wait, windowMs(past, now, settings))
    }
}

/**
 * Everything the opening check remembers, so it survives process death. Past sessions are kept
 * per purpose across all apps: a message is about as long in Instagram as in X, and pooling gets
 * to the 5 sessions that turn learning on sooner. The answer last given is kept per app.
 */
data class OpeningMemory(
    val past: Map<Purpose, List<PastSession>> = emptyMap(),
    val live: Map<String, LiveSession> = emptyMap(),
    val last: Map<String, Purpose> = emptyMap(),
    val browseDay: LocalDate? = null,
    val browseCount: Int = 0,
) {
    fun past(purpose: Purpose): List<PastSession> = past[purpose].orEmpty()

    fun browsesOn(date: LocalDate): Int = if (browseDay == date) browseCount else 0

    fun withBrowse(date: LocalDate) = copy(browseDay = date, browseCount = browsesOn(date) + 1)

    /** Adds a finished session and drops sessions older than 14 days. */
    fun withPast(purpose: Purpose, session: PastSession, now: Long) =
        copy(past = past + (purpose to Opening.recent(past(purpose) + session, now)))

    fun toJson(): String {
        val pastJson = JSONObject()
        for ((purpose, list) in past) {
            pastJson.put(purpose.id, JSONArray().apply { list.forEach { put(JSONArray().put(it.endedAt).put(it.ms).put(it.overran)) } })
        }
        val liveJson = JSONArray()
        for (s in live.values) {
            liveJson.put(JSONObject().put("pkg", s.pkg).put("purpose", s.purpose.id).put("startedAt", s.startedAt)
                .putOpt("windowMs", s.windowMs).put("seenAt", s.seenAt).put("windowPaused", s.windowPaused))
        }
        val lastJson = JSONObject()
        for ((pkg, purpose) in last) lastJson.put(pkg, purpose.id)
        return JSONObject().put("past", pastJson).put("live", liveJson).put("last", lastJson)
            .putOpt("browseDay", browseDay?.toString()).put("browseCount", browseCount).toString()
    }

    companion object {
        /** A broken or missing copy starts empty; it only holds what was learned, never rules. */
        fun parse(json: String?): OpeningMemory {
            if (json == null) return OpeningMemory()
            return try {
                val o = JSONObject(json)
                val pastJson = o.optJSONObject("past") ?: JSONObject()
                val past = Purpose.entries.associateWith { p ->
                    val arr = pastJson.optJSONArray(p.id) ?: JSONArray()
                    (0 until arr.length()).map { i -> arr.getJSONArray(i).let { PastSession(it.getLong(0), it.getLong(1), it.getBoolean(2)) } }
                }.filterValues { it.isNotEmpty() }
                val liveArr = o.optJSONArray("live") ?: JSONArray()
                val live = (0 until liveArr.length()).mapNotNull { i ->
                    val s = liveArr.getJSONObject(i)
                    val purpose = Purpose.of(s.optString("purpose")) ?: return@mapNotNull null
                    LiveSession(s.getString("pkg"), purpose, s.getLong("startedAt"),
                        if (s.has("windowMs")) s.getLong("windowMs") else null, s.getLong("seenAt"), s.optBoolean("windowPaused"))
                }.associateBy { it.pkg }
                val lastJson = o.optJSONObject("last") ?: JSONObject()
                val last = lastJson.keys().asSequence().mapNotNull { k -> Purpose.of(lastJson.optString(k))?.let { k to it } }.toMap()
                val day = o.optString("browseDay").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                OpeningMemory(past, live, last, day, o.optInt("browseCount"))
            } catch (e: JSONException) {
                OpeningMemory()
            } catch (e: java.time.format.DateTimeParseException) {
                OpeningMemory()
            }
        }
    }
}
