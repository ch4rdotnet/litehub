package com.chardidathing.litehub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.SettingsForm
import com.chardidathing.litehub.core.model.Hex
import com.chardidathing.litehub.core.model.SettingsSection
import kotlinx.serialization.json.JsonObject
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.core.model.WidgetSchema
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.widgets.WidgetSchemas
import com.chardidathing.litehub.webui.HubAccess
import com.chardidathing.litehub.webui.WebServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// the hub's side of the web editor and status page. runs for the life of the process so the
// status page answers while the dashboard is behind another app, the activity plugs in when up
class WebHost(private val app: LitehubApp) : HubAccess {

    private var server: WebServer? = null
    private var port = 0
    private var activity = WeakReference<MainActivity>(null)
    private val main = Handler(Looper.getMainLooper())

    fun attach(a: MainActivity) {
        activity = WeakReference(a)
    }

    // on when either part is wanted, restarted when the port changes
    @Synchronized
    fun apply() {
        val web = app.settings.web
        val wanted = web.editor || web.status
        if (!wanted || port != web.port) {
            server?.stop()
            server = null
        }
        if (wanted && server == null) {
            port = web.port
            val next = WebServer(web.port, this, app.assets)
            next.start().fold(
                {
                    server = next
                    AppLog.add("web server on ${address() ?: "this device"}")
                },
                { AppLog.add("web server couldn't start, ${it.message}") },
            )
        }
    }

    // what to type into a browser on the same network
    fun address(): String? {
        val ip = Lan.address() ?: return null
        return "http://${ip.hostAddress}:${app.settings.web.port}"
    }

    override val editorEnabled get() = app.settings.web.editor
    override val statusEnabled get() = app.settings.web.status
    override val pinHash get() = app.settings.pin

    override fun config(): String {
        val file = File(app.filesDir, LitehubApp.CONFIG_FILE)
        return if (file.exists()) file.readText() else app.assets.open(DEFAULT_CONFIG).bufferedReader().use { it.readText() }
    }

    override fun saveConfig(text: String): Result<Unit> = validated {
        ConfigCodec.decode(text)
        val file = File(app.filesDir, LitehubApp.CONFIG_FILE)
        file.writeAtomic(text)
        AppLog.add("config saved from the web editor")
        onActivity { it.load() }
    }

    override fun sources(): String {
        val file = File(app.filesDir, LitehubApp.SOURCES_FILE)
        return if (file.exists()) file.readText() else """{ "version": ${SourcesCodec.VERSION}, "calendars": [], "feeds": [] }"""
    }

    override fun settings(): String = buildJsonObject {
        put("sections", Json.encodeToJsonElement(ListSerializer(SettingsSection.serializer()), SettingsForm.sections))
        put("values", SettingsForm.values(app.hubSettings()))
        // what "add a calendar" starts from, so the defaults live in one place
        putJsonObject("newItems") { SettingsForm.sections.filter { it.items != null }.forEach { put(it.id, SettingsForm.newItem(it.id)) } }
        // the swatches a calendar colour offers, the same ones the device shows
        putJsonArray("palette") { (app.currentTheme ?: Presets.fallbackDark).palette.distinct().forEach { add(JsonPrimitive(Hex.of(it))) } }
    }.toString()

    override fun saveSettings(text: String): Result<Unit> = validated {
        val edits = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ConfigException("expected a json object of settings")
        val before = app.hubSettings()
        val reload = app.saveAll(before, SettingsForm.apply(before, edits))
        AppLog.add("settings saved from the web editor")
        if (reload) onActivity { it.reload() }
    }

    override suspend fun settingsAction(id: String): Result<String> = app.settingsAction(id).map { it.toString() }

    override fun schemas(): String = Json.encodeToString(ListSerializer(WidgetSchema.serializer()), WidgetSchemas.all)

    override suspend fun entities(): Result<String> = app.ha.catalogue().map { list ->
        Json.encodeToString(ListSerializer(com.chardidathing.litehub.core.model.EntityChoice.serializer()), list)
    }

    // drawn in software at half size, the hub's own screen isn't touched
    override fun preview(): ByteArray? {
        val a = activity.get() ?: return null
        var png: ByteArray? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                val view = a.window.decorView
                if (view.width > 0) {
                    val bitmap = Bitmap.createBitmap(view.width / PREVIEW_SCALE, view.height / PREVIEW_SCALE, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bitmap)
                    canvas.scale(1f / PREVIEW_SCALE, 1f / PREVIEW_SCALE)
                    view.draw(canvas)
                    png = ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                        out.toByteArray()
                    }
                    bitmap.recycle()
                }
            } finally {
                done.countDown()
            }
        }
        done.await(PREVIEW_WAIT_MS, TimeUnit.MILLISECONDS)
        return png
    }

    override suspend fun status(): String = app.status.snapshot().toString()

    override fun themeCss(): String {
        val t: Theme = app.currentTheme ?: Presets.fallbackDark
        val c = t.colors
        val metrics = app.resources.displayMetrics
        fun hex(argb: Int) = Hex.of(argb)
        return buildString {
            append(":root {\n")
            append("  --background: ${hex(c.background)};\n  --surface: ${hex(c.surface)};\n")
            append("  --primary: ${hex(c.primary)};\n  --on-primary: ${hex(c.onPrimary)};\n")
            append("  --on-surface: ${hex(c.onSurface)};\n  --on-background: ${hex(c.onBackground)};\n  --error: ${hex(c.error)};\n")
            append("  --radius: ${t.radii.medium}px;\n  --radius-small: ${t.radii.small}px;\n  --touch: ${t.touchTarget}px;\n")
            // tiles the editor pushes aside glide there over the theme's own slide time
            append("  --slide: ${if (t.motion.animations) t.motion.slideMs else 0}ms;\n")
            val scrim = Presets.SCRIM
            append("  --scrim: rgb(${scrim shr 16 and BYTE} ${scrim shr 8 and BYTE} ${scrim and BYTE} / ${(scrim ushr 24) * PERCENT / BYTE}%);\n")
            append("  --space-xs: ${t.spacing.xs}px;\n  --space-s: ${t.spacing.s}px;\n  --space-m: ${t.spacing.m}px;\n")
            append("  --space-l: ${t.spacing.l}px;\n  --space-xl: ${t.spacing.xl}px;\n")
            append("  --h5: ${t.type.h5.size}px;\n  --h6: ${t.type.h6.size}px;\n  --body1: ${t.type.body1.size}px;\n  --body2: ${t.type.body2.size}px;\n")
            append("  --screen-aspect: ${metrics.widthPixels} / ${metrics.heightPixels};\n")
            append("}\n")
        }
    }

    private fun validated(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: ConfigException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(IOException(e.message ?: "couldn't write it", e))
    }

    private fun onActivity(block: (MainActivity) -> Unit) {
        val a = activity.get() ?: return
        main.post { block(a) }
    }

    private companion object {
        const val DEFAULT_CONFIG = "default_config.json"
        const val PREVIEW_SCALE = 2
        const val PREVIEW_WAIT_MS = 2_000L
        const val PNG_QUALITY = 100
        const val BYTE = 0xFF
        const val PERCENT = 100
    }
}
