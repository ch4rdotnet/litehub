package com.chardidathing.litehub

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// the last few hundred things worth knowing, for the status page. also goes to logcat
object AppLog {

    private const val KEEP = 200
    private const val TAG = "litehub"
    private val lines = ArrayDeque<String>()
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun add(message: String) {
        Log.i(TAG, message)
        lines.addLast("${stamp.format(LocalTime.now())} $message")
        while (lines.size > KEEP) lines.removeFirst()
    }

    @Synchronized
    fun recent(): List<String> = lines.toList()
}
