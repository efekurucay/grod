package dev.grod

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [WindowTree] over a live accessibility window. Every node it obtains is recycled before the call
 * returns; [root] stays owned by the caller.
 */
class A11yTree(private val root: AccessibilityNodeInfo) : WindowTree {
    private val rect = Rect()

    override fun findByViewId(fullId: String, visit: (Node) -> Unit) {
        for (info in root.findAccessibilityNodeInfosByViewId(fullId)) {
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
    }
}
