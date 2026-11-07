package dev.grod

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONException
import org.json.JSONObject

/**
 * The notification history the Notifications tab shows and deletes, read from the notification log
 * (docs/data.md). Pure, so parsing, search and deletion are unit tested without Android.
 */
object History {
    const val KIND = "notifications"

    /** One `posted` line. */
    data class Entry(
        val t: Long,
        val pkg: String,
        val app: String?,
        val title: String?,
        val text: String?,
        /** grod held it for the next delivery. */
        val held: Boolean,
        /**
         * The app took back a notification with the same title (a `removed` line with reason `app`).
         * Chat apps also do this when the chat is read elsewhere, so it does not mean the sender deleted it.
         */
        val removedByApp: Boolean,
        /** The log line itself; deleting the row removes exactly this line. */
        val line: String,
    ) {
        val label: String get() = app ?: pkg

        /** Folded once when the log is read, so typing in the search field stays cheap. */
        val searchKey: String = Launcher.fold(listOfNotNull(label, title, text).joinToString(" "))
    }

    sealed interface Item {
        data class Day(val date: LocalDate) : Item

        /** [first] and [last] say where the row sits in its day, for the rounded corners of the group. */
        data class Row(val entry: Entry, val first: Boolean, val last: Boolean) : Item
    }

    /** The `posted` lines of [lines], newest first, matched to removals as `grod phone search --withdrawn` does. */
    fun entries(lines: List<String>): List<Entry> {
        val parsed = lines.mapNotNull { line -> json(line)?.let { line to it } }
        val removedByApp = parsed.filter { (_, o) -> o.optString("e") == "removed" && o.optString("reason") == "app" }
            .mapTo(HashSet()) { key(it.second) }
        return parsed.filter { (_, o) -> o.optString("e") == "posted" }
            .map { (line, o) ->
                Entry(
                    t = o.optLong("t"),
                    pkg = o.optString("pkg"),
                    app = o.str("app"),
                    title = o.str("title"),
                    text = o.str("text"),
                    held = o.optBoolean("held"),
                    removedByApp = key(o) in removedByApp,
                    line = line,
                )
            }
            .sortedByDescending { it.t }
    }

    /** Search is case-, accent- and Turkish-i-blind ([Launcher.fold]); [pkg] narrows to one app. */
    fun filter(entries: List<Entry>, query: String, pkg: String?): List<Entry> {
        val q = Launcher.fold(query.trim())
        return entries.filter { (pkg == null || it.pkg == pkg) && (q.isEmpty() || q in it.searchKey) }
    }

    /** Apps that have rows, as package to label, in label order, for the app filter. */
    fun apps(entries: List<Entry>): List<Pair<String, String>> =
        entries.associate { it.pkg to it.label }.toList().sortedWith(compareBy({ Launcher.fold(it.second) }, { it.first }))

    /** Day headers and rows for entries that are already newest first. */
    fun items(entries: List<Entry>, zone: ZoneId): List<Item> {
        val items = ArrayList<Item>(entries.size + 32)
        val days = entries.groupBy { Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate() }
        for ((date, rows) in days) {
            items.add(Item.Day(date))
            rows.forEachIndexed { i, entry -> items.add(Item.Row(entry, i == 0, i == rows.lastIndex)) }
        }
        return items
    }

    /** Start of the local day [days] days before [now]; "older than 7 days" deletes what came before it. */
    fun cutoff(now: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    /** What a delete covers. Every scope but [Rows] takes `posted` and `removed` lines alike. */
    sealed interface Scope {
        /** Swiped rows: exactly these `posted` lines. */
        data class Rows(val lines: Set<String>) : Scope

        data class App(val pkg: String) : Scope

        /** One conversation or sender: an app's lines with this title. */
        data class Conversation(val pkg: String, val title: String?) : Scope

        data class Day(val date: LocalDate) : Scope

        data class Before(val cutoff: Long) : Scope

        data object Everything : Scope
    }

    fun matches(scope: Scope, line: String, pkg: String, title: String?, t: Long, zone: ZoneId): Boolean = when (scope) {
        is Scope.Rows -> line in scope.lines
        is Scope.App -> pkg == scope.pkg
        is Scope.Conversation -> pkg == scope.pkg && title == scope.title
        is Scope.Day -> Instant.ofEpochMilli(t).atZone(zone).toLocalDate() == scope.date
        is Scope.Before -> t < scope.cutoff
        Scope.Everything -> true
    }

    /** How many rows a delete of [scope] takes, for the confirm dialog. */
    fun count(entries: List<Entry>, scope: Scope, zone: ZoneId): Int =
        entries.count { matches(scope, it.line, it.pkg, it.title, it.t, zone) }

    /**
     * New contents of the log [files] (name to lines) after deleting [scope]. A `removed` line goes too
     * once no `posted` line with its package and title is left, so a deleted message leaves no trace;
     * while one is left, its "removed by app" tag stays. Only changed files are returned, and an empty
     * list means the file goes.
     */
    fun delete(files: Map<String, List<String>>, scope: Scope, zone: ZoneId): Map<String, List<String>> =
        delete(files) { line, o ->
            if (o == null) scope == Scope.Everything
            else matches(scope, line, o.optString("pkg"), o.str("title"), o.optLong("t"), zone)
        }

    private fun delete(files: Map<String, List<String>>, drop: (String, JSONObject?) -> Boolean): Map<String, List<String>> {
        val parsed = files.mapValues { (_, lines) -> lines.map { it to json(it) } }
        val dropped = HashSet<String>()
        val kept = HashSet<String>()
        for ((line, o) in parsed.values.flatten()) {
            if (o?.optString("e") != "posted") continue
            (if (drop(line, o)) dropped else kept).add(key(o))
        }
        val gone = dropped - kept
        val changed = HashMap<String, List<String>>()
        for ((name, lines) in parsed) {
            val left = lines.filterNot { (line, o) -> drop(line, o) || (o?.optString("e") == "removed" && key(o) in gone) }
            if (left.size != lines.size) changed[name] = left.map { it.first }
        }
        return changed
    }

    private fun key(o: JSONObject) = "${o.optString("pkg")}|${o.optString("title")}"

    private fun json(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
}

/** Today's scroll pauses from the events log: shown, then left or continued. */
data class PauseCount(val paused: Int, val left: Int, val continued: Int) {
    companion object {
        fun of(lines: List<String>): PauseCount {
            val actions = lines.mapNotNull { line ->
                try {
                    JSONObject(line).optString("action")
                } catch (e: JSONException) {
                    null
                }
            }.groupingBy { it }.eachCount()
            return PauseCount(actions["paused"] ?: 0, actions["left"] ?: 0, actions["continued"] ?: 0)
        }
    }
}
