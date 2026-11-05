package dev.grod

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The top-level `notifications` settings (docs/rules.md, "Notifications (Android)"). */
data class NotificationSettings(
    val enabled: Boolean,
    val deliverEvery: Int,
    val allow: List<NotificationAllow>,
    val keepDays: Int,
    /** Fixed local delivery times as minutes after midnight, sorted; when set it replaces [deliverEvery]. */
    val deliverAt: List<Int>? = null,
) {
    /** Delivery times of a day, as minutes after local midnight. */
    val slots: List<Int> get() = deliverAt ?: (0 until MINUTES_PER_DAY step deliverEvery).toList()

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_KEEP_DAYS = 30
    }
}

/** Every non-null field must match; all compare case-insensitively. */
data class NotificationAllow(val pkg: String?, val category: String?, val titleContains: String?)

/** What the hold decision needs from a posted notification. */
data class Posted(
    val pkg: String,
    val category: String?,
    val title: String?,
    val ongoing: Boolean,
    val foregroundService: Boolean,
    /** When the system first accepted it; a snoozed notification keeps it when it comes back. */
    val postTime: Long,
)

enum class Verdict {
    /** Show it now. */
    PASS,

    /** Snooze it until the next delivery. */
    HOLD,

    /** It is a held notification coming back at its delivery time; leave it alone. */
    DELIVERED,
}

/** Pure decisions of notification batching, unit tested without Android. */
object Batching {
    /** How early or late a delivery may arrive, and how old a post may be before it counts as a repost. */
    const val GRACE_MS = 60_000L

    private val NEVER_HELD = setOf("call", "alarm")

    fun allows(a: NotificationAllow, n: Posted): Boolean =
        (a.pkg == null || a.pkg.equals(n.pkg, ignoreCase = true)) &&
            (a.category == null || a.category.equals(n.category, ignoreCase = true)) &&
            (a.titleContains == null || n.title?.contains(a.titleContains, ignoreCase = true) == true)

    /**
     * [heldUntil] is the delivery time grod gave this notification's key, if it held it. A post whose
     * time is older than [GRACE_MS] is the system bringing back a snoozed notification: an app's own
     * post or update always carries the time it was posted.
     */
    fun verdict(s: NotificationSettings?, n: Posted, ownPkg: String, heldUntil: Long?, now: Long): Verdict {
        if (heldUntil != null && now >= heldUntil - GRACE_MS) return Verdict.DELIVERED
        if (now - n.postTime > GRACE_MS) return Verdict.DELIVERED
        if (s == null || !s.enabled) return Verdict.PASS
        if (n.pkg == ownPkg || n.ongoing || n.foregroundService) return Verdict.PASS
        if (n.category?.lowercase() in NEVER_HELD) return Verdict.PASS
        if (s.allow.any { allows(it, n) }) return Verdict.PASS
        return Verdict.HOLD
    }

    /** An update of a held notification waits for the same delivery as the original. */
    fun holdUntil(s: NotificationSettings, heldUntil: Long?, now: Long, zone: ZoneId): Long =
        heldUntil?.takeIf { it > now } ?: nextDelivery(now, s.slots, zone)

    /**
     * First delivery strictly after [now], for daily [slots] in minutes after local midnight (sorted).
     * Slots are wall-clock times, so 60-minute slots fall on the hour and listed times keep their hour
     * across DST. A slot that a DST jump skips becomes the moment the clock jumps; in a repeated hour
     * the first matching instant after [now] is used.
     */
    fun nextDelivery(now: Long, slots: List<Int>, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // In a repeated hour slot order is not time order (02:00 the second time comes after 02:15 the
        // first time), so every candidate from yesterday to tomorrow is compared.
        return (-1L..1L).flatMap { day -> slots.flatMap { wallInstants(today.plusDays(day), it, zone) } }
            .filter { it > now }.min()
    }
}

/**
 * The instants a local wall time ([minute] after midnight on [date]) names: one normally, the jump
 * moment when DST skips it, and both passes when DST repeats it.
 */
fun wallInstants(date: LocalDate, minute: Int, zone: ZoneId): List<Long> {
    val wall = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
    val transition = zone.rules.getTransition(wall)
    val instants = when {
        transition == null -> listOf(wall.atZone(zone).toInstant())
        transition.isGap -> listOf(transition.instant)
        else -> listOf(wall.atOffset(transition.offsetBefore).toInstant(), wall.atOffset(transition.offsetAfter).toInstant())
    }
    return instants.map { it.toEpochMilli() }
}
