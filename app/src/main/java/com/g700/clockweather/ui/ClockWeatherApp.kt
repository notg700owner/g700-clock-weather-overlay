package com.g700.clockweather.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g700.clockweather.models.AppUiState
import com.g700.clockweather.startup.StartupPhase
import com.g700.clockweather.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DeckPage {
    MAIN,
    CALIBRATION
}

private data class PermissionSnapshot(
    val installPermissionGranted: Boolean
)

private val ScreenBlack = Color(0xFF0A0A0A)
private val ScreenBlackSoft = Color(0xFF101010)
private val PanelBlack = Color(0xFF141414)
private val BorderGray = Color(0xFF2A2A2A)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8E8E8E)
private val AccentTeal = Color(0xFF21D8E3)
private val ActiveTile = Color(0xFFD8D8D8)
private val ActiveText = Color(0xFF171717)
private val ErrorTint = Color(0xFFFF9B97)

@Composable
fun ClockWeatherApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var page: DeckPage by rememberSaveable { mutableStateOf(DeckPage.MAIN) }
    val context = LocalContext.current
    val permissions = rememberPermissionSnapshot(context)

    PermissionLaunchCoordinator(
        context = context,
        requestInternetWeatherPermissions = uiState.settings.overlay.internetWeatherEnabled
    )

    AppFrame {
        when (page) {
            DeckPage.MAIN -> MainControlScreen(
                uiState = uiState,
                permissions = permissions,
                onClockToggle = viewModel::setClockEnabled,
                onWeatherToggle = viewModel::setOverlayWeatherEnabled,
                onInternetWeatherToggle = viewModel::setInternetWeatherEnabled,
                onCalibrate = {
                    viewModel.startOverlayCalibration()
                    page = DeckPage.CALIBRATION
                },
                onCheckForUpdate = { viewModel.checkForUpdates(silent = false) },
                onInstallUpdate = viewModel::installUpdate,
                onOpenInstallSettings = { openUnknownAppsSettings(context) },
                onAutoStartToggle = viewModel::setAutoStartOnBoot,
                onReEnableAutoStart = viewModel::reEnableAutoStart,
                onResetProtection = viewModel::resetStartupProtection
            )
            DeckPage.CALIBRATION -> CalibrationScreen(
                uiState = uiState,
                onDone = {
                    viewModel.finishOverlayCalibration()
                    page = DeckPage.MAIN
                },
                onReset = viewModel::resetOverlayPosition,
                onAdjustClockX = { viewModel.nudgeClock(it, 0) },
                onAdjustClockY = { viewModel.nudgeClock(0, it) },
                onAdjustWeatherX = { viewModel.nudgeWeather(it, 0) },
                onAdjustWeatherY = { viewModel.nudgeWeather(0, it) }
            )
        }
    }
}

@Composable
private fun PermissionLaunchCoordinator(
    context: Context,
    requestInternetWeatherPermissions: Boolean
) {
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    var requestedLocation by rememberSaveable { mutableStateOf(false) }
    var requestedBackgroundLocation by rememberSaveable { mutableStateOf(false) }
    var requestedNotifications by rememberSaveable { mutableStateOf(false) }
    var requestedBattery by rememberSaveable { mutableStateOf(false) }

    val locationGranted = hasForegroundLocationPermission(context)
    val backgroundLocationGranted = hasBackgroundLocationPermission(context)
    val notificationGranted = hasNotificationPermission(context)
    val batteryOptimizationsIgnored = isIgnoringBatteryOptimizations(context)

    LaunchedEffect(locationGranted, requestInternetWeatherPermissions) {
        if (requestInternetWeatherPermissions && !locationGranted && !requestedLocation) {
            requestedLocation = true
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(locationGranted, backgroundLocationGranted, requestInternetWeatherPermissions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            requestInternetWeatherPermissions &&
            locationGranted &&
            !backgroundLocationGranted &&
            !requestedBackgroundLocation
        ) {
            requestedBackgroundLocation = true
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    LaunchedEffect(notificationGranted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationGranted &&
            !requestedNotifications
        ) {
            requestedNotifications = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(batteryOptimizationsIgnored) {
        if (!batteryOptimizationsIgnored && !requestedBattery) {
            requestedBattery = true
            batteryOptimizationLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }
}

@Composable
private fun AppFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ScreenBlack, ScreenBlackSoft, ScreenBlack)
                )
            )
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar()
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 860.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Clock & Weather",
            style = MaterialTheme.typography.titleMedium,
            color = TextMuted
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = rememberUiClockText(),
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MainControlScreen(
    uiState: AppUiState,
    permissions: PermissionSnapshot,
    onClockToggle: (Boolean) -> Unit,
    onWeatherToggle: (Boolean) -> Unit,
    onInternetWeatherToggle: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onAutoStartToggle: (Boolean) -> Unit,
    onReEnableAutoStart: () -> Unit,
    onResetProtection: () -> Unit
) {
    ScreenIntro(
        title = "Overlay Control",
        subtitle = "Basic controls for the secondary-display clock and external temperature overlay."
    )

    SectionPanel(
        title = "Overlay",
        subtitle = "Only the pieces you want to render."
    ) {
        SettingRow(
            label = "Clock",
            detail = "Show the clock on the overlay.",
            checked = uiState.settings.overlay.clockEnabled,
            onToggle = onClockToggle
        )
        SettingRow(
            label = "Weather",
            detail = "Show outside temperature on the overlay.",
            checked = uiState.settings.overlay.weatherEnabled,
            onToggle = onWeatherToggle
        )
        if (uiState.settings.overlay.weatherEnabled) {
            SettingRow(
                label = "Internet Weather",
                detail = "Use online weather when reachable, otherwise fall back to the car temperature.",
                checked = uiState.settings.overlay.internetWeatherEnabled,
                onToggle = onInternetWeatherToggle
            )
        }
    }

    SectionPanel(
        title = "System",
        subtitle = "Live status and boot protection."
    ) {
        StatusLine(
            label = "Display",
            value = if (uiState.runtime.overlayAttached) {
                uiState.runtime.overlayDisplayName ?: "Secondary display attached"
            } else {
                "Waiting for secondary display"
            }
        )
        SettingRow(
            label = "Start On Boot",
            detail = "Protected by the failure detector after repeated unhealthy launches.",
            checked = uiState.settings.service.autoStartOnBoot,
            onToggle = onAutoStartToggle
        )
        StatusLine(
            label = "Autostart",
            value = startupLabel(uiState)
        )
        if (uiState.settings.overlay.weatherEnabled) {
            StatusLine(
                label = "Source",
                value = uiState.runtime.weatherState?.sourceLabel ?: if (uiState.settings.overlay.internetWeatherEnabled) {
                    "Car fallback"
                } else {
                    "Vehicle API"
                }
            )
            StatusLine(
                label = "External Temp",
                value = uiState.runtime.weatherState?.outsideTemperatureC?.let { formatTemperature(it) } ?: "--"
            )
            StatusLine(
                label = "Weather Status",
                value = uiState.runtime.weatherStatus
            )
        }
        if (uiState.startupProtection.autoStartBlocked) {
            Spacer(Modifier.height(4.dp))
            ButtonStrip(
                primaryLabel = "Re-enable Autostart",
                onPrimaryClick = onReEnableAutoStart,
                secondaryLabel = "Reset Counter",
                onSecondaryClick = onResetProtection
            )
        }
        if (!uiState.runtime.lastError.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = uiState.runtime.lastError.orEmpty(),
                color = ErrorTint,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    SectionPanel(
        title = "Tools",
        subtitle = "Calibration and update controls."
    ) {
        FullWidthActionButton(
            label = "Calibrate",
            onClick = onCalibrate,
            primary = false
        )
        Spacer(Modifier.height(10.dp))
        if (uiState.update.updateAvailable) {
            if (permissions.installPermissionGranted) {
                ButtonStrip(
                    primaryLabel = "Install ${uiState.update.latestVersionName ?: "Update"}",
                    onPrimaryClick = onInstallUpdate,
                    primaryEnabled = !uiState.update.isInstalling,
                    secondaryLabel = "Check For Update",
                    onSecondaryClick = onCheckForUpdate
                )
            } else {
                ButtonStrip(
                    primaryLabel = "Allow Install",
                    onPrimaryClick = onOpenInstallSettings,
                    secondaryLabel = "Check For Update",
                    onSecondaryClick = onCheckForUpdate
                )
            }
        } else {
            FullWidthActionButton(
                label = "Check For Update",
                onClick = onCheckForUpdate
            )
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(
            label = "Updater",
            value = uiState.update.statusMessage
        )
    }
}

@Composable
private fun CalibrationScreen(
    uiState: AppUiState,
    onDone: () -> Unit,
    onReset: () -> Unit,
    onAdjustClockX: (Int) -> Unit,
    onAdjustClockY: (Int) -> Unit,
    onAdjustWeatherX: (Int) -> Unit,
    onAdjustWeatherY: (Int) -> Unit
) {
    DisposableEffect(Unit) {
        onDispose { onDone() }
    }

    ScreenIntro(
        title = "Calibration",
        subtitle = "Adjust X and Y offsets for the clock and temperature overlay."
    )

    SectionPanel(
        title = "Calibration Actions",
        subtitle = "This page stays out of the way until you open it from the main screen."
    ) {
        ButtonStrip(
            primaryLabel = "Done",
            onPrimaryClick = onDone,
            secondaryLabel = "Reset Position",
            onSecondaryClick = onReset
        )
    }

    AxisControlCard("Clock X", uiState.settings.overlay.clockOffsetXDp, onAdjustClockX)
    AxisControlCard("Clock Y", uiState.settings.overlay.clockOffsetYDp, onAdjustClockY)
    AxisControlCard("Weather X", uiState.settings.overlay.weatherOffsetXDp, onAdjustWeatherX)
    AxisControlCard("Weather Y", uiState.settings.overlay.weatherOffsetYDp, onAdjustWeatherY)
}

@Composable
private fun ScreenIntro(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary
        )
        Box(
            modifier = Modifier
                .width(74.dp)
                .height(3.dp)
                .background(AccentTeal, RoundedCornerShape(99.dp))
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted
        )
    }
}

@Composable
private fun SectionPanel(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PanelBlack,
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Spacer(Modifier.width(18.dp))
        SegmentSelector(
            checked = checked,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun SegmentSelector(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ScreenBlack,
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Row {
            SegmentButton(
                label = "OFF",
                selected = !checked,
                onClick = { onCheckedChange(false) }
            )
            SegmentButton(
                label = "ON",
                selected = checked,
                onClick = { onCheckedChange(true) }
            )
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(118.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) ActiveTile else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) ActiveText else TextMuted
        )
    }
}

@Composable
private fun ButtonStrip(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    onSecondaryClick: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ActionButton(
            label = primaryLabel,
            onClick = onPrimaryClick,
            primary = true,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            label = secondaryLabel,
            onClick = onSecondaryClick,
            primary = false,
            enabled = secondaryEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FullWidthActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    ActionButton(
        label = label,
        onClick = onClick,
        primary = primary,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ActiveTile,
                contentColor = ActiveText,
                disabledContainerColor = Color(0xFF777777),
                disabledContentColor = Color(0xFF222222)
            )
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderGray),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextPrimary,
                disabledContentColor = TextMuted
            )
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun AxisControlCard(
    title: String,
    value: Int,
    onAdjust: (Int) -> Unit
) {
    SectionPanel(
        title = title,
        subtitle = "Current offset: $value"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CalibrationButton("-100") { onAdjust(-100) }
            CalibrationButton("-10") { onAdjust(-10) }
            CalibrationButton("-1") { onAdjust(-1) }
            CalibrationButton("+1") { onAdjust(1) }
            CalibrationButton("+10") { onAdjust(10) }
            CalibrationButton("+100") { onAdjust(100) }
        }
    }
}

@Composable
private fun CalibrationButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ScreenBlack,
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary
        )
    }
}

@Composable
private fun rememberUiClockText(): String {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val formatter = remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
    val currentText by produceState(initialValue = formatter.format(Date())) {
        while (true) {
            val now = System.currentTimeMillis()
            value = formatter.format(Date(now))
            val nextMinuteDelay = (60_000L - (now % 60_000L)).coerceAtLeast(1_000L)
            kotlinx.coroutines.delay(nextMinuteDelay)
        }
    }
    return currentText
}

@Composable
private fun rememberPermissionSnapshot(context: Context): PermissionSnapshot {
    return PermissionSnapshot(
        installPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    )
}

private fun startupLabel(uiState: AppUiState): String {
    return when {
        uiState.startupProtection.autoStartBlocked -> "Blocked after repeated failures"
        uiState.startupProtection.phase == StartupPhase.BOOT_DELAY_PENDING -> "Waiting for boot delay"
        uiState.settings.service.autoStartOnBoot -> "Protected and armed"
        else -> "Manual launch only"
    }
}

private fun formatTemperature(value: Float): String {
    val rounded = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    return "$rounded°C"
}

private fun hasForegroundLocationPermission(context: Context): Boolean {
    return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun hasBackgroundLocationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        true
    } else {
        hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun openUnknownAppsSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
