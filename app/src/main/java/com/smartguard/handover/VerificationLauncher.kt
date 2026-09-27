package com.smartguard.handover

import com.smartguard.util.SgLog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.accessibility.SmartGuardAccessibilityService
import com.smartguard.policy.SessionController
import com.smartguard.ui.FaceCheckActivity

/**
 * Decides HOW to run an identity check when a handover signal fires (unlock / pick-up).
 *
 * Android 14+ blocks background apps from opening screens or the camera. Paths, in order:
 * 1. Device Owner -> invisible camera foreground service (exempt from the restriction).
 * 2. SmartGuard's AccessibilityService enabled -> open [FaceCheckActivity] (exempt while bound).
 * 3. Neither -> a high-priority "tap to verify" notification (full-screen where permitted).
 *    Opening a screen here would be silently blocked by Android, which is why checks at unlock
 *    appeared to "do nothing" before.
 */
object VerificationLauncher {

    private const val TAG = "SG-Launcher"
    private const val MIN_GAP_MS = 8_000L
    /** After someone is confirmed, ignore motion-only triggers for a while (they're using it). */
    private const val MOTION_COOLDOWN_AFTER_MATCH_MS = 60_000L
    private const val CHANNEL_ID = "smartguard_check_prompt"
    private const val NOTIFICATION_ID = 3003

    @Volatile
    private var lastLaunchMs = 0L

    fun launch(context: Context, reason: String) {
        if (HandoverSession.isActive(context)) {
            SgLog.i(TAG, "Skipping check ($reason): phone is handed over")
            return
        }
        val now = System.currentTimeMillis()
        // Screen-on / unlock checks only need de-duplicating (both can fire for one unlock);
        // a quick off/on seconds later must still be checked. Motion triggers keep a longer gap.
        val gap = if (reason == "screen on" || reason == "unlock") 1_500L else MIN_GAP_MS
        if (now - lastLaunchMs < gap) {
            SgLog.i(TAG, "Skipping check ($reason): last one ${now - lastLaunchMs}ms ago")
            return
        }
        if (reason == "handover motion") {
            val session = SmartGuardApp.instance.sessionState.value
            if (session.activeProfile != null && now - session.lastHandoverTimestampMs < MOTION_COOLDOWN_AFTER_MATCH_MS) {
                SgLog.i(TAG, "Skipping motion check: ${session.activeProfile.name} verified ${(now - session.lastHandoverTimestampMs) / 1000}s ago")
                return
            }
        }
        lastLaunchMs = now

        // 1. Preferred (tested, shows the result): on-screen check, allowed while accessibility is on.
        val a11y = SmartGuardAccessibilityService.instance
        if (a11y != null) {
            SgLog.i(TAG, "Check ($reason) via on-screen face check [accessibility exemption]")
            launchVisibleCheck(a11y, force = true, trigger = reason)
            return
        }

        // 2. Accessibility is off (e.g. vivo i Manager switched it off): a Device Owner may still
        //    use the camera from the background, so run the invisible camera-service check.
        if (isDeviceOwner(context)) {
            SgLog.i(TAG, "Check ($reason) via background camera service [Device Owner]")
            val intent = Intent(context, VerificationForegroundService::class.java).apply {
                action = VerificationForegroundService.ACTION_START_VERIFICATION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "FGS start refused, falling back", e)
            }
        }

        // 3. Neither: Android would silently block a background screen, so post a prompt instead.
        run {
            SgLog.w(TAG, "Check ($reason): accessibility service OFF -> Android blocks background screens; posting prompt")
            // Fail-safe: until someone verifies, the phone is in restricted mode.
            SessionController.applyFailSafe("Waiting for face verification")
            postVerifyPrompt(context)
        }
    }

    /** Opens the on-screen face check. [force] bypasses the debounce (used for manual "Verify"). */
    fun launchVisibleCheck(context: Context, force: Boolean = false, trigger: String = "manual") {
        if (force) lastLaunchMs = System.currentTimeMillis()
        try {
            context.startActivity(checkIntent(context).putExtra(FaceCheckActivity.EXTRA_TRIGGER, trigger))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open face check", e)
        }
    }

    private fun checkIntent(context: Context) = Intent(context, FaceCheckActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private fun postVerifyPrompt(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Identity check prompts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0, checkIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_smartguard)
            .setContentTitle("Who's using the phone?")
            .setContentText("Tap to verify. Restricted mode stays on until then.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    fun isDeviceOwner(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }
}
