package com.g700.automation.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.g700.automation.core.AppLogger
import com.g700.automation.models.AutomationSettings
import com.g700.automation.overlay.HdmiOverlayManager
import com.g700.automation.overlay.RuntimeWeatherProvider
import com.g700.automation.runtime.ServiceRuntimeBus
import com.g700.automation.runtime.ServiceRuntimePhase
import com.g700.automation.runtime.ServiceRuntimeState
import com.g700.automation.startup.StartupProtectionManager
import com.g700.automation.weather.LocationWeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutomationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null
    private var weatherJob: Job? = null
    private var vehicleTemperatureJob: Job? = null
    private var settings: AutomationSettings = AutomationSettings().normalized()
    private lateinit var overlayManager: HdmiOverlayManager
    private lateinit var weatherRepository: LocationWeatherRepository
    private var healthyReported = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("G700 Clock & Weather")
                .setContentText("Clock and weather overlay running")
                .setOngoing(true)
                .build()
        )

        settings = AutomationSettingsStore.load(this).normalized()
        weatherRepository = LocationWeatherRepository(applicationContext)
        weatherRepository.start()
        overlayManager = HdmiOverlayManager(
            context = applicationContext,
            scope = scope,
            weatherProvider = RuntimeWeatherProvider()
        ) { attached, display ->
            ServiceRuntimeBus.update {
                it.copy(
                    overlayArmed = settings.overlay.shouldRenderAnything(),
                    clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                    weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                    overlayAttached = attached,
                    overlayDisplayId = display?.displayId,
                    overlayDisplayName = display?.name
                )
            }
        }

        overlayManager.start()
        observeSettings()
        observeVehicleTemperature()
        startWeatherLoop()
        ServiceRuntimeBus.set(
            ServiceRuntimeState(
                serviceRunning = true,
                phase = ServiceRuntimePhase.STARTING,
                overlayArmed = settings.overlay.shouldRenderAnything(),
                clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                weatherStatus = if (settings.overlay.weatherEnabled) {
                    if (settings.overlay.internetWeatherEnabled) {
                        "Waiting for internet weather refresh."
                    } else {
                        "Waiting for vehicle temperature."
                    }
                } else {
                    "Weather overlay off."
                },
                activeOverlays = settings.enabledOverlayLabels()
            )
        )
        scope.launch {
            delay(3_000L)
            reportHealthyIfNeeded()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AutomationServiceController.ACTION_START -> {
                AppLogger.log(TAG, "Service start requested reason=${intent.getStringExtra(AutomationServiceController.EXTRA_START_REASON)}")
            }
            AutomationServiceController.ACTION_REFRESH_SETTINGS -> {
                settings = AutomationSettingsStore.load(this).normalized()
                AppLogger.log(TAG, "Settings refreshed")
                ServiceRuntimeBus.update {
                    it.copy(
                        overlayArmed = settings.overlay.shouldRenderAnything(),
                        clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                        weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                        activeOverlays = settings.enabledOverlayLabels()
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        weatherJob?.cancel()
        vehicleTemperatureJob?.cancel()
        weatherRepository.stop()
        overlayManager.stop()
        ServiceRuntimeBus.reset()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            AutomationSettingsStore.observe(this@AutomationForegroundService).collectLatest { latest ->
                settings = latest.normalized()
                ServiceRuntimeBus.update {
                    it.copy(
                        overlayArmed = settings.overlay.shouldRenderAnything(),
                        clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                        weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                        activeOverlays = settings.enabledOverlayLabels(),
                        phase = ServiceRuntimePhase.RUNNING
                    )
                }
                if (!settings.service.enabled) {
                    AppLogger.log(TAG, "Service disabled from settings")
                    stopSelf()
                }
            }
        }
    }

    private fun observeVehicleTemperature() {
        vehicleTemperatureJob?.cancel()
        vehicleTemperatureJob = scope.launch {
            weatherRepository.vehicleTemperatureUpdates.collectLatest { result ->
                result ?: return@collectLatest
                val wantsWeather = settings.overlay.weatherEnabled || settings.overlay.calibrationMode
                if (!wantsWeather) return@collectLatest

                val currentWeather = ServiceRuntimeBus.state.value.weatherState
                val shouldUseVehicleReading = !settings.overlay.internetWeatherEnabled ||
                    currentWeather == null ||
                    currentWeather.sourceLabel == "Vehicle API"

                if (!shouldUseVehicleReading) return@collectLatest

                ServiceRuntimeBus.update {
                    it.copy(
                        serviceRunning = true,
                        phase = if (result.errorMessage == null) ServiceRuntimePhase.RUNNING else ServiceRuntimePhase.DEGRADED,
                        overlayArmed = settings.overlay.shouldRenderAnything(),
                        clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                        weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                        weatherState = result.state,
                        weatherStatus = if (settings.overlay.internetWeatherEnabled) {
                            "Using vehicle temperature until internet weather is available."
                        } else {
                            result.status
                        },
                        lastWeatherRefreshAt = System.currentTimeMillis(),
                        lastAction = "Vehicle temperature updated",
                        lastError = result.errorMessage,
                        activeOverlays = settings.enabledOverlayLabels()
                    )
                }
            }
        }
    }

    private fun startWeatherLoop() {
        weatherJob?.cancel()
        weatherJob = scope.launch {
            while (isActive) {
                runCatching {
                    val wantsWeather = settings.overlay.weatherEnabled || settings.overlay.calibrationMode
                    if (wantsWeather) {
                        val shouldPollWeather = settings.overlay.internetWeatherEnabled ||
                            !weatherRepository.hasVehicleTemperatureSubscription ||
                            ServiceRuntimeBus.state.value.weatherState == null
                        if (shouldPollWeather) {
                            refreshWeather()
                        }
                    } else {
                        ServiceRuntimeBus.update {
                            it.copy(
                                serviceRunning = true,
                                phase = ServiceRuntimePhase.RUNNING,
                                weatherState = null,
                                weatherStatus = "Weather overlay off.",
                                lastAction = "Clock overlay active",
                                lastError = null,
                                activeOverlays = settings.enabledOverlayLabels()
                            )
                        }
                    }
                    reportHealthyIfNeeded()
                }.onFailure { throwable ->
                    AppLogger.log(TAG, "Weather refresh failed", throwable)
                    ServiceRuntimeBus.update {
                        it.copy(
                            serviceRunning = true,
                            phase = ServiceRuntimePhase.DEGRADED,
                            weatherStatus = "Weather refresh failed.",
                            lastError = throwable.message ?: throwable.javaClass.simpleName
                        )
                    }
                }
                delay(
                    when {
                        !(settings.overlay.weatherEnabled || settings.overlay.calibrationMode) -> IDLE_REFRESH_MS
                        settings.overlay.internetWeatherEnabled -> WEATHER_REFRESH_MS
                        weatherRepository.hasVehicleTemperatureSubscription -> IDLE_REFRESH_MS
                        else -> VEHICLE_FALLBACK_POLL_MS
                    }
                )
            }
        }
    }

    private suspend fun refreshWeather() {
        val result = weatherRepository.refresh(settings.overlay.internetWeatherEnabled)
        ServiceRuntimeBus.update {
            it.copy(
                serviceRunning = true,
                phase = if (result.errorMessage == null) ServiceRuntimePhase.RUNNING else ServiceRuntimePhase.DEGRADED,
                overlayArmed = settings.overlay.shouldRenderAnything(),
                clockVisible = settings.overlay.clockEnabled || settings.overlay.calibrationMode,
                weatherVisible = settings.overlay.weatherEnabled || settings.overlay.calibrationMode,
                weatherState = result.state,
                weatherStatus = result.status,
                lastWeatherRefreshAt = System.currentTimeMillis(),
                lastAction = if (result.state != null) "Weather updated" else "Waiting for location",
                lastError = result.errorMessage,
                activeOverlays = settings.enabledOverlayLabels()
            )
        }
    }

    private fun reportHealthyIfNeeded() {
        if (healthyReported) return
        healthyReported = true
        StartupProtectionManager.markHealthy(applicationContext)
        ServiceRuntimeBus.update { it.copy(lastHealthyAt = System.currentTimeMillis()) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Clock and weather overlay",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val TAG = "AutomationService"
        private const val CHANNEL_ID = "automation_runtime"
        private const val NOTIFICATION_ID = 701
        private const val WEATHER_REFRESH_MS = 15 * 60 * 1_000L
        private const val VEHICLE_FALLBACK_POLL_MS = 60 * 1_000L
        private const val IDLE_REFRESH_MS = 60 * 1_000L
    }
}
