package com.g700.automation.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.g700.automation.automation.AutomationSettingsStore
import com.g700.automation.core.AppLogger
import com.g700.automation.models.OverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HdmiOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val weatherProvider: OverlayWeatherProvider,
    private val onAttachmentChanged: (Boolean, Display?) -> Unit
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null
    private var presentation: HdmiOverlayPresentation? = null
    private var currentSettings: OverlaySettings = OverlaySettings()
    private var currentWeather: OverlayWeatherState? = null
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = sync()
        override fun onDisplayRemoved(displayId: Int) = sync()
        override fun onDisplayChanged(displayId: Int) = sync()
    }

    fun start() {
        if (started) return
        started = true
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        settingsJob = scope.launch {
            combine(
                AutomationSettingsStore.observe(context),
                weatherProvider.weather
            ) { settings, weather ->
                settings.overlay to weather
            }.collectLatest { (overlaySettings, weather) ->
                currentSettings = overlaySettings
                currentWeather = weather
                sync()
            }
        }
        sync()
    }

    fun stop() {
        if (!started) return
        started = false
        settingsJob?.cancel()
        settingsJob = null
        displayManager?.unregisterDisplayListener(displayListener)
        dismissPresentation()
    }

    fun sync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sync() }
            return
        }
        if (!started) return
        if (!currentSettings.shouldRenderAnything()) {
            dismissPresentation()
            onAttachmentChanged(false, null)
            return
        }

        val targetDisplay = resolveTargetDisplay()
        if (targetDisplay == null) {
            dismissPresentation()
            onAttachmentChanged(false, null)
            AppLogger.log(TAG, "Overlay enabled but no presentation display is available")
            return
        }

        val activePresentation = if (presentation?.display?.displayId == targetDisplay.displayId) {
            presentation
        } else {
            dismissPresentation()
            val nextPresentation = HdmiOverlayPresentation(context, targetDisplay)
            val shown = runCatching { nextPresentation.show() }
                .onFailure { error -> AppLogger.log(TAG, "Failed to show HDMI overlay", error) }
                .isSuccess
            if (shown) {
                presentation = nextPresentation
                nextPresentation
            } else {
                dismissPresentation()
                null
            }
        }

        activePresentation?.render(currentSettings, currentWeather)
        onAttachmentChanged(activePresentation != null, if (activePresentation != null) targetDisplay else null)
    }

    private fun dismissPresentation() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    private fun resolveTargetDisplay(): Display? {
        val allDisplays = displayManager?.displays.orEmpty()
        val candidateDisplays: List<Display> = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.takeIf { it.isNotEmpty() }
            ?.toList()
            ?: allDisplays.filter { it.displayId != Display.DEFAULT_DISPLAY }

        return candidateDisplays.minWithOrNull(
            compareBy<Display>(
                { if (it.displayId == 2) 0 else 1 },
                { if (it.name.contains("hdmi", ignoreCase = true) || it.name.contains("lanso", ignoreCase = true)) 0 else 1 },
                { it.displayId }
            )
        ) ?: allDisplays.firstOrNull { it.displayId == 2 }
    }

    private companion object {
        const val TAG = "HdmiOverlayManager"
    }
}
