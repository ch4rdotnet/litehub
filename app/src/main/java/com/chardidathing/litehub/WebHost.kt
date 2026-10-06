package com.chardidathing.litehub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.SettingsCodec
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.core.model.WidgetSchema
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.widgets.WidgetSchemas
import com.chardidathing.litehub.webui.HubAccess
import com.chardidathing.litehub.webui.WebServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// the hub's side of the web editor and status page. runs for the life of the process so the
// status page answers while the dashboard is behind another app, the activity plugs in when up
class WebHost(private val app: LitehubApp) : HubAccess {

    private var server: WebServer? = null
    private var port = 0
    private var activity = WeakReference<MainActivity>(null)
    private val main = Handler(Looper.getMainLooper())
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

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
        val ip = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress } ?: return null
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
        if (file.exists()) file.copyTo(File(app.filesDir, LitehubApp.PREVIOUS_CONFIG_FILE), overwrite = true)
        file.writeAtomic(text)
        AppLog.add("config saved from the web editor")
        onActivity { it.load() }
    }

    override fun sources(): String {
        val file = File(app.filesDir, LitehubApp.SOURCES_FILE)
        return if (file.exists()) file.readText() else """{ "version": ${SourcesCodec.VERSION}, "calendars": [], "feeds": [] }"""
    }

    override fun saveSources(text: String): Result<Unit> = validated {
        SourcesCodec.decode(text)
        File(app.filesDir, LitehubApp.SOURCES_FILE).writeAtomic(text)
        AppLog.add("sources saved from the web editor")
        onActivity { it.reload() }
    }

    override fun ha(): String {
        val current = HaCredentials.load(File(app.filesDir, LitehubApp.HA_FILE)).getOrNull()
        return buildJsonObject {
            put("url", current?.url)
            put("tokenSet", current != null && current.token.isNotEmpty())
        }.toString()
    }

    override fun saveHa(url: String, token: String?): Result<Unit> = validated {
        val existing = HaCredentials.load(File(app.filesDir, LitehubApp.HA_FILE)).getOrNull()
        val next = HaCredentials(url.trim(), token ?: existing?.token ?: throw IOException("a token is needed the first time"))
        File(app.filesDir, LitehubApp.HA_FILE).writeAtomic(Json.encodeToString(HaCredentials.serializer(), next))
        AppLog.add("home assistant connection changed from the web editor")
        onActivity { it.reload() }
    }

    override fun screensaver(): String = SettingsCodec.encodeScreensaver(app.settings.screensaver)

    override fun saveScreensaver(text: String): Result<Unit> = validated {
        val next = SettingsCodec.decodeScreensaver(text)
        app.saveSettings(app.settings.copy(screensaver = next))
        AppLog.add("screensaver settings saved from the web editor")
        main.post { app.screensaver.reload() }
    }

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

    override suspend fun status(): String {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        // cpu since the last time someone asked, the whole run the first time
        val cpu = if (nowWall > lastWallMs) (nowCpu - lastCpuMs) * PERCENT / (nowWall - lastWallMs).toDouble() else 0.0
        lastWallMs = nowWall
        lastCpuMs = nowCpu
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val uptimeS = (nowWall - Process.getStartElapsedRealtime()) / MS_PER_S
        val state = app.hubState
        val calendars = app.calendars.snapshot.value.status
        val feeds = app.feeds.snapshot.value.status
        val names = app.sources.getOrNull()
        val latency = app.ha.latencyMs()
        return buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("uptime_s", uptimeS)
            put("uptime", "${uptimeS / S_PER_H}h ${uptimeS % S_PER_H / S_PER_M}m")
            put("active_dashboard", state.dashboard)
            put("page", state.page)
            put("screen_on", state.screenOn)
            put("memory_mb", (memory.totalPss / KB_PER_MB.toDouble() * TENTHS).roundToInt() / TENTHS)
            put("cpu_percent", (cpu * TENTHS).roundToInt() / TENTHS)
            putJsonObject("home_assistant") {
                put("state", app.ha.statusText())
                put("latency_ms", latency)
                put("companion", app.settings.companion?.name)
            }
            putJsonArray("calendars") {
                for (c in names?.calendars.orEmpty()) addJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("last_good", calendars[c.id]?.lastGood?.let { Instant.ofEpochMilli(it).toString() })
                    put("error", calendars[c.id]?.error)
                }
            }
            putJsonArray("feeds") {
                for (f in names?.feeds.orEmpty()) addJsonObject {
                    put("id", f.id)
                    put("name", f.name)
                    put("last_good", feeds[f.id]?.lastGood?.let { Instant.ofEpochMilli(it).toString() })
                    put("error", feeds[f.id]?.error)
                }
            }
            putJsonArray("log") { AppLog.recent().forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    override fun themeCss(): String {
        val t: Theme = app.currentTheme ?: Presets.fallbackDark
        val c = t.colors
        val metrics = app.resources.displayMetrics
        fun hex(argb: Int) = "#%06x".format(argb and RGB)
        return buildString {
            append(":root {\n")
            append("  --background: ${hex(c.background)};\n  --surface: ${hex(c.surface)};\n")
            append("  --primary: ${hex(c.primary)};\n  --on-primary: ${hex(c.onPrimary)};\n")
            append("  --on-surface: ${hex(c.onSurface)};\n  --on-background: ${hex(c.onBackground)};\n  --error: ${hex(c.error)};\n")
            append("  --radius: ${t.radii.medium}px;\n  --touch: ${t.touchTarget}px;\n")
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
        const val RGB = 0xFFFFFF
        const val PERCENT = 100
        const val TENTHS = 10.0
        const val KB_PER_MB = 1024
        const val MS_PER_S = 1000L
        const val S_PER_M = 60
        const val S_PER_H = 3600
    }
}
