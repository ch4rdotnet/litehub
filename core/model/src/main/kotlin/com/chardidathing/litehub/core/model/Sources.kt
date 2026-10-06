package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable

// where calendars and feeds come from. kept out of config.json because private calendar links
// work like passwords, dashboards only name a source by id
@Serializable
data class Sources(
    val version: Int,
    val calendars: List<CalendarSource> = emptyList(),
    val feeds: List<FeedSource> = emptyList(),
)

// exactly one of url (https, webcal or file) or entity (a ha calendar.* entity).
// color falls back to the theme palette by position
@Serializable
data class CalendarSource(
    val id: String,
    val name: String,
    val url: String? = null,
    val entity: String? = null,
    val color: Argb? = null,
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
)

@Serializable
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
)

const val DEFAULT_REFRESH_MINUTES = 30
