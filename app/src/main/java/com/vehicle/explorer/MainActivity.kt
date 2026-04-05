package com.g700.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.g700.automation.ui.VehicleAutomationApp
import com.g700.automation.ui.theme.G700AutomationTheme
import com.g700.automation.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            G700AutomationTheme {
                VehicleAutomationApp(viewModel)
            }
        }
    }
}
