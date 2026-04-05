package com.g700.clockweather.startup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.g700.clockweather.service.OverlayService
import com.g700.clockweather.service.OverlayServiceController
import com.g700.clockweather.settings.SettingsStore
import com.g700.clockweather.core.AppLogger

object StartupProtectionManager {
    const val ACTION_DELAYED_START = "com.g700.clockweather.action.DELAYED_START"
    const val ACTION_STARTUP_TIMEOUT = "com.g700.clockweather.action.STARTUP_TIMEOUT"
    const val EXTRA_ATTEMPT_TOKEN = "attempt_token"

    private const val TAG = "StartupProtection"
    private const val DELAY_REQUEST_CODE = 1701
    private const val TIMEOUT_REQUEST_CODE = 1702
    private const val HEALTHY_START_TIMEOUT_MS = 45_000L
    private const val MAX_FAILURES = 3

    fun handleBootCompleted(context: Context) {
        val settings = SettingsStore.load(context)
        val state = StartupProtectionStore.load(context)
        if (!settings.service.enabled || !settings.service.autoStartOnBoot) {
            AppLogger.log(TAG, "Boot auto-start skipped because overlay or auto-start is disabled")
            return
        }
        if (state.autoStartBlocked) {
            AppLogger.log(TAG, "Boot auto-start skipped because startup protection is blocked")
            return
        }

        val now = System.currentTimeMillis()
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.BOOT_DELAY_PENDING,
                lastAttemptAt = now,
                startupDeadlineAt = null,
                currentAttemptToken = 0L,
                lastFailureReason = null
            )
        )
        scheduleAlarm(context, delayedStartIntent(context), now + (settings.service.bootDelaySeconds * 1_000L))
        AppLogger.log(TAG, "Boot auto-start scheduled after ${settings.service.bootDelaySeconds} seconds")
    }

    fun handlePackageReplaced(context: Context) {
        val settings = SettingsStore.load(context)
        val state = StartupProtectionStore.load(context)
        if (!settings.service.enabled || state.autoStartBlocked) {
            AppLogger.log(TAG, "Package replace start skipped because overlay is off or startup protection is blocked")
            return
        }
        AppLogger.log(TAG, "Restarting service after package replacement")
        OverlayServiceController.start(context, reason = "package_replaced")
    }

    fun handleDelayedStart(context: Context) {
        val settings = SettingsStore.load(context)
        val state = StartupProtectionStore.load(context)
        if (!settings.service.enabled || !settings.service.autoStartOnBoot || state.autoStartBlocked) {
            cancelPendingStartup(context, "Auto-start was disabled before the delay elapsed")
            return
        }

        val now = System.currentTimeMillis()
        val attemptToken = now
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.STARTING,
                currentAttemptToken = attemptToken,
                lastAttemptAt = now,
                startupDeadlineAt = now + HEALTHY_START_TIMEOUT_MS,
                lastFailureReason = null
            )
        )
        scheduleAlarm(context, timeoutIntent(context, attemptToken), now + HEALTHY_START_TIMEOUT_MS)
        AppLogger.log(TAG, "Starting service after boot delay with token=$attemptToken")
        OverlayServiceController.start(context, reason = "boot")
    }

    fun handleStartupTimeout(context: Context, attemptToken: Long) {
        val state = StartupProtectionStore.load(context)
        if (state.phase != StartupPhase.STARTING || state.currentAttemptToken != attemptToken) return

        val failures = state.consecutiveFailures + 1
        val blocked = failures >= MAX_FAILURES
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = if (blocked) StartupPhase.AUTO_START_BLOCKED else StartupPhase.IDLE,
                consecutiveFailures = failures,
                autoStartBlocked = blocked,
                currentAttemptToken = 0L,
                startupDeadlineAt = null,
                lastFailureReason = "Service did not reach a healthy state within 45 seconds."
            )
        )
        context.stopService(Intent(context, OverlayService::class.java))
        AppLogger.log(TAG, "Startup timeout recorded failures=$failures blocked=$blocked")
    }

    fun markHealthy(context: Context) {
        val state = StartupProtectionStore.load(context)
        if (state.phase != StartupPhase.STARTING) return
        cancelTimeout(context)
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.HEALTHY,
                consecutiveFailures = 0,
                autoStartBlocked = false,
                currentAttemptToken = 0L,
                startupDeadlineAt = null,
                lastHealthyAt = System.currentTimeMillis(),
                lastFailureReason = null
            )
        )
        AppLogger.log(TAG, "Startup marked healthy")
    }

    fun cancelPendingStartup(context: Context, reason: String) {
        cancelDelayedStart(context)
        cancelTimeout(context)
        val state = StartupProtectionStore.load(context)
        if (state.phase != StartupPhase.BOOT_DELAY_PENDING && state.phase != StartupPhase.STARTING) return
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.IDLE,
                currentAttemptToken = 0L,
                startupDeadlineAt = null,
                lastFailureReason = reason
            )
        )
        AppLogger.log(TAG, "Pending startup cancelled: $reason")
    }

    fun resetFailureCounter(context: Context) {
        cancelDelayedStart(context)
        cancelTimeout(context)
        val state = StartupProtectionStore.load(context)
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.IDLE,
                consecutiveFailures = 0,
                autoStartBlocked = false,
                currentAttemptToken = 0L,
                startupDeadlineAt = null,
                lastFailureReason = null
            )
        )
        AppLogger.log(TAG, "Startup protection counter reset")
    }

    fun reEnableAutoStart(context: Context) {
        val state = StartupProtectionStore.load(context)
        StartupProtectionStore.save(
            context,
            state.copy(
                phase = StartupPhase.IDLE,
                consecutiveFailures = 0,
                autoStartBlocked = false,
                currentAttemptToken = 0L,
                startupDeadlineAt = null,
                lastFailureReason = null
            )
        )
        AppLogger.log(TAG, "Auto-start re-enabled")
    }

    private fun scheduleAlarm(context: Context, pendingIntent: PendingIntent, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun cancelDelayedStart(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(delayedStartIntent(context))
    }

    private fun cancelTimeout(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(timeoutIntent(context, 0L))
    }

    private fun delayedStartIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java)
            .setAction(ACTION_DELAYED_START)
        return PendingIntent.getBroadcast(
            context,
            DELAY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun timeoutIntent(context: Context, token: Long): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java)
            .setAction(ACTION_STARTUP_TIMEOUT)
            .putExtra(EXTRA_ATTEMPT_TOKEN, token)
        return PendingIntent.getBroadcast(
            context,
            TIMEOUT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
