package com.weekd.miracastreceiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,

            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",

            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return

        if (!AppSettings.isAutoStartOnBoot(context)) {
            Timber.i("Boot received ($action) but auto-start is disabled")
            return
        }

        val serviceIntent = Intent(context, CastReceiverService::class.java).apply {
            putExtra(CastReceiverService.EXTRA_FROM_BOOT, true)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.i("Boot received ($action), cast receiver service starting")
        } catch (e: Exception) {

            Timber.e(e, "Failed to start cast service from $action")
        }
    }
}
