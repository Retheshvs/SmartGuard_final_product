package com.smartguard.handover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot receiver: brings up [SmartGuardMonitorService] after a reboot.
 *
 * Unlock (USER_PRESENT) and screen-on events are NOT delivered to manifest receivers on
 * Android 8+, so the monitor service registers for them at runtime and triggers verification.
 */
class UnlockBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val serviceIntent = Intent(context, SmartGuardMonitorService::class.java).apply {
            action = SmartGuardMonitorService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("SG-Boot", "Could not start monitor after boot", e)
        }
    }
}
