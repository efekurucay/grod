package dev.grod

/** Screen rectangle; a plain class so matching runs in JVM unit tests without android.graphics.Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(other: Box): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
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
    fun findByViewId(fullId: String, visit: (Node) -> Unit)

    /** Breadth-first over at most [limit] nodes. */
    fun walk(limit: Int, visit: (Node) -> Unit)
}

fun Matcher.matches(node: Node, pkg: String): Boolean {
    if (viewId != null && node.viewId != "$pkg:id/$viewId") return false
    if (text != null && !text.equals(node.text?.toString(), ignoreCase = true)) return false
    if (desc != null && !desc.equals(node.desc?.toString(), ignoreCase = true)) return false
    if (selected != null && node.isSelected != selected) return false
    return true
}

/** Caps the fallback walk for text/desc-only matchers; each node costs an IPC into the target app. */
const val WALK_LIMIT = 300

/**
 * Bounds of every node each matcher matches. Matchers with a viewId use the id lookup, which runs
 * inside the target process; the rest share a single capped walk.
 */
fun scan(tree: WindowTree, pkg: String, matchers: Collection<Matcher>): Map<Matcher, List<Box>> {
    val hits = HashMap<Matcher, MutableList<Box>>()
    fun check(candidates: List<Matcher>, node: Node) {
        for (m in candidates) if (m.matches(node, pkg)) hits.getOrPut(m) { ArrayList() }.add(node.bounds)
    }
    val (byId, byWalk) = matchers.distinct().partition { it.viewId != null }
    for ((fullId, group) in byId.groupBy { "$pkg:id/${it.viewId}" }) {
        tree.findByViewId(fullId) { check(group, it) }
    }
    if (byWalk.isNotEmpty()) tree.walk(WALK_LIMIT) { check(byWalk, it) }
    return hits
}
