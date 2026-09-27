package com.smartguard.handover

import android.content.Context
import android.os.SystemClock

/**
 * A parent's hand-over: the phone is lent to someone with only the apps the parent picked, pinned in
 * kiosk mode, until a parent's face ends it. Face checks pause meanwhile (the parent already decided
 * what this person may use), so they aren't interrupted by checks or Guest mode.
 *
 * Kept in prefs so it survives SmartGuard being restarted, but not a reboot (kiosk pinning ends on a
 * reboot, so the normal face check takes over again).
 */
object HandoverSession {

    private const val PREFS = "smartguard_handover"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_PARENT = "parent"
    private const val KEY_BOOT = "boot_time"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Wall-clock time of the last boot; changes only when the phone restarts. */
    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    fun isActive(c: Context): Boolean {
        val p = prefs(c)
        if (!p.contains(KEY_PACKAGES)) return false
        if (kotlin.math.abs(p.getLong(KEY_BOOT, 0L) - bootTime()) > 60_000L) {
            end(c) // rebooted since the hand-over started
            return false
        }
        return true
    }

    fun packages(c: Context): Set<String> = prefs(c).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun parentName(c: Context): String? = prefs(c).getString(KEY_PARENT, null)

    fun start(c: Context, packages: Set<String>, parentName: String?) {
        prefs(c).edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putString(KEY_PARENT, parentName)
            .putLong(KEY_BOOT, bootTime())
            .commit()
    }

    fun end(c: Context) {
        prefs(c).edit().clear().commit()
    }
}
