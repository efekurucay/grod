package dev.grod

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/** One JSON object per line, as docs/data.md describes. */
object LogLines {
    fun posted(t: Long, pkg: String, app: String?, cat: String?, title: String?, text: String?, held: Boolean): String =
        JSONObject().put("t", t).put("e", "posted").put("pkg", pkg)
            .putOpt("app", app).putOpt("cat", cat).putOpt("title", title).putOpt("text", text)
            .put("held", held).toString()

    fun removed(t: Long, pkg: String, title: String?, reason: String): String =
        JSONObject().put("t", t).put("e", "removed").put("pkg", pkg).putOpt("title", title).put("reason", reason).toString()

    fun event(t: Long, rule: String, pkg: String, action: String, purpose: String? = null, ms: Long? = null): String =
        JSONObject().put("t", t).put("rule", rule).put("pkg", pkg).put("action", action)
            .putOpt("purpose", purpose).putOpt("ms", ms).toString()
}

/** Which log files to compress and delete; pure, so pruning is unit tested. */
object LogFiles {
    private val NAME = Regex("""^([a-z]+)-(\d{4}-\d{2}-\d{2})\.jsonl(\.gz)?$""")

    data class Plan(val gzip: List<String>, val delete: List<String>)

    fun name(kind: String, date: LocalDate): String = "$kind-$date.jsonl"

    /** "notifications" for notifications-2026-11-06.jsonl.gz; null for anything that is not a log file. */
    fun kind(name: String): String? = NAME.matchEntire(name)?.groupValues?.get(1)

    fun date(name: String): LocalDate? = NAME.matchEntire(name)?.groupValues?.get(2)?.let(LocalDate::parse)

    /** Files [keepDays] or more days old go; past days that are still plain text get gzipped. */
    fun plan(names: Collection<String>, today: LocalDate, keepDays: Int): Plan {
        val gzip = ArrayList<String>()
        val delete = ArrayList<String>()
        for (name in names.sorted()) {
            val m = NAME.matchEntire(name) ?: continue
            val age = ChronoUnit.DAYS.between(LocalDate.parse(m.groupValues[2]), today)
            when {
                age >= keepDays -> delete.add(name)
                age > 0 && m.groupValues[3].isEmpty() -> gzip.add(name)
            }
        }
        return Plan(gzip, delete)
    }
}

/**
 * Appends log lines under [dir] on one thread, so lines never interleave and the main thread never
 * touches the disk. Compression and pruning run once per day (and when keepDays changes).
 */
class LogWriter(
    private val dir: File,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var tidied: Pair<LocalDate, Int>? = null

    fun append(kind: String, line: String, keepDays: Int) = executor.execute {
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        if (tidied != today to keepDays) {
            tidy(today, keepDays)
            tidied = today to keepDays
        }
        if (keepDays > 0) {
            dir.mkdirs()
            File(dir, LogFiles.name(kind, today)).appendText(line + "\n")
        }
    }

    /**
     * Runs [task] with the log dir on the writer thread, so a read sees only whole lines and a
     * rewrite never races an append. Never blocks the caller.
     */
    fun onLogThread(task: (File) -> Unit) = executor.execute { task(dir) }

    /** Copies every log file into [target], on the writer thread so no half-written line is copied. */
    fun copyTo(target: File) {
        val done = CountDownLatch(1)
        executor.execute {
            try {
                target.mkdirs()
                dir.listFiles()?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    private fun tidy(today: LocalDate, keepDays: Int) {
        val plan = LogFiles.plan(dir.list()?.toList().orEmpty(), today, keepDays)
        for (name in plan.delete) File(dir, name).delete()
        for (name in plan.gzip) {
            val plain = File(dir, name)
            // Appending keeps an earlier .gz intact; concatenated gzip members read as one stream.
            GZIPOutputStream(FileOutputStream(File(dir, "$name.gz"), true)).use { out -> plain.inputStream().use { it.copyTo(out) } }
            plain.delete()
        }
    }

    companion object {
        private var instance: LogWriter? = null

        /** files/log in app storage; `adb shell run-as dev.grod` reads it. */
        fun get(context: Context): LogWriter = instance ?: LogWriter(
            File(context.applicationContext.filesDir, "log"),
            Executors.newSingleThreadExecutor { Thread(it, "grod-log").apply { isDaemon = true } },
        ).also { instance = it }
    }
}

/** Reads and rewrites log files; only on the writer thread ([LogWriter.onLogThread]). */
class LogFolder(private val dir: File) {
    /** The files of [kind], oldest day first. */
    fun names(kind: String): List<String> = dir.list()?.filter { LogFiles.kind(it) == kind }?.sorted().orEmpty()

    fun read(name: String): List<String> {
        val file = File(dir, name)
        if (!file.exists()) return emptyList()
        // A .gz may hold several gzip members (see LogWriter.tidy); GZIPInputStream reads them as one stream.
        val stream = if (name.endsWith(".gz")) GZIPInputStream(file.inputStream()) else file.inputStream()
        return stream.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.toList() }
    }

    /** Every line of [kind] logged on [date], the gzipped part first. */
    fun read(kind: String, date: LocalDate): List<String> = LogFiles.name(kind, date).let { read("$it.gz") + read(it) }

    /**
     * Replaces [name] with [lines] through a temp file and a rename, so a crash leaves either the old
     * or the new file, never half of one. No lines deletes the file.
     */
    fun write(name: String, lines: List<String>) {
        val file = File(dir, name)
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(dir, "$name.tmp")
        val out = FileOutputStream(tmp).let { if (name.endsWith(".gz")) GZIPOutputStream(it) else it }
        out.bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace $name")
        }
    }
}
