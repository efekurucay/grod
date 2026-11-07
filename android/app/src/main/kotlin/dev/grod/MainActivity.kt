package dev.grod

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The app's four tabs: Today (what is missing, today's pauses, the next delivery), Notifications
 * (the history, [HistoryTab]), Apps (the rules and the scroll pause) and Settings (everything else).
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_REVIEW = "review"
        const val REVIEW_HOW = "Plug in or use Wi-Fi adb, then click the grod notification on your Mac."
        private const val K_TAB = "tab"
        private const val KEYBOARD_DP = 150
    }

    private enum class Tab(val label: String, val icon: Int) {
        TODAY("Today", R.drawable.lucide_sun),
        NOTIFICATIONS("Notifications", R.drawable.lucide_bell),
        APPS("Apps", R.drawable.lucide_layout_grid),
        SETTINGS("Settings", R.drawable.lucide_settings),
    }

    private lateinit var store: RuleStore
    private lateinit var ui: Ui
    private lateinit var history: HistoryTab
    private lateinit var tabBar: LinearLayout
    private lateinit var tabBarArea: LinearLayout
    private val pages = HashMap<Tab, View>()
    private lateinit var todayBody: LinearLayout
    private lateinit var appsBody: LinearLayout
    private lateinit var settingsTop: LinearLayout
    private lateinit var settingsBottom: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var urlInput: EditText
    private var reviewSection: View? = null
    private var tab = Tab.TODAY

    /** Today's scroll pauses, read from the events log; null until the first read is back. */
    private var pauses: PauseCount? = null

    /** Set when the weekly review notification opened the app; consumed by the next render. */
    private var scrollToReview = false
    private val onStoreChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        ui = Ui(this)
        history = HistoryTab(this, ui)

        pages[Tab.TODAY] = scrollPage { page ->
            page.addView(brand())
            todayBody = ui.column().also(page::addView)
        }
        pages[Tab.NOTIFICATIONS] = history.view
        pages[Tab.APPS] = scrollPage { page ->
            page.addView(title("Apps"))
            appsBody = ui.column().also(page::addView)
        }
        settingsScroll = scrollPage { page ->
            page.addView(title("Settings"))
            settingsTop = ui.column().also(page::addView)
            // The field lives outside the re-rendered parts so typing survives a refresh.
            page.addView(urlSection())
            settingsBottom = ui.column().also(page::addView)
        }
        pages[Tab.SETTINGS] = settingsScroll

        val frame = FrameLayout(this)
        for (page in pages.values) frame.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in Tab.entries) tabBar.addView(tabButton(t), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabBarArea = ui.column().apply {
            addView(View(context).apply { setBackgroundColor(ui.color(R.color.grod_border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(tabBar)
        }
        val root = ui.column().apply {
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(tabBarArea, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        applySystemBarPadding(root)
        hideTabBarWithKeyboard(root)
        setContentView(root)

        scrollToReview = intent.getStringExtra(EXTRA_SECTION) == SECTION_REVIEW
        val saved = savedInstanceState?.getString(K_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        show(if (scrollToReview) Tab.SETTINGS else saved ?: Tab.TODAY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(K_TAB, tab.name)
    }

    override fun onResume() {
        super.onResume()
        store.addListener(onStoreChanged)
        store.checkLocal()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scrollToReview = intent.getStringExtra(EXTRA_SECTION) == SECTION_REVIEW
        if (scrollToReview) show(Tab.SETTINGS)
    }

    override fun onPause() {
        store.removeListener(onStoreChanged)
        history.commit()
        super.onPause()
    }

    private fun show(next: Tab) {
        if (tab == Tab.NOTIFICATIONS && next != Tab.NOTIFICATIONS) history.commit()
        tab = next
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(tabBar.windowToken, 0)
        for ((t, page) in pages) page.visibility = if (t == next) View.VISIBLE else View.GONE
        for (i in 0 until tabBar.childCount) {
            val button = tabBar.getChildAt(i) as LinearLayout
            val active = button.tag == next
            // Active in the foreground color, the rest muted: no accent, no badge.
            val color = ui.color(if (active) R.color.grod_foreground else R.color.grod_muted_foreground)
            (button.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (button.getChildAt(1) as TextView).apply {
                setTextColor(color)
                typeface = if (active) ui.strong else Typeface.DEFAULT
            }
            button.isSelected = active
        }
        refresh()
    }

    /** Re-reads what the visible tab shows from the logs, then draws it. */
    private fun refresh() {
        when (tab) {
            Tab.TODAY -> loadPauses()
            Tab.NOTIFICATIONS -> history.load()
            else -> Unit
        }
        render()
    }

    private fun render() {
        when (tab) {
            Tab.TODAY -> today()
            Tab.NOTIFICATIONS -> Unit
            Tab.APPS -> apps()
            Tab.SETTINGS -> settingsPage()
        }
    }

    private fun tabButton(t: Tab) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = ui.dp(56)
        setPadding(0, ui.dp(8), 0, ui.dp(6))
        tag = t
        contentDescription = t.label
        isClickable = true
        isFocusable = true
        setOnClickListener { if (tab != t) show(t) }
        addView(ui.icon(t.icon), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        addView(ui.muted(t.label, 12f).apply {
            setPadding(0, ui.dp(4), 0, 0)
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): ScrollView {
        val page = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24)) }
        build(page)
        return ScrollView(this).apply { addView(page) }
    }

    private fun title(text: String) = ui.text(text, 24f, bold = true).apply { setPadding(0, ui.dp(16), 0, 0) }

    private fun brand() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(13), ui.dp(16), ui.dp(13), 0)
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The mark is a "g" with a descender, so it sits a little lower than the wordmark's center.
        brand.addView(
            ImageView(context).apply { setImageResource(R.drawable.grod_mark); translationY = ui.dp(3).toFloat() },
            LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginStart = -ui.dp(4) },
        )
        brand.addView(ui.text("grod", 24f, bold = true).apply { setPadding(ui.dp(8), 0, 0, 0) })
        addView(brand)
        addView(ui.muted("Get rid of distracting. Keep what matters.", 15f).apply { setPadding(0, ui.dp(4), 0, 0) })
    }

    private fun today() {
        todayBody.removeAllViews()
        setup(todayBody, always = false)
        val body = ui.section(todayBody, "Today")
        val p = pauses
        when {
            p == null -> ui.kv(body, "Scroll pauses", "…")
            p.paused == 0 -> ui.kv(body, "Scroll pauses", "None")
            else -> {
                ui.kv(body, "Scroll pauses", "${p.paused}")
                ui.kv(body, "Left", "${p.left}")
                ui.kv(body, "Continued", "${p.continued}")
            }
        }
        val s = store.rules.notifications
        if (s != null && s.enabled) {
            val now = System.currentTimeMillis()
            ui.kv(body, "Next delivery", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Batching.nextDelivery(now, s.slots, ZoneId.systemDefault()))))
            ui.kv(body, "Waiting", "${Held(this).waiting(now).size}")
        }
        ui.kvEnd(body)
    }

    private fun loadPauses() {
        val date = LocalDate.now()
        LogWriter.get(this).onLogThread { dir ->
            val count = try {
                PauseCount.of(LogFolder(dir).read("events", date))
            } catch (e: IOException) {
                null
            }
            runOnUiThread {
                pauses = count ?: PauseCount(0, 0, 0)
                if (tab == Tab.TODAY && !isDestroyed) today()
            }
        }
    }

    private fun apps() {
        appsBody.removeAllViews()
        val s = store.rules.scroll
        val growth = if (s.growth % 1.0 == 0.0) "${s.growth.toInt()}" else "${s.growth}"
        val pause = ui.section(appsBody, "Scroll pause")
        pause.addView(ui.muted(
            "Scroll pause after every ${s.freeSeconds} s of scrolling: ${s.firstPauseSeconds} s, then ×$growth each time, " +
                "up to ${s.maxPauseSeconds} s. Starts over after ${s.resetMinutes} min without scrolling.",
            15f,
        ).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) })
        val body = ui.section(appsBody, "Scrolling counts in")
        for (rule in store.rules.rules) {
            val meta = if (rule.android != null) "paused ${store.fireCount(rule.id)}×" else "not on Android"
            val end = ui.muted(if (rule.enabled) "On" else "Off", 15f)
            ui.row(body, rule.title, meta, end, off = !rule.enabled)
        }
        if (store.rules.rules.isEmpty()) ui.row(body, "No rules yet", off = true)
    }

    private fun settingsPage() {
        settingsTop.removeAllViews()
        notifications(settingsTop)
        launcher(settingsTop)
        review(settingsTop)
        settingsBottom.removeAllViews()
        status(settingsBottom)
        setup(settingsBottom, always = true)
        if (scrollToReview) {
            scrollToReview = false
            reviewSection?.let { target ->
                settingsScroll.post { settingsScroll.smoothScrollTo(0, settingsTop.top + target.top - ui.dp(48)) }
            }
        }
    }

    private fun urlSection(): LinearLayout {
        val section = ui.column()
        val body = ui.section(section, "Rules URL")
        urlInput = ui.input("https://example.com/grod.rules.json").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.rulesUrl.orEmpty())
        }
        body.addView(ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            addView(urlInput)
            addView(ui.muted("Optional. An HTTPS copy of grod.rules.json, checked every 6 hours.").apply {
                setPadding(0, ui.dp(4), 0, ui.dp(12))
            })
            addView(ui.button("Save & refresh", Ui.Kind.PRIMARY) {
                store.rulesUrl = urlInput.text.toString()
                store.checkLocal()
                store.fetch()
                render()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        })
        return section
    }

    private fun review(parent: LinearLayout) {
        val body = ui.section(parent, "Weekly review")
        reviewSection = body
        val review = store.rules.review
        ui.kv(body, "When", review?.label ?: "Not set in the rules")
        if (review != null) {
            body.addView(ui.muted(REVIEW_HOW, 15f).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0) })
        }
        ui.kvEnd(body)
    }

    private class Step(val title: String, val why: String, val done: Boolean, val open: () -> Unit)

    private fun settings(action: String): () -> Unit = { startActivity(Intent(action)) }

    /**
     * Every access grod needs, with a shortcut to grant it. Today shows it only while something is
     * missing, so a sideloaded install can be finished without adb; Settings always shows it.
     */
    private fun setup(parent: LinearLayout, always: Boolean) {
        val steps = mutableListOf(
            Step("Accessibility service", "Pauses endless scrolling. Find grod under Downloaded apps.",
                Permissions.accessibilityService(this), settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Step("Notification access", "Holds notifications until the next delivery.",
                Permissions.notificationAccess(this), settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            Step("Do Not Disturb access", "Keeps the phone quiet while notifications wait. Calls still ring.",
                Permissions.dndAccess(this), settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            Step("Usage access", "Screen time per app, for your own review.",
                Permissions.usageAccess(this), settings(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
        )
        // Android 13+ asks before an app may post; grod only posts the weekly review reminder.
        if (Build.VERSION.SDK_INT >= 33) {
            steps.add(Step("Notifications", "For the weekly review reminder.",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0) })
        }
        val allDone = steps.all { it.done }
        if (allDone && !always) return
        val body = ui.section(parent, if (allDone) "Permissions" else "Set up ${steps.count { it.done }} of ${steps.size}")
        for ((i, step) in steps.withIndex()) {
            val end = if (step.done) ui.muted("Done", 15f) else ui.button("Open", onClick = step.open)
            // Android 13+ greys out these switches for sideloaded apps until restricted settings are allowed.
            val restricted = Build.VERSION.SDK_INT >= 33 && !step.done && i < 2
            val extra = if (!restricted) null else ui.column().apply {
                addView(ui.muted("Greyed out? App info → ⋮ → Allow restricted settings."))
                addView(ui.button("App info", Ui.Kind.GHOST) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                })
            }
            ui.row(body, step.title, step.why, end, extra = extra)
        }
    }

    private fun status(parent: LinearLayout) {
        val body = ui.section(parent, "Status")
        val on = Permissions.accessibilityService(this)
        // The one accent on the screen: grod is on.
        ui.kv(body, "Blocking", if (on) "On" else "Off", if (on) R.color.grod_accent else R.color.grod_muted_foreground)
        ui.kv(body, "Rules", store.revision)
        ui.kv(body, "Source", store.source)
        store.lastError?.let { ui.kv(body, "Last error", it, R.color.grod_caution) }
        if (store.rulesUrl != null) {
            val fetched = store.lastFetchAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
            ui.kv(body, "Last fetch", fetched ?: "never")
        }
        ui.kvEnd(body)
    }

    private fun notifications(parent: LinearLayout) {
        val body = ui.section(parent, "Notifications")
        val s = store.rules.notifications
        if (s == null || !s.enabled) {
            ui.kv(body, "Batching", if (s == null) "Not set in the rules" else "Off")
            ui.kvEnd(body)
            return
        }
        val now = System.currentTimeMillis()
        val schedule = s.deliverAt?.joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) } ?: "Every ${s.deliverEvery} min"
        ui.kv(body, "Batching", "On")
        ui.kv(body, "Delivers", schedule)
        ui.kv(body, "Next", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Batching.nextDelivery(now, s.slots, ZoneId.systemDefault()))))
        ui.kv(body, "Waiting", "${Held(this).waiting(now).size}")
        ui.kvEnd(body)
    }

    private fun launcher(parent: LinearLayout) {
        val body = ui.section(parent, "Launcher")
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        val isHome = home?.activityInfo?.packageName == packageName
        ui.kv(body, "Home screen", if (isHome) "grod" else "Another app")
        body.addView(ui.column().apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12))
            addView(ui.muted("Optional. Apps as a calm text list; distracting ones last and behind a pause.", 15f).apply {
                setPadding(0, 0, 0, ui.dp(12))
            })
            addView(ui.button("Choose home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
    }

    /** targetSdk 35 draws edge to edge, so the content has to keep clear of the system bars itself. */
    private fun applySystemBarPadding(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /** While the keyboard is up (searching notifications), the tab bar steps aside for the list. */
    private fun hideTabBarWithKeyboard(root: View) {
        val visible = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(visible)
            val keyboard = root.rootView.height - visible.bottom > ui.dp(KEYBOARD_DP)
            val want = if (keyboard) View.GONE else View.VISIBLE
            if (tabBarArea.visibility != want) tabBarArea.visibility = want
        }
    }
}
