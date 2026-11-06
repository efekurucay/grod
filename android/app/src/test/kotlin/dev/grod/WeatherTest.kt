package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    @Test
    fun roundsLocationToOneDecimal() {
        assertEquals(36.9, Weather.round1(36.8969), 0.0)
        assertEquals(30.7, Weather.round1(30.7133), 0.0)
        assertEquals(-0.1, Weather.round1(-0.1278), 0.0)
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=36.9&longitude=30.7&current=temperature_2m,weather_code",
            Weather.url(36.8969, 30.7133),
        )
    }

    @Test
    fun mapsWmoCodes() {
        assertEquals("Clear", Weather.describe(0))
        assertEquals("Partly cloudy", Weather.describe(2))
        assertEquals("Cloudy", Weather.describe(3))
        assertEquals("Fog", Weather.describe(48))
        assertEquals("Drizzle", Weather.describe(55))
        assertEquals("Rain", Weather.describe(63))
        assertEquals("Snow", Weather.describe(86))
        assertEquals("Showers", Weather.describe(81))
        assertEquals("Thunderstorm", Weather.describe(99))
        assertNull(Weather.describe(42))
    }

    @Test
    fun labelsTheAnswer() {
        assertEquals("14° · Cloudy", Weather.label("""{"current":{"time":"2026-11-06T15:00","temperature_2m":13.6,"weather_code":3}}"""))
        assertEquals("-2° · Snow", Weather.label("""{"current":{"temperature_2m":-2.4,"weather_code":73}}"""))
        assertEquals("20°", Weather.label("""{"current":{"temperature_2m":20.0,"weather_code":42}}"""))
        assertNull(Weather.label("""{"error":true}"""))
    }
}
