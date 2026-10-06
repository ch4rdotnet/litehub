package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.FeedSnapshot
import com.chardidathing.litehub.ui.components.RowList.Kind
import com.chardidathing.litehub.ui.components.RowList.Row
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// newest headlines across the chosen feeds, with how old each one is
class HeadlinesWidget(context: Context, theme: ResolvedTheme, config: HeadlinesConfig, private val legend: Legend) :
    ListWidget(context, theme, config.title), FeedWidget {

    private val sources = config.feeds.ifEmpty { legend.feeds }

    override fun show(snapshot: FeedSnapshot, now: Moment) {
        val items = snapshot.items.filter { it.source in sources }
        val rows = items.mapTo(ArrayList()) { Row(Kind.ITEM, it.title, it.publishedMs?.let(now::age)) }
        rows += notes(sources, snapshot.status, legend)
        if (items.isEmpty() && allAnswered(sources, snapshot.status)) rows += Row(Kind.NOTE, "no headlines")
        setRows(rows)
    }
}
