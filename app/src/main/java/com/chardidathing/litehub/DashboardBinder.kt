package com.chardidathing.litehub

import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.ui.widgets.EntityWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// keeps only the pages on screen subscribed, that's two while a swipe is between them.
// pages holds each page's entity widgets
class DashboardBinder(
    private val repository: EntityRepository,
    private val pages: List<List<EntityWidget>>,
    private val scope: CoroutineScope,
) {

    private val jobs = HashMap<Int, List<Job>>()

    init {
        for (widget in pages.flatten()) {
            val id = widget.config.entity
            // failures come back through the snapshot, so there's nothing to handle here
            if (repository.canToggle(id)) widget.onTap = { scope.launch { repository.toggle(id) } }
        }
    }

    fun show(first: Int, last: Int) {
        val range = first..last
        jobs.keys.filter { it !in range }.forEach { page -> jobs.remove(page)?.forEach(Job::cancel) }
        for (page in range) {
            if (page in jobs) continue
            val widgets = pages.getOrNull(page) ?: continue
            jobs[page] = widgets.map { widget ->
                scope.launch { repository.snapshot(widget.config.entity).collect(widget::show) }
            }
        }
        repository.setVisible(range.flatMap { pages.getOrNull(it).orEmpty() }.mapTo(HashSet()) { it.config.entity })
    }

    // nothing on screen costs anything, ha stops sending
    fun stop() {
        jobs.values.flatten().forEach(Job::cancel)
        jobs.clear()
        repository.setVisible(emptySet())
    }
}
