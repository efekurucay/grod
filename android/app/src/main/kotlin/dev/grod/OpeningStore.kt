package dev.grod

import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the opening check has learned and which sessions are going ([OpeningMemory]), kept in app
 * storage so it survives process death. Shared by the service, the question screen and the app,
 * which run in one process; main thread only.
 */
class OpeningStore private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("grod.opening", Context.MODE_PRIVATE)

    var memory: OpeningMemory = OpeningMemory.parse(prefs.getString(K_MEMORY, null))
        private set

    fun live(pkg: String): LiveSession? = memory.live[pkg]

    fun update(session: LiveSession) {
        if (memory.live[session.pkg] != session) save(memory.copy(live = memory.live + (session.pkg to session)))
    }

    /** An answer was given: logged, remembered for this app, and a Browse or night answer counts toward today's waits. */
    fun answer(pkg: String, purpose: Purpose, rules: RuleSet, now: Long): Opening.Answer {
        val today = LocalDate.now()
        val night = rules.opening.night(LocalTime.now())
        val answer = Opening.answer(purpose, memory.past(purpose), memory.browsesOn(today), now, rules.opening, rules.scroll, night)
        val remembered = memory.copy(last = memory.last + (pkg to purpose))
        save(if (purpose == Purpose.BROWSE || night) remembered.withBrowse(today) else remembered)
        log(rules, pkg, "opening", now, purpose)
        return answer
    }

    /** The app opens: the session starts now, after any wait. */
    fun start(pkg: String, answer: Opening.Answer, now: Long) =
        save(memory.copy(live = memory.live + (pkg to LiveSession(pkg, answer.purpose, now, answer.windowMs, now))))

    fun finish(session: LiveSession, at: Long, rules: RuleSet) {
        val past = session.end(at)
        save(memory.withPast(session.purpose, past, at).copy(live = memory.live - session.pkg))
        log(rules, session.pkg, "session", at, session.purpose, past.ms)
    }

    fun left(pkg: String, rules: RuleSet) = log(rules, pkg, "left", System.currentTimeMillis())

    private fun save(next: OpeningMemory) {
        memory = next
        prefs.edit().putString(K_MEMORY, next.toJson()).apply()
    }

    private fun log(rules: RuleSet, pkg: String, action: String, t: Long, purpose: Purpose? = null, ms: Long? = null) {
        val rule = rules.rulesFor(pkg).firstOrNull()?.id ?: "launcher"
        LogWriter.get(app).append("events", LogLines.event(t, rule, pkg, action, purpose?.id, ms), rules.keepDays)
    }

    companion object {
        private const val K_MEMORY = "memory"
        private var instance: OpeningStore? = null

        fun get(context: Context): OpeningStore = instance ?: OpeningStore(context.applicationContext).also { instance = it }
    }
}

/**
 * The service side of the opening check (docs/rules.md, "Opening check (Android)"). The service
 * hears window and scroll events only from target apps, so it knows when one is in front but not
 * when it left. Leaves come from UsageStatsManager events since the app was last seen in front,
 * read when a target sends an event and once a minute while a session is going, so the service
 * never has to listen to every app.
 */
class OpeningWatch(private val context: Context) {
    private val store = OpeningStore.get(context)
    private val overlays = setOf(ScrollPauseActivity::class.java.name, OpeningCheckActivity::class.java.name)
    private val checkedAt = HashMap<String, Long>()

    /**
     * [pkg] sent a window event, so it is in front: asks what it is for unless its session is still
     * going. Inside-app navigation finds the session going. Returns true when the question shows.
     */
    fun onFront(pkg: String, rules: RuleSet, targets: Set<String>, now: Long): Boolean {
        if (pkg !in targets || OpeningCheckActivity.showing) return false
        checkedAt[pkg] = now
        // Without usage access a leave cannot be told from inside-app navigation, so nothing is asked.
        if (!Permissions.usageAccess(context)) return false
        val session = store.live(pkg)
        if (session != null) {
            when (val f = Opening.follow(session, trace(pkg, session.seenAt, now), now, rules.opening.awayMs, inFront = true)) {
                is Opening.Follow.Live -> {
                    store.update(f.session)
                    return false
                }
                is Opening.Follow.Ended -> store.finish(session, f.at, rules)
            }
        }
        // An app starting up sends several window events before the question's screen is up.
        if (now - OpeningCheckActivity.launchedAt < ASK_GUARD_MS) return true
        OpeningCheckActivity.launchedAt = now
        context.startActivity(
            Intent(context, OpeningCheckActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OpeningCheckActivity.EXTRA_PKG, pkg),
        )
        return true
    }

    /** A scroll also shows the app is in front (unlocking into it may send no window event); checked every 10 s at most. */
    fun onScroll(pkg: String, rules: RuleSet, targets: Set<String>, now: Long): Boolean =
        now - (checkedAt[pkg] ?: 0L) >= SCROLL_CHECK_MS && onFront(pkg, rules, targets, now)

    /** Closes the sessions whose app has been away awayMinutes; the service runs it every minute. */
    fun tick(rules: RuleSet, now: Long) {
        if (store.memory.live.isEmpty() || !Permissions.usageAccess(context)) return
        for (session in store.memory.live.values.toList()) {
            when (val f = Opening.follow(session, trace(session.pkg, session.seenAt, now), now, rules.opening.awayMs, inFront = false)) {
                is Opening.Follow.Live -> store.update(f.session)
                is Opening.Follow.Ended -> store.finish(session, f.at, rules)
            }
        }
    }

    /** The text for the pause when [pkg]'s quick window is over; it comes once per session. */
    fun windowOver(pkg: String, now: Long): String? {
        val session = store.live(pkg)?.takeIf { it.windowOver(now) } ?: return null
        store.update(session.copy(windowPaused = true))
        return session.purpose.openedAgo(now - session.startedAt)
    }

    /** grod's own pause and question screens sit over the app, so they do not count as leaving it. */
    private fun trace(pkg: String, since: Long, now: Long): FrontTrace {
        val events = context.usageEvents(since, now).filterNot { it.pkg == context.packageName && it.cls in overlays }
        return Opening.trace(events, pkg, since)
    }

    private companion object {
        const val ASK_GUARD_MS = 3_000L
        const val SCROLL_CHECK_MS = 10_000L
    }
}
