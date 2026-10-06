package com.chardidathing.litehub.source.weather

import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.Forecast
import com.chardidathing.litehub.core.model.Weather
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

// a ha weather entity's state and attributes for now, weather.get_forecasts for what's next
internal object HaWeather {

    fun current(entity: Entity, hourly: List<Forecast>, daily: List<Forecast>): Weather {
        val wind = entity.attribute("wind_speed")?.toDoubleOrNull()
        return Weather(
            condition = entity.state,
            temperature = entity.attribute("temperature")?.toDoubleOrNull(),
            unit = entity.attribute("temperature_unit") ?: "°C",
            humidity = entity.attribute("humidity")?.toDoubleOrNull(),
            wind = wind?.let { "${it.toInt()} ${entity.attribute("wind_speed_unit").orEmpty()}".trim() },
            hourly = hourly,
            daily = daily,
        )
    }

    fun forecasts(response: JsonObject?, entity: String, zone: ZoneId): List<Forecast> {
        val list = ((response?.get(entity) as? JsonObject)?.get("forecast") as? JsonArray).orEmpty()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val time = (o["datetime"] as? JsonPrimitive)?.contentOrNull?.let { parse(it, zone) } ?: return@mapNotNull null
            Forecast(
                time,
                (o["condition"] as? JsonPrimitive)?.contentOrNull ?: "exceptional",
                (o["temperature"] as? JsonPrimitive)?.doubleOrNull,
                low = (o["templow"] as? JsonPrimitive)?.doubleOrNull,
                precipitationChance = (o["precipitation_probability"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }

    private fun parse(s: String, zone: ZoneId): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        runCatching { LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    }
}
