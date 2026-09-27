package com.smartguard.accessibility

import com.smartguard.util.SgLog
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.smartguard.policy.EmergencyApps
import com.smartguard.ui.FaceCheckActivity
import com.smartguard.ui.KidLauncherActivity
import com.smartguard.policy.ScreenTimeTracker
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.smartguard.SmartGuardApp
import com.smartguard.handover.VerificationLauncher
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.PolicyStore
import com.smartguard.policy.UserRole

/**
 * Enforcement + unlock detection.
 *
 * 1. UNLOCK DETECTION. vivo/iQOO filters the USER_PRESENT broadcast for background apps, so SmartGuard
 *    detects unlocks itself: it tracks screen/keyguard state (on every window event, plus a 1 s check
 *    while the device is awake) and fires a face check on the transition "screen off or locked" ->
 *    "screen on and unlocked". Works with swipe, PIN/pattern, or no lock screen. While this service is
 *    bound, Android also exempts SmartGuard from the background-launch block, so the check can appear.
 *
 * 2. ENFORCEMENT for CHILD/TEEN (enrolled or age-estimated guest): the parent's restricted apps,
 *    Settings/app stores/system tools (anti-bypass), curfews and the daily budget. A blocked app is
 *    simply closed (sent home and stopped) — no redirect to SmartGuard. Before anyone is verified
 *    (fail-safe mode) every user app is closed.
 *
 *    Never touched: home screen, keyboard, dialer/emergency, system UI, SmartGuard itself, and the
 *    event's HackTracker app.
 */
class SmartGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SG-A11y"
        private const val POLL_MS = 1_000L
        /** A gap this long between checks means the device was asleep (screen was off). */
        private const val SLEEP_GAP_MS = 4_000L

        @Volatile
        var instance: SmartGuardAccessibilityService? = null
            private set

        private val ALWAYS_ALLOWED = setOf(
            "com.smartguard",
            "android",
            "com.android.systemui",
            "com.reskill.hacktracker",              // event monitoring app: must never be interfered with
            // Emergency: always usable, even when screen time is over or nobody is verified.
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",    // Personal Safety / SOS
            "com.vivo.sos",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.vivo.upslide"                      // vivo control centre (its Settings shortcut is still blocked)
        )
    }

    private val policyStore: PolicyStore by lazy { PolicyStore(applicationContext) }
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var activityManager: ActivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var protectedPackages: Set<String> = ALWAYS_ALLOWED
    private val launchableCache = HashMap<String, Boolean>()

    private var cachedBlockKey: String? = null
    private var cachedBlockSet: Set<String> = emptySet()

    private var lastBlockedPackage: String? = null
    private var lastBlockTimestampMs = 0L

    // Unlock state machine.
    private var unlockArmed = false
    private var lastStateCheckElapsed = 0L

    private val screenTime: ScreenTimeTracker by lazy { ScreenTimeTracker(applicationContext) }

    /** Last non-system, non-emergency app seen in the foreground, closed the moment time runs out. */
    private var lastUserApp: String? = null

    /** Calls, messages, camera, WhatsApp, safety/SOS apps: never blocked for kids (see EmergencyApps). */
    private var emergencyPackages: Set<String> = emptySet()
    private var pollCount = 0L

    private val poller = object : Runnable {
        override fun run() {
            checkUnlock("poll")
            // Pick up newly installed emergency apps (e.g. WhatsApp) every 5 minutes.
            if (++pollCount % 300L == 0L) emergencyPackages = EmergencyApps.resolve(applicationContext)
            if (power.isInteractive) {
                when (screenTime.tick()) {
                    ScreenTimeTracker.Event.WARN_5_MIN -> toast("5 minutes of screen time left today")
                    ScreenTimeTracker.Event.WARN_1_MIN -> toast("1 minute of screen time left today")
                    ScreenTimeTracker.Event.EXHAUSTED -> onScreenTimeExhausted()
                    ScreenTimeTracker.Event.NONE -> Unit
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Time's up: close whatever app is open right now (enforcement otherwise only happens when an app
     * is opened), stop background media, and leave only calls/emergency, home and SmartGuard usable.
     */
    private fun onScreenTimeExhausted() {
        SgLog.i(TAG, "Screen time exhausted; closing ${lastUserApp ?: "nothing"}")
        // Never cut into a phone / WhatsApp call.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        if (!inCall) silenceMedia()
        val open = lastUserApp
        openKidHome(open, "Screen time is over for today. Calls, messages, camera and safety apps still work.")
        open?.let { pkg ->
            handler.postDelayed({ tryKill(pkg) }, 400L)
            handler.postDelayed({ tryKill(pkg) }, 2_000L)
        }
        lastUserApp = null
    }

    /** Taking permanent audio focus makes well-behaved players (YouTube, Spotify…) stop, not just duck. */
    private fun silenceMedia() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            audio.requestAudioFocus(request)
            // Release after other apps have paused; they don't auto-resume after a permanent loss.
            handler.postDelayed({ audio.abandonAudioFocusRequest(request) }, 1_500L)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop media", e)
        }
    }

    private fun tryKill(pkg: String) {
        try {
            activityManager.killBackgroundProcesses(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop $pkg", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Backup path: if the OEM does deliver USER_PRESENT, use it too (debounced downstream). */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked("broadcast")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        protectedPackages = ALWAYS_ALLOWED + devicePackagesToProtect()
        emergencyPackages = EmergencyApps.resolve(applicationContext)
        SgLog.i(TAG, "Emergency apps: $emergencyPackages")
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lastStateCheckElapsed = SystemClock.elapsedRealtime()
        handler.post(poller)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, handler)

        // vivo's i Manager removes this service when Settings opens; when SmartGuard re-enables itself,
        // the window event for that Settings screen has already passed. If a child/teen (or nobody
        // verified) is using the phone, close whatever is open so Settings can't stay on screen.
        val role = SmartGuardApp.instance.sessionState.value.activeRole
        if (role != UserRole.ADULT && power.isInteractive && !keyguard.isKeyguardLocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            SgLog.i(TAG, "Reconnected during a $role session -> returned to home screen")
        }
        SgLog.i(TAG, "Connected; protected=${protectedPackages.size} packages, unlock detection running")
    }

    // ---------------------------------------------------------------- screen-on / unlock checks
    //
    // 1. SCREEN ON: the display listener (a direct callback from the display service, which vivo does
    //    not filter, and which catches even a 0.3 s off/on) launches the face check immediately, on top
    //    of the lock screen, so recognition starts while the person is still reaching to swipe.
    // 2. UNLOCK: when the keyguard goes away, check again ONLY if nobody was verified since the screen
    //    came on (e.g. the screen-on check found no face because a notification woke the phone).

    private lateinit var displayManager: DisplayManager
    private var screenOnWallMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val on = displayManager.getDisplay(displayId)?.state == Display.STATE_ON
            val wasOn = displayOn
            displayOn = on
            // The listener also fires for refresh-rate / brightness changes while the screen stays on:
            // only an actual OFF -> ON transition starts a check.
            if (!on) {
                if (!unlockArmed) SgLog.i(TAG, "Screen off -> armed")
                unlockArmed = true
            } else if (!wasOn && unlockArmed) {
                screenOnWallMs = now()
                SgLog.i(TAG, "Screen on -> identity check over the lock screen")
                VerificationLauncher.launch(this@SmartGuardAccessibilityService, "screen on")
            }
        }
    }

    /** Last known display state (true = fully on). Starts as "on": the service connects while in use. */
    private var displayOn = true

    private fun now() = System.currentTimeMillis()

    /** Backup detection (poll + window events) in case a display callback is ever missed. */
    private fun checkUnlock(source: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val slept = nowElapsed - lastStateCheckElapsed > SLEEP_GAP_MS
        lastStateCheckElapsed = nowElapsed

        if (!power.isInteractive) {
            if (!unlockArmed) SgLog.i(TAG, "Screen off (poll) -> armed")
            unlockArmed = true
            return
        }
        if (slept) unlockArmed = true
        if (!keyguard.isKeyguardLocked) onUnlocked(source)
    }

    /** The phone is on and unlocked. Check unless someone was already verified since screen-on. */
    private fun onUnlocked(source: String) {
        if (!unlockArmed) return
        unlockArmed = false
        val lastVerdict = SmartGuardApp.instance.sessionState.value.lastHandoverTimestampMs
        if (screenOnWallMs > 0 && lastVerdict >= screenOnWallMs) {
            SgLog.i(TAG, "Unlocked ($source): already verified at screen-on, no second check")
            return
        }
        if (FaceCheckActivity.isRunning) {
            SgLog.i(TAG, "Unlocked ($source): face check already on screen")
            return
        }
        SgLog.i(TAG, "Unlock detected ($source) -> identity check")
        VerificationLauncher.launch(this, "unlock")
    }

    /** Called when a screen-on check closed without seeing any face: the unlock must still be checked. */
    fun rearmForUnlock() {
        unlockArmed = true
        screenOnWallMs = 0L
    }

    /** Home launcher(s), keyboards and default dialer of THIS device (e.g. com.bbk.launcher2 on vivo). */
    private fun devicePackagesToProtect(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
                .forEach { result += it.activityInfo.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "home query failed", e)
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { result += it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "ime query failed", e)
        }
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { result += it }
        } catch (e: Exception) {
            Log.w(TAG, "dialer query failed", e)
        }
        // Never protect the tools kids could use to switch SmartGuard off.
        return result - KidsPolicy.ALWAYS_BLOCKED
    }

    private fun isUserLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        checkUnlock("window")

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank() || packageName in protectedPackages) return

        val session = SmartGuardApp.instance.sessionState.value
        val role = session.activeRole
        if (role == UserRole.ADULT) return

        // Emergency apps are always usable: time's up, not verified, curfew or restricted list.
        if (packageName in emergencyPackages) {
            SgLog.i(TAG, "Allowed emergency app $packageName ($role, ${session.remainingScreenTimeSeconds}s left)")
            return
        }

        // Anti-bypass tools (Settings, stores, installer, i Manager) are checked BEFORE the
        // "user-launchable" filter because some of them (installer dialogs) have no launcher icon.
        if (packageName in KidsPolicy.ALWAYS_BLOCKED) {
            closeApp(packageName, "Settings and system tools are for parents only")
            return
        }
        if (packageName.startsWith("com.android.internal") || !isUserLaunchable(packageName)) return
        lastUserApp = packageName

        when {
            // Nobody verified yet (fail-safe): only the phone, keyboard and SmartGuard are usable.
            role == UserRole.UNKNOWN_RESTRICTIVE || session.isSessionLocked ->
                closeApp(packageName, "Verify your face in SmartGuard to use apps")
            // Time's up: every app is closed; only calls/emergency (protected above) remain.
            session.remainingScreenTimeSeconds <= 0 ->
                closeApp(packageName, "Screen time is over for today. Only calls, messages, camera and emergency apps are available.")
            packageName in blockedFor(session.activeProfile?.id, session.activeProfile?.blockedPackagesJson) ->
                closeApp(packageName, "This app is restricted by your parent")
            policyStore.isCurrentlyCurfewed(packageName, role) ->
                closeApp(packageName, "This app isn't allowed at this time")
        }
    }

    private fun blockedFor(profileId: Long?, json: String?): Set<String> {
        val key = "$profileId:$json"
        if (key != cachedBlockKey) {
            cachedBlockSet = KidsPolicy.parse(json)
            cachedBlockKey = key
        }
        return cachedBlockSet
    }

    /** Closes a restricted app: back to the home screen and stop it. No SmartGuard screen is shown. */
    private fun closeApp(packageName: String, reason: String) {
        val now = System.currentTimeMillis()
        val repeat = packageName == lastBlockedPackage && now - lastBlockTimestampMs < 1_500L
        lastBlockedPackage = packageName
        lastBlockTimestampMs = now

        // Cover the app with SmartGuard's kid-safe home (notice + only the allowed apps), then stop it.
        openKidHome(packageName, reason)
        handler.postDelayed({ tryKill(packageName) }, 400L)
        if (!repeat) SgLog.i(TAG, "Closed $packageName: $reason")
    }

    private fun openKidHome(blockedPackage: String?, reason: String) {
        try {
            startActivity(KidLauncherActivity.intent(this, blockedPackage, reason))
        } catch (e: Exception) {
            // Fallback: plain home screen + toast.
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        screenTime.flush()
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            // not registered
        }
        super.onDestroy()
    }
}
