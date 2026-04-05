package com.g700.clockweather.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.g700.clockweather.startup.StartupProtectionManager

object OverlayServiceController {
    const val ACTION_START = "com.g700.clockweather.action.START"
    const val ACTION_REFRESH_SETTINGS = "com.g700.clockweather.action.REFRESH_SETTINGS"
    const val EXTRA_START_REASON = "start_reason"

    fun start(context: Context, reason: String = "manual") {
        startServiceCompat(
            context,
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_START_REASON, reason)
        )
    }

    fun stop(context: Context) {
        StartupProtectionManager.cancelPendingStartup(context, "Service stopped by user")
        context.stopService(Intent(context, OverlayService::class.java))
    }

    fun refreshSettings(context: Context) {
        startServiceCompat(
            context,
            Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH_SETTINGS)
        )
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
