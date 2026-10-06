package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import com.chardidathing.litehub.core.model.SourceStatus
import com.chardidathing.litehub.ui.components.RowList
import com.chardidathing.litehub.ui.components.RowList.Kind
import com.chardidathing.litehub.ui.components.RowList.Row
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// an optional title over a RowList, the shape of the agenda and headlines
abstract class ListWidget(context: Context, theme: ResolvedTheme, private val titleText: String?) : WidgetView(context, theme) {

    private val title = TextBlock(maxLines = 1)
    private val list = RowList(theme)
    private var rows: List<Row> = emptyList()

    protected fun setRows(next: List<Row>) {
        if (next == rows) return
        rows = next
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val w = content.width().toInt()
        title.set(titleText.orEmpty(), theme.type.h6, theme.colors.onSurface, w)
        list.set(rows, w, (content.height() - listTop()).toInt())
    }

    override fun drawContent(canvas: Canvas) {
        if (titleText != null) title.draw(canvas, content.left, content.top)
        list.draw(canvas, content.left, content.top + listTop())
    }

    private fun listTop() = if (titleText != null) title.height + theme.spacing.s else 0f

    companion object {
        // a failed source says so, one that's never answered says it's still loading
        fun notes(sources: List<String>, status: Map<String, SourceStatus>, legend: Legend): List<Row> = sources.mapNotNull { id ->
            val name = legend.names[id] ?: id
            val s = status[id]
            when {
                s?.error != null -> Row(Kind.NOTE, "couldn't fetch $name, ${s.error}", error = true)
                s?.lastGood == null -> Row(Kind.NOTE, "loading $name")
                else -> null
            }
        }

        // only claim there's nothing when every source actually answered
        fun allAnswered(sources: List<String>, status: Map<String, SourceStatus>) =
            sources.all { status[it]?.let { s -> s.error == null && s.lastGood != null } == true }
    }
}
