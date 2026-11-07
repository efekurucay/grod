package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private val now = 1_000_000L

    private fun texts(entries: List<LogEntry>) = entries.map { it.title to it.text }

    @Test
    fun chatLogsEveryMessageWithItsOwnTime() {
        val direct = NotificationContent(
            title = "Ayşe",
            text = "3 new messages",
            messages = listOf(ChatMessage(10, "Ayşe", "geliyor musun?"), ChatMessage(20, null, "evet"), ChatMessage(0, "Ayşe", "tamam")),
        )
        val entries = NotificationText.entries(direct, now)
        assertEquals(listOf("Ayşe" to "geliyor musun?", "Ayşe" to "You: evet", "Ayşe" to "tamam"), texts(entries))
        assertEquals(listOf(10L, 20L, now), entries.map { it.t })

        val group = NotificationContent(
            title = "Aile: Ayşe",
            conversationTitle = "Aile",
            isGroup = true,
            selfName = "Efe",
            messages = listOf(ChatMessage(10, "Ayşe", "akşam yemeği?"), ChatMessage(11, null, "geliyorum")),
        )
        assertEquals(listOf("Aile" to "Ayşe: akşam yemeği?", "Aile" to "Efe: geliyorum"), texts(NotificationText.entries(group, now)))
    }

    @Test
    fun inboxLinesThenFullTextThenText() {
        val inbox = NotificationContent(title = "2 new messages", textLines = listOf("Bank  Statement ready", "", "Ali  Lunch?"), text = "Bank, Ali")
        assertEquals(listOf("2 new messages" to "Bank  Statement ready", "2 new messages" to "Ali  Lunch?"), texts(NotificationText.entries(inbox, now)))
        val mail = NotificationContent(title = "Ali", text = "Lunch?", bigText = "Lunch?\nSee you at 12 at the usual place.")
        assertEquals(listOf("Ali" to "Lunch?\nSee you at 12 at the usual place."), texts(NotificationText.entries(mail, now)))
        assertEquals(listOf("Ali" to "Lunch?"), texts(NotificationText.entries(mail.copy(bigText = null), now)))
        assertEquals(listOf<Pair<String?, String?>>(null to null), texts(NotificationText.entries(NotificationContent(title = null), now)))
    }

    @Test
    fun summaryWithoutEntriesIsNotLogged() {
        assertTrue(NotificationText.entries(NotificationContent(title = "WhatsApp", text = "5 new messages", groupSummary = true), now).isEmpty())
        val withLines = NotificationContent(title = "Gmail", textLines = listOf("Ali  Lunch?"), groupSummary = true)
        assertEquals(1, NotificationText.entries(withLines, now).size)
    }

    @Test
    fun updatesLogOnlyWhatIsNew() {
        val memory = NotificationMemory()
        val first = NotificationContent(title = "Ayşe", messages = listOf(ChatMessage(10, "Ayşe", "selam")))
        val second = first.copy(messages = first.messages + ChatMessage(20, "Ayşe", "selam"))
        assertEquals(1, memory.fresh("k", null, false, NotificationText.entries(first, now)).size)
        assertEquals(listOf(20L), memory.fresh("k", null, false, NotificationText.entries(second, now)).map { it.t })
        assertTrue(memory.fresh("k", null, false, NotificationText.entries(second, now)).isEmpty())
        // Another notification may say the same.
        assertEquals(2, memory.fresh("other", null, false, NotificationText.entries(second, now)).size)
    }

    @Test
    fun plainTextIsLoggedAgainOnlyAfterTheNotificationWasRemoved() {
        val memory = NotificationMemory()
        val low = NotificationText.entries(NotificationContent(title = "Battery", text = "15% left"), now)
        assertEquals(1, memory.fresh("k", null, false, low).size)
        assertTrue(memory.fresh("k", null, false, low).isEmpty())
        assertEquals(1, memory.fresh("k", null, false, NotificationText.entries(NotificationContent(title = "Battery", text = "10% left"), now)).size)
        val chat = NotificationText.entries(NotificationContent(title = "Ayşe", messages = listOf(ChatMessage(10, "Ayşe", "selam"))), now)
        memory.fresh("c", null, false, chat)
        memory.removed("k")
        memory.removed("c")
        assertEquals(1, memory.fresh("k", null, false, low).size)
        // Messages carry their time, so one shown again after the chat was opened is still the same message.
        assertTrue(memory.fresh("c", null, false, chat).isEmpty())
    }

    @Test
    fun summaryAddsOnlyWhatItsGroupDidNotLog() {
        val memory = NotificationMemory()
        val child = NotificationText.entries(NotificationContent(title = "Ali", text = "Lunch?", bigText = "Lunch? See you at 12"), now)
        memory.fresh("child", "g", false, child)
        val summary = NotificationText.entries(
            NotificationContent(title = "2 new messages", textLines = listOf("Ali   Lunch?", "Bank  Statement ready"), groupSummary = true),
            now,
        )
        assertEquals(listOf("Bank  Statement ready"), memory.fresh("summary", "g", true, summary).map { it.text })
        assertTrue(memory.fresh("summary", "g", true, summary).isEmpty())
        val chatChild = NotificationText.entries(NotificationContent(title = "Ayşe", messages = listOf(ChatMessage(10, "Ayşe", "selam"))), now)
        memory.fresh("chat", "w", false, chatChild)
        val chatSummary = NotificationText.entries(NotificationContent(title = "WhatsApp", textLines = listOf("Ayşe: selam"), groupSummary = true), now)
        assertTrue(memory.fresh("wsummary", "w", true, chatSummary).isEmpty())
    }

    @Test
    fun memoryIsBounded() {
        val memory = NotificationMemory(maxKeys = 2, maxPerKey = 2)
        val one = NotificationText.entries(NotificationContent(title = "a", text = "1"), now)
        memory.fresh("k1", null, false, one)
        memory.fresh("k2", null, false, one)
        memory.fresh("k3", null, false, one)
        // k1 was the least recently posted key, so it was forgotten.
        assertEquals(1, memory.fresh("k1", null, false, one).size)
        val lines = NotificationText.entries(NotificationContent(title = "t", textLines = listOf("x", "y", "z")), now)
        assertEquals(3, memory.fresh("k4", null, false, lines).size)
        // Only y and z are remembered.
        assertEquals(listOf("x"), memory.fresh("k4", null, false, lines).map { it.text })
    }
}
