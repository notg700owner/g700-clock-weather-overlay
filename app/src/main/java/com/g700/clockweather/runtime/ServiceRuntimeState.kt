package com.g700.clockweather.runtime

import com.g700.clockweather.models.VehicleState
import com.g700.clockweather.overlay.OverlayWeatherState

enum class ServiceRuntimePhase {
    STOPPED,
    STARTING,
    RUNNING,
    DEGRADED
}

data class ServiceRuntimeState(
    val serviceRunning: Boolean = false,
    val phase: ServiceRuntimePhase = ServiceRuntimePhase.STOPPED,
    val overlayArmed: Boolean = false,
    val clockVisible: Boolean = false,
    val weatherVisible: Boolean = false,
    val overlayAttached: Boolean = false,
    val overlayDisplayId: Int? = null,
    val overlayDisplayName: String? = null,
    val weatherStatus: String = "Weather idle.",
    val weatherState: OverlayWeatherState? = null,
    val vehicleState: VehicleState = VehicleState(),
    val vehicleTemperatureDiagnostic: String = "Waiting for vehicle temperature.",
    val lastAction: String = "Waiting",
    val lastError: String? = null,
    val lastWeatherRefreshAt: Long? = null,
    val lastHealthyAt: Long? = null,
    val activeOverlays: List<String> = emptyList()
)
