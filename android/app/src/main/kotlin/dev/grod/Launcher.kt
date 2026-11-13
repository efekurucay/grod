package dev.grod

import java.text.Normalizer
import java.util.Locale

/** The top-level `launcher` settings (docs/rules.md, "Launcher (Android)"). */
data class LauncherSettings(
    val distracting: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    /** Open-Meteo weather next to the date; off means no location prompt and no network. */
    val weather: Boolean = false,
    /** Packages pinned in the bottom row, in order. */
    val dock: List<String> = emptyList(),
)

/** One launchable activity. [component] is the activity class. */
data class AppEntry(val pkg: String, val component: String, val label: String)

/**
 * Choices made on the phone by long-press, applied on top of the rules. An "un-" set records that
 * the user overruled the rules for that package.
 */
data class LocalMarks(
    val distracting: Set<String> = emptySet(),
    val notDistracting: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val unhidden: Set<String> = emptySet(),
    val docked: Set<String> = emptySet(),
    val undocked: Set<String> = emptySet(),
)

/** What the launcher shows: useful apps in full, distracting ones after them, quieter. */
data class Listing(val useful: List<AppEntry>, val distracting: List<AppEntry>)

/** Pure launcher logic, unit tested without Android. */
object Launcher {
    /** Text sizes of the cloud, smallest to largest. */
    val SIZES_SP = listOf(15f, 18f, 22f, 28f, 36f)
    private const val TOP = 4
    private const val MAX_AT_TOP = 3

    /** One launch from the home screen counts like this much foreground time. */
    private const val LAUNCH_MS = 2 * 60_000L

    /**
     * Size step (0..4) per package from foreground time and launch counts, log-scaled against the
     * most-used app. At most three apps get the top step. Distracting apps always get step 0 and are
     * left out of the scale, so heavy use of them never makes them, or shrinks anything else, bigger.
     */
    fun sizeSteps(
        pkgs: Collection<String>,
        foregroundMs: Map<String, Long>,
        launches: Map<String, Int>,
        distracting: Set<String>,
    ): Map<String, Int> {
        val score = pkgs.associateWith { pkg ->
            if (pkg in distracting) 0.0
            else Math.log1p(((foregroundMs[pkg] ?: 0L) + (launches[pkg] ?: 0) * LAUNCH_MS) / 60_000.0)
        }
        val max = score.values.maxOrNull()?.takeIf { it > 0 } ?: return pkgs.associateWith { 0 }
        val steps = score.mapValues { (_, s) -> Math.round(TOP * s / max).toInt().coerceIn(0, TOP) }.toMutableMap()
        steps.filterValues { it == TOP }.keys.sortedByDescending { score[it] }.drop(MAX_AT_TOP).forEach { steps[it] = TOP - 1 }
        return steps
    }

    /**
     * The largest scale in [min]..1 at which the cloud fits, found by binary search ([fits] grows
     * false as the scale grows). Null when even [min] does not fit; the cloud then scrolls at [min].
     */
    fun fitScale(min: Double = 0.55, steps: Int = 10, fits: (Double) -> Boolean): Double? {
        if (fits(1.0)) return 1.0
        if (!fits(min)) return null
        var lo = min
        var hi = 1.0
        repeat(steps) {
            val mid = (lo + hi) / 2
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
    }

    /** Other home screens are no use as tiles; grod's own entry stays. */
    fun withoutHomes(apps: List<AppEntry>, homes: Set<String>, own: String): List<AppEntry> =
        apps.filter { it.pkg == own || it.pkg !in homes }

    /** Packages of enabled android rules, plus `launcher.distracting`, plus phone marks, minus phone unmarks. */
    fun distractingBy(rules: RuleSet): Set<String> =
        rules.rules.filter { it.enabled }.flatMap { it.android?.packages.orEmpty() }.toSet() + rules.launcher.distracting

    fun distracting(rules: RuleSet, local: LocalMarks): Set<String> =
        distractingBy(rules) + local.distracting - local.notDistracting

    fun hidden(rules: RuleSet, local: LocalMarks): Set<String> = rules.launcher.hidden.toSet() + local.hidden - local.unhidden

    /** The dock: `launcher.dock` in its order, minus phone removals, then phone additions by package name. */
    fun dock(rules: RuleSet, local: LocalMarks): List<String> =
        (rules.launcher.dock.filter { it !in local.undocked } + (local.docked - rules.launcher.dock.toSet()).sorted()).distinct()

    /** Long-press "Add to dock/Remove from dock", with the same override logic as [toggleDistracting]. */
    fun toggleDocked(local: LocalMarks, pkg: String, byRules: Boolean, now: Boolean): LocalMarks = if (now) {
        local.copy(docked = local.docked - pkg, undocked = if (byRules) local.undocked + pkg else local.undocked)
    } else {
        local.copy(docked = if (byRules) local.docked else local.docked + pkg, undocked = local.undocked - pkg)
    }

    /** Long-press "Mark/Unmark distracting": undoes a phone mark, or overrules the rules. */
    fun toggleDistracting(local: LocalMarks, pkg: String, byRules: Boolean, now: Boolean): LocalMarks = if (now) {
        local.copy(distracting = local.distracting - pkg, notDistracting = if (byRules) local.notDistracting + pkg else local.notDistracting)
    } else {
        local.copy(distracting = if (byRules) local.distracting else local.distracting + pkg, notDistracting = local.notDistracting - pkg)
    }

    /** Long-press "Hide/Unhide", with the same override logic as [toggleDistracting]. */
    fun toggleHidden(local: LocalMarks, pkg: String, byRules: Boolean, now: Boolean): LocalMarks = if (now) {
        local.copy(hidden = local.hidden - pkg, unhidden = if (byRules) local.unhidden + pkg else local.unhidden)
    } else {
        local.copy(hidden = if (byRules) local.hidden else local.hidden + pkg, unhidden = local.unhidden - pkg)
    }

    /**
     * Search key: lower case, accents dropped, and every Turkish i (I, İ, ı, i) folded to "i", so
     * "instagram", "INSTAGRAM" and "ınstagram" find the same app and "ogrenci" finds "Öğrenci".
     */
    fun fold(text: String): String {
        val dotless = text.replace('İ', 'i').replace('I', 'i').replace('ı', 'i')
        return Normalizer.normalize(dotless.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
    }

    private val MARKS = Regex("\\p{M}+")

    /**
     * What voice search types into the field: the first heard phrase that search finds (as heard,
     * before an apostrophe so "WhatsApp'ı" counts, or without spaces), else the label of the app whose
     * name sounds closest to a phrase or one of its words, else null.
     */
    fun voiceQuery(apps: Collection<AppEntry>, heard: List<String>): String? {
        val phrases = heard.flatMap { h ->
            val base = h.substringBefore('\'').substringBefore('’').trim()
            listOf(h.trim(), base, base.replace(" ", ""))
        }.filter { it.isNotEmpty() }.distinct()
        phrases.firstOrNull { p -> search(apps, p, emptySet()).isNotEmpty() }?.let { return it }
        val spoken = (phrases + phrases.flatMap { it.split(' ') }).map(::sound).filter { it.length >= 3 }.distinct()
        return apps.mapNotNull { app ->
            val name = sound(app.label)
            spoken.minOfOrNull { editDistance(name, it) }?.takeIf { it <= name.length / 4 }?.let { app to it }
        }.minByOrNull { it.second }?.first?.label
    }

    /**
     * How a name sounds, roughly, as a Turkish speaker says an English one: "WhatsApp" and "vatsap"
     * both become "vatsap". Letters and digits only, w → v, ph → f, c/q/ck → k, doubled letters once.
     */
    fun sound(text: String): String {
        val s = fold(text).filter { it.isLetterOrDigit() }
            .replace("wh", "v").replace("w", "v").replace("ph", "f").replace("ck", "k").replace("c", "k").replace("q", "k")
        return s.filterIndexed { i, ch -> i == 0 || ch != s[i - 1] }
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Most used first (larger size step), alphabetical within a step. */
    fun byUse(apps: Collection<AppEntry>, step: (String) -> Int): List<AppEntry> =
        apps.sortedWith(compareByDescending<AppEntry> { step(it.pkg) }.thenBy { fold(it.label) }.thenBy { it.label })

    /**
     * Packages in the cloud: opened in the last [KEEP_MS], by any route ([lastOpened], from usage
     * stats) or from grod. An app found by search and opened joins at once and stays until it goes
     * unopened that long.
     */
    fun recent(lastOpened: Map<String, Long>, launchTimes: Map<String, List<Long>>, now: Long): Set<String> =
        lastOpened.filterValues { it > now - KEEP_MS }.keys +
            launchTimes.filterValues { times -> times.any { it > now - KEEP_MS } }.keys

    /**
     * Apps to offer for uninstalling: installed by the user ([installedAt], system apps left out) more
     * than [UNUSED_MS] ago and not opened in that time by any route.
     */
    fun unused(installedAt: Map<String, Long>, lastOpened: Map<String, Long>, launchTimes: Map<String, List<Long>>, now: Long): Set<String> =
        installedAt.filter { (pkg, at) ->
            at < now - UNUSED_MS && (lastOpened[pkg] ?: 0L) < now - UNUSED_MS && launchTimes[pkg].orEmpty().none { it > now - UNUSED_MS }
        }.keys

    /** The window the size steps are computed over. */
    const val RECENT_MS = 14 * 24 * 60 * 60 * 1000L
    const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    const val UNUSED_MS = 30 * 24 * 60 * 60 * 1000L

    /**
     * The cloud: everything not hidden or [docked] and, when [recent] is known (usage access), only apps
     * opened recently; the rest are found by search. Distracting apps follow the same rule.
     */
    fun list(
        apps: Collection<AppEntry>,
        distracting: Set<String>,
        hidden: Set<String>,
        recent: Set<String>? = null,
        step: (String) -> Int = { 0 },
        docked: Set<String> = emptySet(),
    ): Listing {
        val shown = apps.filter { it.pkg !in hidden && it.pkg !in docked && (recent == null || it.pkg in recent) }
        val (quiet, useful) = shown.partition { it.pkg in distracting }
        return Listing(byUse(useful, step), byUse(quiet, step))
    }

    /**
     * Hidden apps are searchable too. Order: useful before distracting, then labels that start with
     * the query, then a word that starts with it, then any match, alphabetical within each.
     */
    fun search(apps: Collection<AppEntry>, query: String, distracting: Set<String>): List<AppEntry> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        fun rank(label: String): Int? {
            val l = fold(label)
            return when {
                l.startsWith(q) -> 0
                l.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 1
                q in l -> 2
                else -> null
            }
        }
        return apps.mapNotNull { app -> rank(app.label)?.let { app to it } }
            .sortedWith(compareBy({ it.first.pkg in distracting }, { it.second }, { fold(it.first.label) }))
            .map { it.first }
    }
}
