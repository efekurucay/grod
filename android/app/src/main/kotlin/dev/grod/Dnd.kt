package dev.grod

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.os.Build

/**
 * Do Not Disturb while batching is on: only calls (anyone, and repeat callers), alarms, media,
 * system sounds, events and reminders make a sound. The user's own filter and policy are saved
 * when grod takes over and restored when batching is turned off.
 */
class Dnd(context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.applicationContext.getSharedPreferences("grod.dnd", Context.MODE_PRIVATE)

    fun set(on: Boolean) {
        if (!nm.isNotificationPolicyAccessGranted) return
        val saved = prefs.contains(K_FILTER)
        if (on) {
            if (!saved) save()
            nm.notificationPolicy = grodPolicy()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else if (saved) {
            restore()
        }
    }

    private fun grodPolicy(): Policy {
        var categories = Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            Policy.PRIORITY_CATEGORY_EVENTS or Policy.PRIORITY_CATEGORY_REMINDERS
        if (Build.VERSION.SDK_INT >= 28) {
            categories = categories or Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA or
                Policy.PRIORITY_CATEGORY_SYSTEM
        }
        // No suppressed visual effects: what is not held still shows, just silently.
        return policy(categories, Policy.PRIORITY_SENDERS_ANY, Policy.PRIORITY_SENDERS_STARRED, 0)
    }

    private fun save() {
        val p = nm.notificationPolicy
        prefs.edit()
            .putInt(K_FILTER, nm.currentInterruptionFilter)
            .putInt(K_CATEGORIES, p.priorityCategories)
            .putInt(K_CALLS, p.priorityCallSenders)
            .putInt(K_MESSAGES, p.priorityMessageSenders)
            .putInt(K_EFFECTS, p.suppressedVisualEffects)
            .apply()
    }

    private fun restore() {
        nm.notificationPolicy = policy(
            prefs.getInt(K_CATEGORIES, 0),
            prefs.getInt(K_CALLS, Policy.PRIORITY_SENDERS_ANY),
            prefs.getInt(K_MESSAGES, Policy.PRIORITY_SENDERS_ANY),
            prefs.getInt(K_EFFECTS, 0),
        )
        nm.setInterruptionFilter(prefs.getInt(K_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL))
        prefs.edit().clear().apply()
    }

    private fun policy(categories: Int, calls: Int, messages: Int, effects: Int): Policy =
        if (Build.VERSION.SDK_INT >= 28) Policy(categories, calls, messages, effects) else Policy(categories, calls, messages)

    private companion object {
        const val K_FILTER = "filter"
        const val K_CATEGORIES = "categories"
        const val K_CALLS = "calls"
        const val K_MESSAGES = "messages"
        const val K_EFFECTS = "effects"
    }
}
