package dev.grod

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Covers the recommendation grid in Instagram's native Explore tab. */
@Suppress("DEPRECATION") // Recycle accessibility nodes on Android versions that pool them.
class InstagramExploreService : AccessibilityService() {
    private lateinit var store: RuleStore
    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private var coveredBounds: Rect? = null
    private var queued = false

    private val refresh = Runnable {
        queued = false
        checkExplore()
    }
    private val foregroundCheck = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val onInstagram = try {
                root?.packageName?.toString() == INSTAGRAM
            } finally {
                root?.recycle()
            }
            if (onInstagram) main.postDelayed(this, FOREGROUND_CHECK_MS) else hideCover()
        }
    }
    private val onRulesChanged: () -> Unit = { queueCheck() }

    override fun onServiceConnected() {
        windows = getSystemService(WindowManager::class.java)
        store = RuleStore.get(this)
        store.addListener(onRulesChanged)
        store.checkLocal()
        queueCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != INSTAGRAM) return
        store.onEvent()
        queueCheck()
    }

    private fun queueCheck() {
        if (queued) return
        queued = true
        main.postDelayed(refresh, EVENT_DELAY_MS)
    }

    private fun checkExplore() {
        val bounds = if (store.rules.rulesFor(INSTAGRAM).any { it.id == "instagram" }) exploreBounds() else null
        if (bounds == null) {
            hideCover()
            return
        }
        showCover(bounds)
    }

    private fun exploreBounds(): Rect? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != INSTAGRAM) return null
            if (root.uniqueView("search_tab") { it.isSelected } != true) return null
            val toolbar = root.uniqueView("explore_action_bar") { it.bounds() } ?: return null
            val tabs = root.uniqueView("tab_bar") { it.bounds() } ?: return null
            val grid = root.uniqueView("layout_recyclerview_parent_container") { holder ->
                holder.uniqueView("recycler_view") { it.bounds() }
            } ?: return null
            // Instagram's scroll container extends behind the floating search bar.
            return grid.takeIf { it.intersect(toolbar.left, toolbar.bottom, toolbar.right, tabs.top) }
        } finally {
            root.recycle()
        }
    }

    private fun <T> AccessibilityNodeInfo.uniqueView(id: String, read: (AccessibilityNodeInfo) -> T): T? {
        val matches = findAccessibilityNodeInfosByViewId("$INSTAGRAM:id/$id")
        try {
            return matches.singleOrNull { it.isVisibleToUser }?.let(read)
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    private fun AccessibilityNodeInfo.bounds() = Rect().also(::getBoundsInScreen)

    private fun showCover(bounds: Rect) {
        if (bounds == coveredBounds) return
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            title = getString(R.string.instagram_explore_label)
        }
        val current = cover
        if (current != null) {
            windows.updateViewLayout(current, params)
        } else {
            val view = Ui(this).muted(getString(R.string.instagram_explore_hidden)).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(getColor(R.color.grod_background))
                isClickable = true
            }
            try {
                windows.addView(view, params)
            } catch (_: WindowManager.BadTokenException) {
                return // Android can revoke the service's window token while it is being disabled.
            }
            cover = view
            // Package-filtered callbacks don't report leaving Instagram for another app.
            main.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS)
        }
        coveredBounds = bounds
    }

    private fun hideCover() {
        main.removeCallbacks(foregroundCheck)
        cover?.let { windows.removeViewImmediate(it) }
        cover = null
        coveredBounds = null
    }

    override fun onInterrupt() {
        main.removeCallbacks(refresh)
        queued = false
        hideCover()
    }

    override fun onDestroy() {
        if (::store.isInitialized) store.removeListener(onRulesChanged)
        onInterrupt()
        super.onDestroy()
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val EVENT_DELAY_MS = 40L
        const val FOREGROUND_CHECK_MS = 150L
    }
}
