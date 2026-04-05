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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g700.clockweather.models.AppUiState
import com.g700.clockweather.models.OverlaySettings
import com.g700.clockweather.overlay.OverlayPreviewCanvas
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
    val locationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationGranted: Boolean,
    val batteryOptimizedIgnored: Boolean,
    val installPermissionGranted: Boolean
)

@Composable
fun ClockWeatherApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(DeckPage.MAIN) }
    val context = LocalContext.current
    val permissions = rememberPermissionSnapshot(context)

    PermissionLaunchCoordinator(
        context = context,
        requestInternetWeatherPermissions = uiState.settings.overlay.internetWeatherEnabled
    )

    DeckShell(
        page = page,
        uiState = uiState,
        preview = {
            PreviewPanel(
                settings = previewOverlaySettings(uiState.settings.overlay),
                uiState = uiState,
                modifier = Modifier.fillMaxHeight()
            )
        }
    ) {
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
                onRefresh = viewModel::refresh,
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
private fun DeckShell(
    page: DeckPage,
    uiState: AppUiState,
    preview: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val isWide = LocalConfiguration.current.screenWidthDp >= 960
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF05060E),
                        Color(0xFF0C0F1D),
                        Color(0xFF13172B)
                    )
                )
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopStrip(page = page)
            Spacer(Modifier.height(18.dp))
            if (isWide) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    SidebarRail(
                        modifier = Modifier.width(212.dp),
                        highlightCalibration = page == DeckPage.CALIBRATION
                    )
                    Box(modifier = Modifier.weight(1.15f)) { content() }
                    Box(modifier = Modifier.weight(0.95f)) { preview() }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SidebarRailCompact(highlightCalibration = page == DeckPage.CALIBRATION)
                    Box(modifier = Modifier.fillMaxWidth()) { content() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) { preview() }
                }
            }
            Spacer(Modifier.height(16.dp))
            BottomDock(uiState = uiState)
        }
    }
}

@Composable
private fun TopStrip(page: DeckPage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TinyIndicator(active = false)
            TinyIndicator(active = page == DeckPage.CALIBRATION)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = rememberUiClockText(),
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFFF2F4FF),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TinyGlyph(label = "BT")
            TinyGlyph(label = "GPS")
            TinyGlyph(label = "WiFi")
        }
    }
}

@Composable
private fun TinyIndicator(active: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp, 14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFFFA6376) else Color(0xFF2A2F4A))
    )
}

@Composable
private fun TinyGlyph(label: String) {
    Text(
        text = label,
        color = Color(0xFFA9B2D6),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun SidebarRail(modifier: Modifier = Modifier, highlightCalibration: Boolean) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0x0FEEF1FF),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RailItem("Vehicle", selected = false)
            RailItem("Energy", selected = false)
            RailItem("Drive", selected = false)
            RailItem("Display", selected = true)
            RailItem("Sound", selected = false)
            RailItem("Connect", selected = false)
            RailItem("Calibrate", selected = highlightCalibration)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Clock & Weather",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAB4D8)
            )
        }
    }
}

@Composable
private fun SidebarRailCompact(highlightCalibration: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RailChip("Vehicle", false)
        RailChip("Energy", false)
        RailChip("Drive", false)
        RailChip("Display", true)
        RailChip("Calibrate", highlightCalibration)
    }
}

@Composable
private fun RailChip(label: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFFE0E4FF) else Color(0x141B2034)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (selected) Color(0xFF0C1022) else Color(0xFFC1CAE8),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun RailItem(label: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color(0xFFE3E6FF) else Color(0x00FFFFFF),
        border = if (!selected) null else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xFF7B87C7) else Color(0xFF2F3553))
            )
            Text(
                text = label,
                color = if (selected) Color(0xFF101428) else Color(0xFFDAE0F7),
                style = MaterialTheme.typography.titleMedium
            )
        }
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
    onRefresh: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onAutoStartToggle: (Boolean) -> Unit,
    onReEnableAutoStart: () -> Unit,
    onResetProtection: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderPanel(
            title = "Display Settings",
            subtitle = "Single-purpose clock and weather overlay for HDMI 2"
        )
        GlassPanel(title = "Clock Overlay", subtitle = "Keep the main clock visible on the secondary display.") {
            BinaryModeRow(
                label = "Clock",
                checked = uiState.settings.overlay.clockEnabled,
                onCheckedChange = onClockToggle
            )
        }
        GlassPanel(
            title = "Weather Overlay",
            subtitle = uiState.runtime.weatherStatus
        ) {
            BinaryModeRow(
                label = "Weather",
                checked = uiState.settings.overlay.weatherEnabled,
                onCheckedChange = onWeatherToggle
            )
            if (uiState.settings.overlay.weatherEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Internet weather",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = "When disabled, weather uses the car API temperature only. When enabled and online, internet forecast is used and falls back automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8C1E5)
                        )
                    }
                    Switch(
                        checked = uiState.settings.overlay.internetWeatherEnabled,
                        onCheckedChange = onInternetWeatherToggle
                    )
                }
                Spacer(Modifier.height(6.dp))
                StatusLine(
                    "Source",
                    uiState.runtime.weatherState?.sourceLabel ?: if (uiState.settings.overlay.internetWeatherEnabled) "Internet when online" else "Vehicle API"
                )
            }
            if (!uiState.runtime.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = uiState.runtime.lastError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9AA7)
                )
            }
        }
        GlassPanel(title = "Actions", subtitle = "Calibration, updates, and runtime refresh.") {
            WrapRow {
                ActionButton("Calibrate", onCalibrate)
                ActionButton("Refresh", onRefresh)
                ActionButton("Check for Update", onCheckForUpdate)
            }
            if (uiState.update.updateAvailable) {
                Spacer(Modifier.height(12.dp))
                WrapRow {
                    if (permissions.installPermissionGranted) {
                        ActionButton(
                            label = "Install ${uiState.update.latestVersionName ?: "Update"}",
                            onClick = onInstallUpdate,
                            primary = true,
                            enabled = !uiState.update.isInstalling
                        )
                    } else {
                        ActionButton("Allow Install", onOpenInstallSettings)
                    }
                }
            }
        }
        GlassPanel(title = "Status", subtitle = "Startup safety, display route, and permissions.") {
            StatusLine("Display", if (uiState.runtime.overlayAttached) {
                uiState.runtime.overlayDisplayName ?: "Attached"
            } else {
                "Waiting for HDMI 2"
            })
            StatusLine("Startup", startupLabel(uiState))
            StatusLine("Update", uiState.update.statusMessage)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusPill("Location", permissions.locationGranted)
                StatusPill("Background", permissions.backgroundLocationGranted)
                StatusPill("Notify", permissions.notificationGranted)
                StatusPill("Battery", permissions.batteryOptimizedIgnored)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Start on boot",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Protected by the failure detector after repeated bad launches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8C1E5)
                    )
                }
                Switch(
                    checked = uiState.settings.service.autoStartOnBoot,
                    onCheckedChange = onAutoStartToggle
                )
            }
            if (uiState.startupProtection.autoStartBlocked) {
                Spacer(Modifier.height(12.dp))
                WrapRow {
                    ActionButton("Re-enable Autostart", onReEnableAutoStart)
                    ActionButton("Reset Counter", onResetProtection)
                }
            }
        }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderPanel(
            title = "Overlay Calibration",
            subtitle = "Fine-tune the clock and weather positions with +100, +10, +1, and negative steps."
        )
        GlassPanel(title = "Calibration Controls", subtitle = "Use the hidden page to match the screen overlay.") {
            WrapRow {
                ActionButton("Done", onDone, primary = true)
                ActionButton("Reset Position", onReset)
            }
        }
        PreviewPanel(
            settings = previewOverlaySettings(uiState.settings.overlay.copy(calibrationMode = true)),
            uiState = uiState,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        )
        AxisControlCard("Clock X", uiState.settings.overlay.clockOffsetXDp, onAdjustClockX)
        AxisControlCard("Clock Y", uiState.settings.overlay.clockOffsetYDp, onAdjustClockY)
        AxisControlCard("Weather X", uiState.settings.overlay.weatherOffsetXDp, onAdjustWeatherX)
        AxisControlCard("Weather Y", uiState.settings.overlay.weatherOffsetYDp, onAdjustWeatherY)
    }
}

@Composable
private fun HeaderPanel(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB8C1E5)
        )
    }
}

@Composable
private fun GlassPanel(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0x1BF2F4FF),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB5BEDF)
                )
                content()
            }
        )
    }
}

@Composable
private fun BinaryModeRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFECEFFF)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0x10191F33),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4464))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                SegmentButton(
                    label = "OFF",
                    selected = !checked,
                    modifier = Modifier.weight(1f),
                    onClick = { onCheckedChange(false) }
                )
                SegmentButton(
                    label = "ON",
                    selected = checked,
                    modifier = Modifier.weight(1f),
                    onClick = { onCheckedChange(true) }
                )
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFE4E7FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0D1124) else Color(0xFFCFD6F4),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WrapRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val colors = if (primary) {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFFE4E7FF),
            contentColor = Color(0xFF0C1022),
            disabledContainerColor = Color(0xFF7C83A5),
            disabledContentColor = Color(0xFF1A1F36)
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            contentColor = Color(0xFFE4E7FF),
            disabledContentColor = Color(0xFF7C83A5)
        )
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, colors = colors, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, colors = colors, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Text(label)
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
            color = Color(0xFFB5BEDF)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatusPill(label: String, granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (granted) Color(0xFF6FE2D6) else Color(0xFF2B324D)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (granted) Color(0xFF08131C) else Color(0xFFE6EAFF)
        )
    }
}

@Composable
private fun PreviewPanel(
    settings: OverlaySettings,
    uiState: AppUiState,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        title = "HDMI 2 Preview",
        subtitle = uiState.runtime.overlayDisplayName ?: "Secondary display preview"
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF171B33),
                            Color(0xFF1E2442),
                            Color(0xFF0E1122)
                        )
                    )
                )
                .border(1.dp, Color(0xFF3B4468), RoundedCornerShape(24.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x223AE9FF), Color.Transparent)
                        )
                    )
            )
            if (settings.shouldRenderAnything()) {
                OverlayPreviewCanvas(
                    settings = settings,
                    weatherState = uiState.runtime.weatherState,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Turn on Clock or Weather to arm the overlay.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFBAC4E7)
                )
            }
            Text(
                text = if (uiState.runtime.overlayAttached) "LIVE" else "STANDBY",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (uiState.runtime.overlayAttached) Color(0xFF70F0DB) else Color(0xFFC5CEE9)
            )
        }
    }
}

@Composable
private fun AxisControlCard(title: String, value: Int, onAdjust: (Int) -> Unit) {
    GlassPanel(
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
private fun CalibrationButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0x111F2440),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF394261)),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White
        )
    }
}

@Composable
private fun BottomDock(uiState: AppUiState) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x101A2035),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (uiState.runtime.weatherState?.outsideTemperatureC != null) {
                    "${uiState.runtime.weatherState.outsideTemperatureC.toInt()}°"
                } else {
                    "--°"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Text(
                text = if (uiState.runtime.overlayAttached) "Overlay live" else "Overlay standby",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFBAC4E7)
            )
            Text(
                text = uiState.update.latestVersionName?.let { "Feed $it" } ?: "No feed",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9FA9CD)
            )
        }
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
        locationGranted = hasForegroundLocationPermission(context),
        backgroundLocationGranted = hasBackgroundLocationPermission(context),
        notificationGranted = hasNotificationPermission(context),
        batteryOptimizedIgnored = isIgnoringBatteryOptimizations(context),
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

private fun previewOverlaySettings(settings: OverlaySettings): OverlaySettings {
    return settings.copy(
        clockOffsetXDp = (settings.clockOffsetXDp / 10).coerceIn(-140, 140),
        clockOffsetYDp = (settings.clockOffsetYDp / 10).coerceIn(-90, 90),
        weatherOffsetXDp = (settings.weatherOffsetXDp / 10).coerceIn(-140, 140),
        weatherOffsetYDp = (settings.weatherOffsetYDp / 10).coerceIn(-90, 90),
        clockFontSizeSp = 28,
        weatherFontSizeSp = 16
    )
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
