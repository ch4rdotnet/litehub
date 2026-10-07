package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.ui.components.ColorKey
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import kotlin.math.min

// this month as a grid, today circled, a dot per calendar with something on that day
class MonthWidget(context: Context, theme: ResolvedTheme, config: MonthConfig, private val legend: Legend) :
    WidgetView(context, theme), CalendarWidget {

    private data class Model(
        val first: LocalDate,
        val today: LocalDate,
        val label: String,
        val weekStart: DayOfWeek,
        val weekdays: List<String>,
        val dots: Map<LocalDate, List<Int>>,
    )

    private val sources = config.calendars.ifEmpty { legend.calendars }
    private var model: Model? = null

    private val keyEntries = if (config.key) CalendarWidget.key(sources, legend, theme.colors.primary) else emptyList()
    private val key = ColorKey(theme)

    private val header = TextBlock(maxLines = 1)
    private val weekdays = Array(DAYS) { TextBlock(maxLines = 1) }
    private val numbers = Array(CELLS) { TextBlock(maxLines = 1) }
    private val numberX = FloatArray(CELLS)
    private val numberY = FloatArray(CELLS)
    private val inMonth = BooleanArray(CELLS)
    private var todayCell = -1
    private var todayX = 0f
    private var todayY = 0f
    private var todayRadius = 0f
    private var weekdayTop = 0f
    private var cellWidth = 0f
    // flattened x, y, colour for every dot
    private var dotX = FloatArray(0)
    private var dotY = FloatArray(0)
    private var dotColor = IntArray(0)

    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun show(snapshot: CalendarSnapshot, now: Moment) {
        val today = now.today
        val first = today.withDayOfMonth(1)
        val week = WeekFields.of(now.locale)
        val names = (0 until DAYS).map { week.firstDayOfWeek.plus(it.toLong()) }
            .map { it.getDisplayName(TextStyle.NARROW, now.locale).lowercase(now.locale) }
        val dots = HashMap<LocalDate, MutableList<Int>>()
        val monthEnd = first.plusMonths(1)
        for (e in snapshot.events) {
            if (e.source !in sources) continue
            val color = legend.colors[e.source] ?: theme.colors.primary
            val start = if (e.allDay) LocalDate.ofEpochDay(e.startMs / DAY_MS) else Instant.ofEpochMilli(e.startMs).atZone(now.zone).toLocalDate()
            val end = if (e.allDay) LocalDate.ofEpochDay(e.endMs / DAY_MS) else start.plusDays(1)
            var d = maxOf(start, first)
            while (d.isBefore(end) && d.isBefore(monthEnd)) {
                val list = dots.getOrPut(d) { ArrayList() }
                if (color !in list) list += color
                d = d.plusDays(1)
            }
        }
        val next = Model(first, today, now.month(today), week.firstDayOfWeek, names, dots)
        // a failed calendar has nowhere else to say so in a grid this dense
        setBadge(ListWidget.notes(sources, snapshot.status, legend).firstOrNull { it.error }?.text)
        if (next == model) return
        model = next
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val m = model ?: return
        val w = content.width()
        header.set(m.label, theme.type.h6, theme.colors.onSurface, w.toInt())
        weekdayTop = header.height + theme.spacing.s
        cellWidth = w / DAYS
        val center = Layout.Alignment.ALIGN_CENTER
        m.weekdays.forEachIndexed { i, name -> weekdays[i].set(name, theme.type.caption, theme.colors.onSurface, cellWidth.toInt()) }
        val gridTop = weekdayTop + weekdays[0].height + theme.spacing.xs
        key.set(keyEntries, w.toInt())
        val keySpace = if (keyEntries.isEmpty()) 0f else key.height + theme.spacing.s
        val cellHeight = (content.height() - gridTop - keySpace) / WEEKS

        val lead = Math.floorMod(m.first.dayOfWeek.value - m.weekStart.value, DAYS)
        val daysInMonth = m.first.lengthOfMonth()
        val dx = ArrayList<Float>()
        val dy = ArrayList<Float>()
        val dc = ArrayList<Int>()
        // dots are spacing.xs in radius with a dot's width between them
        val dot = theme.spacing.xs
        todayCell = -1
        for (cell in 0 until CELLS) {
            val day = cell - lead + 1
            inMonth[cell] = day in 1..daysInMonth
            if (!inMonth[cell]) continue
            val date = m.first.withDayOfMonth(day)
            val isToday = date == m.today
            val color = if (isToday) theme.colors.onPrimary else theme.colors.onSurface
            numbers[cell].set(day.toString(), theme.type.body2, color, cellWidth.toInt(), center)
            val col = cell % DAYS
            val row = cell / DAYS
            numberX[cell] = col * cellWidth
            numberY[cell] = gridTop + row * cellHeight
            if (isToday) {
                todayCell = cell
                todayX = numberX[cell] + cellWidth / 2
                todayY = numberY[cell] + numbers[cell].height / 2f
                todayRadius = min(cellWidth / 2, numbers[cell].height / 2f + theme.spacing.xs)
            }
            val colors = m.dots[date].orEmpty().take(MAX_DOTS)
            val spread = colors.size * dot * 2 + (colors.size - 1) * dot
            var x = numberX[cell] + (cellWidth - spread) / 2 + dot
            val y = numberY[cell] + numbers[cell].height + theme.spacing.xs * 2 + dot
            for (c in colors) {
                dx += x
                dy += y
                dc += c
                x += dot * 3
            }
        }
        dotX = dx.toFloatArray()
        dotY = dy.toFloatArray()
        dotColor = dc.toIntArray()
    }

    override fun drawContent(canvas: Canvas) {
        if (model == null) return
        val left = content.left
        val top = content.top
        header.draw(canvas, left, top)
        for (i in 0 until DAYS) weekdays[i].draw(canvas, left + i * cellWidth + (cellWidth - weekdays[i].lineWidth) / 2, top + weekdayTop)
        if (todayCell >= 0) canvas.drawCircle(left + todayX, top + todayY, todayRadius, todayPaint)
        for (cell in 0 until CELLS) if (inMonth[cell]) numbers[cell].draw(canvas, left + numberX[cell], top + numberY[cell])
        for (i in dotX.indices) {
            dotPaint.color = dotColor[i]
            canvas.drawCircle(left + dotX[i], top + dotY[i], theme.spacing.xs, dotPaint)
        }
        if (keyEntries.isNotEmpty()) key.draw(canvas, left, content.bottom - key.height)
    }

    private companion object {
        const val DAYS = 7
        const val WEEKS = 6
        const val CELLS = DAYS * WEEKS
        const val MAX_DOTS = 3
    }
}
