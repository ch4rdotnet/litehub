package com.chardidathing.litehub

import android.app.ActivityManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.Display
import com.chardidathing.litehub.ui.editor.SettingsScreen.InfoRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.math.roundToInt

// how the hub is doing and what it's running on. one snapshot for the web status page and the
// device's own settings screen, so they say the same things
class HubStatus(private val app: LitehubApp) {

    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

    @Synchronized
    private fun cpuPercent(): Double {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        // cpu since the last time someone asked, the whole run the first time
        val cpu = if (nowWall > lastWallMs) (nowCpu - lastCpuMs) * PERCENT / (nowWall - lastWallMs).toDouble() else 0.0
        lastWallMs = nowWall
        lastCpuMs = nowCpu
        return cpu
    }

    suspend fun snapshot(): JsonObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val uptimeS = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / MS_PER_S
        val state = app.hubState
        val calendars = app.calendars.snapshot.value.status
        val feeds = app.feeds.snapshot.value.status
        val names = app.sources.getOrNull()
        val latency = app.ha.latencyMs()
        return buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("uptime_s", uptimeS)
            put("uptime", duration(uptimeS))
            put("active_dashboard", state.dashboard)
            put("page", state.page)
            put("screen_on", state.screenOn)
            put("memory_mb", tenths(memory.totalPss / KB_PER_MB.toDouble()))
            put("cpu_percent", tenths(cpuPercent()))
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
            put("device", device())
            putJsonArray("log") { AppLog.recent().forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun device(): JsonObject {
        val am = app.getSystemService(ActivityManager::class.java)
        val ram = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val sensors = app.getSystemService(SensorManager::class.java)
        val cameras = runCatching { app.getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("soc", soc)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull())
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
            put("android", Build.VERSION.RELEASE)
            put("api", Build.VERSION.SDK_INT)
            put("ram_mb", ram.totalMem / BYTES_PER_MB)
            put("ram_free_mb", ram.availMem / BYTES_PER_MB)
            put("low_ram_flag", am.isLowRamDevice)
            put("tier", if (DeviceTier.isLow(app)) "low" else "normal")
            put("screen", if (mode == null) null else "${mode.physicalWidth}x${mode.physicalHeight}")
            put("density_dpi", app.resources.displayMetrics.densityDpi)
            put("refresh_hz", mode?.refreshRate?.roundToInt())
            put("ip", Lan.address()?.hostAddress)
            put("storage_free_mb", app.filesDir.usableSpace / BYTES_PER_MB)
            put("light_sensor", sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null)
            put("cameras", cameras)
            put("device_uptime", duration(SystemClock.elapsedRealtime() / MS_PER_S))
        }
    }

    // the same snapshot as rows for the settings screen, in the status page's order. headings
    // have no value, bad rows are the ones the status page shows red
    fun rows(s: JsonObject): List<InfoRow> = buildList {
        fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        fun row(label: String, value: String?, bad: Boolean = false) = add(InfoRow(label, value ?: "unknown", bad))
        add(InfoRow("this hub"))
        row("version", text(s, "version"))
        row("up for", text(s, "uptime"))
        row("dashboard", text(s, "active_dashboard"))
        row("page", text(s, "page"))
        row("screen", if ((s["screen_on"] as? JsonPrimitive)?.booleanOrNull == false) "off" else "on")
        row("memory", "${text(s, "memory_mb")} mb")
        row("cpu", "${text(s, "cpu_percent")}%")
        val ha = s["home_assistant"] as? JsonObject ?: JsonObject(emptyMap())
        add(InfoRow("home assistant"))
        row("connection", text(ha, "state"), text(ha, "state") != "connected")
        row("latency", text(ha, "latency_ms")?.let { "$it ms" })
        row("companion device", text(ha, "companion") ?: "not registered")
        for ((key, heading) in listOf("calendars" to "calendars", "feeds" to "feeds")) {
            add(InfoRow(heading))
            val list = (s[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (list.isEmpty()) row("none", "")
            for (o in list) {
                val error = text(o, "error")
                val good = text(o, "last_good")
                row(text(o, "name").orEmpty(), if (error != null) "failing, $error" else if (good != null) "ok, $good" else "not fetched yet", error != null)
            }
        }
        val d = s["device"] as? JsonObject ?: JsonObject(emptyMap())
        add(InfoRow("device"))
        row("made by", text(d, "manufacturer"))
        row("model", "${text(d, "model")} (${text(d, "device")})")
        row("soc", text(d, "soc"))
        row("cpu", "${text(d, "cpu_cores")} cores, ${text(d, "abi")}")
        row("android", "${text(d, "android")} (api ${text(d, "api")})")
        row("ram", "${text(d, "ram_mb")} mb, ${text(d, "ram_free_mb")} mb free")
        row("tier", "${text(d, "tier")}${if ((d["low_ram_flag"] as? JsonPrimitive)?.booleanOrNull == true) ", firmware says low ram" else ""}")
        row("screen", "${text(d, "screen")} at ${text(d, "density_dpi")}dpi, ${text(d, "refresh_hz")}hz")
        row("ip address", text(d, "ip") ?: "no network")
        row("free storage", "${text(d, "storage_free_mb")} mb")
        row("light sensor", if ((d["light_sensor"] as? JsonPrimitive)?.booleanOrNull == true) "yes" else "none")
        row("cameras", text(d, "cameras"))
        row("device up for", text(d, "device_uptime"))
    }

    private fun duration(seconds: Long) = "${seconds / S_PER_H}h ${seconds % S_PER_H / S_PER_M}m"

    private fun tenths(n: Double) = (n * TENTHS).roundToInt() / TENTHS

    private companion object {
        const val PERCENT = 100
        const val TENTHS = 10.0
        const val KB_PER_MB = 1024
        const val BYTES_PER_MB = 1024L * 1024
        const val MS_PER_S = 1000L
        const val S_PER_M = 60
        const val S_PER_H = 3600
    }
}
