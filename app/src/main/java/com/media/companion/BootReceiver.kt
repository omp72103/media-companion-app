package com.media.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** Auto-restarts the monitor after a reboot so the owner never has to. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = Config(context)
        if (!cfg.isConfigured()) return
        val svc = Intent(context, CallMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ContextCompat.startForegroundService(context, svc)
        else
            context.startService(svc)
    }
}