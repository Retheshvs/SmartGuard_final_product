package com.smartguard.policy

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent per-profile screen-time usage for the current day (on-device SharedPreferences).
 *
 * Remaining time is always budget - usedToday, so re-verifying (or restarting the app) can never
 * top the budget back up. Counters roll over automatically at midnight because keys include the date.
 */
class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("smartguard_usage", Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun key(profileId: Long) = "used_${today()}_$profileId"

    fun usedSecondsToday(profileId: Long): Long = prefs.getLong(key(profileId), 0L)

    fun addSeconds(profileId: Long, seconds: Long) {
        val k = key(profileId)
        prefs.edit().putLong(k, prefs.getLong(k, 0L) + seconds).apply()
    }

    fun resetToday(profileId: Long) {
        prefs.edit().remove(key(profileId)).apply()
    }

    /** Remaining seconds today for a profile with [budgetMinutes] (0 = unlimited). */
    fun remainingSeconds(profileId: Long, budgetMinutes: Int): Long =
        if (budgetMinutes <= 0) UNLIMITED else (budgetMinutes * 60L - usedSecondsToday(profileId)).coerceAtLeast(0L)

    /** Drops counters from previous days so the file never grows unbounded. */
    fun pruneOldDays() {
        val prefix = "used_${today()}_"
        val stale = prefs.all.keys.filter { it.startsWith("used_") && !it.startsWith(prefix) }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    companion object {
        const val UNLIMITED = 999_999L
    }
}
