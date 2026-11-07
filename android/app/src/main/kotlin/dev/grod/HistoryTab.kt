package dev.grod

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * The Notifications tab: the notification log (docs/data.md) as a history, newest first and grouped
 * by day, with search and an app filter. Rows carry quiet "held" and "removed by app" tags. Swiping
 * a row left deletes it after a short undo; the trash button and a long-press on a row or a day
 * delete in bulk, behind a confirm. Reading and rewriting the files run on the log thread, never here.
 */
class HistoryTab(private val activity: Activity, private val ui: Ui) {
    private val main = Handler(Looper.getMainLooper())
    private val log = LogWriter.get(activity)
    private val adapter = Adapter()
    private val list: ListView
    private val search: EditText
    private val filterLine: LinearLayout
    private val filterText: TextView
    private val undoLine: LinearLayout
    private val undoText: TextView
    private val empty: TextView

    private var all: List<History.Entry> = emptyList()
    private var loaded = false
    private var pkg: String? = null

    /** Each load gets a number, so a slower earlier load never overwrites a newer one. */
    private var generation = 0

    /** Lines swiped away and still waiting for [UNDO_MS] before they are deleted from the files. */
    private val pending = LinkedHashSet<String>()
    private val commitLater = Runnable { commit() }

    val view: LinearLayout = ui.column()

    init {
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(4), 0)
        }
        title.addView(ui.text("Notifications", 24f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        title.addView(ui.iconButton(R.drawable.lucide_trash_2, "Delete…", ::deleteMenu))
        view.addView(title)

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.box(R.color.grod_surface, R.color.grod_input, 6)
            setPadding(ui.dp(12), 0, ui.dp(4), 0)
        }
        box.addView(ui.icon(R.drawable.lucide_search), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
        search = ui.input("Search notifications").apply {
            background = null
            setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    render()
                    list.setSelection(0)
                }
            })
        }
        box.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        searchRow.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        searchRow.addView(ui.iconButton(R.drawable.lucide_list_filter, "Filter by app", ::chooseApp))
        view.addView(searchRow)

        filterLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(4), 0)
            visibility = View.GONE
        }
        filterText = ui.muted("", 15f)
        filterLine.addView(filterText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        filterLine.addView(ui.iconButton(R.drawable.lucide_x, "Show all apps") { pickApp(null) })
        view.addView(filterLine)

        undoLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            visibility = View.GONE
        }
        undoText = ui.muted("", 15f)
        undoLine.addView(undoText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        undoLine.addView(ui.button("Undo", Ui.Kind.GHOST, ::undo))
        view.addView(undoLine)

        list = ListView(activity).apply {
            adapter = this@HistoryTab.adapter
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16))
            setOnTouchListener(SwipeToDelete(this, { row -> (row.tag as? RowHolder)?.content }) { item ->
                (item as? History.Item.Row)?.let { swiped(it.entry) }
            })
            setOnItemLongClickListener { _, _, position, _ ->
                itemMenu(this@HistoryTab.adapter.items[position])
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }

                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
            })
        }
        empty = ui.muted("", 15f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(16), ui.dp(48), ui.dp(16), 0)
        }
        val listFrame = FrameLayout(activity)
        listFrame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listFrame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        list.emptyView = empty
        view.addView(listFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    /** Reads the log again: the newest day first so the tab fills at once, then every day. */
    fun load() {
        val run = ++generation
        log.onLogThread { dir ->
            val folder = LogFolder(dir)
            val names = folder.names(History.KIND)
            val newest = names.lastOrNull()?.let(LogFiles::date)
            if (newest != null && names.size > 2) {
                show(run, History.entries(names.filter { LogFiles.date(it) == newest }.flatMap { read(folder, it).orEmpty() }))
            }
            show(run, History.entries(names.flatMap { read(folder, it).orEmpty() }))
        }
    }

    /** Deletes swiped rows now instead of after the undo time; called when the tab or the app is left. */
    fun commit() {
        main.removeCallbacks(commitLater)
        undoLine.visibility = View.GONE
        if (pending.isEmpty()) return
        val lines = pending.toSet()
        pending.clear()
        all = all.filter { it.line !in lines }
        rewrite(History.Scope.Rows(lines))
    }

    private fun show(run: Int, entries: List<History.Entry>) = main.post {
        if (run != generation) return@post
        all = entries
        loaded = true
        render()
    }

    private fun render() {
        val shown = History.filter(all.filter { it.line !in pending }, search.text.toString(), pkg)
        adapter.items = History.items(shown, ZoneId.systemDefault())
        adapter.notifyDataSetChanged()
        empty.text = when {
            !loaded -> "Loading…"
            all.isEmpty() -> "No notifications yet."
            else -> "Nothing matches."
        }
        val app = pkg
        filterLine.visibility = if (app == null) View.GONE else View.VISIBLE
        if (app != null) filterText.text = "Only ${all.firstOrNull { it.pkg == app }?.label ?: app}"
    }

    private fun swiped(entry: History.Entry) {
        pending.add(entry.line)
        render()
        undoText.setTextColor(ui.color(R.color.grod_muted_foreground))
        undoText.text = if (pending.size == 1) "Deleted 1 notification." else "Deleted ${pending.size} notifications."
        undoLine.visibility = View.VISIBLE
        main.removeCallbacks(commitLater)
        main.postDelayed(commitLater, UNDO_MS)
    }

    private fun undo() {
        main.removeCallbacks(commitLater)
        pending.clear()
        undoLine.visibility = View.GONE
        render()
    }

    private fun chooseApp() {
        val apps = History.apps(all)
        val choices = listOf<Pair<String, () -> Unit>>("All apps" to { pickApp(null) }) +
            apps.map { (app, label) -> label to { pickApp(app) } }
        ui.dialog("Show notifications from", choices = choices, buttons = listOf("Cancel" to {}))
    }

    private fun pickApp(app: String?) {
        pkg = app
        render()
        list.setSelection(0)
    }

    private fun appLabel(app: String) = all.firstOrNull { it.pkg == app }?.label ?: app

    /** The trash button: deletes that fit the current view, each behind a confirm with its count. */
    private fun deleteMenu() {
        commit()
        val zone = ZoneId.systemDefault()
        val choices = ArrayList<Pair<String, () -> Unit>>()
        pkg?.let { app -> choices.add("All from ${appLabel(app)}" to { confirm(History.Scope.App(app), "from ${appLabel(app)}") }) }
        val old = History.Scope.Before(History.cutoff(System.currentTimeMillis(), KEEP_DAYS, zone))
        if (History.count(all, old, zone) > 0) choices.add("Older than $KEEP_DAYS days" to { confirm(old, "older than $KEEP_DAYS days") })
        if (all.isNotEmpty()) choices.add("Everything" to { confirm(History.Scope.Everything, null) })
        ui.dialog(
            "Delete…",
            message = if (choices.isEmpty()) "Nothing to delete." else "Long-press a row or a day for more.",
            choices = choices,
            buttons = listOf("Cancel" to {}),
        )
    }

    /** Long-press on a row or a day header. */
    private fun itemMenu(item: History.Item) {
        commit()
        when (item) {
            is History.Item.Day -> {
                val day = dayName(item.date)
                ui.dialog(day.replaceFirstChar { it.uppercase() }, choices = listOf("Delete all from $day" to {
                    confirm(History.Scope.Day(item.date), "from $day")
                }), buttons = listOf("Cancel" to {}))
            }
            is History.Item.Row -> {
                val e = item.entry
                val from = if (e.title.isNullOrBlank()) e.label else "${e.title} (${e.label})"
                val scope = if (e.title.isNullOrBlank()) History.Scope.App(e.pkg) else History.Scope.Conversation(e.pkg, e.title)
                ui.dialog(e.title ?: e.label, choices = listOf(
                    "Delete this notification" to { swiped(e) },
                    "Delete all from $from" to { confirm(scope, "from $from") },
                ), buttons = listOf("Cancel" to {}))
            }
        }
    }

    /** Every delete but a single row asks first, saying how many rows it takes. */
    private fun confirm(scope: History.Scope, what: String?) {
        val zone = ZoneId.systemDefault()
        val n = History.count(all, scope, zone)
        if (n == 0) return
        val count = if (n == 1) "1 notification" else "$n notifications"
        ui.dialog(
            if (what == null) "Delete all $count?" else "Delete $count $what?",
            message = "This cannot be undone.",
            buttons = listOf("Cancel" to {}, "Delete" to {
                all = all.filterNot { History.matches(scope, it.line, it.pkg, it.title, it.t, zone) }
                render()
                rewrite(scope)
            }),
        )
    }

    /** "today", "yesterday" or the date, to read inside a sentence. */
    private fun dayName(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> DAY_FORMAT.format(date)
        }
    }

    /** Rewrites the notification files without [scope], then reads them again. */
    private fun rewrite(scope: History.Scope) {
        val zone = ZoneId.systemDefault()
        log.onLogThread { dir ->
            val folder = LogFolder(dir)
            // A file that cannot be read is left as it is rather than rewritten from nothing.
            val files = folder.names(History.KIND).mapNotNull { name -> read(folder, name)?.let { name to it } }.toMap()
            try {
                History.delete(files, scope, zone).forEach(folder::write)
            } catch (e: IOException) {
                main.post {
                    undoText.setTextColor(ui.color(R.color.grod_caution))
                    undoText.text = "Could not delete. Try again."
                    undoLine.visibility = View.VISIBLE
                }
            }
        }
        load()
    }

    private fun read(folder: LogFolder, name: String): List<String>? = try {
        folder.read(name)
    } catch (e: IOException) {
        null
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private class RowHolder(
        val content: View,
        val app: TextView,
        val time: TextView,
        val title: TextView,
        val text: TextView,
        val tags: View,
        val held: TextView,
        val removed: TextView,
    ) {
        var shape: Pair<Boolean, Boolean>? = null
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<History.Item> = emptyList()
        private val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is History.Item.Day) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[position]) {
                is History.Item.Day -> day(convertView as? TextView, item.date)
                is History.Item.Row -> row(convertView, item)
            }

        private fun day(reuse: TextView?, date: LocalDate): TextView {
            val view = reuse ?: ui.text("", 12f, R.color.grod_muted_foreground, bold = true).apply {
                letterSpacing = 0.06f
                setPadding(ui.dp(13), ui.dp(20), ui.dp(13), ui.dp(8))
            }
            view.text = dayName(date).uppercase(Locale.getDefault())
            return view
        }

        private fun row(reuse: View?, item: History.Item.Row): View {
            val view = reuse ?: newRow()
            val h = view.tag as RowHolder
            val e = item.entry
            // A recycled row may still be mid-swipe.
            h.content.animate().cancel()
            h.content.translationX = 0f
            h.content.alpha = 1f
            val shape = item.first to item.last
            if (h.shape != shape) {
                view.background = card(item.first, item.last)
                h.shape = shape
            }
            h.app.text = e.label
            h.time.text = timeFormat.format(Date(e.t))
            h.title.text = e.title.orEmpty()
            h.title.visibility = if (e.title.isNullOrBlank()) View.GONE else View.VISIBLE
            h.text.text = e.text.orEmpty()
            h.text.visibility = if (e.text.isNullOrBlank()) View.GONE else View.VISIBLE
            h.held.visibility = if (e.held) View.VISIBLE else View.GONE
            h.removed.visibility = if (e.removedByApp) View.VISIBLE else View.GONE
            h.tags.visibility = if (e.held || e.removedByApp) View.VISIBLE else View.GONE
            return view
        }

        private fun newRow(): View {
            val content = ui.column().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
            val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val app = ui.muted("", 13f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val time = ui.muted("", 13f)
            top.addView(app, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            top.addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(12) })
            content.addView(top)
            val title = ui.text("", 16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            content.addView(title)
            val text = ui.muted("", 15f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            content.addView(text)
            val held = tag("held")
            val removed = tag("removed by app")
            val tags = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(6), 0, 0)
                addView(held, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ui.dp(6) })
                addView(removed)
            }
            content.addView(tags)
            return FrameLayout(activity).apply {
                addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                tag = RowHolder(content, app, time, title, text, tags, held, removed)
            }
        }

        /** A quiet tag: muted text in a thin border, no fill. */
        private fun tag(label: String) = ui.muted(label, 12f).apply {
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(4).toFloat()
                setStroke(maxOf(1, ui.dp(1)), ui.color(R.color.grod_border))
            }
            setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
        }

        /**
         * A day's rows read as one bordered card, like a section: rounded corners on the first and last
         * row, and each later row's top border pushed out of view so the row above draws the divider.
         */
        private fun card(first: Boolean, last: Boolean): Drawable {
            val r = ui.dp(10).toFloat()
            val stroke = maxOf(1, ui.dp(1))
            val top = if (first) r else 0f
            val bottom = if (last) r else 0f
            val shape = GradientDrawable().apply {
                setColor(ui.color(R.color.grod_surface))
                setStroke(stroke, ui.color(R.color.grod_border))
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            }
            return if (first) shape else InsetDrawable(shape, 0, -stroke, 0, 0)
        }
    }

    private companion object {
        const val UNDO_MS = 5_000L
        const val KEEP_DAYS = 7L
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    }
}
