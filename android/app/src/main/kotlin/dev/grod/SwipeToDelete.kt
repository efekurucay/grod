package dev.grod

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ListView
import kotlin.math.abs

/**
 * Swipe a [list] row to the left to delete it. [slider] gives the part of a row that follows the
 * finger, or null for rows that cannot be deleted (day headers). A row let go past a third of its
 * width, or flung left, fades out and [onSwiped] gets its adapter item; otherwise it slides back.
 * Set as the list's OnTouchListener, so the list still scrolls and only a clearly sideways drag swipes.
 */
class SwipeToDelete(
    private val list: ListView,
    private val slider: (View) -> View?,
    private val onSwiped: (Any?) -> Unit,
) : View.OnTouchListener {
    private val config = ViewConfiguration.get(list.context)
    private val slop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity * 8
    private var target: View? = null
    private var item: Any? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var velocity: VelocityTracker? = null

    // Clicks are not used on this list; the list's own onTouchEvent still gets every event we do not take.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                val position = list.pointToPosition(e.x.toInt(), e.y.toInt())
                if (position == ListView.INVALID_POSITION) return false
                val row = list.getChildAt(position - list.firstVisiblePosition) ?: return false
                target = slider(row) ?: return false
                item = list.getItemAtPosition(position)
                downX = e.x
                downY = e.y
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val view = target ?: return false
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!swiping) {
                    // The list is scrolling: leave the row alone for the rest of this gesture.
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                        reset()
                        return false
                    }
                    // Clearly sideways only, so a slanted scroll never starts a delete.
                    if (dx < -slop && abs(dx) > 2 * abs(dy)) {
                        swiping = true
                        list.requestDisallowInterceptTouchEvent(true)
                        // Stops the list's own scroll and press handling for this gesture.
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        list.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (swiping) {
                    val x = minOf(0f, dx)
                    view.translationX = x
                    view.alpha = 1f - (abs(x) / view.width).coerceIn(0f, 0.7f)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val view = target
                if (!swiping || view == null) {
                    reset()
                    return false
                }
                val tracker = velocity!!.apply { addMovement(e); computeCurrentVelocity(1000) }
                val gone = view.translationX < -view.width / 3f || (tracker.xVelocity < -minFling && view.translationX < 0)
                val swipedItem = item
                if (gone) {
                    view.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(DecelerateInterpolator())
                        .withEndAction { onSwiped(swipedItem) }
                } else {
                    settle(view)
                }
                reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                target?.let(::settle)
                reset()
            }
        }
        return false
    }

    private fun settle(view: View) {
        view.animate().translationX(0f).alpha(1f).setDuration(SETTLE_MS).setInterpolator(DecelerateInterpolator())
    }

    private fun reset() {
        target = null
        item = null
        swiping = false
        velocity?.recycle()
        velocity = null
    }

    private companion object {
        // ui/README.md motion: 120 or 180 ms, ease-out; the system animator scale (reduced motion) applies.
        const val SETTLE_MS = 120L
        const val FADE_MS = 180L
    }
}
