package com.chardidathing.litehub.source.weather

import com.chardidathing.litehub.core.model.Forecast
import com.chardidathing.litehub.core.model.Location
import com.chardidathing.litehub.core.model.Weather
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

// open-meteo, for hubs without ha. free, keyless, and its wmo codes map onto ha's conditions
object OpenMeteo {

    const val HOURS = 24
    const val DAYS = 7

    fun url(location: Location) = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${location.latitude}&longitude=${location.longitude}" +
        "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=auto&forecast_days=$DAYS"

    fun parse(text: String, nowMs: Long): Weather {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("open-meteo sent something unreadable", e)
        }
        val offset = ZoneOffset.ofTotalSeconds((root["utc_offset_seconds"] as? JsonPrimitive)?.intOrNull ?: 0)
        val current = root["current"] as? JsonObject ?: throw IOException("open-meteo sent no current weather")
        val units = root["current_units"] as? JsonObject
        val hourly = root["hourly"] as? JsonObject
        val daily = root["daily"] as? JsonObject

        val hours = column(hourly, "time").mapIndexedNotNull { i, t ->
            val time = (t as? JsonPrimitive)?.contentOrNull?.let { LocalDateTime.parse(it).toInstant(offset).toEpochMilli() } ?: return@mapIndexedNotNull null
            Forecast(
                time,
                condition(int(hourly, "weather_code", i), int(hourly, "is_day", i) != 0),
                double(hourly, "temperature_2m", i),
                precipitationChance = int(hourly, "precipitation_probability", i),
            )
        }.filter { it.timeMs >= nowMs - HOUR_MS }.take(HOURS)

        val days = column(daily, "time").mapIndexedNotNull { i, t ->
            val day = (t as? JsonPrimitive)?.contentOrNull?.let { LocalDate.parse(it).atStartOfDay().toInstant(offset).toEpochMilli() } ?: return@mapIndexedNotNull null
            Forecast(
                day,
                condition(int(daily, "weather_code", i), true),
                double(daily, "temperature_2m_max", i),
                low = double(daily, "temperature_2m_min", i),
                precipitationChance = int(daily, "precipitation_probability_max", i),
            )
        }

        val wind = (current["wind_speed_10m"] as? JsonPrimitive)?.doubleOrNull
        return Weather(
            condition = condition((current["weather_code"] as? JsonPrimitive)?.intOrNull, (current["is_day"] as? JsonPrimitive)?.intOrNull != 0),
            temperature = (current["temperature_2m"] as? JsonPrimitive)?.doubleOrNull,
            unit = (units?.get("temperature_2m") as? JsonPrimitive)?.contentOrNull ?: "°C",
            humidity = (current["relative_humidity_2m"] as? JsonPrimitive)?.doubleOrNull,
            wind = wind?.let { "${it.toInt()} ${(units?.get("wind_speed_10m") as? JsonPrimitive)?.contentOrNull ?: "km/h"}" },
            hourly = hours,
            daily = days,
        )
    }

    // wmo weather interpretation codes to ha's condition names
    fun condition(code: Int?, day: Boolean): String = when (code) {
        0 -> if (day) "sunny" else "clear-night"
        1, 2 -> "partlycloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57, 61, 63, 80, 81 -> "rainy"
        65, 82 -> "pouring"
        66, 67 -> "snowy-rainy"
        71, 73, 75, 77, 85, 86 -> "snowy"
        95 -> "lightning-rainy"
        96, 99 -> "hail"
        else -> "exceptional"
    }

    private fun column(o: JsonObject?, key: String): List<JsonElement> = (o?.get(key) as? JsonArray).orEmpty()

    private fun int(o: JsonObject?, key: String, i: Int) = (column(o, key).getOrNull(i) as? JsonPrimitive)?.intOrNull

    private fun double(o: JsonObject?, key: String, i: Int) = (column(o, key).getOrNull(i) as? JsonPrimitive)?.doubleOrNull

    private const val HOUR_MS = 3_600_000L
}
