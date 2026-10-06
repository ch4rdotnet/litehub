package com.chardidathing.litehub

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.Themes
import com.chardidathing.litehub.core.model.Density
import com.chardidathing.litehub.core.model.Placement
import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.core.model.ThemeSelection
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.PhotoWidget
import com.chardidathing.litehub.ui.widgets.WidgetCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

// one tile drawn the way the hub would draw it, for the web editor. the widget is made at the
// tile's real size, fed what the hub already knows, drawn at half size and thrown away
class TilePreview(private val app: LitehubApp) {

    // what the editor sends, its own copy of the page and theme, saved or not
    private class Request(
        val placement: Placement,
        val columns: Int,
        val rows: Int,
        val density: Density,
        val theme: ThemeSelection?,
        val themes: List<JsonObject>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): Request? = try {
        val o = json.parseToJsonElement(body).jsonObject
        Request(
            placement = json.decodeFromJsonElement(Placement.serializer(), o.getValue("placement")),
            columns = o.getValue("columns").jsonPrimitive.int,
            rows = o.getValue("rows").jsonPrimitive.int,
            density = if (o["density"]?.jsonPrimitive?.contentOrNull == "compact") Density.COMPACT else Density.COMFORTABLE,
            theme = o["theme"]?.let { json.decodeFromJsonElement(ThemeSelection.serializer(), it) },
            themes = (o["themes"] as? JsonArray)?.map { it.jsonObject }.orEmpty(),
        )
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: NoSuchElementException) {
        null
    }

    private val cache = LinkedHashMap<String, Pair<Long, ByteArray>>()
    private var resolved: Triple<String, Theme, ResolvedTheme>? = null

    // null when there's no dashboard on screen to measure against, or the request makes no sense
    suspend fun png(body: String, activity: MainActivity?): ByteArray? {
        val a = activity ?: return null
        synchronized(cache) {
            cache[body]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        }
        val request = parse(body) ?: return null
        if (request.columns < 1 || request.rows < 1) return null
        val (theme, drawn) = theme(request, a) ?: return null
        val bitmap = withContext(Dispatchers.Main) { draw(request, theme, drawn, a) } ?: return null
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
        bitmap.recycle()
        synchronized(cache) {
            cache[body] = System.currentTimeMillis() to png
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return png
    }

    // the dashboard's own theme, from the editor's copy of the themes, built off the main thread
    // and kept while it doesn't change. the screen's theme when that copy doesn't resolve
    private fun theme(request: Request, a: MainActivity): Pair<Theme, ResolvedTheme>? {
        val key = "${request.theme}${request.themes}"
        resolved?.takeIf { it.first == key }?.let { return it.second to it.third }
        val dark = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = try {
            request.theme?.let { Themes(Presets.all, request.themes).select(it, dark) }
        } catch (e: ConfigException) {
            null
        } ?: app.currentTheme ?: return null
        val built = ResolvedTheme(theme, a.resources.displayMetrics, app.fonts, DeviceTier.isLow(app))
        resolved = Triple(key, theme, built)
        return theme to built
    }

    private suspend fun draw(request: Request, model: Theme, base: ResolvedTheme, a: MainActivity): Bitmap? {
        val screen = a.window.decorView
        if (screen.width == 0 || screen.height == 0) return null
        val theme = if (request.density == Density.COMPACT) base.compact else base
        // the same sums PageView does, edges stay comfortable and gaps follow the density
        val edge = base.spacing.m
        val gap = theme.spacing.m
        val cellW = (screen.width - edge * 2 - gap * (request.columns - 1)) / request.columns
        val cellH = (screen.height - edge * 2 - gap * (request.rows - 1)) / request.rows
        val p = request.placement
        val w = (cellW * p.w + gap * (p.w - 1)).roundToInt()
        val h = (cellH * p.h + gap * (p.h - 1)).roundToInt()
        if (w <= 0 || h <= 0) return null
        val legend = DashboardLoader(app).legend(model)
        val widget = WidgetCatalog.create(a, theme, app.icons, legend, p)
        widget.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        widget.layout(0, 0, w, h)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            if (widget is PhotoWidget) {
                // one photo from the source, a slideshow isn't much use as a still
                val frame = app.photoFrame().getOrNull()
                val photo = frame?.let { withTimeoutOrNull(PHOTO_WAIT_MS) { it.next(w, h).getOrNull() } }
                if (photo != null) widget.show(photo) else widget.note("photos show here on the hub")
            } else {
                val binder = DashboardBinder(
                    app.ha, app.calendars, app.feeds, app.weather, { _, _ -> }, app.notifications,
                    app::photoFrame, { app.settings.screensaver.photoSeconds }, a.now, listOf(listOf(widget)), scope,
                )
                binder.preview(widget)
                // the first values arrive a dispatch or two after binding
                delay(SETTLE_MS)
            }
            val bitmap = Bitmap.createBitmap(w / SCALE, h / SCALE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(theme.colors.background)
            canvas.scale(1f / SCALE, 1f / SCALE)
            widget.draw(canvas)
            return bitmap
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        // half size, the editor's tiles are smaller than the hub's
        const val SCALE = 2
        const val PNG_QUALITY = 100
        const val SETTLE_MS = 60L
        const val PHOTO_WAIT_MS = 5_000L
        // an unchanged tile asked for again within this long is answered from the last drawing
        const val FRESH_MS = 20_000L
        const val CACHE_SIZE = 64
    }
}
