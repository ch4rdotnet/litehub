package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.ui.components.IconBlock
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class EntitiesConfig(val entities: List<String> = emptyList(), val title: String? = null)

// several entities in one tile, a small cell each with its icon, name and state. the grid fits
// itself to the tile (a 2 by 1 holds 8 comfortably), tapping a cell toggles it when the binder
// allows and holding one opens its controls when it has any
class EntitiesWidget(context: Context, theme: ResolvedTheme, private val icons: Icons, val config: EntitiesConfig) : WidgetView(context, theme) {

    var canTap: (String) -> Boolean = { false }
    var onTap: ((String) -> Unit)? = null
    var canHold: (String) -> Boolean = { false }
    var onHold: ((String) -> Unit)? = null

    private class Cell(val id: String) {
        val rect = RectF()
        val icon = IconBlock()
        val name = TextBlock(maxLines = 1)
        val state = TextBlock(maxLines = 1)
    }

    private val cells = config.entities.map(::Cell)
    private val snapshots = HashMap<String, EntitySnapshot>()
    private val heading = TextBlock(maxLines = 1)
    private val note = TextBlock(maxLines = 2)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.background }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private var pressed: Cell? = null

    init {
        isClickable = true
    }

    fun show(id: String, snapshot: EntitySnapshot) {
        if (snapshots[id] == snapshot) return
        snapshots[id] = snapshot
        setBadge(snapshots.values.filterIsInstance<EntitySnapshot.Stale>().firstOrNull()?.reason)
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val w = content.width()
        heading.set(config.title.orEmpty(), theme.type.subtitle1, theme.colors.onSurface, w.toInt())
        note.set(if (cells.isEmpty()) "no entities picked" else "", theme.type.body2, theme.colors.onSurface, w.toInt())
        val top = content.top + if (config.title != null) heading.height + theme.spacing.s else 0f
        if (cells.isEmpty()) return
        val gap = theme.spacing.s
        val (columns, rows) = grid(cells.size, w, content.bottom - top, gap)
        val cw = (w - gap * (columns - 1)) / columns
        val ch = (content.bottom - top - gap * (rows - 1)) / rows
        cells.forEachIndexed { i, c ->
            val x = content.left + (i % columns) * (cw + gap)
            val y = top + (i / columns) * (ch + gap)
            c.rect.set(x, y, x + cw, y + ch)
            layoutCell(c)
        }
    }

    private fun layoutCell(c: Cell) {
        val inset = theme.spacing.s
        val snapshot = snapshots[c.id] ?: EntitySnapshot.Connecting
        val entity = entity(snapshot)
        val active = entity?.let(EntityStates::isActive) == true
        val iconColor = if (c === pressed) theme.colors.onPrimary else if (active) theme.colors.primary else theme.colors.onSurface
        val text = if (c === pressed) theme.colors.onPrimary else theme.colors.onSurface
        c.icon.set(icons.path(EntityIcons.name(c.id, entity, null, icons)), theme.iconSize, iconColor)
        val textWidth = (c.rect.width() - inset * 3 - theme.iconSize).toInt().coerceAtLeast(0)
        c.name.set(entity?.attribute("friendly_name") ?: c.id, theme.type.body2, text, textWidth)
        val problem = when (snapshot) {
            EntitySnapshot.Connecting -> "connecting"
            EntitySnapshot.NotFound -> "not found"
            is EntitySnapshot.Failed -> snapshot.reason
            is EntitySnapshot.Live -> snapshot.error
            is EntitySnapshot.Stale -> snapshot.error
        }
        val bad = snapshot is EntitySnapshot.NotFound || snapshot is EntitySnapshot.Failed || (problem != null && entity != null)
        c.state.set(problem ?: entity?.let(EntityStates::describe).orEmpty(), theme.type.subtitle2, if (bad && c !== pressed) theme.colors.error else text, textWidth)
    }

    private fun entity(s: EntitySnapshot): Entity? = when (s) {
        is EntitySnapshot.Live -> s.entity
        is EntitySnapshot.Stale -> s.entity
        else -> null
    }

    override fun drawContent(canvas: Canvas) {
        if (config.title != null) heading.draw(canvas, content.left, content.top)
        if (cells.isEmpty()) note.draw(canvas, content.left, content.top + heading.height + theme.spacing.s)
        val r = theme.radii.small
        for (c in cells) {
            canvas.drawRoundRect(c.rect, r, r, if (c === pressed) pressedPaint else cellPaint)
            val inset = theme.spacing.s
            c.icon.draw(canvas, c.rect.left + inset, c.rect.centerY() - theme.iconSize / 2)
            val textLeft = c.rect.left + inset * 2 + theme.iconSize
            val textTop = c.rect.centerY() - (c.name.height + c.state.height) / 2
            c.name.draw(canvas, textLeft, textTop)
            c.state.draw(canvas, textLeft, textTop + c.name.height)
        }
    }

    override fun hold(x: Float, y: Float): Boolean {
        val h = onHold ?: return false
        val cell = cells.firstOrNull { it.rect.contains(x, y) }?.takeIf { canHold(it.id) } ?: return false
        h(cell.id)
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = cells.firstOrNull { it.rect.contains(e.x, e.y) }?.takeIf { canTap(it.id) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(hit)
            MotionEvent.ACTION_MOVE -> if (hit !== pressed) press(null)
            MotionEvent.ACTION_UP -> {
                val tapped = pressed
                press(null)
                if (tapped != null && tapped === hit) onTap?.invoke(tapped.id)
            }
            MotionEvent.ACTION_CANCEL -> press(null)
        }
        return true
    }

    private fun press(c: Cell?) {
        if (c === pressed) return
        val before = pressed
        pressed = c
        before?.let(::layoutCell)
        c?.let(::layoutCell)
        invalidate()
    }

    private companion object {
        // cells aim to be this many times wider than tall, an icon beside two short lines
        const val CELL_ASPECT = 2.5f

        // the column count that keeps cells nearest the aspect they want while using the room
        fun grid(n: Int, w: Float, h: Float, gap: Float): Pair<Int, Int> {
            var best = 1 to n
            var bestScore = -1f
            for (columns in 1..n) {
                val rows = ceil(n / columns.toFloat()).toInt()
                val cw = (w - gap * (columns - 1)) / columns
                val ch = (h - gap * (rows - 1)) / rows
                val score = minOf(cw / CELL_ASPECT, ch)
                if (score > bestScore) {
                    bestScore = score
                    best = columns to rows
                }
            }
            return best
        }
    }
}
