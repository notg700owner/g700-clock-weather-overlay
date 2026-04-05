package com.g700.automation.models

import com.g700.automation.BuildConfig
import com.g700.automation.runtime.ServiceRuntimeState
import com.g700.automation.startup.StartupProtectionState

data class UpdateUiState(
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val isChecking: Boolean = false,
    val isInstalling: Boolean = false,
    val updateAvailable: Boolean = false,
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
    val settings: AutomationSettings = AutomationSettings(),
    val runtime: ServiceRuntimeState = ServiceRuntimeState(),
    val startupProtection: StartupProtectionState = StartupProtectionState(),
    val update: UpdateUiState = UpdateUiState(),
    val logs: List<String> = emptyList()
)
