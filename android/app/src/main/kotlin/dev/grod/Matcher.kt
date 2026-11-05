package dev.grod

/** Screen rectangle; a plain class so matching runs in JVM unit tests without android.graphics.Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(other: Box): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    /** The overlapping part, or null when the boxes do not overlap. */
    fun intersect(other: Box): Box? =
        Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            .takeUnless { it.isEmpty }
}

/**
 * The parts of an accessibility node a matcher reads. Properties are read lazily, so text is only
 * fetched from the app when a matcher asks for text. A node is valid only inside the visit callback.
 */
interface Node {
    val viewId: String?
    val text: CharSequence?
    val desc: CharSequence?
    val isSelected: Boolean
    val bounds: Box
}

/** The active window's view tree. Implementations pass only visible nodes with non-empty bounds. */
interface WindowTree {
    /** Part of the screen the window really shows, without system bars; null when unknown. */
    val clip: Box?

    fun findByViewId(fullId: String, visit: (Node) -> Unit)

    /** Nodes whose text or content description contains [value], case-insensitive. */
    fun findByText(value: String, visit: (Node) -> Unit)

    /** Breadth-first over at most [limit] nodes. */
    fun walk(limit: Int, visit: (Node) -> Unit)
}

fun Matcher.matches(node: Node, pkg: String): Boolean {
    if (viewId != null && node.viewId != "$pkg:id/$viewId") return false
    if (text != null && !text.equals(node.text?.toString(), ignoreCase = true)) return false
    if (desc != null && !desc.equals(node.desc?.toString(), ignoreCase = true)) return false
    if (descContains != null && node.desc?.toString()?.contains(descContains, ignoreCase = true) != true) return false
    if (descStartsWith != null && node.desc?.toString()?.startsWith(descStartsWith, ignoreCase = true) != true) return false
    if (selected != null && node.isSelected != selected) return false
    return true
}

/** A string the app-side text search can pre-filter on; null when the matcher has none. */
private val Matcher.searchText: String? get() = text ?: desc ?: descContains ?: descStartsWith

/** Caps the fallback walk; each node costs an IPC into the target app. */
const val WALK_LIMIT = 300

/**
 * Finds the screen bounds each matcher matches. viewId and text lookups run inside the target
 * process, which is far cheaper than walking the tree. Views served by a custom accessibility
 * provider (YouTube's Compose and Litho screens) may not answer the text search. So when a text
 * matcher misses, a capped walk checks the misses at most every [probeIntervalMs]; if the walk finds
 * one, the whole package is walked from then on. Learning per package means a tab found on the first
 * screen also switches the feed tiles to the walk at once. Past [maxTextSearches] distinct strings,
 * one walk is cheaper than a search per string.
 */
class Scanner(
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
    private val maxTextSearches: Int = MAX_TEXT_SEARCHES,
    /** Packages learned earlier. Probes only run on events, so a still screen can wait long to be learned. */
    learned: Set<String> = emptySet(),
    private val onLearned: (Set<String>) -> Unit = {},
) {
    private val walkPackages = HashSet(learned)
    private val lastProbe = HashMap<String, Long>()

    /** [onDesc] receives the description of each node a matcher in [describe] matches. */
    fun scan(
        tree: WindowTree,
        pkg: String,
        matchers: Collection<Matcher>,
        now: Long,
        describe: Set<Matcher> = emptySet(),
        onDesc: (String) -> Unit = {},
    ): Map<Matcher, List<Box>> {
        val hits = HashMap<Matcher, MutableList<Box>>()
        fun check(candidates: List<Matcher>, node: Node) {
            for (m in candidates) {
                if (!m.matches(node, pkg)) continue
                hits.getOrPut(m) { ArrayList() }.add(node.bounds)
                if (m in describe) node.desc?.let { onDesc(it.toString()) }
            }
        }
        val (byId, rest) = matchers.distinct().partition { it.viewId != null }
        for ((fullId, group) in byId.groupBy { "$pkg:id/${it.viewId}" }) {
            tree.findByViewId(fullId) { check(group, it) }
        }

        val (searchable, unsearchable) = rest.partition { it.searchText != null }
        // The search ignores case, so needles differing only in case share one call into the app.
        val byNeedle = searchable.groupBy { it.searchText!!.lowercase() }
        val toWalk = ArrayList(unsearchable)
        var probes = emptyList<Matcher>()
        if (pkg in walkPackages || byNeedle.size > maxTextSearches) {
            toWalk += searchable
        } else {
            for ((needle, group) in byNeedle) tree.findByText(needle) { check(group, it) }
            val missed = searchable.filter { it !in hits }
            val last = lastProbe[pkg]
            if (missed.isNotEmpty() && (last == null || now - last >= probeIntervalMs)) {
                lastProbe[pkg] = now
                probes = missed
            }
        }

        val walked = toWalk + probes
        if (walked.isNotEmpty()) tree.walk(WALK_LIMIT) { check(walked, it) }
        if (probes.any { it in hits } && walkPackages.add(pkg)) onLearned(walkPackages.toSet())
        return hits
    }

    companion object {
        const val PROBE_INTERVAL_MS = 5_000L
        const val MAX_TEXT_SEARCHES = 8
    }
}
