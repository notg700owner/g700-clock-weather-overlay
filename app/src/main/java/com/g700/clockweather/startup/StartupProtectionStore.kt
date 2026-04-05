package com.g700.clockweather.startup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object StartupProtectionStore {
    private const val PREFS = "clock_weather_startup_protection"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): StartupProtectionState {
        val prefs = prefs(context)
        return StartupProtectionState(
            phase = runCatching {
                StartupPhase.valueOf(prefs.getString("phase", StartupPhase.IDLE.name) ?: StartupPhase.IDLE.name)
            }.getOrDefault(StartupPhase.IDLE),
            consecutiveFailures = prefs.getInt("consecutiveFailures", 0),
            autoStartBlocked = prefs.getBoolean("autoStartBlocked", false),
            currentAttemptToken = prefs.getLong("currentAttemptToken", 0L),
            lastAttemptAt = prefs.getLong("lastAttemptAt", 0L).takeIf { it > 0L },
            lastHealthyAt = prefs.getLong("lastHealthyAt", 0L).takeIf { it > 0L },
            startupDeadlineAt = prefs.getLong("startupDeadlineAt", 0L).takeIf { it > 0L },
            lastFailureReason = prefs.getString("lastFailureReason", null)
        )
    }

    fun observe(context: Context): Flow<StartupProtectionState> = callbackFlow {
        val sharedPrefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(load(context)).isSuccess
        }
        trySend(load(context)).isSuccess
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun save(context: Context, state: StartupProtectionState) {
        prefs(context).edit().apply {
            putString("phase", state.phase.name)
            putInt("consecutiveFailures", state.consecutiveFailures)
            putBoolean("autoStartBlocked", state.autoStartBlocked)
            putLong("currentAttemptToken", state.currentAttemptToken)
            writeNullableLong(this, "lastAttemptAt", state.lastAttemptAt)
            writeNullableLong(this, "lastHealthyAt", state.lastHealthyAt)
            writeNullableLong(this, "startupDeadlineAt", state.startupDeadlineAt)
            if (state.lastFailureReason == null) remove("lastFailureReason") else putString("lastFailureReason", state.lastFailureReason)
            apply()
        }
    }

    private fun writeNullableLong(editor: SharedPreferences.Editor, key: String, value: Long?) {
        if (value == null) editor.remove(key) else editor.putLong(key, value)
    }
}
