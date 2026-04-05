package com.g700.clockweather.overlay

import com.g700.clockweather.runtime.ServiceRuntimeBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class OverlayWeatherState(
    val conditionLabel: String? = null,
    val outsideTemperatureC: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sourceLabel: String = "Open-Meteo",
    val fetchedAt: Long = System.currentTimeMillis()
)

interface OverlayWeatherProvider {
    val weather: Flow<OverlayWeatherState?>
}

class RuntimeWeatherProvider : OverlayWeatherProvider {
    override val weather: Flow<OverlayWeatherState?> = ServiceRuntimeBus.state
        .map { runtime ->
            runtime.weatherState ?: runtime.vehicleState.outdoorTemp?.let { outsideTemperature ->
                OverlayWeatherState(
                    outsideTemperatureC = outsideTemperature,
                    sourceLabel = "Vehicle API"
                )
            }
        }
        .distinctUntilChanged()
}
