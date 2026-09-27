package com.smartguard.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.smartguard.SmartGuardApp
import com.smartguard.policy.EmergencyApps
import com.smartguard.accessibility.SmartGuardAccessibilityService
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.KioskStore
import com.smartguard.ui.KidLauncherActivity
import com.smartguard.policy.PolicyStore
import com.smartguard.policy.SessionState
import com.smartguard.policy.UserRole
import com.smartguard.util.SgLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * System-level enforcement that only a Device Owner can do. Re-applied every time the session changes.
 *
 * 1. APP PAUSING: restricted apps are suspended by Android itself (greyed icon, "app paused" dialog).
 *    Unlike the AccessibilityService, this keeps working if vivo switches accessibility off, freezes
 *    SmartGuard, or the phone reboots.
 *      - parent            -> nothing paused
 *      - child/teen/guest  -> their restricted apps + curfewed apps + Settings / app stores / system tools
 *      - time's up / nobody verified -> every app except emergency apps
 * 2. CHILD-SESSION PHONE RULES: no installing/uninstalling apps, no force-stop / clear data, no date/time
 *    changes (defeats curfew and limit cheating), no adding users. Cleared for a parent.
 *
 * Never paused: SmartGuard, HackTracker (the event's monitoring app), the home screen, keyboards, the
 * dialer and emergency apps. [releaseAll] undoes everything (used by "Release Device Owner").
 */
object DeviceRules {

    private const val TAG = "SG-DeviceRules"
    private const val PREFS = "smartguard_device_rules"
    private const val KEY_SUSPENDED = "suspended_packages"

    private val CHILD_RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_ADD_USER
    )

    private val NEVER_PAUSE = setOf(
        "com.smartguard",
        "com.reskill.hacktracker",
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts following the session. Safe to call once from Application.onCreate. */
    fun start(app: SmartGuardApp) {
        scope.launch {
            app.sessionState
                .distinctUntilChangedBy { s -> key(s) }
                .collectLatest { s -> applyFor(app, s) }
        }
    }

    /** Only the parts of the session that change what should be paused (not every one-second tick). */
    private fun key(s: SessionState): List<Any?> = listOf(
        s.activeRole,
        s.isSessionLocked,
        s.remainingScreenTimeSeconds <= 0,
        s.activeProfile?.id,
        s.activeProfile?.blockedPackagesJson,
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY) // curfews change on the hour
    )

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(context: Context) = ComponentName(context, SmartGuardDeviceAdminReceiver::class.java)
    private fun isOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    @Synchronized
    fun applyFor(context: Context, session: SessionState) {
        if (!isOwner(context)) return
        try {
            val parent = session.activeRole == UserRole.ADULT
            val desired = if (parent) emptySet() else desiredPaused(context, session)
            val actual = setPaused(context, desired)
            setChildRestrictions(context, enabled = !parent)
            openKidHomeIfKiosk(context, session)
            SgLog.i(TAG, "${session.activeRole}${if (session.isSessionLocked) "/locked" else ""}: ${actual.size} apps paused, child rules ${if (parent) "off" else "on"}")
        } catch (e: Exception) {
            SgLog.w(TAG, "Applying device rules failed: ${e.message}")
        }
    }

    private fun desiredPaused(context: Context, session: SessionState): Set<String> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

        val keep = NEVER_PAUSE + EmergencyApps.resolve(context) + deviceEssentials(context)
        val lockAll = session.activeRole == UserRole.UNKNOWN_RESTRICTIVE ||
            session.isSessionLocked || session.remainingScreenTimeSeconds <= 0

        val candidates = if (lockAll) {
            launchable
        } else {
            val curfews = PolicyStore(context)
            KidsPolicy.parse(session.activeProfile?.blockedPackagesJson) +
                launchable.filter { curfews.isCurrentlyCurfewed(it, session.activeRole) }
        }
        return (candidates + KidsPolicy.ALWAYS_BLOCKED) - keep
    }

    /** A child with Kiosk mode on: bring up their pinned home screen (it starts lock task itself). */
    private fun openKidHomeIfKiosk(context: Context, session: SessionState) {
        if (session.activeRole != UserRole.CHILD || session.isSessionLocked) return
        if (!KioskStore(context).isOn(session.activeProfile?.id)) return
        if (KidLauncherActivity.isVisible) return
        val launcher = SmartGuardAccessibilityService.instance ?: context
        try {
            launcher.startActivity(KidLauncherActivity.intent(launcher, null, null))
        } catch (e: Exception) {
            SgLog.w(TAG, "Couldn't open kiosk home: ${e.message}")
        }
    }

    /** Home launcher(s), keyboards and the default dialer of this phone. */
    private fun deviceEssentials(context: Context): Set<String> {
        val pm = context.packageManager
        val out = mutableSetOf<String>()
        try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                .forEach { out += it.activityInfo.packageName }
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.forEach { out += it.packageName }
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { out += it }
        } catch (e: Exception) { /* best effort */ }
        return out
    }

    /** Moves Android's paused set to [desired]; returns what is actually paused (some apps can't be). */
    private fun setPaused(context: Context, desired: Set<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        val dpm = dpm(context)
        val admin = admin(context)

        val release = current - desired
        if (release.isNotEmpty()) dpm.setPackagesSuspended(admin, release.toTypedArray(), false)

        val add = desired - current
        val failed = if (add.isNotEmpty()) dpm.setPackagesSuspended(admin, add.toTypedArray(), true).toSet() else emptySet()

        val actual = desired - failed
        prefs.edit().putStringSet(KEY_SUSPENDED, actual).apply()
        return actual
    }

    private fun setChildRestrictions(context: Context, enabled: Boolean) {
        val dpm = dpm(context)
        val admin = admin(context)
        CHILD_RESTRICTIONS.forEach {
            if (enabled) dpm.addUserRestriction(admin, it) else dpm.clearUserRestriction(admin, it)
        }
    }

    /** Undo everything: un-pause every app SmartGuard paused and remove child-session rules. */
    @Synchronized
    fun releaseAll(context: Context) {
        if (!isOwner(context)) return
        try {
            setPaused(context, emptySet())
            setChildRestrictions(context, enabled = false)
            SgLog.i(TAG, "All device rules released")
        } catch (e: Exception) {
            SgLog.w(TAG, "Release failed: ${e.message}")
        }
    }

    /** Locks the screen straight away (e.g. after a failed face check). */
    fun lockNow(context: Context) {
        if (!isOwner(context)) return
        try {
            dpm(context).lockNow()
            SgLog.i(TAG, "Screen locked")
        } catch (e: Exception) {
            SgLog.w(TAG, "lockNow failed: ${e.message}")
        }
    }
}
