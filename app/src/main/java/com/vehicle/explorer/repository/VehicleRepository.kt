package com.g700.automation.repository

import android.content.Context
import com.g700.automation.automation.AutomationServiceController
import com.g700.automation.automation.AutomationSettingsStore
import com.g700.automation.core.AppLogger
import com.g700.automation.models.AppUiState
import com.g700.automation.models.AutomationSettings
import com.g700.automation.models.OverlaySettings
import com.g700.automation.models.UpdateUiState
import com.g700.automation.runtime.ServiceRuntimeBus
import com.g700.automation.startup.StartupProtectionManager
import com.g700.automation.startup.StartupProtectionStore
import com.g700.automation.update.GitHubUpdateChecker
import com.g700.automation.update.RemoteUpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VehicleRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableUiState = MutableStateFlow(AppUiState())
    private val updateUiState = MutableStateFlow(UpdateUiState())
    private val updateChecker = GitHubUpdateChecker(context.applicationContext)
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    fun start() {
        scope.launch {
            combine(
                AutomationSettingsStore.observe(context),
                StartupProtectionStore.observe(context),
                ServiceRuntimeBus.state,
                updateUiState
            ) { settings, startupProtection, runtime, update ->
                CombinedUiStateSources(
                    settings = settings.normalized(),
                    startupProtection = startupProtection,
                    runtime = runtime,
                    update = update
                )
            }.combine(AppLogger.logs) { combined, logs ->
                AppUiState(
                    settings = combined.settings,
                    startupProtection = combined.startupProtection,
                    runtime = combined.runtime,
                    update = combined.update,
                    logs = logs
                )
            }.collectLatest { uiState ->
                mutableUiState.value = uiState
            }
        }
        checkForUpdates(silent = true)
    }

    fun refresh() {
        if (uiState.value.settings.service.enabled) {
            AutomationServiceController.start(context, reason = "refresh")
            AutomationServiceController.refreshSettings(context)
        }
        checkForUpdates(silent = true)
    }

    fun saveSettings(settings: AutomationSettings) {
        val current = AutomationSettingsStore.load(context).normalized()
        val next = settings.normalized()
        AutomationSettingsStore.save(context, next)
        when {
            current.service.enabled && !next.service.enabled -> {
                StartupProtectionManager.cancelPendingStartup(context, "Service disabled from the app")
                AutomationServiceController.stop(context)
            }
            !current.service.enabled && next.service.enabled -> {
                AutomationServiceController.start(context, reason = "user_enable")
            }
            current != next && next.service.enabled -> {
                AutomationServiceController.refreshSettings(context)
            }
        }
    }

    fun setClockEnabled(enabled: Boolean) {
        saveSettings(uiState.value.settings.copy(overlay = uiState.value.settings.overlay.copy(clockEnabled = enabled)))
    }

    fun setOverlayWeatherEnabled(enabled: Boolean) {
        saveSettings(uiState.value.settings.copy(overlay = uiState.value.settings.overlay.copy(weatherEnabled = enabled)))
    }

    fun setInternetWeatherEnabled(enabled: Boolean) {
        saveSettings(uiState.value.settings.copy(overlay = uiState.value.settings.overlay.copy(internetWeatherEnabled = enabled)))
    }

    fun startOverlayCalibration() {
        saveSettings(uiState.value.settings.copy(overlay = uiState.value.settings.overlay.copy(calibrationMode = true)))
    }

    fun finishOverlayCalibration() {
        saveSettings(uiState.value.settings.copy(overlay = uiState.value.settings.overlay.copy(calibrationMode = false)))
    }

    fun resetOverlayPosition() {
        val defaults = OverlaySettings()
        saveSettings(
            uiState.value.settings.copy(
                overlay = uiState.value.settings.overlay.copy(
                    clockOffsetXDp = defaults.clockOffsetXDp,
                    clockOffsetYDp = defaults.clockOffsetYDp,
                    weatherOffsetXDp = defaults.weatherOffsetXDp,
                    weatherOffsetYDp = defaults.weatherOffsetYDp
                )
            )
        )
    }

    fun nudgeClock(deltaX: Int, deltaY: Int) {
        val overlay = uiState.value.settings.overlay
        saveSettings(
            uiState.value.settings.copy(
                overlay = overlay.copy(
                    calibrationMode = true,
                    clockOffsetXDp = (overlay.clockOffsetXDp + deltaX).coerceIn(-1500, 1500),
                    clockOffsetYDp = (overlay.clockOffsetYDp + deltaY).coerceIn(-900, 900)
                )
            )
        )
    }

    fun nudgeWeather(deltaX: Int, deltaY: Int) {
        val overlay = uiState.value.settings.overlay
        saveSettings(
            uiState.value.settings.copy(
                overlay = overlay.copy(
                    calibrationMode = true,
                    weatherOffsetXDp = (overlay.weatherOffsetXDp + deltaX).coerceIn(-1500, 1500),
                    weatherOffsetYDp = (overlay.weatherOffsetYDp + deltaY).coerceIn(-900, 900)
                )
            )
        )
    }

    fun reEnableAutoStart() {
        StartupProtectionManager.reEnableAutoStart(context)
    }

    fun resetStartupProtection() {
        StartupProtectionManager.resetFailureCounter(context)
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        saveSettings(uiState.value.settings.copy(service = uiState.value.settings.service.copy(autoStartOnBoot = enabled)))
    }

    fun updateSettings(transform: (AutomationSettings) -> AutomationSettings) {
        saveSettings(transform(uiState.value.settings))
    }

    fun checkForUpdates(silent: Boolean) {
        scope.launch(Dispatchers.IO) {
            val current = updateUiState.value
            updateUiState.value = current.copy(
                isChecking = true,
                errorMessage = null,
                statusMessage = if (silent) "Checking for updates in the background..." else "Checking GitHub for updates..."
            )
            when (val result = updateChecker.check()) {
                is GitHubUpdateChecker.UpdateCheckResult.Available -> {
                    updateUiState.value = current.copy(
                        isChecking = false,
                        updateAvailable = true,
                        latestVersionCode = result.manifest.versionCode,
                        latestVersionName = result.manifest.versionName,
                        downloadUrl = result.manifest.apkUrl,
                        releaseNotes = result.manifest.notes,
                        publishedAt = result.manifest.publishedAt,
                        lastCheckedAt = System.currentTimeMillis(),
                        statusMessage = "Update ${result.manifest.versionName} is available.",
                        errorMessage = null
                    )
                }
                is GitHubUpdateChecker.UpdateCheckResult.UpToDate -> {
                    updateUiState.value = current.copy(
                        isChecking = false,
                        updateAvailable = false,
                        latestVersionCode = result.manifest.versionCode,
                        latestVersionName = result.manifest.versionName,
                        downloadUrl = result.manifest.apkUrl,
                        releaseNotes = result.manifest.notes,
                        publishedAt = result.manifest.publishedAt,
                        lastCheckedAt = System.currentTimeMillis(),
                        statusMessage = "This build is current.",
                        errorMessage = null
                    )
                }
                is GitHubUpdateChecker.UpdateCheckResult.Error -> {
                    updateUiState.value = current.copy(
                        isChecking = false,
                        lastCheckedAt = System.currentTimeMillis(),
                        statusMessage = if (silent) current.statusMessage else "Could not reach the update feed.",
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun installUpdate() {
        val manifest = currentUpdateManifest() ?: return
        scope.launch(Dispatchers.IO) {
            val current = updateUiState.value
            updateUiState.value = current.copy(
                isInstalling = true,
                errorMessage = null,
                statusMessage = "Downloading update..."
            )
            when (val result = updateChecker.downloadAndInstall(manifest)) {
                is GitHubUpdateChecker.InstallResult.Started -> {
                    updateUiState.value = current.copy(
                        isInstalling = false,
                        statusMessage = "Installer opened for ${manifest.versionName}.",
                        errorMessage = null
                    )
                }
                is GitHubUpdateChecker.InstallResult.Error -> {
                    updateUiState.value = current.copy(
                        isInstalling = false,
                        statusMessage = "Could not start the update installer.",
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    private fun currentUpdateManifest(): RemoteUpdateManifest? {
        val update = updateUiState.value
        return RemoteUpdateManifest(
            versionCode = update.latestVersionCode ?: return null,
            versionName = update.latestVersionName ?: return null,
            apkUrl = update.downloadUrl ?: return null,
            notes = update.releaseNotes,
            publishedAt = update.publishedAt
        )
    }

    private data class CombinedUiStateSources(
        val settings: AutomationSettings,
        val startupProtection: com.g700.automation.startup.StartupProtectionState,
        val runtime: com.g700.automation.runtime.ServiceRuntimeState,
        val update: UpdateUiState
    )
}
