package com.chardidathing.litehub

import com.chardidathing.litehub.source.calendar.CalendarRepository
import com.chardidathing.litehub.source.feed.FeedRepository
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.widgets.CalendarWidget
import com.chardidathing.litehub.ui.widgets.EntityWidget
import com.chardidathing.litehub.ui.widgets.FeedWidget
import com.chardidathing.litehub.ui.widgets.Moment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// feeds only the pages on screen, that's two while a swipe is between them. pages holds each
// page's widgets, anything that isn't fed by a source is skipped
class DashboardBinder(
    private val ha: EntityRepository,
    private val calendars: CalendarRepository,
    private val feeds: FeedRepository,
    private val now: StateFlow<Moment>,
    private val pages: List<List<WidgetView>>,
    private val scope: CoroutineScope,
) {

    private val jobs = HashMap<Int, List<Job>>()

    init {
        for (widget in pages.flatten().filterIsInstance<EntityWidget>()) {
            val id = widget.config.entity
            // failures come back through the snapshot, so there's nothing to handle here
            if (ha.canToggle(id)) widget.onTap = { scope.launch { ha.toggle(id) } }
        }
    }

    fun show(first: Int, last: Int) {
        val range = first..last
        jobs.keys.filter { it !in range }.forEach { page -> jobs.remove(page)?.forEach(Job::cancel) }
        for (page in range) {
            if (page in jobs) continue
            val widgets = pages.getOrNull(page) ?: continue
            jobs[page] = widgets.mapNotNull(::bind)
        }
        val entities = range.flatMap { pages.getOrNull(it).orEmpty() }.filterIsInstance<EntityWidget>()
        ha.setVisible(entities.mapTo(HashSet()) { it.config.entity })
    }

    // nothing on screen costs anything, ha stops sending
    fun stop() {
        jobs.values.flatten().forEach(Job::cancel)
        jobs.clear()
        ha.setVisible(emptySet())
    }

    private fun bind(widget: WidgetView): Job? = when (widget) {
        is EntityWidget -> scope.launch { ha.snapshot(widget.config.entity).collect(widget::show) }
        is CalendarWidget -> scope.launch { combine(calendars.snapshot, now, ::Pair).collect { (s, m) -> widget.show(s, m) } }
        is FeedWidget -> scope.launch { combine(feeds.snapshot, now, ::Pair).collect { (s, m) -> widget.show(s, m) } }
        else -> null
    }
}
