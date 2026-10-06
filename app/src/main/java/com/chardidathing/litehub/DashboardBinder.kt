package com.chardidathing.litehub

import com.chardidathing.litehub.source.calendar.CalendarRepository
import com.chardidathing.litehub.source.feed.FeedRepository
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.widgets.CalendarWidget
import com.chardidathing.litehub.ui.widgets.EntityWidget
import com.chardidathing.litehub.ui.widgets.EntitiesWidget
import com.chardidathing.litehub.ui.widgets.ClockWidget
import com.chardidathing.litehub.ui.widgets.FeedWidget
import com.chardidathing.litehub.ui.widgets.PhotoWidget
import com.chardidathing.litehub.ui.widgets.Moment
import com.chardidathing.litehub.ui.widgets.NotificationsWidget
import com.chardidathing.litehub.ui.widgets.TodoWidget
import com.chardidathing.litehub.ui.widgets.WeatherWidget
import com.chardidathing.litehub.source.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// feeds the page on screen and keeps its neighbours laid out with their last known data, so a
// swipe only has to slide them in. only the page on screen is subscribed to ha. pages holds
// each page's widgets, anything that isn't fed by a source is skipped
class DashboardBinder(
    private val ha: EntityRepository,
    private val calendars: CalendarRepository,
    private val feeds: FeedRepository,
    private val weather: WeatherRepository,
    // a list's "type" chip wants the keyboard, the activity owns that
    private val askText: (title: String, onText: (String) -> Unit) -> Unit,
    private val notifications: NotificationCenter,
    // a fresh frame per photo tile, each keeps its own shuffled queue
    private val photoFrame: () -> Result<PhotoFrame>,
    private val photoSeconds: () -> Int,
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
        for (group in pages.flatten().filterIsInstance<EntitiesWidget>()) {
            group.canTap = ha::canToggle
            group.onTap = { id -> scope.launch { ha.toggle(id) } }
        }
        for (panel in pages.flatten().filterIsInstance<NotificationsWidget>()) {
            panel.onRemove = notifications::remove
            panel.onClear = notifications::clear
        }
        for (list in pages.flatten().filterIsInstance<TodoWidget>()) {
            val id = list.config.entity
            list.onToggle = { uid, done -> scope.launch { ha.todoSetDone(id, uid, done) } }
            list.onAdd = { text -> scope.launch { ha.todoAdd(id, text) } }
            list.onType = { askText("add to ${list.config.title ?: "the list"}") { text -> scope.launch { ha.todoAdd(id, text) } } }
        }
    }

    fun show(page: Int) {
        val range = page - 1..page + 1
        jobs.keys.filter { it !in range }.forEach { page -> jobs.remove(page)?.forEach(Job::cancel) }
        for (page in range) {
            if (page in jobs) continue
            val widgets = pages.getOrNull(page) ?: continue
            jobs[page] = widgets.mapNotNull(::bind)
        }
        val onScreen = pages.getOrNull(page).orEmpty()
        val entities = onScreen.filterIsInstance<EntityWidget>().map { it.config.entity } +
            onScreen.filterIsInstance<EntitiesWidget>().flatMap { it.config.entities } +
            onScreen.filterIsInstance<WeatherWidget>().mapNotNull { it.config.entity }
        ha.setVisible(entities.toSet())
    }

    // nothing on screen costs anything, ha stops sending
    fun stop() {
        jobs.values.flatten().forEach(Job::cancel)
        jobs.clear()
        ha.setVisible(emptySet())
    }

    // one widget fed without the page bookkeeping, for tile previews. it leaves what ha sends
    // the live dashboard alone
    fun preview(widget: WidgetView): Job? = bind(widget)

    private fun bind(widget: WidgetView): Job? = when (widget) {
        is EntityWidget -> scope.launch { ha.snapshot(widget.config.entity).collect(widget::show) }
        is CalendarWidget -> scope.launch { combine(calendars.snapshot, now, ::Pair).collect { (s, m) -> widget.show(s, m) } }
        is FeedWidget -> scope.launch { combine(feeds.snapshot, now, ::Pair).collect { (s, m) -> widget.show(s, m) } }
        is WeatherWidget -> scope.launch {
            combine(weather.watch(weather.key(widget.config.entity)), now, ::Pair).collect { (s, m) -> widget.show(s, m) }
        }
        is EntitiesWidget -> scope.launch {
            for (id in widget.config.entities.distinct()) launch { ha.snapshot(id).collect { widget.show(id, it) } }
        }
        is ClockWidget -> scope.launch { now.collect(widget::show) }
        is PhotoWidget -> scope.launch {
            val frame = photoFrame().getOrElse {
                widget.fail(it.message ?: "no photos")
                return@launch
            }
            while (true) {
                // decoded at the tile's own size, which isn't known until it's laid out
                if (widget.width == 0 || widget.height == 0) {
                    delay(LAYOUT_WAIT_MS)
                    continue
                }
                frame.next(widget.width, widget.height).fold(widget::show) { widget.fail(it.message ?: "couldn't load a photo") }
                delay((widget.config.seconds ?: photoSeconds()) * MS_PER_S)
            }
        }
        is NotificationsWidget -> scope.launch { combine(notifications.items, now, ::Pair).collect { (n, m) -> widget.show(n, m) } }
        is TodoWidget -> scope.launch {
            try {
                ha.todo(widget.config.entity).collect(widget::show)
            } finally {
                ha.releaseTodo(widget.config.entity)
            }
        }
        else -> null
    }

    private companion object {
        const val LAYOUT_WAIT_MS = 100L
        const val MS_PER_S = 1000L
    }
}
