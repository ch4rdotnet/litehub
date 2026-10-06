package com.chardidathing.litehub.source.weather

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OpenMeteoTest {

    private val sample = """{"utc_offset_seconds":37800,
      "current_units":{"temperature_2m":"°C","wind_speed_10m":"km/h"},
      "current":{"temperature_2m":17.3,"relative_humidity_2m":55,"weather_code":2,"wind_speed_10m":12.4,"is_day":1},
      "hourly":{"time":["2026-10-07T13:00","2026-10-07T14:00","2026-10-07T23:00"],"temperature_2m":[17.0,18.1,11.0],
                "weather_code":[61,65,0],"precipitation_probability":[60,80,0],"is_day":[1,1,0]},
      "daily":{"time":["2026-10-07","2026-10-08"],"weather_code":[63,3],"temperature_2m_max":[19.0,21.5],
               "temperature_2m_min":[9.0,10.2],"precipitation_probability_max":[80,10]}}"""

    @Test
    fun `current, hourly and daily with wmo codes as ha conditions`() {
        val now = Instant.parse("2026-10-07T02:45:00Z").toEpochMilli() // 13:15 acdt
        val w = OpenMeteo.parse(sample, now)
        assertEquals("partlycloudy", w.condition)
        assertEquals(17.3, w.temperature!!, 0.0)
        assertEquals("12 km/h", w.wind)
        assertEquals(listOf("rainy", "pouring", "clear-night"), w.hourly.map { it.condition })
        assertEquals(Instant.parse("2026-10-07T02:30:00Z").toEpochMilli(), w.hourly[0].timeMs)
        assertEquals(9.0, w.daily[0].low!!, 0.0)
        assertEquals(80, w.daily[0].precipitationChance)
    }
}
