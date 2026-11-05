package dev.grod

import android.content.Context

/**
 * Keys of the notifications grod snoozed and when each is due. Kept in preferences so a restarted
 * listener still recognises its own deliveries, and so the app can show how many are waiting.
 */
class Held(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("grod.held", Context.MODE_PRIVATE)

    fun until(key: String): Long? = prefs.getLong(key, 0L).takeIf { it > 0 }

    fun put(key: String, at: Long) = prefs.edit().putLong(key, at).apply()

    fun remove(key: String) = prefs.edit().remove(key).apply()

    /** Keys still waiting at [now] with their delivery time. */
    fun waiting(now: Long): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.takeIf { it > now }?.let { k to it } }.toMap()

    /** Drops records long past their delivery, which the system delivered or the app withdrew. */
    fun prune(now: Long) {
        val stale = prefs.all.filter { (_, v) -> (v as? Long ?: 0L) < now - STALE_MS }.keys
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    private companion object {
        const val STALE_MS = 24 * 60 * 60 * 1000L
    }
}
