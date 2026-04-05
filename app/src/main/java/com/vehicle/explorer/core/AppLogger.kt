package com.g700.automation.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "VehicleExplorer"
    private const val MAX_LINES = 400
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = mutableLogs

    @Synchronized
    fun log(scope: String, message: String, throwable: Throwable? = null) {
        val prefix = formatter.format(Date()) + " [" + scope + "] "
        val lines = mutableLogs.value.toMutableList()
        lines += prefix + message
        throwable?.let {
            lines += prefix + it.javaClass.name + ": " + (it.message ?: "")
            it.stackTrace.take(12).forEach { ste ->
                lines += prefix + "    at " + ste.toString()
            }
        }
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        mutableLogs.value = lines
        Log.d(TAG, "$scope | $message", throwable)
    }

    @Synchronized
    fun snapshot(): List<String> = mutableLogs.value.toList()

    @Synchronized
    fun clear() {
        mutableLogs.value = emptyList()
    }
}
