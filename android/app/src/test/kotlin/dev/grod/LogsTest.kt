package dev.grod

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun linesFollowDataMd() {
        val posted = JSONObject(LogLines.posted(1L, "com.whatsapp", "WhatsApp", "msg", "Ayşe", "geliyor \"musun\"?", true))
        assertEquals("posted", posted.getString("e"))
        assertEquals("geliyor \"musun\"?", posted.getString("text"))
        assertTrue(posted.getBoolean("held"))
        val sparse = JSONObject(LogLines.posted(1L, "x", null, null, null, null, false))
        assertFalse(sparse.has("cat") || sparse.has("title") || sparse.has("app"))
        val removed = JSONObject(LogLines.removed(2L, "com.whatsapp", "Ayşe", "app"))
        assertEquals("app", removed.getString("reason"))
        assertEquals(setOf("t", "rule", "pkg", "action"), JSONObject(LogLines.event(3L, "instagram-reels", "com.instagram.android", "back")).keySet())
    }

    @Test
    fun plansCompressionAndPruning() {
        val today = LocalDate.parse("2026-11-06")
        val names = listOf(
            "notifications-2026-11-06.jsonl", "notifications-2026-11-05.jsonl", "events-2026-11-04.jsonl.gz",
            "notifications-2026-10-07.jsonl.gz", "notifications-2026-10-08.jsonl.gz", "other.txt",
        )
        val plan = LogFiles.plan(names, today, 30)
        assertEquals(listOf("notifications-2026-11-05.jsonl"), plan.gzip)
        assertEquals(listOf("notifications-2026-10-07.jsonl.gz"), plan.delete)
        assertEquals(names.filter { it != "other.txt" }.sorted(), LogFiles.plan(names, today, 0).delete)
    }

    @Test
    fun writerAppendsCompressesAndPrunes() {
        val dir = tmp.newFolder("log")
        var now = java.time.ZonedDateTime.parse("2026-11-05T23:59:00+03:00").toInstant().toEpochMilli()
        val writer = LogWriter(dir, { it.run() }, { now }, { ZoneId.of("Europe/Istanbul") })
        File(dir, "events-2026-09-01.jsonl.gz").writeText("old")
        writer.append("notifications", "{\"a\":1}", 30)
        writer.append("notifications", "{\"a\":2}", 30)
        assertEquals("{\"a\":1}\n{\"a\":2}\n", File(dir, "notifications-2026-11-05.jsonl").readText())
        assertFalse(File(dir, "events-2026-09-01.jsonl.gz").exists())
        now += 2 * 60_000
        writer.append("notifications", "{\"a\":3}", 30)
        val gz = File(dir, "notifications-2026-11-05.jsonl.gz")
        assertEquals("{\"a\":1}\n{\"a\":2}\n", GZIPInputStream(gz.inputStream()).bufferedReader().readText())
        assertFalse(File(dir, "notifications-2026-11-05.jsonl").exists())
        assertEquals("{\"a\":3}\n", File(dir, "notifications-2026-11-06.jsonl").readText())
        writer.append("notifications", "{\"a\":4}", 0)
        assertTrue(dir.list()!!.isEmpty())
    }
}
