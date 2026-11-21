package dev.grod

import android.content.Context

/** The top-level `packs` settings (docs/rules.md, "Packs"); no section means no packs. */
data class PackSettings(val count: Int = 0, val minutes: Int = 5, val refilled: String? = null)

/** Packs taken on this phone since the refill they belong to. Pure, so it is unit tested. */
data class PackUse(val refilled: String?, val used: Int) {
    fun left(settings: PackSettings): Int = (settings.count - usedOf(settings)).coerceAtLeast(0)

    fun take(settings: PackSettings): PackUse = PackUse(settings.refilled, usedOf(settings) + 1)

    /** A new refill starts the count over. */
    private fun usedOf(settings: PackSettings): Int = if (refilled == settings.refilled) used else 0
}

/** Keeps [PackUse] on the phone, so packs taken survive a restart. */
class PackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("grod.packs", Context.MODE_PRIVATE)

    var use: PackUse
        get() = PackUse(prefs.getString(K_REFILLED, null), prefs.getInt(K_USED, 0))
        set(value) = prefs.edit().putString(K_REFILLED, value.refilled).putInt(K_USED, value.used).apply()

    private companion object {
        const val K_REFILLED = "refilled"
        const val K_USED = "used"
    }
}
