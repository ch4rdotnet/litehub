package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import com.chardidathing.litehub.core.model.TodoItem
import com.chardidathing.litehub.core.model.TodoSnapshot
import com.chardidathing.litehub.ui.components.IconBlock
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

// quick is comma separated, each one becomes a chip that adds it in one tap
@Serializable
data class TodoConfig(val entity: String, val title: String? = null, val quick: String = "")

// a ha todo list. tap an item to tick it off, chips along the bottom add things, "type" asks
// the host for the keyboard
class TodoWidget(context: Context, theme: ResolvedTheme, private val icons: Icons, val config: TodoConfig) : WidgetView(context, theme) {

    var onToggle: ((uid: String, done: Boolean) -> Unit)? = null
    var onAdd: ((text: String) -> Unit)? = null
    var onType: (() -> Unit)? = null

    private class Row(val item: TodoItem, val rect: RectF = RectF(), val icon: IconBlock = IconBlock(), val text: TextBlock = TextBlock(maxLines = 1))

    private class Chip(val label: String, val action: () -> Unit, val rect: RectF = RectF(), val text: TextBlock = TextBlock(maxLines = 1))

    private var snapshot: TodoSnapshot = TodoSnapshot.Loading
    private var rows: List<Row> = emptyList()
    private val chips = (config.quick.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { q -> Chip(q, { onAdd?.invoke(q) }) } +
        Chip("type", { onType?.invoke() }))
    private val title = TextBlock(maxLines = 1)
    private val note = TextBlock(maxLines = 2)
    private val more = TextBlock(maxLines = 1)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private var pressed: Any? = null
    private var moreTop = 0f
    private var noteTop = 0f

    init {
        isClickable = true
    }

    fun show(snapshot: TodoSnapshot) {
        if (snapshot == this.snapshot) return
        this.snapshot = snapshot
        setBadge((snapshot as? TodoSnapshot.Ready)?.stale)
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val w = content.width()
        title.set(config.title ?: "list", theme.type.h6, theme.colors.onSurface, w.toInt())
        var y = content.top + title.height + theme.spacing.s
        // chips sit along the bottom, at touch target height
        val chipTop = content.bottom - theme.touchTarget
        var x = content.left
        for (c in chips) {
            val cw = c.text.width(c.label, theme.type.button) + theme.spacing.m * 2
            c.rect.set(x, chipTop, x + cw, content.bottom)
            c.text.set(c.label, theme.type.button, theme.colors.onPrimary, cw.toInt(), Layout.Alignment.ALIGN_CENTER)
            x += cw + theme.spacing.s
        }
        val s = snapshot
        val open = (s as? TodoSnapshot.Ready)?.items?.filter { !it.done }.orEmpty()
        val noteText = when (s) {
            TodoSnapshot.Loading -> "loading"
            is TodoSnapshot.Failed -> "couldn't load the list, ${s.reason}"
            is TodoSnapshot.Ready -> s.error?.let { "couldn't change the list, $it" } ?: if (open.isEmpty()) "nothing on the list" else null
        }
        val isError = s is TodoSnapshot.Failed || (s as? TodoSnapshot.Ready)?.error != null
        note.set(noteText.orEmpty(), theme.type.body2, if (isError) theme.colors.error else theme.colors.onSurface, w.toInt())
        val rowHeight = theme.touchTarget
        val bottom = chipTop - theme.spacing.s - if (noteText != null) note.height + theme.spacing.s else 0f
        val fit = ((bottom - y) / rowHeight).toInt().coerceAtLeast(0)
        val shown = if (open.size > fit) open.take((fit - 1).coerceAtLeast(0)) else open
        rows = shown.map { item ->
            Row(item).also { r ->
                r.rect.set(content.left, y, content.right, y + rowHeight)
                r.icon.set(icons.path(if (item.done) "checkbox-marked-circle-outline" else "checkbox-blank-circle-outline"), theme.iconSize, theme.colors.onSurface)
                r.text.set(item.summary, theme.type.body1, theme.colors.onSurface, (w - theme.iconSize - theme.spacing.m).toInt())
                y += rowHeight
            }
        }
        val hidden = open.size - shown.size
        more.set(if (hidden > 0) "$hidden more" else "", theme.type.caption, theme.colors.onSurface, w.toInt())
        moreTop = y
        noteTop = bottom + theme.spacing.s
    }

    override fun drawContent(canvas: Canvas) {
        title.draw(canvas, content.left, content.top)
        for (r in rows) {
            r.icon.draw(canvas, r.rect.left, r.rect.centerY() - theme.iconSize / 2)
            r.text.draw(canvas, r.rect.left + theme.iconSize + theme.spacing.m, r.rect.centerY() - r.text.height / 2f)
        }
        more.draw(canvas, content.left, moreTop)
        note.draw(canvas, content.left, noteTop)
        val radius = theme.radii.medium
        for (c in chips) {
            canvas.drawRoundRect(c.rect, radius, radius, chipPaint)
            c.text.draw(canvas, c.rect.left, c.rect.centerY() - c.text.height / 2f)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit: Any? = rows.firstOrNull { it.rect.contains(e.x, e.y) } ?: chips.firstOrNull { it.rect.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit
            MotionEvent.ACTION_UP -> {
                if (hit != null && hit === pressed) when (hit) {
                    is Row -> onToggle?.invoke(hit.item.uid, !hit.item.done)
                    is Chip -> hit.action()
                }
                pressed = null
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return true
    }
}
