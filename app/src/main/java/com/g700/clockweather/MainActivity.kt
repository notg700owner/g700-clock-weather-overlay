package com.g700.clockweather

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.g700.clockweather.ui.ClockWeatherApp
import com.g700.clockweather.ui.theme.G700ClockWeatherTheme
import com.g700.clockweather.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            G700ClockWeatherTheme {
                ClockWeatherApp(viewModel)
            }
        }
    }
}
