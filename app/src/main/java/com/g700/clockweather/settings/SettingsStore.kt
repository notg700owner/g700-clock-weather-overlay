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
    private const val LEGACY_CLOCK_X = 1376
    private const val LEGACY_CLOCK_Y = -52
    private const val LEGACY_WEATHER_X = 1420
    private const val LEGACY_WEATHER_Y = 62

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val defaults = AppSettings().normalized()
        val storedClockX = p.getInt("clockOffsetXDp", defaults.overlay.clockOffsetXDp)
        val storedClockY = p.getInt("clockOffsetYDp", defaults.overlay.clockOffsetYDp)
        val storedWeatherX = p.getInt("weatherOffsetXDp", defaults.overlay.weatherOffsetXDp)
        val storedWeatherY = p.getInt("weatherOffsetYDp", defaults.overlay.weatherOffsetYDp)

        val migratedClockX = if (
            p.contains("clockOffsetXDp") &&
            p.contains("clockOffsetYDp") &&
            storedClockX == LEGACY_CLOCK_X &&
            storedClockY == LEGACY_CLOCK_Y
        ) {
            defaults.overlay.clockOffsetXDp
        } else {
            storedClockX
        }

        val migratedClockY = if (
            p.contains("clockOffsetXDp") &&
            p.contains("clockOffsetYDp") &&
            storedClockX == LEGACY_CLOCK_X &&
            storedClockY == LEGACY_CLOCK_Y
        ) {
            defaults.overlay.clockOffsetYDp
        } else {
            storedClockY
        }

        val migratedWeatherX = if (
            p.contains("weatherOffsetXDp") &&
            p.contains("weatherOffsetYDp") &&
            storedWeatherX == LEGACY_WEATHER_X &&
            storedWeatherY == LEGACY_WEATHER_Y
        ) {
            defaults.overlay.weatherOffsetXDp
        } else {
            storedWeatherX
        }

        val migratedWeatherY = if (
            p.contains("weatherOffsetXDp") &&
            p.contains("weatherOffsetYDp") &&
            storedWeatherX == LEGACY_WEATHER_X &&
            storedWeatherY == LEGACY_WEATHER_Y
        ) {
            defaults.overlay.weatherOffsetYDp
        } else {
            storedWeatherY
        }

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
                clockOffsetXDp = migratedClockX,
                clockOffsetYDp = migratedClockY,
                weatherOffsetXDp = migratedWeatherX,
                weatherOffsetYDp = migratedWeatherY,
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
