package com.g700.automation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.g700.automation.models.AutomationSettings
import com.g700.automation.repository.VehicleRepository

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VehicleRepository(application.applicationContext)
    val uiState = repository.uiState

    init {
        repository.start()
    }

    fun refresh() = repository.refresh()
    fun saveSettings(settings: AutomationSettings) = repository.saveSettings(settings)
    fun setClockEnabled(enabled: Boolean) = repository.setClockEnabled(enabled)
    fun setOverlayWeatherEnabled(enabled: Boolean) = repository.setOverlayWeatherEnabled(enabled)
    fun setInternetWeatherEnabled(enabled: Boolean) = repository.setInternetWeatherEnabled(enabled)
    fun startOverlayCalibration() = repository.startOverlayCalibration()
    fun finishOverlayCalibration() = repository.finishOverlayCalibration()
    fun resetOverlayPosition() = repository.resetOverlayPosition()
    fun nudgeClock(deltaX: Int, deltaY: Int) = repository.nudgeClock(deltaX, deltaY)
    fun nudgeWeather(deltaX: Int, deltaY: Int) = repository.nudgeWeather(deltaX, deltaY)
    fun reEnableAutoStart() = repository.reEnableAutoStart()
    fun resetStartupProtection() = repository.resetStartupProtection()
    fun setAutoStartOnBoot(enabled: Boolean) = repository.setAutoStartOnBoot(enabled)
    fun updateSettings(transform: (AutomationSettings) -> AutomationSettings) = repository.updateSettings(transform)
    fun checkForUpdates(silent: Boolean = false) = repository.checkForUpdates(silent)
    fun installUpdate() = repository.installUpdate()
}
