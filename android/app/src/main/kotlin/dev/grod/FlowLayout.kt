package dev.grod

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Words in rows: children wrap left to right, each row is centered, words of different sizes share
 * a baseline, and the whole block sits in the vertical middle when it is shorter than the view.
 */
// Built only in code, never inflated from XML, so the XML constructors are not needed. Rows are
// rebuilt in onMeasure, which runs when the words change, not on every frame.
@SuppressLint("ViewConstructor", "DrawAllocation")
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    private class Row(val children: List<View>, val width: Int, val ascent: Int, val descent: Int)

    private var rows: List<Row> = emptyList()
    private var contentHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        val built = ArrayList<Row>()
        var current = ArrayList<View>()
        var rowWidth = 0
        fun close() {
            if (current.isEmpty()) return
            val ascent = current.maxOf { baseline(it) }
            val descent = current.maxOf { it.measuredHeight - baseline(it) }
            built.add(Row(current, rowWidth, ascent, descent))
            current = ArrayList()
            rowWidth = 0
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            val added = if (current.isEmpty()) child.measuredWidth else rowWidth + hGap + child.measuredWidth
            if (current.isNotEmpty() && added > available) close()
            rowWidth = if (current.isEmpty()) child.measuredWidth else rowWidth + hGap + child.measuredWidth
            current.add(child)
        }
        close()
        rows = built
        contentHeight = paddingTop + paddingBottom + built.sumOf { it.ascent + it.descent } + vGap * maxOf(0, built.size - 1)
        setMeasuredDimension(width, resolveSize(contentHeight, heightMeasureSpec).coerceAtLeast(contentHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = paddingTop + maxOf(0, (b - t) - contentHeight) / 2
        val available = (r - l) - paddingLeft - paddingRight
        for (row in rows) {
            var left = paddingLeft + (available - row.width) / 2
            for (child in row.children) {
                val y = top + row.ascent - baseline(child)
                child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
                left += child.measuredWidth + hGap
            }
            top += row.ascent + row.descent + vGap
        }
    }

    private fun baseline(child: View) = child.baseline.takeIf { it >= 0 } ?: child.measuredHeight
}
