package com.chardidathing.litehub

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

// a sudden jump in room light (a lamp going on, curtains opening) wakes the screen. slow
// changes like dusk only move the baseline
class LightWake(context: Context, private val onWake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var baseline = -1f

    // a jump is this many times the baseline and at least minLux more, set from settings
    var ratio = 1f
    var minLux = 0f

    val available get() = light != null

    fun start() {
        val s = light ?: return
        baseline = -1f
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        if (baseline < 0) {
            baseline = lux
            return
        }
        if (lux > baseline * ratio && lux - baseline > minLux) {
            baseline = lux
            onWake()
            return
        }
        baseline += (lux - baseline) * SMOOTHING
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.1f
    }
}
