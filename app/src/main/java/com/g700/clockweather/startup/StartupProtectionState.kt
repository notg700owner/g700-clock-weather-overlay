package com.g700.clockweather.startup

enum class StartupPhase {
    IDLE,
    BOOT_DELAY_PENDING,
    STARTING,
    HEALTHY,
    AUTO_START_BLOCKED
}

data class StartupProtectionState(
    val phase: StartupPhase = StartupPhase.IDLE,
    val consecutiveFailures: Int = 0,
    val autoStartBlocked: Boolean = false,
    val currentAttemptToken: Long = 0L,
    val lastAttemptAt: Long? = null,
    val lastHealthyAt: Long? = null,
    val startupDeadlineAt: Long? = null,
    val lastFailureReason: String? = null
)
