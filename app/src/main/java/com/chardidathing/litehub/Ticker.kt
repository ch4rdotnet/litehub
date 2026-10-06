package com.chardidathing.litehub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import com.chardidathing.litehub.ui.widgets.Moment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.util.Locale

// a new Moment every minute and whenever the clock, date, zone or 24 hour setting moves.
// rides the system's own minute tick, so there's no timer of ours waking the cpu
class Ticker(private val context: Context) {

    private val _now = MutableStateFlow(moment())
    val now: StateFlow<Moment> = _now
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _now.value = moment()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        registered = true
        _now.value = moment()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun moment() = Moment(System.currentTimeMillis(), ZoneId.systemDefault(), DateFormat.is24HourFormat(context), Locale.getDefault())
}
