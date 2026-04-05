package com.g700.clockweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.g700.clockweather.ui.ClockWeatherApp
import com.g700.clockweather.ui.theme.G700ClockWeatherTheme
import com.g700.clockweather.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            G700ClockWeatherTheme {
                ClockWeatherApp(viewModel)
            }
        }
    }
}
