package dev.grod

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.ZoneId

/**
 * Notification batching (docs/rules.md, "Notifications (Android)"): held notifications are snoozed to
 * the next delivery time, and every notification is logged (docs/data.md). Callbacks run on the main thread.
 */
class NotificationService : NotificationListenerService() {
    private lateinit var store: RuleStore
    private lateinit var dnd: Dnd
    private lateinit var held: Held
    private var settings: NotificationSettings? = null
    private val labels = HashMap<String, String?>()

    /** Ongoing notifications (players, downloads) update constantly; they are logged once per key. */
    private val loggedOngoing = HashSet<String>()

    // The store also notifies for fire counts and sync status; only a settings change matters here.
    private val onStoreChanged: () -> Unit = { if (store.rules.notifications != settings) apply(store.rules.notifications) }

    override fun onListenerConnected() {
        store = RuleStore.get(this)
        dnd = Dnd(this)
        held = Held(this)
        store.addListener(onStoreChanged)
        store.checkLocal()
        apply(store.rules.notifications)
    }

    override fun onListenerDisconnected() {
        if (::store.isInitialized) store.removeListener(onStoreChanged)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!::store.isInitialized || sbn.packageName == packageName) return
        val now = System.currentTimeMillis()
        val n = posted(sbn)
        val heldUntil = held.until(sbn.key)
        when (Batching.verdict(settings, n, packageName, heldUntil, now)) {
            Verdict.DELIVERED -> if (heldUntil != null) {
                held.remove(sbn.key)
                log(LogLines.removed(now, n.pkg, n.title, "delivered"))
            }
            Verdict.HOLD -> {
                val at = Batching.holdUntil(settings!!, heldUntil, now, ZoneId.systemDefault())
                snoozeNotification(sbn.key, at - now)
                held.put(sbn.key, at)
                logPosted(sbn, n, now, held = true)
            }
            Verdict.PASS -> logPosted(sbn, n, now, held = false)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // A snooze is grod's own hold, already logged as held.
        if (reason == REASON_SNOOZED || sbn.packageName == packageName) return
        loggedOngoing.remove(sbn.key)
        val why = when (reason) {
            REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> "app"
            REASON_CANCEL, REASON_CANCEL_ALL, REASON_CLICK -> "user"
            else -> "other"
        }
        log(LogLines.removed(System.currentTimeMillis(), sbn.packageName, title(sbn), why))
    }

    private fun apply(next: NotificationSettings?) {
        val before = settings
        settings = next
        val on = next?.enabled == true
        dnd.set(on)
        val now = System.currentTimeMillis()
        held.prune(now)
        val waiting = held.waiting(now)
        when {
            // Re-snoozing a snoozed notification only works where the platform allows it (not on
            // Android 10); elsewhere held ones still arrive at the delivery time they were given.
            !on && before?.enabled == true -> waiting.keys.forEach { snoozeNotification(it, RELEASE_MS) }
            on && before?.enabled == true && before.slots != next.slots -> {
                val at = Batching.nextDelivery(now, next.slots, ZoneId.systemDefault())
                for ((key, until) in waiting) {
                    if (until == at) continue
                    snoozeNotification(key, at - now)
                    held.put(key, at)
                }
            }
        }
    }

    private fun posted(sbn: StatusBarNotification): Posted {
        val notification = sbn.notification
        return Posted(
            pkg = sbn.packageName,
            category = notification.category,
            title = title(sbn),
            ongoing = sbn.isOngoing,
            foregroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            postTime = sbn.postTime,
        )
    }

    private fun logPosted(sbn: StatusBarNotification, n: Posted, now: Long, held: Boolean) {
        if ((n.ongoing || n.foregroundService) && !loggedOngoing.add(sbn.key)) return
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        log(LogLines.posted(now, n.pkg, label(n.pkg), n.category, n.title, text, held))
    }

    private fun title(sbn: StatusBarNotification): String? =
        sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun label(pkg: String): String? = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun log(line: String) = LogWriter.get(this).append("notifications", line, store.rules.keepDays)

    private companion object {
        const val RELEASE_MS = 1_000L
    }
}
