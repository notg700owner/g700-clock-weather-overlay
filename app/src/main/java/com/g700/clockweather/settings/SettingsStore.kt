package com.g700.clockweather.settings

import android.content.Context
import android.content.SharedPreferences
import com.g700.clockweather.models.AppSettings
import com.g700.clockweather.models.OverlaySettings
import com.g700.clockweather.models.ServiceSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object SettingsStore {
    private const val PREFS = "g700_clock_weather_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val defaults = AppSettings().normalized()
        return AppSettings(
            service = ServiceSettings(
                autoStartOnBoot = p.getBoolean("autoStartOnBoot", defaults.service.autoStartOnBoot),
                bootDelaySeconds = p.getInt("bootDelaySeconds", defaults.service.bootDelaySeconds)
            ),
            overlay = OverlaySettings(
                calibrationMode = p.getBoolean("overlayCalibrationMode", defaults.overlay.calibrationMode),
                clockEnabled = p.getBoolean("clockEnabled", defaults.overlay.clockEnabled),
                weatherEnabled = p.getBoolean("weatherEnabled", defaults.overlay.weatherEnabled),
                internetWeatherEnabled = p.getBoolean("internetWeatherEnabled", defaults.overlay.internetWeatherEnabled),
                clockOffsetXDp = p.getInt("clockOffsetXDp", defaults.overlay.clockOffsetXDp),
                clockOffsetYDp = p.getInt("clockOffsetYDp", defaults.overlay.clockOffsetYDp),
                weatherOffsetXDp = p.getInt("weatherOffsetXDp", defaults.overlay.weatherOffsetXDp),
                weatherOffsetYDp = p.getInt("weatherOffsetYDp", defaults.overlay.weatherOffsetYDp),
                clockFontSizeSp = p.getInt("clockFontSizeSp", defaults.overlay.clockFontSizeSp),
                weatherFontSizeSp = p.getInt("weatherFontSizeSp", defaults.overlay.weatherFontSizeSp)
            )
        ).normalized()
    }

    fun observe(context: Context): Flow<AppSettings> = callbackFlow {
        val sharedPrefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(load(context)).isSuccess
        }
        trySend(load(context)).isSuccess
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun save(context: Context, settings: AppSettings) {
        val normalized = settings.normalized()
        prefs(context).edit().apply {
            putBoolean("autoStartOnBoot", normalized.service.autoStartOnBoot)
            putInt("bootDelaySeconds", normalized.service.bootDelaySeconds)
            putBoolean("overlayCalibrationMode", normalized.overlay.calibrationMode)
            putBoolean("clockEnabled", normalized.overlay.clockEnabled)
            putBoolean("weatherEnabled", normalized.overlay.weatherEnabled)
            putBoolean("internetWeatherEnabled", normalized.overlay.internetWeatherEnabled)
            putInt("clockOffsetXDp", normalized.overlay.clockOffsetXDp)
            putInt("clockOffsetYDp", normalized.overlay.clockOffsetYDp)
            putInt("weatherOffsetXDp", normalized.overlay.weatherOffsetXDp)
            putInt("weatherOffsetYDp", normalized.overlay.weatherOffsetYDp)
            putInt("clockFontSizeSp", normalized.overlay.clockFontSizeSp)
            putInt("weatherFontSizeSp", normalized.overlay.weatherFontSizeSp)
            apply()
        }
    }
}
