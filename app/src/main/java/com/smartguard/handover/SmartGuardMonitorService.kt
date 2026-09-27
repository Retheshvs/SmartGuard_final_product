package com.smartguard.handover

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.smartguard.accessibility.AccessibilityGuard
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.policy.UsageStore
import com.smartguard.policy.UserRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on lightweight monitor. Owns two responsibilities that the rest of the app relies on:
 *
 * 1. Screen-time ticker — decrements the active session's remaining budget once per second for
 *    any non-Adult profile, so time limits are actually enforced (the AccessibilityService blocks
 *    launches once the budget hits zero). Persistent session state survives until the next handover.
 *
 * 2. Handover signal — registers [HandoverSensorListener] (accelerometer pick-up pattern) and
 *    kicks off event-triggered verification when the phone changes hands. This is the on-device,
 *    battery-friendly alternative to continuously polling the camera.
 *
 * Runs as a foreground service so it is not killed in the background. No camera is held here —
 * the camera only opens briefly inside [VerificationForegroundService] during a verification.
 */
class SmartGuardMonitorService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.smartguard.action.START_MONITOR"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "smartguard_monitor_channel"
    }

    private var sensorListener: HandoverSensorListener? = null
    private var tickerJob: Job? = null

    /**
     * USER_PRESENT (device unlocked) is only delivered to runtime-registered receivers on
     * Android 8+, so it is registered here rather than in the manifest.
     */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                VerificationLauncher.launch(context, "unlock")
            }
        }
    }
    private var unlockReceiverRegistered = false

    /** Fires the moment anything changes the enabled-accessibility list (e.g. OS disabling us). */
    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            AccessibilityGuard.ensureEnabled(applicationContext)
        }
    }
    private var observerRegistered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (sensorListener == null) {
            sensorListener = HandoverSensorListener(applicationContext) {
                onHandoverDetected()
            }.also { it.startListening() }
        }

        if (!unlockReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            unlockReceiverRegistered = true
        }

        if (!observerRegistered) {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver
            )
            observerRegistered = true
        }
        AccessibilityGuard.ensureEnabled(applicationContext)

        if (tickerJob == null || tickerJob?.isActive != true) {
            tickerJob = lifecycleScope.launch { runScreenTimeTicker() }
        }

        return START_STICKY
    }

    /**
     * Pick-up / hand-over motion. Only acts while the screen is on and the phone is unlocked
     * (i.e. someone is actively holding a usable phone); a locked phone is checked on unlock instead.
     */
    private fun onHandoverDetected() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        VerificationLauncher.launch(this, "handover motion")
    }

    /**
     * Backstop that re-checks the accessibility service every 30 s. Screen time itself is counted by
     * the AccessibilityService (see ScreenTimeTracker): vivo's freezer stops this service, which used
     * to stop the clock so a child's time never ran out.
     */
    private suspend fun runScreenTimeTicker() {
        while (lifecycleScope.isActive) {
            delay(30_000L)
            AccessibilityGuard.ensureEnabled(applicationContext)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SmartGuard Monitor",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartGuard active")
            .setContentText("Guarding this device on-device. No data leaves the phone.")
            .setSmallIcon(R.drawable.ic_stat_smartguard)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorListener?.stopListening()
        sensorListener = null
        tickerJob?.cancel()
        if (unlockReceiverRegistered) {
            unregisterReceiver(unlockReceiver)
            unlockReceiverRegistered = false
        }
        if (observerRegistered) {
            contentResolver.unregisterContentObserver(accessibilityObserver)
            observerRegistered = false
        }
    }
}
