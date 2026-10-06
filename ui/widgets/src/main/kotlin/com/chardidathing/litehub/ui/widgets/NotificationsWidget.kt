package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import com.chardidathing.litehub.core.model.HubNotification
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

@Serializable
data class NotificationsConfig(val title: String? = null)

// the notifications ha has sent, newest first. tap one to clear it, "clear all" for the lot.
// the same view fills the pull down shade
class NotificationsWidget(context: Context, theme: ResolvedTheme, private val config: NotificationsConfig) : WidgetView(context, theme) {

    var onRemove: ((id: String) -> Unit)? = null
    var onClear: (() -> Unit)? = null

    private class Row(val id: String, val rect: RectF = RectF(), val title: TextBlock = TextBlock(maxLines = 1), val message: TextBlock = TextBlock(maxLines = 2), val age: TextBlock = TextBlock(maxLines = 1))

    private var items: List<HubNotification> = emptyList()
    private var moment: Moment? = null
    private var rows: List<Row> = emptyList()
    private val heading = TextBlock(maxLines = 1)
    private val note = TextBlock(maxLines = 1)
    private val clearRect = RectF()
    private val clearText = TextBlock(maxLines = 1)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val divider = Paint().apply { color = theme.colors.background }
    private var pressed: Any? = null

    init {
        isClickable = true
    }

    fun show(items: List<HubNotification>, now: Moment) {
        if (items == this.items && now.nowMs / MINUTE_MS == (moment?.nowMs ?: 0) / MINUTE_MS) return
        this.items = items
        moment = now
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val w = content.width()
        heading.set(config.title ?: "notifications", theme.type.h6, theme.colors.onSurface, w.toInt())
        val label = "clear all"
        val cw = clearText.width(label, theme.type.button) + theme.spacing.m * 2
        clearRect.set(content.right - cw, content.top, content.right, content.top + theme.touchTarget)
        clearText.set(label, theme.type.button, theme.colors.onPrimary, cw.toInt(), Layout.Alignment.ALIGN_CENTER)
        if (items.isEmpty()) clearRect.setEmpty()
        note.set(if (items.isEmpty()) "no notifications" else "", theme.type.body2, theme.colors.onSurface, w.toInt())
        var y = content.top + theme.touchTarget + theme.spacing.s
        val now = moment
        val out = ArrayList<Row>()
        for (n in items) {
            val r = Row(n.id)
            val ageText = now?.age(n.timeMs).orEmpty()
            val ageWidth = r.age.width(ageText, theme.type.caption) + 1
            r.age.set(ageText, theme.type.caption, theme.colors.onSurface, ageWidth.toInt() + 1)
            r.title.set(n.title ?: n.message, theme.type.subtitle1, theme.colors.onSurface, (w - ageWidth - theme.spacing.s).toInt())
            r.message.set(if (n.title != null) n.message else "", theme.type.body2, theme.colors.onSurface, w.toInt())
            val h = r.title.height + (if (n.title != null) theme.spacing.xs + r.message.height else 0f) + theme.spacing.m
            if (y + h > content.bottom) break
            r.rect.set(content.left, y, content.right, y + h)
            out += r
            y += h
        }
        rows = out
    }

    override fun drawContent(canvas: Canvas) {
        heading.draw(canvas, content.left, content.top + (theme.touchTarget - heading.height) / 2)
        if (!clearRect.isEmpty) {
            canvas.drawRoundRect(clearRect, theme.radii.medium, theme.radii.medium, chip)
            clearText.draw(canvas, clearRect.left, clearRect.centerY() - clearText.height / 2f)
        }
        note.draw(canvas, content.left, content.top + theme.touchTarget + theme.spacing.s)
        for (r in rows) {
            canvas.drawRect(r.rect.left, r.rect.top, r.rect.right, r.rect.top + theme.spacing.xs / 2, divider)
            val top = r.rect.top + theme.spacing.s
            r.title.draw(canvas, r.rect.left, top)
            r.age.draw(canvas, r.rect.right - r.age.lineWidth, top)
            r.message.draw(canvas, r.rect.left, top + r.title.height + theme.spacing.xs)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit: Any? = if (clearRect.contains(e.x, e.y)) clearRect else rows.firstOrNull { it.rect.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit
            MotionEvent.ACTION_UP -> {
                if (hit != null && hit === pressed) when (hit) {
                    is Row -> onRemove?.invoke(hit.id)
                    else -> onClear?.invoke()
                }
                pressed = null
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return true
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
