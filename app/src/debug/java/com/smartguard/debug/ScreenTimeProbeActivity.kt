package com.smartguard.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.smartguard.SmartGuardApp
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.policy.SessionController
import com.smartguard.policy.SessionState
import com.smartguard.policy.UserRole

/**
 * DEBUG BUILDS ONLY. Starts a temporary CHILD test session with a few seconds of screen time left,
 * so the "time's up" behaviour can be tested without a child's face. Uses a synthetic profile
 * (negative id) so no real profile's usage is touched.
 *
 *   adb shell am start -n com.smartguard/.debug.ScreenTimeProbeActivity --ei seconds 15
 *   adb shell am start -n com.smartguard/.debug.ScreenTimeProbeActivity --ez reset true
 */
class ScreenTimeProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra("capture")) {
            // Debug-only: save liveness frames for offline tuning (true) / stop (false).
            val on = intent.getBooleanExtra("capture", false)
            com.smartguard.recognition.liveness.LivenessCapture.setOn(applicationContext, on)
            com.smartguard.util.SgLog.i(TAG, "Debug: liveness capture ${if (on) "on" else "off"}")
        } else if (intent.getBooleanExtra("handoverpicker", false)) {
            // Debug-only: parent session + open the hand-over app picker (it isn't exported).
            val owner = kotlinx.coroutines.runBlocking { SmartGuardApp.instance.profileRepository.getOwnerProfile() }
            if (owner != null) SessionController.applyConfirmedMatch(owner)
            startActivity(android.content.Intent(this, com.smartguard.ui.handover.HandoverPickerActivity::class.java))
        } else if (intent.getBooleanExtra("endhandover", false)) {
            // Debug-only: end a hand-over without a face (clearing the lock-task list unpins the task).
            com.smartguard.handover.HandoverSession.end(applicationContext)
            runCatching {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                dpm.setLockTaskPackages(
                    android.content.ComponentName(this, com.smartguard.deviceowner.SmartGuardDeviceAdminReceiver::class.java),
                    emptyArray()
                )
            }
            com.smartguard.util.SgLog.i(TAG, "Debug: hand-over ended")
        } else if (intent.hasExtra("kiosk")) {
            // Debug-only: switch Kiosk mode on/off for the first enrolled child and (on) start their session.
            val on = intent.getBooleanExtra("kiosk", false)
            val child = kotlinx.coroutines.runBlocking {
                SmartGuardApp.instance.profileRepository.getAllProfiles().firstOrNull { it.role.equals("CHILD", true) }
            }
            if (child != null) {
                com.smartguard.policy.KioskStore(applicationContext).set(child.id, on)
                if (on) SessionController.applyConfirmedMatch(child)
            }
            com.smartguard.util.SgLog.i(TAG, "Debug kiosk=$on for ${child?.name}")
        } else if (intent.getBooleanExtra("parent", false)) {
            // Debug-only: start a parent session with the owner profile (for UI testing).
            val owner = kotlinx.coroutines.runBlocking { SmartGuardApp.instance.profileRepository.getOwnerProfile() }
            if (owner != null) SessionController.applyConfirmedMatch(owner)
            com.smartguard.util.SgLog.i(TAG, "Debug parent session: ${owner?.name}")
        } else if (intent.getBooleanExtra("reset", false)) {
            SessionController.applyFailSafe("Screen-time test finished")
            com.smartguard.util.SgLog.i(TAG, "Session reset to restricted (verify to continue)")
        } else {
            val seconds = intent.getIntExtra("seconds", 15).toLong()
            val child = ProfileEntity(
                id = -99L,
                name = "Screen-time test child",
                role = UserRole.CHILD.name,
                screenTimeBudgetMinutes = 1,
                allowedPackagesJson = "[]",
                blockedPackagesJson = "[]"
            )
            SmartGuardApp.instance.updateSessionState(
                SessionState(
                    activeProfile = child,
                    activeRole = UserRole.CHILD,
                    screenTimeBudgetMinutes = 1,
                    remainingScreenTimeSeconds = seconds,
                    isSessionLocked = false,
                    lastHandoverTimestampMs = System.currentTimeMillis()
                )
            )
            com.smartguard.util.SgLog.i(TAG, "Test CHILD session started with ${seconds}s of screen time")
        }
        finish()
    }

    companion object {
        private const val TAG = "SG-TimeProbe"
    }
}
