package com.g700.clockweather.core

import android.util.Log

object AppLogger {
    private const val TAG = "ClockWeather"

    fun log(scope: String, message: String, throwable: Throwable? = null) {
        Log.d(TAG, "$scope | $message", throwable)
    }
}
