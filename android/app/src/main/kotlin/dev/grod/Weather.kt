package dev.grod

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

/** Pure parts of the launcher weather (docs/rules.md, "Launcher (Android)"), unit tested. */
object Weather {
    /** Coordinates are sent to one decimal (about 10 km), enough for the weather and no more. */
    fun round1(x: Double): Double = Math.round(x * 10) / 10.0

    fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${round1(lat)}&longitude=${round1(lon)}&current=temperature_2m,weather_code"

    /** WMO weather interpretation code to one short word; null for codes Open-Meteo does not use. */
    fun describe(code: Int): String? = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        80, 81, 82 -> "Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> null
    }

    /** "14° · Cloudy" from an Open-Meteo answer; null when the answer is not usable. */
    fun label(json: String): String? = try {
        val current = JSONObject(json).getJSONObject("current")
        val temp = Math.round(current.getDouble("temperature_2m"))
        val sky = describe(current.getInt("weather_code"))
        if (sky == null) "$temp°" else "$temp° · $sky"
    } catch (e: Exception) {
        null
    }
}

/**
 * Weather text for the launcher header, only when `launcher.weather` is on: asks for coarse location
 * once, uses the last known location (no GPS, no Play services), fetches at most hourly and keeps the
 * last value. Anything missing (setting off, permission denied, no location, offline) means no text.
 */
class WeatherSource(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("grod.weather", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var fetching = false

    fun update(activity: Activity, enabled: Boolean, show: (String?) -> Unit) {
        if (!enabled) return show(null)
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean(K_ASKED, false)) ask(activity)
            return show(null)
        }
        show(prefs.getString(K_TEXT, null))
        if (System.currentTimeMillis() - prefs.getLong(K_AT, 0L) < MAX_AGE_MS || fetching) return
        val url = location()?.let { Weather.url(it.first, it.second) } ?: return
        fetching = true
        thread(name = "grod-weather") {
            val text = try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    try {
                        if (responseCode == 200) Weather.label(inputStream.use { it.readNBytesCompat(MAX_BYTES).toString(Charsets.UTF_8) }) else null
                    } finally {
                        disconnect()
                    }
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                fetching = false
                if (text != null) prefs.edit().putString(K_TEXT, text).putLong(K_AT, System.currentTimeMillis()).apply()
                show(text ?: prefs.getString(K_TEXT, null))
            }
        }
    }

    private fun ask(activity: Activity) {
        prefs.edit().putBoolean(K_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle("Weather")
            .setMessage("To show the weather next to the date, grod needs your approximate location. It is rounded to about 10 km and sent only to Open-Meteo, at most once an hour.")
            .setPositiveButton("Continue") { _, _ -> activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 0) }
            .setNegativeButton("Not now", null)
            .show()
    }

    @Suppress("MissingPermission")
    private fun location(): Pair<Double, Double>? {
        val lm = app.getSystemService(LocationManager::class.java)
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return last.latitude to last.longitude
    }

    private companion object {
        const val K_ASKED = "asked"
        const val K_TEXT = "text"
        const val K_AT = "at"
        const val MAX_AGE_MS = 60 * 60 * 1000L
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 64 * 1024
    }
}
