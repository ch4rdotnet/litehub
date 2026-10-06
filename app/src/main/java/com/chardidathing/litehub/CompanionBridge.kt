package com.chardidathing.litehub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.chardidathing.litehub.core.model.CompanionRegistration
import com.chardidathing.litehub.source.ha.MobileApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.util.UUID

// this hub as a mobile_app device in ha: its sensors kept current, its notifications turned
// into commands or banners. everything is off until someone registers from the menu
class CompanionBridge(private val app: LitehubApp, private val scope: CoroutineScope, private val commands: Commands) {

    interface Commands {
        fun screen(on: Boolean)
        fun screensaver(on: Boolean)
        // 0 to 255, like ha's companion app
        fun brightness(level: Int)
        fun dashboard(id: String)
        // 1 based, as a person would count them
        fun page(number: Int)
        fun reload()
        fun speak(text: String)
        // chime is false when the automation asked for a quiet one, tag lets it be cleared later
        fun notify(title: String?, message: String, chime: Boolean, tag: String?)
    }

    data class State(
        val screenOn: Boolean = true,
        // null is the system's own brightness
        val brightness: Int? = null,
        val page: Int = 1,
        val dashboard: String = "",
        val lastInteraction: Long = System.currentTimeMillis(),
        // awake, screensaver, dimmed or blank
        val display: String = "awake",
    )

    private var state = State()
    private var reporting: Job? = null
    private var heartbeat: Job? = null
    private var lastInteractionReport = 0L

    val registration: CompanionRegistration? get() = app.settings.companion

    // two hubs called the same thing fight over one notify service, so the model goes in the name

    // the legacy notify service ha makes for this device, it's what automations call
    val notifyService: String? get() = registration?.name?.let { "notify.mobile_app_" + it.replace(Regex("[^a-z0-9]+"), "_").trim('_') }

    fun start() {
        val reg = registration ?: return
        app.ha.setPushChannel(reg.webhookId, rejected = { scope.launch { forget() } }, handler = ::onPush)
        // registering again is harmless, it's how sensors added in an update reach ha
        scope.launch { app.ha.mobileApp?.let { m -> sensors().forEach { s -> m.registerSensor(reg.webhookId, s) } } }
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                send()
                delay(HEARTBEAT_MS)
            }
        }
    }

    fun stop() {
        heartbeat?.cancel()
        heartbeat = null
    }

    suspend fun register(): Result<Unit> {
        val mobileApp = app.ha.mobileApp ?: return Result.failure(IllegalStateException("home assistant isn't set up"))
        val deviceId = registration?.deviceId ?: UUID.randomUUID().toString()
        val device = MobileApp.Device(deviceId, LitehubApp.DEVICE_NAME, BuildConfig.VERSION_NAME, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
        val webhook = mobileApp.register(device).getOrElse { return Result.failure(it) }
        for (sensor in sensors()) mobileApp.registerSensor(webhook, sensor).onFailure { return Result.failure(it) }
        withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(companion = CompanionRegistration(deviceId, webhook, LitehubApp.DEVICE_NAME))) }
        start()
        return Result.success(Unit)
    }

    // ha keeps the device until it's deleted there, this only stops talking to it
    suspend fun forget() {
        stop()
        app.ha.setPushChannel(null) {}
        withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(companion = null)) }
    }

    fun update(change: (State) -> State) {
        val next = change(state)
        if (next == state) return
        state = next
        reporting?.cancel()
        reporting = scope.launch {
            delay(DEBOUNCE_MS)
            send()
        }
    }

    // touches are many, ha hears about them at most once a minute
    fun interacted() {
        val now = System.currentTimeMillis()
        state = state.copy(lastInteraction = now)
        if (now - lastInteractionReport < INTERACTION_REPORT_MS) return
        lastInteractionReport = now
        scope.launch { send() }
    }

    private suspend fun send() {
        val reg = registration ?: return
        val mobileApp = app.ha.mobileApp ?: return
        mobileApp.update(reg.webhookId, states()).onFailure {
            // deleted in ha, stop pretending we're registered so the menu offers it again
            if (it is MobileApp.Gone) forget()
        }
    }

    private fun onPush(event: JsonObject) {
        val message = (event["message"] as? JsonPrimitive)?.contentOrNull ?: return
        val title = (event["title"] as? JsonPrimitive)?.contentOrNull
        val data = event["data"] as? JsonObject
        val command = data?.get("command") as? JsonPrimitive
        scope.launch {
            when (message) {
                "command_screen_on" -> commands.screen(true)
                "command_screen_off" -> commands.screen(false)
                "command_screensaver_on" -> commands.screensaver(true)
                "command_screensaver_off" -> commands.screensaver(false)
                "command_screen_brightness_level" -> command?.intOrNull?.let { commands.brightness(it.coerceIn(0, MAX_LEVEL)) }
                "command_dashboard" -> command?.contentOrNull?.let(commands::dashboard)
                "command_page" -> command?.intOrNull?.let(commands::page)
                "command_reload" -> commands.reload()
                "TTS" -> ((data?.get("tts_text") as? JsonPrimitive)?.contentOrNull ?: title)?.let(commands::speak)
                // ha companion's way of taking a notification back
                "clear_notification" -> (data?.get("tag") as? JsonPrimitive)?.contentOrNull?.let(app.notifications::removeTag)
                else -> commands.notify(
                    title,
                    message,
                    chime = (data?.get("chime") as? JsonPrimitive)?.contentOrNull != "false",
                    tag = (data?.get("tag") as? JsonPrimitive)?.contentOrNull,
                )
            }
        }
    }

    private fun sensors() = listOf(
        MobileApp.Sensor("screen", "screen", "binary_sensor", "mdi:monitor"),
        MobileApp.Sensor("brightness", "screen brightness", "sensor", "mdi:brightness-6", unit = "%"),
        MobileApp.Sensor("page", "page", "sensor", "mdi:book-open-page-variant"),
        MobileApp.Sensor("dashboard", "dashboard", "sensor", "mdi:view-dashboard"),
        MobileApp.Sensor("last_interaction", "last interaction", "sensor", "mdi:gesture-tap", deviceClass = "timestamp"),
        MobileApp.Sensor("app_version", "app version", "sensor", "mdi:package-variant", diagnostic = true),
        MobileApp.Sensor("display", "display", "sensor", "mdi:monitor-shimmer"),
    ) + if (battery() != null) listOf(
        MobileApp.Sensor("battery_level", "battery level", "sensor", "mdi:battery", deviceClass = "battery", unit = "%"),
        MobileApp.Sensor("charging", "charging", "binary_sensor", "mdi:battery-charging", deviceClass = "battery_charging"),
    ) else emptyList()

    private fun states(): List<MobileApp.State> {
        val s = state
        val out = mutableListOf(
            MobileApp.State("screen", "binary_sensor", "mdi:monitor", JsonPrimitive(s.screenOn)),
            // the system brightness setting is 0 to 255, report it as a percentage either way
            MobileApp.State("brightness", "sensor", "mdi:brightness-6", JsonPrimitive(((s.brightness ?: systemBrightness()) * PERCENT / MAX_LEVEL))),
            MobileApp.State("page", "sensor", "mdi:book-open-page-variant", JsonPrimitive(s.page)),
            MobileApp.State("dashboard", "sensor", "mdi:view-dashboard", JsonPrimitive(s.dashboard)),
            MobileApp.State("last_interaction", "sensor", "mdi:gesture-tap", JsonPrimitive(Instant.ofEpochMilli(s.lastInteraction).toString())),
            MobileApp.State("app_version", "sensor", "mdi:package-variant", JsonPrimitive(BuildConfig.VERSION_NAME)),
            MobileApp.State("display", "sensor", "mdi:monitor-shimmer", JsonPrimitive(s.display)),
        )
        battery()?.let { (level, charging) ->
            out += MobileApp.State("battery_level", "sensor", "mdi:battery", JsonPrimitive(level))
            out += MobileApp.State("charging", "binary_sensor", "mdi:battery-charging", JsonPrimitive(charging))
        }
        return out
    }

    private fun systemBrightness(): Int =
        android.provider.Settings.System.getInt(app.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, MAX_LEVEL)

    // level and charging, or null on a mains only panel with no battery
    private fun battery(): Pair<Int, Boolean>? {
        val intent: Intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return level * PERCENT / scale to charging
    }

    private companion object {
        const val MAX_LEVEL = 255
        const val PERCENT = 100
        const val DEBOUNCE_MS = 2_000L
        const val HEARTBEAT_MS = 15 * 60_000L
        const val INTERACTION_REPORT_MS = 60_000L
    }
}
