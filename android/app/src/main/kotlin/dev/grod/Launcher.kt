package dev.grod

import java.text.Normalizer
import java.util.Locale

/** The top-level `launcher` settings (docs/rules.md, "Launcher (Android)"). */
data class LauncherSettings(
    val distracting: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val pauseSeconds: Int = DEFAULT_PAUSE,
    /** Open-Meteo weather next to the date; off means no location prompt and no network. */
    val weather: Boolean = false,
) {
    companion object {
        const val DEFAULT_PAUSE = 10
    }
}

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

    /** Packages of enabled android rules, plus `launcher.distracting`, plus phone marks, minus phone unmarks. */
    fun distractingBy(rules: RuleSet): Set<String> =
        rules.rules.filter { it.enabled }.flatMap { it.android?.packages.orEmpty() }.toSet() + rules.launcher.distracting

    fun distracting(rules: RuleSet, local: LocalMarks): Set<String> =
        distractingBy(rules) + local.distracting - local.notDistracting

    fun hidden(rules: RuleSet, local: LocalMarks): Set<String> = rules.launcher.hidden.toSet() + local.hidden - local.unhidden

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

    private fun sorted(apps: Collection<AppEntry>) = apps.sortedWith(compareBy({ fold(it.label) }, { it.label }))

    fun list(apps: Collection<AppEntry>, distracting: Set<String>, hidden: Set<String>): Listing {
        val shown = apps.filter { it.pkg !in hidden }
        val (quiet, useful) = shown.partition { it.pkg in distracting }
        return Listing(sorted(useful), sorted(quiet))
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
