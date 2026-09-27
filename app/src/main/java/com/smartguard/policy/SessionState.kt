package com.smartguard.policy

import com.smartguard.data.local.entity.ProfileEntity

/**
 * Represents the current active phone session state.
 * Persistent session state: once confirmed, active mode + screen time budget stay active
 * until the NEXT handover signal.
 */
data class SessionState(
    val activeProfile: ProfileEntity? = null,
    val activeRole: UserRole = UserRole.UNKNOWN_RESTRICTIVE,
    val screenTimeBudgetMinutes: Int = 30, // Restrictive default budget
    val remainingScreenTimeSeconds: Long = 1800L,
    val isSessionLocked: Boolean = false,
    val lastHandoverTimestampMs: Long = System.currentTimeMillis(),
    val confirmationCount: Int = 0
) {
    val isRestrictedMode: Boolean
        get() = activeRole == UserRole.CHILD || activeRole == UserRole.UNKNOWN_RESTRICTIVE
}
