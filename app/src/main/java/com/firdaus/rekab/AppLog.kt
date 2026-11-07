package com.firdaus.rekab

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown on screen, so problems are visible without adb. */
object AppLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(msg: String) {
        Log.i("ReKab", msg)
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e("ReKab", msg, t)
        i("ERROR: $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    fun text(): String = lines.reversed().joinToString("\n")
}
