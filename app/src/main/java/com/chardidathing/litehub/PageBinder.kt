package com.chardidathing.litehub

import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.ui.widgets.EntityWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// connects a page's entity widgets to the repository while the page is on screen
class PageBinder(
    private val repository: EntityRepository,
    private val widgets: List<EntityWidget>,
    private val scope: CoroutineScope,
) {

    private var jobs: List<Job> = emptyList()

    init {
        for (widget in widgets) {
            val id = widget.config.entity
            if (!repository.canToggle(id)) continue
            // failures come back through the snapshot, so there's nothing to handle here
            widget.onTap = { scope.launch { repository.toggle(id) } }
        }
    }

    fun start() {
        if (jobs.isNotEmpty() || widgets.isEmpty()) return
        repository.setVisible(widgets.mapTo(HashSet()) { it.config.entity })
        jobs = widgets.map { widget ->
            scope.launch { repository.snapshot(widget.config.entity).collect(widget::show) }
        }
    }

    // a page that isn't visible stops costing anything, ha stops sending its entities
    fun stop() {
        if (jobs.isEmpty()) return
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        repository.setVisible(emptySet())
    }
}
