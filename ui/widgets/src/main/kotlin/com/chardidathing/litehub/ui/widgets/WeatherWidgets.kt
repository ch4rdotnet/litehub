package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import com.chardidathing.litehub.core.model.Forecast
import com.chardidathing.litehub.core.model.Weather
import com.chardidathing.litehub.core.model.WeatherSnapshot
import com.chardidathing.litehub.core.model.WeatherShows
import com.chardidathing.litehub.ui.components.IconBlock
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.ScreensaverView
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.roundToInt

// no entity means open-meteo for the location in sources.json
@Serializable
data class WeatherConfig(val entity: String? = null, val hours: Int = 12, val days: Int = 7)

interface WeatherWidget {
    val config: WeatherConfig

    fun show(snapshot: WeatherSnapshot, now: Moment)
}

// ha's condition names to mdi icons and to words
object Conditions {
    fun icon(condition: String): String = when (condition) {
        "sunny" -> "weather-sunny"
        "clear-night" -> "weather-night"
        "partlycloudy" -> "weather-partly-cloudy"
        "cloudy" -> "weather-cloudy"
        "fog" -> "weather-fog"
        "rainy" -> "weather-rainy"
        "pouring" -> "weather-pouring"
        "snowy" -> "weather-snowy"
        "snowy-rainy" -> "weather-snowy-rainy"
        "lightning" -> "weather-lightning"
        "lightning-rainy" -> "weather-lightning-rainy"
        "hail" -> "weather-hail"
        "windy" -> "weather-windy"
        "windy-variant" -> "weather-windy-variant"
        else -> "alert-circle-outline"
    }

    fun words(condition: String): String = when (condition) {
        "partlycloudy" -> "partly cloudy"
        "clear-night" -> "clear"
        "windy-variant" -> "windy"
        else -> condition.replace('-', ' ')
    }

    fun degrees(t: Double?): String = t?.let { "${it.roundToInt()}°" } ?: "–"
}

// shared by the three weather widgets: the snapshot, a failure message, the stale badge
abstract class BaseWeatherWidget(context: Context, theme: ResolvedTheme, protected val icons: Icons, final override val config: WeatherConfig) :
    WidgetView(context, theme), WeatherWidget {

    protected var weather: Weather? = null
    protected var moment: Moment? = null
    private val problem = TextBlock(maxLines = 3)
    private var problemText: String? = null

    override fun show(snapshot: WeatherSnapshot, now: Moment) {
        val next = (snapshot as? WeatherSnapshot.Ready)?.weather
        val text = when (snapshot) {
            WeatherSnapshot.Loading -> "loading the weather"
            is WeatherSnapshot.Failed -> "couldn't get the weather, ${snapshot.reason}"
            is WeatherSnapshot.Ready -> null
        }
        setBadge((snapshot as? WeatherSnapshot.Ready)?.stale)
        if (next == weather && text == problemText && now.today == moment?.today && now.nowMs / HOUR_MS == (moment?.nowMs ?: 0) / HOUR_MS) return
        weather = next
        problemText = text
        moment = now
        onContentChanged()
        invalidate()
    }

    final override fun onContentChanged() {
        val color = if (problemText?.startsWith("couldn't") == true) theme.colors.error else theme.colors.onSurface
        problem.set(problemText.orEmpty(), theme.type.body2, color, content.width().toInt())
        val w = weather
        val m = moment
        if (w != null && m != null) layoutWeather(w, m)
    }

    final override fun drawContent(canvas: Canvas) {
        if (weather == null || moment == null) {
            problem.draw(canvas, content.left, content.top)
            return
        }
        drawWeather(canvas)
    }

    protected abstract fun layoutWeather(w: Weather, now: Moment)

    protected abstract fun drawWeather(canvas: Canvas)

    protected fun upcoming(list: List<Forecast>, now: Moment) = list.filter { it.timeMs + HOUR_MS > now.nowMs }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}

// now: a big icon and temperature, the condition, then humidity, wind and today's range
class WeatherNowWidget(context: Context, theme: ResolvedTheme, icons: Icons, config: WeatherConfig) :
    BaseWeatherWidget(context, theme, icons, config) {

    private val icon = IconBlock()
    private val temp = TextBlock(maxLines = 1)
    private val condition = TextBlock(maxLines = 1)
    private val detail = TextBlock(maxLines = 2)
    private val big get() = theme.iconSize * 2

    private companion object {
        const val NBSP = "\u00A0"
        const val JOIN = "\u2060"
    }

    override fun layoutWeather(w: Weather, now: Moment) {
        val width = content.width().toInt()
        icon.set(icons.path(Conditions.icon(w.condition)), big, theme.colors.onSurface)
        temp.set(Conditions.degrees(w.temperature), theme.type.h2, theme.colors.onSurface, (width - big - theme.spacing.m).toInt())
        condition.set(Conditions.words(w.condition), theme.type.h6, theme.colors.onSurface, width)
        val today = w.daily.firstOrNull()
        val parts = listOfNotNull(
            today?.let { "${Conditions.degrees(it.temperature)} / ${Conditions.degrees(it.low)}" },
            w.humidity?.let { "humidity ${it.roundToInt()}%" },
            w.wind?.let { "wind $it" },
        )
        // each part stays whole when the line wraps, "17 km/h" never splits at its space or slash
        detail.set(parts.joinToString("  ·  ") { it.replace(" ", NBSP).replace("/", "$JOIN/$JOIN") }, theme.type.body2, theme.colors.onSurface, width)
    }

    override fun drawWeather(canvas: Canvas) {
        icon.draw(canvas, content.left, content.top)
        temp.draw(canvas, content.left + big + theme.spacing.m, content.top + (big - temp.height) / 2)
        val y = content.top + big + theme.spacing.s
        condition.draw(canvas, content.left, y)
        detail.draw(canvas, content.left, y + condition.height + theme.spacing.xs)
    }
}

// the next hours as columns, as many as fit across
class HourlyWidget(context: Context, theme: ResolvedTheme, icons: Icons, config: WeatherConfig) :
    BaseWeatherWidget(context, theme, icons, config) {

    private class Column(val time: TextBlock = TextBlock(maxLines = 1), val icon: IconBlock = IconBlock(), val temp: TextBlock = TextBlock(maxLines = 1))

    private var columns: List<Column> = emptyList()
    private var columnWidth = 0f

    override fun layoutWeather(w: Weather, now: Moment) {
        val hours = upcoming(w.hourly, now).take(config.hours)
        // a column needs room for its icon plus a gap, any more than fits is dropped
        val fit = (content.width() / (theme.iconSize + theme.spacing.l)).toInt().coerceAtLeast(1)
        val shown = hours.take(fit)
        columnWidth = if (shown.isEmpty()) 0f else content.width() / shown.size
        val center = Layout.Alignment.ALIGN_CENTER
        columns = shown.map { f ->
            Column().apply {
                time.set(now.hour(f.timeMs), theme.type.caption, theme.colors.onSurface, columnWidth.toInt(), center)
                icon.set(icons.path(Conditions.icon(f.condition)), theme.iconSize, theme.colors.onSurface)
                temp.set(Conditions.degrees(f.temperature), theme.type.body1, theme.colors.onSurface, columnWidth.toInt(), center)
            }
        }
    }

    override fun drawWeather(canvas: Canvas) {
        columns.forEachIndexed { i, c ->
            val x = content.left + i * columnWidth
            var y = content.top
            c.time.draw(canvas, x, y)
            y += c.time.height + theme.spacing.s
            c.icon.draw(canvas, x + (columnWidth - theme.iconSize) / 2, y)
            y += theme.iconSize + theme.spacing.s
            c.temp.draw(canvas, x, y)
        }
    }
}

// the coming days as rows: day, icon, high and low, chance of rain
class DailyWidget(context: Context, theme: ResolvedTheme, icons: Icons, config: WeatherConfig) :
    BaseWeatherWidget(context, theme, icons, config) {

    private class Row(val day: TextBlock = TextBlock(maxLines = 1), val icon: IconBlock = IconBlock(), val range: TextBlock = TextBlock(maxLines = 1), val rain: TextBlock = TextBlock(maxLines = 1))

    private var rows: List<Row> = emptyList()
    private var rowHeight = 0f
    private var dayWidth = 0f
    private var rainWidth = 0f
    private val measure = TextBlock()

    override fun layoutWeather(w: Weather, now: Moment) {
        rowHeight = theme.iconSize + theme.spacing.s
        val fit = (content.height() / rowHeight).toInt().coerceAtLeast(1)
        val days = w.daily.filter { Instant.ofEpochMilli(it.timeMs).atZone(now.zone).toLocalDate() >= now.today }.take(minOf(config.days, fit))
        // today, then short weekday names, columns as wide as their widest entry
        val labels = days.map { f ->
            val date = Instant.ofEpochMilli(f.timeMs).atZone(now.zone).toLocalDate()
            if (date == now.today) "today" else now.weekday(date)
        }
        val rains = days.map { f -> f.precipitationChance?.takeIf { it > 0 }?.let { "$it%" }.orEmpty() }
        dayWidth = labels.maxOfOrNull { measure.width(it, theme.type.body1) }?.plus(theme.spacing.s) ?: 0f
        rainWidth = rains.maxOfOrNull { measure.width(it, theme.type.body2) } ?: 0f
        val rangeWidth = (content.width() - dayWidth - theme.iconSize - theme.spacing.m * 2 - rainWidth).toInt()
        rows = days.mapIndexed { i, f ->
            Row().apply {
                day.set(labels[i], theme.type.body1, theme.colors.onSurface, dayWidth.toInt() + 1)
                icon.set(icons.path(Conditions.icon(f.condition)), theme.iconSize, theme.colors.onSurface)
                range.set("${Conditions.degrees(f.temperature)} / ${Conditions.degrees(f.low)}", theme.type.body1, theme.colors.onSurface, rangeWidth)
                rain.set(rains[i], theme.type.body2, theme.colors.onSurface, rainWidth.toInt() + 1, Layout.Alignment.ALIGN_OPPOSITE)
            }
        }
    }

    override fun drawWeather(canvas: Canvas) {
        rows.forEachIndexed { i, r ->
            val y = content.top + i * rowHeight
            val mid = y + theme.iconSize / 2
            r.day.draw(canvas, content.left, mid - r.day.height / 2f)
            val iconX = content.left + dayWidth + theme.spacing.m
            r.icon.draw(canvas, iconX, y)
            r.range.draw(canvas, iconX + theme.iconSize + theme.spacing.m, mid - r.range.height / 2f)
            r.rain.draw(canvas, content.right - rainWidth - 1, mid - r.rain.height / 2f)
        }
    }
}

// the screensaver's weather, worded the way the tiles word it. null while it's still loading
object SaverWeather {

    // past this the row reaches across into the clock's half
    private const val DAYS = 4

    fun of(snapshot: WeatherSnapshot, shows: WeatherShows, icons: Icons, now: Moment): ScreensaverView.Weather? = when (snapshot) {
        WeatherSnapshot.Loading -> null
        is WeatherSnapshot.Failed -> ScreensaverView.Weather(null, "", "couldn't get the weather, ${snapshot.reason}")
        is WeatherSnapshot.Ready -> {
            val w = snapshot.weather
            fun date(f: Forecast) = Instant.ofEpochMilli(f.timeMs).atZone(now.zone).toLocalDate()
            fun range(f: Forecast) = "${Conditions.degrees(f.temperature)} / ${Conditions.degrees(f.low)}"
            val today = w.daily.firstOrNull { date(it) == now.today }
            val detail = listOfNotNull(Conditions.words(w.condition), today?.takeIf { shows != WeatherShows.NOW }?.let(::range))
            val days = if (shows != WeatherShows.DAYS) emptyList() else w.daily.filter { date(it) > now.today }.take(DAYS).map { f ->
                ScreensaverView.Day(now.weekday(date(f)), icons.path(Conditions.icon(f.condition)), range(f))
            }
            ScreensaverView.Weather(icons.path(Conditions.icon(w.condition)), Conditions.degrees(w.temperature), detail.joinToString("  ·  "), days)
        }
    }
}
