package com.g700.clockweather.overlay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g700.clockweather.models.OverlaySettings
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class OverlayPresentationState(
    val settings: OverlaySettings = OverlaySettings(),
    val weatherState: OverlayWeatherState? = null
)

class HdmiOverlayPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var presentationState: OverlayPresentationState = OverlayPresentationState()

    private lateinit var rootView: FrameLayout
    private lateinit var clockView: TextView
    private lateinit var weatherView: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (!::clockView.isInitialized) return
            clockView.text = formatClockText(context, System.currentTimeMillis())
            mainHandler.postDelayed(this, nextClockTickDelayMillis())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.decorView?.setBackgroundColor(Color.TRANSPARENT)

        rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setWillNotDraw(false)
        }

        clockView = buildOverlayTextView(defaultBold = true)
        weatherView = buildOverlayTextView(defaultBold = false).apply {
            visibility = View.GONE
        }

        rootView.addView(clockView, centeredLayoutParams())
        rootView.addView(weatherView, centeredLayoutParams())
        setContentView(rootView)
        applyPresentationState()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(tick)
        super.onStop()
    }

    fun render(settings: OverlaySettings, weatherState: OverlayWeatherState?) {
        presentationState = OverlayPresentationState(settings = settings, weatherState = weatherState)
        if (::rootView.isInitialized) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                applyPresentationState()
            } else {
                mainHandler.post { applyPresentationState() }
            }
        }
    }

    private fun applyPresentationState() {
        val settings = presentationState.settings
        val showClock = settings.clockEnabled || settings.calibrationMode
        val showWeather = settings.weatherEnabled || settings.calibrationMode

        clockView.visibility = if (showClock) View.VISIBLE else View.GONE
        clockView.text = formatClockText(context, System.currentTimeMillis())
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.clockFontSizeSp.toFloat())
        clockView.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        clockView.translationX = dpToPx(settings.clockOffsetXDp).toFloat()
        clockView.translationY = dpToPx(settings.clockOffsetYDp).toFloat()

        val weatherLabel = if (showWeather) weatherLabel(settings, presentationState.weatherState) else null
        weatherView.visibility = if (weatherLabel.isNullOrBlank()) View.GONE else View.VISIBLE
        weatherView.text = weatherLabel.orEmpty()
        weatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.weatherFontSizeSp.toFloat())
        weatherView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        weatherView.translationX = dpToPx(settings.weatherOffsetXDp).toFloat()
        weatherView.translationY = dpToPx(settings.weatherOffsetYDp).toFloat()
    }

    private fun buildOverlayTextView(defaultBold: Boolean): TextView {
        return TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setShadowLayer(14f, 0f, 0f, Color.argb(165, 0, 0, 0))
            setTypeface(Typeface.DEFAULT, if (defaultBold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    }

    private fun centeredLayoutParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    private fun nextClockTickDelayMillis(): Long {
        val now = System.currentTimeMillis()
        return (60_000L - (now % 60_000L)).coerceAtLeast(1_000L)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

@Composable
fun OverlayPreviewCanvas(
    settings: OverlaySettings,
    weatherState: OverlayWeatherState?,
    modifier: Modifier = Modifier
) {
    Surface(color = ComposeColor.Transparent, modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (settings.clockEnabled || settings.calibrationMode) {
                Text(
                    text = rememberClockText().value,
                    color = ComposeColor.White,
                    fontSize = settings.clockFontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = overlayTextStyle(22f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = settings.clockOffsetXDp.dp, y = settings.clockOffsetYDp.dp)
                )
            }

            val weatherLine = weatherLabel(settings, weatherState)
            if ((settings.weatherEnabled || settings.calibrationMode) && !weatherLine.isNullOrBlank()) {
                Text(
                    text = weatherLine,
                    color = ComposeColor.White.copy(alpha = 0.92f),
                    fontSize = settings.weatherFontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    style = overlayTextStyle(18f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = settings.weatherOffsetXDp.dp, y = settings.weatherOffsetYDp.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberClockText(): State<String> {
    val context = LocalContext.current
    return produceState(initialValue = formatClockText(context, System.currentTimeMillis())) {
        while (true) {
            val now = System.currentTimeMillis()
            value = formatClockText(context, now)
            val nextMinuteDelay = 60_000L - (now % 60_000L)
            delay(nextMinuteDelay.coerceAtLeast(1_000L))
        }
    }
}

private fun overlayTextStyle(blurRadius: Float): TextStyle {
    return TextStyle(
        shadow = Shadow(
            color = ComposeColor.Black.copy(alpha = 0.55f),
            blurRadius = blurRadius
        )
    )
}

private fun formatClockText(context: Context, now: Long): String {
    val locale = Locale.getDefault()
    val pattern = if (DateFormat.is24HourFormat(context)) {
        DateFormat.getBestDateTimePattern(locale, "Hm")
    } else {
        DateFormat.getBestDateTimePattern(locale, "hm")
    }
    return SimpleDateFormat(pattern, locale).format(Date(now))
}

private fun weatherLabel(settings: OverlaySettings, state: OverlayWeatherState?): String? {
    if (state == null) {
        return if (settings.calibrationMode) "28°C  Clear" else null
    }

    val temperature = state.outsideTemperatureC?.let { "${DecimalFormat("0.#").format(it)}°C" }
    val condition = state.conditionLabel
    return listOfNotNull(temperature, condition).joinToString("  ").ifBlank { null }
}
