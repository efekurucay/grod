package dev.grod

/** One message of a chat (MessagingStyle) notification. [sender] null is the phone's owner. */
data class ChatMessage(val time: Long, val sender: String?, val text: String)

/** What a posted notification shows, read from its extras by [NotificationService]. */
data class NotificationContent(
    val title: String?,
    val conversationTitle: String? = null,
    val isGroup: Boolean = false,
    /** The owner's name in a chat, for their own replies. */
    val selfName: String? = null,
    /** Earlier (historic) messages first, then the current ones. */
    val messages: List<ChatMessage> = emptyList(),
    /** InboxStyle lines, such as one per email in a mail app's summary. */
    val textLines: List<String> = emptyList(),
    val bigText: String? = null,
    val text: String? = null,
    val groupSummary: Boolean = false,
)

/** One `posted` log line to write. [seenAs] tells it apart from what an update posts again. */
data class LogEntry(val t: Long, val title: String?, val text: String?, val seenAs: String)

/**
 * What a notification logs (docs/data.md): one line per message or entry, with the full text where
 * the app provides it, so the history keeps what was written instead of "3 new messages". Pure, so
 * extraction is unit tested without Android.
 */
object NotificationText {
    fun entries(c: NotificationContent, now: Long): List<LogEntry> {
        val chat = c.conversationTitle ?: c.title
        if (c.messages.isNotEmpty()) {
            return c.messages.map { m ->
                val sender = m.sender ?: c.selfName ?: "You"
                // A one-to-one chat is titled with the other person; anyone else gets named.
                val text = if (c.isGroup || sender != chat) "$sender: ${m.text}" else m.text
                LogEntry(m.time.takeIf { it > 0 } ?: now, chat, text, "m|${m.time}|${m.sender}|${m.text}")
            }
        }
        val lines = c.textLines.filter { it.isNotBlank() }
        if (lines.isNotEmpty()) return lines.map { LogEntry(now, chat, it, "l|$it") }
        // A summary without entries of its own ("5 new messages") only stands for its group's notifications.
        if (c.groupSummary) return emptyList()
        val text = c.bigText ?: c.text
        return listOf(LogEntry(now, c.title, text, "$PLAIN${c.title}|$text"))
    }

    /** Marks a whole-notification entry, which may be logged again once the notification is gone. */
    const val PLAIN = "p|"
}

/**
 * Remembers what was logged, so an update that posts everything again (a chat with all its messages,
 * a mail summary with all its emails) logs only what is new, and a group summary logs only entries
 * its group's notifications did not already log. Bounded: the least recently posted keys and groups,
 * and the oldest entries of each, are forgotten first.
 */
class NotificationMemory(private val maxKeys: Int = 200, private val maxPerKey: Int = 200) {
    private val byKey = lru()
    private val byGroup = lru()

    /** The [entries] of notification [key] in [group] still to log; from now on they count as logged. */
    fun fresh(key: String, group: String?, summary: Boolean, entries: List<LogEntry>): List<LogEntry> {
        val seen = byKey.getOrPut(key) { LinkedHashSet() }
        var fresh = entries.filter { seen.add(it.seenAs) }
        trim(seen)
        if (group == null) return fresh
        val logged = byGroup.getOrPut(group) { LinkedHashSet() }
        if (summary) {
            fresh = fresh.filter { e -> searchable(e.text).let { s -> s.isEmpty() || logged.none { s in it } } }
        } else {
            fresh.forEach { logged.add(searchable(listOfNotNull(it.title, it.text).joinToString(" "))) }
            trim(logged)
        }
        return fresh
    }

    /** The notification is gone: the same text posted again later is a new notification. */
    fun removed(key: String) {
        byKey[key]?.removeAll { it.startsWith(NotificationText.PLAIN) }
    }

    private fun trim(set: LinkedHashSet<String>) {
        val over = set.size - maxPerKey
        if (over > 0) set.iterator().let { old -> repeat(over) { old.next(); old.remove() } }
    }

    private fun lru() = object : LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>) = size > maxKeys
    }

    private companion object {
        /**
         * Letters and digits only, folded: a summary line "Ayşe: hi" or "Ayşe   Lunch?" then matches
         * the notification it lists ("Ayşe" + "hi", "Ayşe" + "Lunch? See you at 12").
         */
        fun searchable(text: String?): String = Launcher.fold(text.orEmpty()).filter { it.isLetterOrDigit() }
    }
}
