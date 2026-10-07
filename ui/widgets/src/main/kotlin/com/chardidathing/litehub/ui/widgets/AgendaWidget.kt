package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// upcoming events grouped by day, as many as fit
class AgendaWidget(context: Context, theme: ResolvedTheme, private val config: AgendaConfig, private val legend: Legend) :
    ListWidget(context, theme, config.title), CalendarWidget {

    private val sources = config.calendars.ifEmpty { legend.calendars }

    init {
        if (config.key) key = CalendarWidget.key(sources, legend, theme.colors.primary)
    }

    override fun show(snapshot: CalendarSnapshot, now: Moment) = setRows(AgendaRows.build(snapshot, sources, config.days, legend, now))
}
