package dev.grod

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * [WindowTree] over a live accessibility window. Every node it obtains is recycled before the call
 * returns; [root] stays owned by the caller.
 */
class A11yTree(private val root: AccessibilityNodeInfo) : WindowTree {
    private val rect = Rect()

    /**
     * The window's bounds as accessibility reports them, which leave out the parts covered by the
     * status and navigation bars. Needs flagRetrieveInteractiveWindows; without it the window is null.
     */
    override val clip: Box? by lazy {
        val window = root.window ?: return@lazy null
        try {
            window.getBoundsInScreen(rect)
            Box(rect.left, rect.top, rect.right, rect.bottom).takeUnless { it.isEmpty }
        } finally {
            recycle(window)
        }
    }

    override fun findByViewId(fullId: String, visit: (Node) -> Unit) =
        visitAll(root.findAccessibilityNodeInfosByViewId(fullId), visit)

    override fun findByText(value: String, visit: (Node) -> Unit) =
        visitAll(root.findAccessibilityNodeInfosByText(value), visit)

    /**
     * Clicks the first visible node with this id; used to close what a fired rule left running.
     * Some apps ignore the accessibility click (YouTube's miniplayer close does), so [tap] gets the
     * node's bounds to tap it like a finger instead. False when no such node is on screen.
     */
    fun click(fullId: String, tap: (Box) -> Unit): Boolean {
        var done = false
        for (info in root.findAccessibilityNodeInfosByViewId(fullId)) {
            try {
                val bounds = if (done) null else visibleBounds(info)
                if (bounds != null) {
                    if (!info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(bounds)
                    done = true
                }
            } finally {
                recycle(info)
            }
        }
        return done
    }

    private fun visitAll(found: List<AccessibilityNodeInfo>, visit: (Node) -> Unit) {
        for (info in found) {
            try {
                visibleBounds(info)?.let { visit(A11yNode(info, it)) }
            } finally {
                recycle(info)
            }
        }
    }

    override fun walk(limit: Int, visit: (Node) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var taken = 0
        var current: AccessibilityNodeInfo? = root
        while (current != null) {
            val info = current
            try {
                val bounds = visibleBounds(info)
                // Children of an invisible node are invisible too (IG keeps hidden tabs in the tree), so skip them.
                if (bounds != null) {
                    visit(A11yNode(info, bounds))
                    for (i in 0 until info.childCount) {
                        if (taken + queue.size + 1 >= limit) break
                        info.getChild(i)?.let(queue::addLast)
                    }
                }
            } finally {
                if (info !== root) recycle(info)
            }
            taken++
            current = queue.removeFirstOrNull()
        }
    }

    private fun visibleBounds(info: AccessibilityNodeInfo): Box? {
        if (!info.isVisibleToUser) return null
        info.getBoundsInScreen(rect)
        return Box(rect.left, rect.top, rect.right, rect.bottom).takeUnless { it.isEmpty }
    }

    private class A11yNode(private val info: AccessibilityNodeInfo, override val bounds: Box) : Node {
        override val viewId: String? get() = info.viewIdResourceName
        override val text: CharSequence? get() = info.text
        override val desc: CharSequence? get() = info.contentDescription
        override val isSelected: Boolean get() = info.isSelected
    }

    companion object {
        /** Recycling is a no-op from API 33; before that, leaked nodes exhaust the shared pool. */
        fun recycle(info: AccessibilityNodeInfo) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                info.recycle()
            }
        }

        private fun recycle(window: AccessibilityWindowInfo) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                window.recycle()
            }
        }
    }
}
