package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable

// conditions use ha's names (sunny, partlycloudy, rainy...), open-meteo codes are mapped onto them
@Serializable
data class Weather(
    val condition: String,
    val temperature: Double?,
    val unit: String,
    val humidity: Double? = null,
    val wind: String? = null,
    val hourly: List<Forecast> = emptyList(),
    val daily: List<Forecast> = emptyList(),
)

// one hour or one day. low is only set for days
@Serializable
data class Forecast(
    val timeMs: Long,
    val condition: String,
    val temperature: Double?,
    val low: Double? = null,
    val precipitationChance: Int? = null,
)

// the weather or why there isn't any, stale keeps the last good one while showing the reason
sealed interface WeatherSnapshot {
    data object Loading : WeatherSnapshot

    data class Ready(val weather: Weather, val stale: String? = null) : WeatherSnapshot

    data class Failed(val reason: String) : WeatherSnapshot
}

data class TodoItem(val uid: String, val summary: String, val done: Boolean)

sealed interface TodoSnapshot {
    data object Loading : TodoSnapshot

    // error is a failed tick or add, shown until the next change
    data class Ready(val items: List<TodoItem>, val stale: String? = null, val error: String? = null) : TodoSnapshot

    data class Failed(val reason: String) : TodoSnapshot
}
