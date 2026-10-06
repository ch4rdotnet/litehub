package com.chardidathing.litehub.ui.editor

import com.chardidathing.litehub.core.model.Config
import com.chardidathing.litehub.core.model.Page
import com.chardidathing.litehub.core.model.Placement

// small immutable edits on the active dashboard of a config
internal fun Config.pages(): List<Page> = dashboards.first { it.id == activeDashboard }.pages

internal fun Config.withPages(pages: List<Page>): Config =
    copy(dashboards = dashboards.map { if (it.id == activeDashboard) it.copy(pages = pages) else it })

internal fun Config.withPage(index: Int, page: Page): Config = withPages(pages().toMutableList().also { it[index] = page })

internal fun Page.withWidget(index: Int, placement: Placement?): Page =
    copy(widgets = widgets.toMutableList().also { if (placement == null) it.removeAt(index) else it[index] = placement })

internal fun Page.fits(x: Int, y: Int, w: Int, h: Int, ignore: Int = -1): Boolean {
    if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > columns || y + h > rows) return false
    return widgets.withIndex().none { (i, p) ->
        i != ignore && x < p.x + p.w && p.x < x + w && y < p.y + p.h && p.y < y + h
    }
}

// the first free spot, row by row, at the wanted size or failing that a single cell
internal fun Page.freeSpot(w: Int, h: Int): Pair<Int, Int>? {
    for (size in listOf(w to h, 1 to 1)) {
        for (y in 0 until rows) for (x in 0 until columns) {
            if (fits(x, y, size.first, size.second)) return x to y
        }
    }
    return null
}
