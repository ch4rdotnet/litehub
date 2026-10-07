package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.core.model.FeedSnapshot
import com.chardidathing.litehub.ui.components.ColorKey
import kotlinx.serialization.Serializable

// widgets fed by calendar sources, empty calendars means every one
interface CalendarWidget {
    fun show(snapshot: CalendarSnapshot, now: Moment)

    companion object {
        // a dot and name per calendar the widget shows, in the colours it draws them in
        fun key(sources: List<String>, legend: Legend, fallback: Int): List<ColorKey.Entry> =
            sources.map { ColorKey.Entry(legend.names[it] ?: it, legend.colors[it] ?: fallback) }
    }
}

interface FeedWidget {
    fun show(snapshot: FeedSnapshot, now: Moment)
}

@Serializable
data class AgendaConfig(val calendars: List<String> = emptyList(), val days: Int = 7, val title: String? = null, val key: Boolean = true)

@Serializable
data class MonthConfig(val calendars: List<String> = emptyList(), val key: Boolean = true)

@Serializable
data class HeadlinesConfig(val feeds: List<String> = emptyList(), val title: String? = null)
