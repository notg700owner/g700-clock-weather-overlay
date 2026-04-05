package com.g700.clockweather.models

data class OverlaySettings(
    val calibrationMode: Boolean = false,
    val clockEnabled: Boolean = true,
    val weatherEnabled: Boolean = true,
    val internetWeatherEnabled: Boolean = false,
    val clockOffsetXDp: Int = 1376,
    val clockOffsetYDp: Int = -52,
    val weatherOffsetXDp: Int = 1420,
    val weatherOffsetYDp: Int = 62,
    val clockFontSizeSp: Int = 34,
    val weatherFontSizeSp: Int = 18
) {
    fun shouldRenderAnything(): Boolean = calibrationMode || clockEnabled || weatherEnabled
}

data class ServiceSettings(
    val enabled: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val bootDelaySeconds: Int = 10
)

data class AppSettings(
    val service: ServiceSettings = ServiceSettings(),
    val overlay: OverlaySettings = OverlaySettings()
) {
    fun normalized(): AppSettings {
        return copy(service = service.copy(enabled = overlay.shouldRenderAnything()))
    }

    fun enabledOverlayLabels(): List<String> = buildList {
        if (overlay.clockEnabled) add("Clock")
        if (overlay.weatherEnabled) add("Weather")
        if (overlay.calibrationMode) add("Calibration")
    }
}
