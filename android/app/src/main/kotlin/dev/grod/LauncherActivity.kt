package dev.grod

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
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
 * grod's optional home screen (docs/rules.md, "Launcher (Android)"): the apps opened in the last
 * week as a centered word cloud, sized and ordered by use, in grod colors. Distracting apps are always the smallest, muted word; the
 * opening check catches them once they are in front, whatever route opened them.
 */
class LauncherActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: RuleStore
    private lateinit var prefs: LauncherPrefs
    private lateinit var sizes: CloudSizes
    private lateinit var cloud: FlowLayout
    private lateinit var scroll: ScrollView
    private lateinit var dockRow: LinearLayout
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
        page.addView(searchRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(20) })
        cloud = FlowLayout(this, hGap = ui.dp(14), vGap = 0).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(cloud)
        }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        page.addView(dockRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        // The cloud is fitted to the space it has; refit when that space changes (keyboard, rotation).
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scroll.post(::fit)
        }
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

    /** The search field, with a microphone when the phone has a speech recognizer. */
    private fun searchRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(searchField(), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (voiceIntent().resolveActivity(packageManager) != null) {
            addView(ui.iconButton(R.drawable.lucide_mic, "Search by voice") {
                try {
                    startActivityForResult(voiceIntent(), REQUEST_VOICE)
                } catch (e: ActivityNotFoundException) {
                    // The recognizer went away since the check; the field still works.
                }
            })
        }
    }

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an app")

    /** Voice search: what [Launcher.voiceQuery] finds fills the search; a single match opens at once. */
    @Deprecated("startActivityForResult is used for every API level the app supports.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val phrase = Launcher.voiceQuery(apps, heard) ?: heard.firstOrNull() ?: return
        search.setText(phrase)
        search.setSelection(phrase.length)
        results.singleOrNull()?.let(::open)
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
            val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.toSet()
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
            val found = Launcher.withoutHomes(launchable, homes, packageName)
            // The cloud shows at once from the last pass; the day's first pass reads usage stats, which takes a moment.
            main.post {
                apps = found
                render()
            }
            // Only apps the user installed can be uninstalled, so only those are suggested.
            val installedAt = found.map { it.pkg }.toSet().minus(packageName).mapNotNull { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0).takeIf { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }
                        ?.let { pkg to it.firstInstallTime }
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }.toMap()
            sizes.refreshIfStale(found.map { it.pkg }.toSet(), distracting, installedAt)
            main.post(::render)
        }
    }

    private fun render() {
        val marks = prefs.marks
        val distracting = Launcher.distracting(store.rules, marks)
        val query = search.text.toString()
        results = if (query.isBlank()) emptyList() else Launcher.search(apps, query, distracting)
        val dock = Launcher.dock(store.rules, marks).mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
        showDock(dock)
        // Most used first; distracting words sit among the least used, small and muted.
        val shown = if (query.isBlank()) {
            Launcher.list(apps, distracting, Launcher.hidden(store.rules, marks), sizes.recent(), docked = dock.map { it.pkg }.toSet())
                .let { Launcher.byUse(it.useful + it.distracting) { pkg -> if (pkg in distracting) 0 else sizes.step(pkg) } }
        } else {
            results
        }
        cloud.removeAllViews()
        shown.forEach { cloud.addView(word(it, it.pkg in distracting)) }
        if (query.isNotBlank() && shown.isEmpty()) cloud.addView(ui.muted("No app matches.", 16f))
        scroll.post(::fit)
    }

    /**
     * Scales every word by one factor so the whole cloud fits on the screen without scrolling, as on
     * a home screen; the size steps keep their ratios. Below 0.55 the words get too small, so a
     * larger cloud scrolls instead.
     */
    private fun fit() {
        val available = scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (available <= 0 || cloud.childCount == 0) return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(scroll.width - scroll.paddingLeft - scroll.paddingRight, View.MeasureSpec.EXACTLY)
        fun apply(scale: Double) {
            for (i in 0 until cloud.childCount) {
                val word = cloud.getChildAt(i) as TextView
                val step = word.tag as? Int ?: continue
                word.setTextSize(TypedValue.COMPLEX_UNIT_SP, (Launcher.SIZES_SP[step] * scale).toFloat())
                word.minHeight = maxOf(ui.dp(MIN_TOUCH_DP), (ui.dp(TOUCH_DP) * scale).toInt())
            }
        }
        val scale = Launcher.fitScale { candidate ->
            apply(candidate)
            cloud.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            cloud.measuredHeight <= available
        } ?: MIN_SCALE
        apply(scale)
        cloud.requestLayout()
    }

    private fun word(app: AppEntry, distracting: Boolean): TextView {
        // Use never makes a distracting app bigger: it stays the smallest, muted word.
        val step = if (distracting) 0 else sizes.step(app.pkg)
        val color = if (step >= 2) R.color.grod_foreground else R.color.grod_muted_foreground
        return ui.text(app.label, Launcher.SIZES_SP[step], color).apply {
            tag = step
            setLineSpacing(0f, 1f)
            maxLines = 1
            gravity = Gravity.CENTER
            minHeight = ui.dp(TOUCH_DP)
            isClickable = true
            setOnClickListener { open(app) }
            setOnLongClickListener { menu(app); true }
        }
    }

    private fun open(app: AppEntry) = launch(app)

    /** The fixed bottom row: one logo per docked app, in one color, spread evenly; long-press works as on a word. */
    private fun showDock(dock: List<AppEntry>) {
        dockRow.removeAllViews()
        dockRow.visibility = if (dock.isEmpty()) View.GONE else View.VISIBLE
        for (app in dock) {
            val res = DOCK_ICONS[app.pkg]
            val view = if (res != null) {
                ui.iconButton(res, app.label) { open(app) }.apply { imageTintList = ColorStateList.valueOf(ui.color(R.color.grod_foreground)) }
            } else {
                // No icon for this app: its first letter, in the same quiet weight.
                ui.text(app.label.take(1).uppercase(), 22f).apply {
                    contentDescription = app.label
                    gravity = Gravity.CENTER
                    minHeight = ui.dp(TOUCH_DP)
                    isClickable = true
                    setOnClickListener { open(app) }
                }
            }
            view.setOnLongClickListener { menu(app); true }
            dockRow.addView(view, LinearLayout.LayoutParams(0, ui.dp(TOUCH_DP + 8), 1f))
        }
    }

    private fun menu(app: AppEntry) {
        val marks = prefs.marks
        val distracting = app.pkg in Launcher.distracting(store.rules, marks)
        val hidden = app.pkg in Launcher.hidden(store.rules, marks)
        val docked = app.pkg in Launcher.dock(store.rules, marks)
        val choices = arrayOf(
            "App info",
            if (hidden) "Unhide" else "Hide",
            if (distracting) "Not distracting" else "Mark as distracting",
            if (docked) "Remove from dock" else "Add to dock",
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
                    1 -> prefs.marks = Launcher.toggleHidden(marks, app.pkg, app.pkg in store.rules.launcher.hidden, hidden)
                    2 -> prefs.marks = Launcher.toggleDistracting(marks, app.pkg, app.pkg in Launcher.distractingBy(store.rules), distracting)
                    3 -> prefs.marks = Launcher.toggleDocked(marks, app.pkg, app.pkg in store.rules.launcher.dock, docked)
                }
                render()
            }
            .show()
    }

    private companion object {
        const val WEATHER_CHECK_MS = 10 * 60 * 1000L
        const val TOUCH_DP = 48
        const val MIN_TOUCH_DP = 36
        const val MIN_SCALE = 0.55
        const val REQUEST_VOICE = 1

        /** Docked apps drawn with their own logo in one color (the app's monochrome icon); any other app shows its first letter. */
        val DOCK_ICONS = mapOf(
            "com.whatsapp" to R.drawable.brand_whatsapp,
            "com.huawei.camera" to R.drawable.brand_camera,
            "com.google.android.apps.youtube.music" to R.drawable.brand_youtube_music,
            "com.microsoft.teams" to R.drawable.brand_teams,
            "com.google.android.apps.messaging" to R.drawable.brand_messages,
            "com.garanti.cepsubesi" to R.drawable.brand_garanti,
        )
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
