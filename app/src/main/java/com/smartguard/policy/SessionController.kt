package com.smartguard.policy

import com.smartguard.SmartGuardApp
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.recognition.model.FaceMatchResult

/**
 * Single place that turns a recognition result into session state, shared by the background
 * verification service and the visible FaceCheckActivity so both behave identically.
 *
 * Fail-safe philosophy: anything other than a confirmed match lands in a restricted mode.
 * Unrecognized people get Guest mode (limited apps); a stranger never gets parent (ADULT) access.
 */
object SessionController {

    /** Final outcome of a verification attempt, or null while still scanning. */
    data class Outcome(val title: String, val detail: String, val matched: Boolean, val guest: Boolean = false) {
        /** One-line form for toasts and logs. */
        val message: String get() = "$title — $detail"
    }

    private fun roleLabel(role: UserRole) = when (role) {
        UserRole.CHILD -> "Child mode"
        UserRole.TEEN -> "Teen mode"
        UserRole.ADULT -> "Parent mode"
        UserRole.UNKNOWN_RESTRICTIVE -> "Restricted"
    }

    /** Synthetic profile id for Guest mode (never stored in the database). */
    const val GUEST_ID = -1L
    const val GUEST_BUDGET_MINUTES = 30

    private val usage by lazy { UsageStore(SmartGuardApp.instance) }

    suspend fun resolve(result: FaceMatchResult): Outcome? = when (result) {
        is FaceMatchResult.ConfirmedMatch -> applyConfirmedMatch(result.profile, result.similarityScore)
        is FaceMatchResult.NoMatch -> applyGuestMode(result.reason)
        is FaceMatchResult.SpoofSuspected -> applyFailSafe("Spoof suspected (photo/screen replay)")
        is FaceMatchResult.LivenessFailed -> applyFailSafe("Liveness check not passed: ${result.reason}")
        is FaceMatchResult.MarginFailed,
        is FaceMatchResult.LowConfidence,
        FaceMatchResult.UnknownFace -> applyFailSafe("No enrolled faces to match")
        is FaceMatchResult.Error -> applyFailSafe("Recognition error")
        // Keep scanning: confirming, still evaluating, poor frame, or no face in view yet.
        is FaceMatchResult.PendingMatch,
        is FaceMatchResult.LivenessChallenge,
        is FaceMatchResult.Scanning,
        is FaceMatchResult.LowQualityFrame,
        FaceMatchResult.NoFaceDetected -> null
    }

    fun applyConfirmedMatch(profile: ProfileEntity, score: Float? = null): Outcome {
        val role = UserRole.fromString(profile.role)
        val remaining = if (role == UserRole.ADULT) UsageStore.UNLIMITED
        else usage.remainingSeconds(profile.id, profile.screenTimeBudgetMinutes)

        SmartGuardApp.instance.updateSessionState(
            SessionState(
                activeProfile = profile,
                activeRole = role,
                screenTimeBudgetMinutes = profile.screenTimeBudgetMinutes,
                remainingScreenTimeSeconds = remaining,
                isSessionLocked = false,
                lastHandoverTimestampMs = System.currentTimeMillis()
            )
        )
        val scoreText = score?.let { " · %.0f%% match".format(it * 100) } ?: ""
        val timeText = if (role == UserRole.ADULT) "" else " · ${remaining / 60} min left today"
        return Outcome("Welcome, ${profile.name}", "${roleLabel(role)}$timeText$scoreText", matched = true)
    }

    /**
     * Unrecognized face (adult or child alike) -> Guest mode.
     *
     * Guests get safe, child-level rules: Settings / app stores blocked, everything any child or teen
     * is restricted from, plus social media and browsers (so a visitor can't open the family's
     * logged-in accounts). Emergency apps always stay available. A stranger never gets parent access.
     */
    suspend fun applyGuestMode(reason: String = ""): Outcome {
        val profiles = SmartGuardApp.instance.profileRepository.getAllProfiles()
        val blocked = profiles
            .filter { UserRole.fromString(it.role) != UserRole.ADULT }
            .flatMap { KidsPolicy.parse(it.blockedPackagesJson) }
            .toSet() + KidsPolicy.defaultBlockedFor(UserRole.CHILD)

        val guest = ProfileEntity(
            id = GUEST_ID,
            name = "Guest",
            role = UserRole.CHILD.name,
            screenTimeBudgetMinutes = GUEST_BUDGET_MINUTES,
            allowedPackagesJson = "[]",
            blockedPackagesJson = KidsPolicy.toJson(blocked)
        )
        SmartGuardApp.instance.updateSessionState(
            SessionState(
                activeProfile = guest,
                activeRole = UserRole.CHILD,
                screenTimeBudgetMinutes = GUEST_BUDGET_MINUTES,
                remainingScreenTimeSeconds = GUEST_BUDGET_MINUTES * 60L,
                isSessionLocked = false,
                lastHandoverTimestampMs = System.currentTimeMillis()
            )
        )
        return Outcome(
            "Guest mode",
            "Face not recognised · limited apps for $GUEST_BUDGET_MINUTES min",
            matched = false,
            guest = true
        )
    }

    fun applyFailSafe(reason: String): Outcome {
        SmartGuardApp.instance.updateSessionState(
            SessionState(
                activeProfile = null,
                activeRole = UserRole.UNKNOWN_RESTRICTIVE,
                screenTimeBudgetMinutes = 15,
                remainingScreenTimeSeconds = 900L,
                isSessionLocked = true,
                lastHandoverTimestampMs = System.currentTimeMillis()
            )
        )
        return Outcome("Couldn't verify", "$reason · apps stay locked", matched = false)
    }
}
