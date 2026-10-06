package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable

// one occurrence, recurrences are already expanded. all day events use utc midnight of their
// local dates (like android's CalendarContract), so they don't shift when the timezone does.
// end is exclusive
@Serializable
data class CalendarEvent(
    val source: String,
    val id: String,
    val title: String,
    val location: String? = null,
    val allDay: Boolean,
    val startMs: Long,
    val endMs: Long,
)
