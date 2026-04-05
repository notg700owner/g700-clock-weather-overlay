package com.g700.clockweather.models

import com.g700.clockweather.runtime.ServiceRuntimeState
import com.g700.clockweather.startup.StartupProtectionState

data class UpdateUiState(
    val isChecking: Boolean = false,
    val isInstalling: Boolean = false,
    val updateAvailable: Boolean = false,
    val installedVersionCode: Int? = null,
    val installedVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val latestVersionName: String? = null,
    val downloadUrl: String? = null,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val lastCheckedAt: Long? = null,
    val statusMessage: String = "Checks run on launch and on demand.",
    val errorMessage: String? = null
)

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val runtime: ServiceRuntimeState = ServiceRuntimeState(),
    val startupProtection: StartupProtectionState = StartupProtectionState(),
    val update: UpdateUiState = UpdateUiState()
)
