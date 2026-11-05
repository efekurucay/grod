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
    if (selected != null && node.isSelected != selected) return false
    return true
}

/** A string the app-side text search can pre-filter on; null when the matcher has none. */
private val Matcher.searchText: String? get() = text ?: desc ?: descContains

/** Caps the fallback walk; each node costs an IPC into the target app. */
const val WALK_LIMIT = 300

/**
 * Finds the screen bounds each matcher matches. viewId and text lookups run inside the target
 * process, which is far cheaper than walking the tree. Views served by a custom accessibility
 * provider (some Compose and Litho screens) may not answer the text search, so a text matcher the
 * search misses is checked with a capped walk at most every [probeIntervalMs]; once the walk finds
 * it, that matcher is walked from then on in that package.
 */
class Scanner(private val probeIntervalMs: Long = PROBE_INTERVAL_MS) {
    private val walkOnly = HashSet<Pair<String, Matcher>>()
    private val lastProbe = HashMap<Pair<String, Matcher>, Long>()

    fun scan(tree: WindowTree, pkg: String, matchers: Collection<Matcher>, now: Long): Map<Matcher, List<Box>> {
        val hits = HashMap<Matcher, MutableList<Box>>()
        fun check(candidates: List<Matcher>, node: Node) {
            for (m in candidates) if (m.matches(node, pkg)) hits.getOrPut(m) { ArrayList() }.add(node.bounds)
        }
        val (byId, rest) = matchers.distinct().partition { it.viewId != null }
        for ((fullId, group) in byId.groupBy { "$pkg:id/${it.viewId}" }) {
            tree.findByViewId(fullId) { check(group, it) }
        }

        val toWalk = ArrayList<Matcher>()
        val probes = ArrayList<Matcher>()
        val searchable = rest.filter { m ->
            (m.searchText != null && (pkg to m) !in walkOnly).also { if (!it) toWalk.add(m) }
        }
        // The search ignores case, so needles differing only in case share one call into the app.
        for ((needle, group) in searchable.groupBy { it.searchText!!.lowercase() }) {
            tree.findByText(needle) { check(group, it) }
            for (m in group) {
                if (m in hits) continue
                val last = lastProbe[pkg to m]
                if (last == null || now - last >= probeIntervalMs) {
                    lastProbe[pkg to m] = now
                    probes.add(m)
                }
            }
        }

        val walked = toWalk + probes
        if (walked.isNotEmpty()) tree.walk(WALK_LIMIT) { check(walked, it) }
        for (m in probes) if (m in hits) walkOnly.add(pkg to m)
        return hits
    }

    companion object {
        const val PROBE_INTERVAL_MS = 5_000L
    }
}
