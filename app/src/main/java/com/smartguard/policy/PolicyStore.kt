package com.smartguard.policy

import android.content.Context
import java.util.Calendar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists active curfew rules locally (SharedPreferences, on-device only) so the
 * AccessibilityService can enforce time-window restrictions produced by the Policy Assistant.
 */
class PolicyStore(context: Context) {

    private val prefs = context.getSharedPreferences("smartguard_policies", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getCurfews(): List<CurfewRule> {
        val json = prefs.getString(KEY_CURFEWS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CurfewRule>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addCurfews(newRules: List<CurfewRule>) {
        if (newRules.isEmpty()) return
        // Replace any existing rule for the same (role, category) pair.
        val merged = getCurfews()
            .filterNot { existing ->
                newRules.any { it.category == existing.category && it.targetRole == existing.targetRole }
            } + newRules
        prefs.edit().putString(KEY_CURFEWS, gson.toJson(merged)).apply()
    }

    fun curfewsForRole(role: String): List<CurfewRule> =
        getCurfews().filter { it.targetRole.equals(role, ignoreCase = true) }

    fun removeCurfew(rule: CurfewRule) {
        val remaining = getCurfews().filterNot { it == rule }
        prefs.edit().putString(KEY_CURFEWS, gson.toJson(remaining)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_CURFEWS).apply()
    }

    /**
     * Returns true if [packageName] is currently curfewed for [role] at this moment in time.
     */
    fun isCurrentlyCurfewed(packageName: String, role: UserRole): Boolean {
        val category = AppCategories.categoryOf(packageName) ?: return false
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK) // Sunday=1 .. Saturday=7

        return getCurfews().any { rule ->
            rule.category.equals(category, ignoreCase = true) &&
                rule.targetRole.equals(role.name, ignoreCase = true) &&
                hour >= rule.afterHour &&
                dayScopeMatches(rule.days, dayOfWeek)
        }
    }

    private fun dayScopeMatches(scope: DayScope, dayOfWeek: Int): Boolean = when (scope) {
        DayScope.DAILY -> true
        // School nights = Sun(1), Mon(2), Tue(3), Wed(4), Thu(5)
        DayScope.SCHOOL_NIGHTS -> dayOfWeek in intArrayOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY
        )
        // Weekends = Fri(6), Sat(7)
        DayScope.WEEKENDS -> dayOfWeek in intArrayOf(Calendar.FRIDAY, Calendar.SATURDAY)
    }

    companion object {
        private const val KEY_CURFEWS = "curfew_rules_json"
    }
}
