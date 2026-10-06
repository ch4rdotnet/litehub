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
        if (lux > baseline * JUMP_RATIO && lux - baseline > MIN_JUMP_LUX) {
            baseline = lux
            onWake()
            return
        }
        baseline += (lux - baseline) * SMOOTHING
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val JUMP_RATIO = 3f
        const val MIN_JUMP_LUX = 15f
        const val SMOOTHING = 0.1f
    }
}
