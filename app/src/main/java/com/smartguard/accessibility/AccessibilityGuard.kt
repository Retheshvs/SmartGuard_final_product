package com.smartguard.accessibility

import com.smartguard.util.SgLog
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartguard.R

/**
 * Keeps SmartGuard's accessibility service switched on.
 *
 * Android removes an app's accessibility service from the enabled list whenever the app is
 * FORCE-STOPPED — and vivo/iQOO's background manager force-stops apps that aren't exempt from
 * battery optimisation. That is why the service "kept turning off".
 *
 * Defences:
 *  1. Battery-optimisation exemption (asked for in setup) so the OS stops killing the app.
 *  2. Self-heal: if the parent granted WRITE_SECURE_SETTINGS once over adb, SmartGuard re-adds its
 *     own service to the enabled list (other services such as HackTracker's are preserved as-is).
 *  3. Otherwise: fail-safe restricted mode + a notification asking the parent to turn it back on.
 */
object AccessibilityGuard {

    private const val TAG = "SG-Guard"
    private const val CHANNEL_ID = "smartguard_guard"
    private const val NOTIFICATION_ID = 4004

    fun component(context: Context) =
        ComponentName(context, SmartGuardAccessibilityService::class.java).flattenToString()

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = component(context)
        return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
    }

    fun canSelfHeal(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Android's battery-optimisation exemption (the list HackTracker is on). Stops vivo freezing us. */
    fun isBatteryExempt(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * vivo's own "allow background activity / high background power" switch. This is a separate
     * setting from [isBatteryExempt]; checking only the latter made the UI say "off" when it was on.
     */
    fun isBackgroundAllowed(context: Context): Boolean =
        !(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isBackgroundRestricted

    /**
     * Makes sure the service is enabled. Returns true if it is (or was just re-enabled).
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context)) {
            cancelNotification(context)
            return true
        }
        if (canSelfHeal(context)) {
            return try {
                val resolver = context.contentResolver
                val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val updated = if (current.isBlank()) component(context) else "$current:${component(context)}"
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                SgLog.i(TAG, "Accessibility service was off -> re-enabled automatically")
                cancelNotification(context)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Self-heal failed", e)
                notifyDisabled(context)
                false
            }
        }
        SgLog.w(TAG, "Accessibility service is off and self-heal isn't granted")
        notifyDisabled(context)
        return false
    }

    private fun notifyDisabled(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_HIGH))
        }
        // Straight to the Accessibility page so a parent can switch SmartGuard back on in one tap.
        val pi = PendingIntent.getActivity(
            context, 1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_smartguard)
            .setContentTitle("SmartGuard protection is off")
            .setContentText("vivo i Manager switched it off. Tap to turn SmartGuard back on.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }

    private fun cancelNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}
