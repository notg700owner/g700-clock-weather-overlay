package com.g700.automation.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.g700.automation.startup.StartupProtectionManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> StartupProtectionManager.handleBootCompleted(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> StartupProtectionManager.handlePackageReplaced(context)
            StartupProtectionManager.ACTION_DELAYED_START -> StartupProtectionManager.handleDelayedStart(context)
            StartupProtectionManager.ACTION_STARTUP_TIMEOUT -> {
                val token = intent.getLongExtra(StartupProtectionManager.EXTRA_ATTEMPT_TOKEN, 0L)
                StartupProtectionManager.handleStartupTimeout(context, token)
            }
        }
    }
}
