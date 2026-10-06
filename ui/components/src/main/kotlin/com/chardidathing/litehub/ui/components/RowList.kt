package com.chardidathing.litehub.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a vertical list of single line rows that fits what it can and says how many it left out.
// headers group items, items have an optional colour bar and a lead column (a time, an age),
// notes are small print and can be errors. laid out once per change, draw never allocates
class RowList(private val theme: ResolvedTheme) {

    enum class Kind { HEADER, ITEM, NOTE }

    data class Row(
        val kind: Kind,
        val text: String,
        val lead: String? = null,
        val accent: Int? = null,
        val error: Boolean = false,
    )

    private class Placed(
        val text: TextBlock,
        val textX: Float,
        val lead: TextBlock?,
        val leadX: Float,
        val y: Float,
        val bar: RectF?,
        val barColor: Int,
    )

    private val measurer = TextBlock()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rows: List<Row> = emptyList()
    private var width = 0
    private var height = 0
    private var placed: List<Placed> = emptyList()

    fun set(rows: List<Row>, width: Int, height: Int) {
        if (rows == this.rows && width == this.width && height == this.height) return
        this.rows = rows
        this.width = width
        this.height = height
        placed = layout()
    }

    fun draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        for (p in placed) {
            p.bar?.let {
                barPaint.color = p.barColor
                canvas.drawRect(it, barPaint)
            }
            p.lead?.draw(canvas, p.leadX, p.y)
            p.text.draw(canvas, p.textX, p.y)
        }
        canvas.restore()
    }

    private fun layout(): List<Placed> {
        if (width <= 0 || height <= 0) return emptyList()
        val (notes, body) = rows.partition { it.kind == Kind.NOTE }
        // the lead column is as wide as its widest entry, so times line up
        val leadColumn = body.mapNotNull { it.lead }.maxOfOrNull { measurer.width(it, theme.type.body2) } ?: 0f
        val barSpace = theme.spacing.xs + theme.spacing.s
        val gap = theme.spacing.xs

        // notes always fit, they're the failures and matter more than the last few items
        val noteBlocks = notes.map { block(it, width) }
        val notesHeight = noteBlocks.sumOf { (it.height + gap).toDouble() }.toFloat()
        val limit = height - notesHeight
        val moreHeight = measurer.lineHeight(theme.type.caption) + gap

        val out = ArrayList<Placed>()
        var y = 0f
        for ((i, row) in body.withIndex()) {
            val before = if (row.kind == Kind.HEADER && i > 0) theme.spacing.s else 0f
            val leadX = if (row.accent != null) barSpace else 0f
            val textX = leadX + if (row.lead != null) leadColumn + theme.spacing.s else 0f
            val text = block(row, (width - textX).toInt())
            val last = i == body.lastIndex
            if (y + before + text.height + (if (last) 0f else moreHeight) > limit) {
                val hidden = body.drop(i).count { it.kind == Kind.ITEM }
                if (hidden > 0) {
                    val more = TextBlock(maxLines = 1).apply { set("$hidden more", theme.type.caption, theme.colors.onSurface, width) }
                    out += Placed(more, 0f, null, 0f, y, null, 0)
                }
                break
            }
            y += before
            val lead = row.lead?.let {
                TextBlock(maxLines = 1).apply { set(it, theme.type.body2, theme.colors.onSurface, leadColumn.toInt() + 1) }
            }
            val bar = row.accent?.let { RectF(0f, y, theme.spacing.xs, y + text.height) }
            out += Placed(text, textX, lead, leadX, y, bar, row.accent ?: 0)
            y += text.height + gap
        }
        var ny = limit
        for (b in noteBlocks) {
            out += Placed(b, 0f, null, 0f, ny, null, 0)
            ny += b.height + gap
        }
        return out
    }

    private fun block(row: Row, width: Int): TextBlock {
        val style = when (row.kind) {
            Kind.HEADER -> theme.type.subtitle2
            Kind.ITEM -> theme.type.body1
            Kind.NOTE -> theme.type.caption
        }
        val color = if (row.error) theme.colors.error else theme.colors.onSurface
        return TextBlock(maxLines = 1).apply { set(row.text, style, color, width) }
    }
}
