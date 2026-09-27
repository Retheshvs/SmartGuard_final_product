package com.smartguard.policy

import android.content.Context
import com.smartguard.SmartGuardApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Counts screen time for the active CHILD/TEEN (or age-estimated guest) session, once per second
 * while the screen is on. Driven by the AccessibilityService, which Android keeps bound while
 * enabled (vivo's freezer stops ordinary background services, which used to stop the clock).
 *
 * Per-profile daily usage is persisted (buffered, flushed every 15 s) so re-verifying can never
 * refill the budget, and the budget refreshes automatically at midnight.
 */
class ScreenTimeTracker(context: Context) {

    /** Events the caller reacts to. */
    enum class Event { NONE, WARN_5_MIN, WARN_1_MIN, EXHAUSTED }

    private val usage = UsageStore(context.applicationContext).also { it.pruneOldDays() }
    private var pendingProfileId: Long? = null
    private var pendingSeconds = 0L
    private var day = today()

    /** Call once per second while the screen is on. */
    fun tick(): Event {
        val app = SmartGuardApp.instance
        var state = app.sessionState.value
        if (state.activeRole == UserRole.ADULT || state.isSessionLocked) return Event.NONE

        // New day: refresh an enrolled profile's budget from today's (empty) usage.
        val now = today()
        if (now != day) {
            flush()
            day = now
            usage.pruneOldDays()
            state.activeProfile?.takeIf { it.id > 0 }?.let { p ->
                state = state.copy(remainingScreenTimeSeconds = usage.remainingSeconds(p.id, p.screenTimeBudgetMinutes))
                app.updateSessionState(state)
            }
        }

        if (state.remainingScreenTimeSeconds <= 0L) return Event.NONE

        val profileId = state.activeProfile?.id
        if (profileId != pendingProfileId) {
            flush()
            pendingProfileId = profileId
        }
        // Enrolled profiles only; age-estimated guests (negative ids) have a per-session budget.
        if (profileId != null && profileId > 0) pendingSeconds++
        if (pendingSeconds >= 15) flush()

        val remaining = (state.remainingScreenTimeSeconds - 1L).coerceAtLeast(0L)
        app.updateSessionState(state.copy(remainingScreenTimeSeconds = remaining))

        return when (remaining) {
            0L -> { flush(); Event.EXHAUSTED }
            60L -> Event.WARN_1_MIN
            300L -> Event.WARN_5_MIN
            else -> Event.NONE
        }
    }

    fun flush() {
        pendingProfileId?.let { if (it > 0 && pendingSeconds > 0) usage.addSeconds(it, pendingSeconds) }
        pendingSeconds = 0L
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
