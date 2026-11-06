package dev.grod

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * grod's optional home screen (docs/rules.md, "Launcher (Android)"): app names as a centered word
 * cloud, sized by use, in grod colors. Distracting apps are always the smallest, muted word and open
 * only after [PauseActivity].
 */
class LauncherActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: RuleStore
    private lateinit var prefs: LauncherPrefs
    private lateinit var sizes: CloudSizes
    private lateinit var cloud: FlowLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var weatherText: TextView
    private lateinit var weather: WeatherSource
    private val main = Handler(Looper.getMainLooper())
    private val weatherTick = object : Runnable {
        override fun run() {
            showWeather()
            main.postDelayed(this, WEATHER_CHECK_MS)
        }
    }
    private var apps: List<AppEntry> = emptyList()
    private var results: List<AppEntry> = emptyList()

    private val onStoreChanged: () -> Unit = { render() }
    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = RuleStore.get(this)
        prefs = LauncherPrefs(this)
        sizes = CloudSizes(this)
        weather = WeatherSource(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Takes focus first, so the keyboard opens only when the search field is tapped.
            isFocusableInTouchMode = true
        }
        page.addView(header())
        page.addView(searchField(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(20) })
        cloud = FlowLayout(this, hGap = ui.dp(14), vGap = 0).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(cloud)
        }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        padForBars(page, ui.dp(16))
        setContentView(page)

        registerReceiver(packagesChanged, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        load()
    }

    override fun onResume() {
        super.onResume()
        store.addListener(onStoreChanged)
        render()
        // Weather refreshes only while the launcher is on screen; the source itself fetches at most hourly.
        main.post(weatherTick)
    }

    override fun onPause() {
        store.removeListener(onStoreChanged)
        main.removeCallbacks(weatherTick)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packagesChanged)
        super.onDestroy()
    }

    /** Home pressed while already home: clear the search and go back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        clearSearch()
    }

    /** Back on the home screen only clears the search; there is nothing behind home. */
    @Deprecated("Back is handled here for every API level the app supports.")
    override fun onBackPressed() = clearSearch()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = showWeather()

    /** Clock, then the date with the weather (when turned on) to its right. */
    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(4), ui.dp(16), ui.dp(4), 0)
        addView(TextClock(context).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            setTextColor(ui.color(R.color.grod_foreground))
        })
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextClock(context).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ui.color(R.color.grod_muted_foreground))
        })
        weatherText = ui.muted("", 16f).apply { visibility = View.GONE }
        line.addView(weatherText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(16) })
        addView(line)
    }

    private fun showWeather() = weather.update(this, store.rules.launcher.weather) { text ->
        weatherText.text = text.orEmpty()
        weatherText.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Always visible; the keyboard opens only when it is tapped. Enter opens the first match. */
    private fun searchField() = ui.input("Search apps").apply {
        search = this
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO
        setOnEditorActionListener { _, _, _ -> results.firstOrNull()?.let(::open); true }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
    }

    private fun clearSearch() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        search.clearFocus()
        scroll.scrollTo(0, 0)
    }

    /** Reads launchable apps and refreshes the day's sizes off the main thread. */
    private fun load() {
        val pm = packageManager
        val distracting = Launcher.distracting(store.rules, prefs.marks)
        thread(name = "grod-apps") {
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
            sizes.refreshIfStale(found.map { it.pkg }.toSet(), distracting)
            main.post {
                apps = found
                render()
            }
        }
    }

    private fun render() {
        val marks = prefs.marks
        val distracting = Launcher.distracting(store.rules, marks)
        val query = search.text.toString()
        results = if (query.isBlank()) emptyList() else Launcher.search(apps, query, distracting)
        // One stable alphabetical cloud; distracting words sit among the rest, just small and muted.
        val shown = if (query.isBlank()) {
            Launcher.list(apps, distracting, Launcher.hidden(store.rules, marks)).let { it.useful + it.distracting }
                .sortedWith(compareBy({ Launcher.fold(it.label) }, { it.label }))
        } else {
            results
        }
        cloud.removeAllViews()
        shown.forEach { cloud.addView(word(it, it.pkg in distracting)) }
        if (query.isNotBlank() && shown.isEmpty()) cloud.addView(ui.muted("No app matches.", 16f))
    }

    private fun word(app: AppEntry, distracting: Boolean): TextView {
        // Use never makes a distracting app bigger: it stays the smallest, muted word.
        val step = if (distracting) 0 else sizes.step(app.pkg)
        val color = if (step >= 2) R.color.grod_foreground else R.color.grod_muted_foreground
        return ui.text(app.label, Launcher.SIZES_SP[step], color).apply {
            setLineSpacing(0f, 1f)
            maxLines = 1
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            isClickable = true
            setOnClickListener { open(app) }
            setOnLongClickListener { menu(app); true }
        }
    }

    private fun open(app: AppEntry) {
        val distracting = app.pkg in Launcher.distracting(store.rules, prefs.marks)
        val pause = store.rules.launcher.pauseSeconds
        if (distracting && pause > 0) {
            startActivity(Intent(this, PauseActivity::class.java)
                .putExtra(PauseActivity.EXTRA_PKG, app.pkg)
                .putExtra(PauseActivity.EXTRA_COMPONENT, app.component)
                .putExtra(PauseActivity.EXTRA_LABEL, app.label))
        } else {
            if (distracting) logLauncher(app.pkg, "opened")
            launch(app)
        }
    }

    private fun menu(app: AppEntry) {
        val marks = prefs.marks
        val distracting = app.pkg in Launcher.distracting(store.rules, marks)
        val hidden = app.pkg in Launcher.hidden(store.rules, marks)
        val choices = arrayOf("App info", if (hidden) "Unhide" else "Hide", if (distracting) "Not distracting" else "Mark as distracting")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
                    1 -> prefs.marks = Launcher.toggleHidden(marks, app.pkg, app.pkg in store.rules.launcher.hidden, hidden)
                    2 -> prefs.marks = Launcher.toggleDistracting(marks, app.pkg, app.pkg in Launcher.distractingBy(store.rules), distracting)
                }
                render()
            }
            .show()
    }

    private companion object {
        const val WEATHER_CHECK_MS = 10 * 60 * 1000L
    }

    /** Base padding now; bar insets are added when the window reports them (edge to edge on Android 15). */
    private fun padForBars(view: View, base: Int) {
        view.setPadding(base, base, base, base)
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft,
                    base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight,
                    base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }
}
