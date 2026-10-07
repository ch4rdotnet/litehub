package com.chardidathing.litehub

import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.os.PowerManager
import com.chardidathing.litehub.core.config.SettingsCodec
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.core.model.NightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// decides what the screen should be doing: awake, the screensaver, dimmed for the night, or
// blank. lives with the process, not the activity, so a blank screen can still be woken by ha,
// the light sensor or the camera. the activity is only how it shows itself
class ScreensaverController(private val app: LitehubApp, private val scope: CoroutineScope) {

    enum class Mode(val label: String) { AWAKE("awake"), SCREENSAVER("screensaver"), DIMMED("dimmed"), BLANK("blank") }

    // the activity, when there is one
    interface Display {
        fun showScreensaver()
        fun hideScreensaver()
        // percent, null back to normal
        fun dim(percent: Int?)
        fun overlayBlank(on: Boolean)
    }

    var display: Display? = null
        set(value) {
            field = value
            apply(mode.value, force = true)
        }

    private val _mode = MutableStateFlow(Mode.AWAKE)
    val mode: StateFlow<Mode> = _mode

    private var lastInteraction = System.currentTimeMillis()
    // ha or the menu asked for a mode, it holds until something wakes the screen
    private var forced: Mode? = null
    private var ticking: Job? = null
    private var entityWatch: Job? = null
    private val light by lazy { LightWake(app) { wake("the light changed") } }
    private val camera by lazy { CameraMotion(app) { wake("motion") } }
    private var adminLocked = false
    private var held = false

    fun start() {
        if (ticking != null) return
        ticking = scope.launch {
            while (true) {
                evaluate()
                // the next minute boundary so night lines up with the clock, or sooner when the idle
                // time runs out first (a minute's idle otherwise took up to two)
                val now = System.currentTimeMillis()
                val minute = MINUTE_MS - now % MINUTE_MS
                val idleLeft = lastInteraction + app.settings.screensaver.idleMinutes * MINUTE_MS - now
                delay(if (idleLeft > 0) minOf(minute, idleLeft) else minute)
            }
        }
        watchEntities()
    }

    // settings changed, wake entities and everything else are read again
    fun reload() {
        watchEntities()
        evaluate()
        // the mode may be the same but what it looks like (the dim level) may not
        apply(_mode.value, force = true)
    }

    fun interacted() {
        lastInteraction = System.currentTimeMillis()
        if (mode.value != Mode.AWAKE) wake("touch")
    }

    // callers can be on any thread (the camera's is its own), the screen is only touched on main
    fun wake(reason: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { wake(reason) }
            return
        }
        forced = null
        lastInteraction = System.currentTimeMillis()
        if (mode.value == Mode.AWAKE) return
        AppLog.add("screen woken by $reason")
        set(Mode.AWAKE)
    }

    // a video playing keeps the screen awake, the idle clock restarts when it's done
    fun hold(on: Boolean) {
        if (on == held) return
        held = on
        if (on) wake("video") else {
            lastInteraction = System.currentTimeMillis()
            evaluate()
        }
    }

    fun force(m: Mode) {
        forced = m
        set(m)
    }

    fun evaluate() {
        val s = app.settings.screensaver
        val now = System.currentTimeMillis()
        val idle = now - lastInteraction >= s.idleMinutes * MINUTE_MS
        val night = s.night?.takeIf { inWindow(it.start, it.end) }
        val target = forced ?: when {
            held -> Mode.AWAKE
            idle && night != null -> if (night.mode == NightMode.BLANK) Mode.BLANK else Mode.DIMMED
            idle && s.enabled -> Mode.SCREENSAVER
            else -> Mode.AWAKE
        }
        set(target)
        scheduleNight()
    }

    private fun set(m: Mode) {
        if (m == _mode.value) return
        val before = _mode.value
        _mode.value = m
        if (before != Mode.AWAKE && m != Mode.AWAKE) AppLog.add("screen ${before.label} to ${m.label}")
        else if (m != Mode.AWAKE) AppLog.add("screen ${m.label}")
        apply(m, force = false)
        watchSensors(m)
        app.hubState = app.hubState.copy(screenOn = m != Mode.BLANK)
    }

    private fun apply(m: Mode, force: Boolean) {
        val d = display
        val night = app.settings.screensaver.night
        if (m != Mode.SCREENSAVER) d?.hideScreensaver()
        if (m != Mode.DIMMED && m != Mode.SCREENSAVER) d?.dim(null)
        if (m != Mode.BLANK) {
            d?.overlayBlank(false)
            if (adminLocked) turnPanelOn()
        }
        when (m) {
            Mode.AWAKE -> Unit
            Mode.SCREENSAVER -> {
                d?.showScreensaver()
                val s = app.settings.screensaver
                d?.dim(if (s.dimWhileShowing) s.showingDimPercent else null)
            }
            Mode.DIMMED -> d?.dim(night?.dimPercent ?: DEFAULT_DIM)
            Mode.BLANK -> if (ScreenAdmin.active(app)) {
                // a real panel off, android takes the screen away from us here
                if (!force) {
                    adminLocked = true
                    app.getSystemService(DevicePolicyManager::class.java).lockNow()
                }
            } else {
                d?.overlayBlank(true)
            }
        }
    }

    @Suppress("DEPRECATION") // the only way to turn the panel back on from outside an activity
    private fun turnPanelOn() {
        adminLocked = false
        val power = app.getSystemService(PowerManager::class.java)
        power.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "litehub:wake")
            .acquire(WAKE_HOLD_MS)
    }

    private fun watchSensors(m: Mode) {
        val s = app.settings.screensaver
        val sleeping = m != Mode.AWAKE
        light.ratio = s.lightWakeRatio
        light.minLux = s.lightWakeLux.toFloat()
        camera.changedPercent = s.cameraWakePercent
        if (sleeping && s.lightWake && light.available) light.start() else light.stop()
        val cameraOk = s.cameraWake && !DeviceTier.isLow(app) && camera.available &&
            app.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (sleeping && cameraOk) camera.start() else camera.stop()
    }

    // a pir or door going to on (or open, detected) wakes the screen
    private fun watchEntities() {
        entityWatch?.cancel()
        val ids = app.settings.screensaver.wakeEntities.toSet()
        app.ha.setWatched(ids)
        if (ids.isEmpty()) return
        entityWatch = scope.launch {
            for (id in ids) launch {
                var last: String? = null
                app.ha.snapshot(id).collect { snap ->
                    val state = (snap as? EntitySnapshot.Live)?.entity?.state ?: return@collect
                    if (last != null && state != last && state in ON_STATES) wake(id)
                    last = state
                }
            }
        }
    }

    // the next time the night window opens or closes, so a sleeping cpu still gets there
    private fun scheduleNight() {
        val n = app.settings.screensaver.night ?: return NightAlarm.schedule(app, null)
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val times = listOfNotNull(SettingsCodec.time(n.start), SettingsCodec.time(n.end))
        val next = times.map { t -> now.toLocalDate().atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) } }.minOrNull()
        NightAlarm.schedule(app, next?.atZone(zone)?.toInstant()?.toEpochMilli())
    }

    private fun inWindow(start: String, end: String): Boolean {
        val s = SettingsCodec.time(start) ?: return false
        val e = SettingsCodec.time(end) ?: return false
        val now = LocalTime.now()
        return if (s <= e) now >= s && now < e else now >= s || now < e
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val WAKE_HOLD_MS = 3_000L
        const val DEFAULT_DIM = 10
        val ON_STATES = setOf("on", "open", "detected", "home", "unlocked")
    }
}
