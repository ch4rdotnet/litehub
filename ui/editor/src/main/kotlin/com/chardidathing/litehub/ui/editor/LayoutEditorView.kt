package com.chardidathing.litehub.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.chardidathing.litehub.core.model.Config
import com.chardidathing.litehub.core.model.Placement
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.WidgetSchemas
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

// the page as boxes on its grid. drag a box to move it, its corner to resize, tap it for its
// settings. everything snaps to cells and nothing may overlap. edits a copy of the config,
// nothing is saved until done
class LayoutEditorView(
    context: Context,
    private val theme: ResolvedTheme,
    initial: Config,
    startPage: Int,
    private val host: Host,
) : View(context) {

    interface Host {
        fun openSettings(page: Int, index: Int)
        fun addWidget(page: Int)
        fun done(config: Config)
        fun cancel()
    }

    var config: Config = initial
        private set
    var page = startPage.coerceIn(0, initial.pages().lastIndex)
        private set
    private val undo = ArrayDeque<Config>()

    private class Button(val label: String, val action: () -> Unit, val enabled: () -> Boolean) {
        val rect = RectF()
        val text = TextBlock(maxLines = 1)
    }

    private val buttons = listOf(
        Button("prev", { turn(-1) }, { page > 0 }),
        Button("next", { turn(1) }, { page < config.pages().lastIndex }),
        Button("add widget", { host.addWidget(page) }, { true }),
        Button("add page", ::addPage, { true }),
        Button("remove page", ::removePage, { config.pages().size > 1 }),
        Button("undo", ::undoLast, { undo.isNotEmpty() }),
        Button("cancel", { host.cancel() }, { true }),
        Button("done", { host.done(config) }, { true }),
    )

    private val pageLabel = TextBlock(maxLines = 1)
    private val boxes = ArrayList<RectF>()
    private val labels = ArrayList<TextBlock>()
    private val handles = ArrayList<RectF>()
    private val cell = RectF()
    private val preview = RectF()
    private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val pressedButton = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = theme.spacing.xs / 2
        color = theme.colors.primary
    }
    private val bad = Paint(outline).apply { color = theme.colors.error }
    private val grid = Paint(outline).apply { color = theme.colors.onBackground }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }

    private var gridTop = 0f
    private var cellW = 0f
    private var cellH = 0f
    private val gutter = theme.spacing.m

    private enum class Mode { NONE, BUTTON, TAP, MOVE, RESIZE }
    private var mode = Mode.NONE
    private var target = -1
    private var downX = 0f
    private var downY = 0f
    private var candidate: Placement? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setBackgroundColor(theme.colors.background)
    }

    // an edit from outside (settings sheet, add widget), undoable like a drag
    fun replace(next: Config) {
        undo.addLast(config)
        config = next
        relayout()
        invalidate()
    }

    fun widget(page: Int, index: Int): Placement? = config.pages().getOrNull(page)?.widgets?.getOrNull(index)

    // false when the page has no room left, not even for a single cell
    fun addWidget(page: Int, type: String): Boolean {
        val schema = WidgetSchemas.of(type) ?: return false
        val p = config.pages()[page]
        val (x, y) = p.freeSpot(schema.w, schema.h) ?: return false
        val w = if (p.fits(x, y, schema.w, schema.h)) schema.w else 1
        val h = if (p.fits(x, y, schema.w, schema.h)) schema.h else 1
        replace(config.withPage(page, p.copy(widgets = p.widgets + Placement(x, y, w, h, type))))
        return true
    }

    fun updateWidget(page: Int, index: Int, settings: JsonObject) {
        val p = config.pages()[page]
        val w = p.widgets.getOrNull(index) ?: return
        replace(config.withPage(page, p.withWidget(index, w.copy(config = settings))))
    }

    fun removeWidget(page: Int, index: Int) {
        val p = config.pages()[page]
        if (index !in p.widgets.indices) return
        replace(config.withPage(page, p.withWidget(index, null)))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        if (width == 0) return
        val bar = theme.touchTarget
        var x = width - gutter
        // buttons right aligned in the toolbar, page label on the left
        for (b in buttons.asReversed()) {
            if (!b.enabled()) {
                b.rect.setEmpty()
                continue
            }
            val bw = b.text.width(b.label, theme.type.button) + theme.spacing.m * 2
            b.rect.set(x - bw, gutter, x, gutter + bar)
            b.text.set(b.label, theme.type.button, theme.colors.onSurface, bw.toInt(), Layout.Alignment.ALIGN_CENTER)
            x -= bw + theme.spacing.s
        }
        val pages = config.pages()
        pageLabel.set("page ${page + 1} of ${pages.size}", theme.type.h6, theme.colors.onBackground, (x - gutter).toInt())
        gridTop = gutter + bar
        val p = pages[page]
        cellW = (width - gutter * (p.columns + 1)) / p.columns
        cellH = (height - gridTop - gutter * (p.rows + 1)) / p.rows
        boxes.clear()
        labels.clear()
        handles.clear()
        for (w in p.widgets) {
            val r = rectOf(w.x, w.y, w.w, w.h, RectF())
            boxes += r
            labels += TextBlock(maxLines = 3).apply {
                set(describe(w), theme.type.subtitle2, theme.colors.onSurface, (r.width() - theme.spacing.m * 2).toInt())
            }
            val hs = theme.touchTarget / 2
            handles += RectF(r.right - hs, r.bottom - hs, r.right, r.bottom)
        }
    }

    private fun describe(p: Placement): String {
        val name = WidgetSchemas.of(p.type)?.name ?: p.type
        val detail = listOf("title", "name", "entity").firstNotNullOfOrNull { (p.config[it] as? JsonPrimitive)?.contentOrNull }
        return if (detail != null) "$name\n$detail" else name
    }

    private fun rectOf(x: Int, y: Int, w: Int, h: Int, out: RectF): RectF {
        val left = gutter + x * (cellW + gutter)
        val top = gridTop + gutter + y * (cellH + gutter)
        return out.apply { set(left, top, left + cellW * w + gutter * (w - 1), top + cellH * h + gutter * (h - 1)) }
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        pageLabel.draw(canvas, gutter, gutter + (theme.touchTarget - pageLabel.height) / 2)
        for (b in buttons) {
            if (!b.enabled()) continue
            canvas.drawRoundRect(b.rect, r, r, if (mode == Mode.BUTTON && buttons[target] === b) pressedButton else button)
            b.text.draw(canvas, b.rect.left, b.rect.centerY() - b.text.height / 2f)
        }
        val p = config.pages()[page]
        for (y in 0 until p.rows) for (x in 0 until p.columns) canvas.drawRoundRect(rectOf(x, y, 1, 1, cell), r, r, grid)
        boxes.forEachIndexed { i, box ->
            canvas.drawRoundRect(box, r, r, surface)
            canvas.drawRoundRect(box, r, r, outline)
            labels[i].draw(canvas, box.left + theme.spacing.m, box.top + theme.spacing.m)
            canvas.drawRect(handles[i], handle)
        }
        candidate?.let { c ->
            rectOf(c.x, c.y, c.w, c.h, preview)
            canvas.drawRoundRect(preview, r, r, if (p.fits(c.x, c.y, c.w, c.h, target)) outline else bad)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP -> up(e)
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun down(e: MotionEvent) {
        downX = e.x
        downY = e.y
        val b = buttons.indexOfFirst { it.enabled() && it.rect.contains(e.x, e.y) }
        if (b >= 0) {
            mode = Mode.BUTTON
            target = b
            invalidate()
            return
        }
        val h = handles.indexOfFirst { it.contains(e.x, e.y) }
        if (h >= 0) {
            mode = Mode.RESIZE
            target = h
            return
        }
        target = boxes.indexOfFirst { it.contains(e.x, e.y) }
        mode = if (target >= 0) Mode.TAP else Mode.NONE
    }

    private fun move(e: MotionEvent) {
        val dx = e.x - downX
        val dy = e.y - downY
        if (mode == Mode.TAP && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) mode = Mode.MOVE
        val p = config.pages()[page].widgets.getOrNull(target) ?: return
        val cx = (dx / (cellW + gutter)).roundToInt()
        val cy = (dy / (cellH + gutter)).roundToInt()
        val page = config.pages()[page]
        candidate = when (mode) {
            Mode.MOVE -> p.copy(x = (p.x + cx).coerceIn(0, page.columns - p.w), y = (p.y + cy).coerceIn(0, page.rows - p.h))
            Mode.RESIZE -> p.copy(w = (p.w + cx).coerceIn(1, page.columns - p.x), h = (p.h + cy).coerceIn(1, page.rows - p.y))
            else -> return
        }
        invalidate()
    }

    private fun up(e: MotionEvent) {
        when (mode) {
            Mode.BUTTON -> if (buttons[target].rect.contains(e.x, e.y)) buttons[target].action()
            Mode.TAP -> host.openSettings(page, target)
            Mode.MOVE, Mode.RESIZE -> {
                val c = candidate
                val current = config.pages()[page]
                // an overlap or the edge just drops back where it was
                if (c != null && current.fits(c.x, c.y, c.w, c.h, target)) replace(config.withPage(page, current.withWidget(target, c)))
            }
            Mode.NONE -> Unit
        }
        reset()
    }

    private fun reset() {
        mode = Mode.NONE
        target = -1
        candidate = null
        invalidate()
    }

    private fun turn(by: Int) {
        page = (page + by).coerceIn(0, config.pages().lastIndex)
        relayout()
        invalidate()
    }

    private fun addPage() {
        val pages = config.pages()
        val like = pages[page]
        val id = generateSequence(pages.size + 1) { it + 1 }.map { "page$it" }.first { id -> pages.none { it.id == id } }
        replace(config.withPages(pages + like.copy(id = id, widgets = emptyList())))
        page = config.pages().lastIndex
        relayout()
        invalidate()
    }

    private fun removePage() {
        val pages = config.pages()
        if (pages.size < 2) return
        replace(config.withPages(pages.filterIndexed { i, _ -> i != page }))
        page = page.coerceAtMost(config.pages().lastIndex)
        relayout()
        invalidate()
    }

    private fun undoLast() {
        config = undo.removeLastOrNull() ?: return
        page = page.coerceAtMost(config.pages().lastIndex)
        relayout()
        invalidate()
    }
}
