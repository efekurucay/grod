package dev.grod

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Opaque boxes over nodes such as the Reels tab. TYPE_ACCESSIBILITY_OVERLAY needs no
 * SYSTEM_ALERT_WINDOW permission, and without FLAG_NOT_TOUCHABLE the box swallows taps.
 * Must be used from the service's main thread with the service as [context].
 */
class Overlays(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = ArrayList<View>()
    private val placed = ArrayList<Box>()

    val isShowing: Boolean get() = views.isNotEmpty()

    /** Shows exactly [boxes], reusing existing windows so a moving node does not flicker. */
    fun show(boxes: List<Box>) {
        while (views.size > boxes.size) {
            wm.removeViewImmediate(views.removeAt(views.lastIndex))
            placed.removeAt(placed.lastIndex)
        }
        boxes.forEachIndexed { i, box ->
            if (i < views.size) {
                if (placed[i] != box) {
                    wm.updateViewLayout(views[i], params(box))
                    placed[i] = box
                }
            } else {
                val view = View(context).apply { setBackgroundColor(COLOR) }
                wm.addView(view, params(box))
                views.add(view)
                placed.add(box)
            }
        }
    }

    fun clear() = show(emptyList())

    private fun params(box: Box) = WindowManager.LayoutParams(
        box.right - box.left,
        box.bottom - box.top,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        title = "grod cover"
    }

    private companion object {
        const val COLOR = 0xFF121212.toInt()
    }
}
