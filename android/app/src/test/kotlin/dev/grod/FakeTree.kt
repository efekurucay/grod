package dev.grod

class FakeNode(
    override val viewId: String? = null,
    private val textValue: String? = null,
    private val descValue: String? = null,
    override val isSelected: Boolean = false,
    override val bounds: Box = Box(0, 0, 100, 100),
    val visible: Boolean = true,
) : Node {
    var textReads = 0

    override val text: CharSequence?
        get() {
            textReads++
            return textValue
        }
    override val desc: CharSequence? get() = descValue
}

/** Flat stand-in for a window; applies the same visibility filter as the Android tree. */
class FakeTree(private val nodes: List<FakeNode>) : WindowTree {
    var walks = 0
    var walkLimit = -1

    private val shown get() = nodes.filter { it.visible && !it.bounds.isEmpty }

    override fun findByViewId(fullId: String, visit: (Node) -> Unit) {
        shown.filter { it.viewId == fullId }.forEach(visit)
    }

    override fun walk(limit: Int, visit: (Node) -> Unit) {
        walks++
        walkLimit = limit
        shown.take(limit).forEach(visit)
    }
}
