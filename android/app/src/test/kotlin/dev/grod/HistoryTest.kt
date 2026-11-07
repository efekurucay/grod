package dev.grod

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private val zone = ZoneId.of("Europe/Istanbul")

    private fun at(time: String) = ZonedDateTime.parse("${time}+03:00[Europe/Istanbul]").toInstant().toEpochMilli()

    private val ayse1 = LogLines.posted(at("2026-11-05T10:00:00"), "com.whatsapp", "WhatsApp", "msg", "Ayşe", "GELİYOR musun?", true)
    private val ayse2 = LogLines.posted(at("2026-11-06T09:00:00"), "com.whatsapp", "WhatsApp", "msg", "Ayşe", "tamam", false)
    private val ayseGone = LogLines.removed(at("2026-11-06T09:01:00"), "com.whatsapp", "Ayşe", "app")
    private val mail = LogLines.posted(at("2026-11-06T11:00:00"), "com.mail", null, null, "Fatura", "Ödeme hatırlatma", false)
    private val mailSeen = LogLines.removed(at("2026-11-06T11:05:00"), "com.mail", "Fatura", "user")

    @Test
    fun rowsArePostedLinesNewestFirstWithTags() {
        val rows = History.entries(listOf(ayse1, "not json", ayse2, ayseGone, mail, mailSeen))
        assertEquals(listOf("Fatura", "Ayşe", "Ayşe"), rows.map { it.title })
        assertEquals(listOf(false, false, true), rows.map { it.held })
        assertEquals(listOf(false, true, true), rows.map { it.removedByApp })
        assertEquals("com.mail", rows[0].label)
        assertEquals(mail, rows[0].line)
    }

    @Test
    fun searchIsTurkishAndCaseBlind() {
        val rows = History.entries(listOf(ayse1, ayse2, mail))
        assertEquals(listOf(ayse1), History.filter(rows, "geliyor", null).map { it.line })
        assertEquals(listOf(ayse1), History.filter(rows, " gelıyor ", null).map { it.line })
        assertEquals(listOf(mail), History.filter(rows, "odeme", null).map { it.line })
        assertEquals(2, History.filter(rows, "whatsapp", null).size)
        assertEquals(listOf(mail), History.filter(rows, "", "com.mail").map { it.line })
        assertTrue(History.filter(rows, "geliyor", "com.mail").isEmpty())
        assertEquals(listOf("com.mail" to "com.mail", "com.whatsapp" to "WhatsApp"), History.apps(rows))
    }

    @Test
    fun itemsGroupByLocalDay() {
        val rows = History.entries(listOf(ayse1, ayse2, mail))
        val items = History.items(rows, zone)
        assertEquals(History.Item.Day(LocalDate.parse("2026-11-06")), items[0])
        assertEquals(History.Item.Row(rows[0], first = true, last = false), items[1])
        assertEquals(History.Item.Row(rows[1], first = false, last = true), items[2])
        assertEquals(History.Item.Day(LocalDate.parse("2026-11-05")), items[3])
        assertEquals(History.Item.Row(rows[2], first = true, last = true), items[4])
        assertTrue(History.items(emptyList(), zone).isEmpty())
    }

    @Test
    fun deletingARowKeepsTheRemovedTagWhileTheTitleHasRows() {
        val files = mapOf(
            "notifications-2026-11-05.jsonl.gz" to listOf(ayse1),
            "notifications-2026-11-06.jsonl" to listOf(ayse2, ayseGone, mail, mailSeen),
        )
        val one = History.delete(files, History.Scope.Rows(setOf(ayse2)), zone)
        assertEquals(mapOf("notifications-2026-11-06.jsonl" to listOf(ayseGone, mail, mailSeen)), one)

        val both = History.delete(files, History.Scope.Rows(setOf(ayse1, ayse2)), zone)
        assertEquals(
            mapOf("notifications-2026-11-05.jsonl.gz" to emptyList(), "notifications-2026-11-06.jsonl" to listOf(mail, mailSeen)),
            both,
        )
        assertTrue(History.delete(files, History.Scope.Rows(setOf("missing")), zone).isEmpty())
    }

    @Test
    fun removedLinesWithoutAnyPostedLineAreLeftAlone() {
        val ongoingGone = LogLines.removed(at("2026-11-06T12:00:00"), "com.player", "Song", "app")
        val files = mapOf("notifications-2026-11-06.jsonl" to listOf(mail, mailSeen, ongoingGone))
        assertEquals(mapOf("notifications-2026-11-06.jsonl" to listOf(ongoingGone)), History.delete(files, History.Scope.Rows(setOf(mail)), zone))
    }

    @Test
    fun olderThanDeletesWholeEarlierDays() {
        val now = at("2026-11-13T15:00:00")
        val cutoff = History.cutoff(now, 7, zone)
        assertEquals(at("2026-11-06T00:00:00"), cutoff)
        val files = mapOf(
            "notifications-2026-11-05.jsonl.gz" to listOf(ayse1),
            "notifications-2026-11-06.jsonl.gz" to listOf(ayse2, ayseGone, mail, mailSeen),
        )
        assertEquals(mapOf("notifications-2026-11-05.jsonl.gz" to emptyList<String>()), History.delete(files, History.Scope.Before(cutoff), zone))
        val later = History.delete(files, History.Scope.Before(at("2026-11-06T10:00:00")), zone)
        assertEquals(listOf("notifications-2026-11-05.jsonl.gz", "notifications-2026-11-06.jsonl.gz"), later.keys.sorted())
        assertEquals(listOf(mail, mailSeen), later["notifications-2026-11-06.jsonl.gz"])
        assertFalse(History.entries(later.values.flatten()).any { it.removedByApp })
    }

    @Test
    fun scopesMatchAppConversationDayAndAge() {
        val rows = History.entries(listOf(ayse1, ayse2, mail))
        fun count(scope: History.Scope) = History.count(rows, scope, zone)
        assertEquals(2, count(History.Scope.App("com.whatsapp")))
        assertEquals(2, count(History.Scope.Conversation("com.whatsapp", "Ayşe")))
        assertEquals(0, count(History.Scope.Conversation("com.mail", "Ayşe")))
        assertEquals(2, count(History.Scope.Day(LocalDate.parse("2026-11-06"))))
        assertEquals(1, count(History.Scope.Before(at("2026-11-06T00:00:00"))))
        assertEquals(3, count(History.Scope.Everything))
        assertEquals(1, count(History.Scope.Rows(setOf(mail, ayseGone))))
    }

    @Test
    fun scopedDeletesTakeRemovedLinesOfTheScopeToo() {
        val files = mapOf(
            "notifications-2026-11-05.jsonl.gz" to listOf(ayse1),
            "notifications-2026-11-06.jsonl" to listOf(ayse2, ayseGone, mail, mailSeen, "{broken"),
        )
        val app = History.delete(files, History.Scope.App("com.whatsapp"), zone)
        assertEquals(mapOf("notifications-2026-11-05.jsonl.gz" to emptyList(), "notifications-2026-11-06.jsonl" to listOf(mail, mailSeen, "{broken")), app)
        assertEquals(app, History.delete(files, History.Scope.Conversation("com.whatsapp", "Ayşe"), zone))
        val day = History.delete(files, History.Scope.Day(LocalDate.parse("2026-11-06")), zone)
        assertEquals(mapOf("notifications-2026-11-06.jsonl" to listOf("{broken")), day)
        val all = History.delete(files, History.Scope.Everything, zone)
        assertEquals(mapOf("notifications-2026-11-05.jsonl.gz" to emptyList<String>(), "notifications-2026-11-06.jsonl" to emptyList()), all)
    }

    @Test
    fun countsTodaysPauses() {
        val lines = listOf(
            LogLines.event(1, "instagram", "com.instagram.android", "paused"),
            LogLines.event(2, "instagram", "com.instagram.android", "left"),
            LogLines.event(3, "youtube", "com.google.android.youtube", "paused"),
            LogLines.event(4, "youtube", "com.google.android.youtube", "continued"),
            "{",
        )
        assertEquals(PauseCount(2, 1, 1), PauseCount.of(lines))
        assertEquals(PauseCount(0, 0, 0), PauseCount.of(emptyList()))
    }

    @Test
    fun countsTodaysOpeningsApartFromPauses() {
        val ig = "com.instagram.android"
        val lines = listOf(
            LogLines.event(1, "instagram", ig, "opening", "message"),
            LogLines.event(2, "instagram", ig, "paused"),
            LogLines.event(3, "instagram", ig, "left"),
            LogLines.event(4, "instagram", ig, "session", "message", 180_000),
            // Leave on the question: a left that does not follow a pause.
            LogLines.event(5, "instagram", ig, "left"),
            LogLines.event(6, "launcher", "com.twitter.android", "opening", "browse"),
            LogLines.event(7, "instagram", ig, "opening", "message"),
            LogLines.event(8, "instagram", ig, "opening", "lookup"),
        )
        val count = PauseCount.of(lines)
        assertEquals(PauseCount(1, 1, 0, mapOf(Purpose.MESSAGE to 2, Purpose.BROWSE to 1, Purpose.LOOKUP to 1), 1), count)
    }
}
