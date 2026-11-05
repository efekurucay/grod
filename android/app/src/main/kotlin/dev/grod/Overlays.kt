package dev.grod

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Opaque boxes over nodes. TYPE_ACCESSIBILITY_OVERLAY needs no SYSTEM_ALERT_WINDOW permission.
 * A [touchable] pool (cover) swallows taps; the other (mask) lets touches and scrolls through.
 * Windows are pooled: while the app stays in front, boxes that go away are parked rather than removed,
 * so a scrolling grid of masks does not add and remove windows on every event.
 * Must be used from the service's main thread with the service as [context].
 */
class Overlays(private val context: Context, private val touchable: Boolean) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = ArrayList<View>()

    /** Box each pooled window shows; null while parked. */
    private val placed = ArrayList<Box?>()

    val isShowing: Boolean get() = placed.any { it != null }

    /** Shows exactly [boxes]. */
    fun show(boxes: List<Box>) {
        boxes.forEachIndexed { i, box -> if (i < views.size) place(i, box) else add(box) }
        for (i in boxes.size until views.size) park(i)
    }

    /** Removes every window; used when the app leaves the front or rules change. */
    fun clear() {
        views.forEach(wm::removeViewImmediate)
        views.clear()
        placed.clear()
    }

    private fun add(box: Box) {
        val view = View(context).apply { setBackgroundColor(COLOR) }
        wm.addView(view, params(box, touchable))
        views.add(view)
        placed.add(box)
    }

    private fun place(i: Int, box: Box) {
        if (placed[i] == box) return
        if (placed[i] == null) views[i].visibility = View.VISIBLE
        wm.updateViewLayout(views[i], params(box, touchable))
        placed[i] = box
    }

    /** A parked window is hidden and never touchable: an invisible window that still took taps would block the app. */
    private fun park(i: Int) {
        if (placed[i] == null) return
        views[i].visibility = View.GONE
        wm.updateViewLayout(views[i], params(PARKED, touchable = false))
        placed[i] = null
    }

    private fun params(box: Box, touchable: Boolean) = WindowManager.LayoutParams(
        box.right - box.left,
        box.bottom - box.top,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.OPAQUE,
    ).apply {
        // Node bounds are screen coordinates. The window must not be pushed down by the status bar
        // or a display cutout, otherwise x/y would be offset from the node it covers.
        gravity = Gravity.TOP or Gravity.START
        x = box.left
        y = box.top
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = if (touchable) "grod cover" else "grod mask"
    }

    private companion object {
        const val COLOR = 0xFF121212.toInt()
        val PARKED = Box(0, 0, 1, 1)
    }
}
