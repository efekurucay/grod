package dev.grod

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

    fun event(t: Long, rule: String, pkg: String, action: String): String =
        JSONObject().put("t", t).put("rule", rule).put("pkg", pkg).put("action", action).toString()
}

/** Which log files to compress and delete; pure, so pruning is unit tested. */
object LogFiles {
    private val NAME = Regex("""^[a-z]+-(\d{4}-\d{2}-\d{2})\.jsonl(\.gz)?$""")

    data class Plan(val gzip: List<String>, val delete: List<String>)

    fun name(kind: String, date: LocalDate): String = "$kind-$date.jsonl"

    /** Files [keepDays] or more days old go; past days that are still plain text get gzipped. */
    fun plan(names: Collection<String>, today: LocalDate, keepDays: Int): Plan {
        val gzip = ArrayList<String>()
        val delete = ArrayList<String>()
        for (name in names.sorted()) {
            val m = NAME.matchEntire(name) ?: continue
            val age = ChronoUnit.DAYS.between(LocalDate.parse(m.groupValues[1]), today)
            when {
                age >= keepDays -> delete.add(name)
                age > 0 && m.groupValues[2].isEmpty() -> gzip.add(name)
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
