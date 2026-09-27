package com.smartguard.recognition.model

import com.smartguard.data.local.entity.ProfileEntity

sealed class FaceMatchResult {
    data class ConfirmedMatch(
        val profile: ProfileEntity,
        val similarityScore: Float,
        val marginToRunnerUp: Float,
        val confirmationCount: Int
    ) : FaceMatchResult()

    data class PendingMatch(
        val candidateProfile: ProfileEntity,
        val similarityScore: Float,
        val currentCount: Int,
        val requiredCount: Int = 2,
        val marginToRunnerUp: Float = 0f
    ) : FaceMatchResult()

    data class MarginFailed(
        val topCandidate: ProfileEntity,
        val runnerUpCandidate: ProfileEntity,
        val topScore: Float,
        val margin: Float
    ) : FaceMatchResult()

    data class LowConfidence(
        val topScore: Float,
        val requiredThreshold: Float
    ) : FaceMatchResult()

    /**
     * A real, live face was seen clearly but matches nobody enrolled -> Guest mode.
     */
    data class NoMatch(val reason: String) : FaceMatchResult()

    /** A good-quality face that did not match yet; keep scanning before concluding "unknown". */
    data class Scanning(
        val topScore: Float,
        val attempts: Int,
        val attemptsNeeded: Int
    ) : FaceMatchResult()

    /** Face found but the frame is too poor to judge (too far, turned away, eyes closed). */
    data class LowQualityFrame(val hint: String) : FaceMatchResult()

    /**
     * Passive liveness / anti-spoof check failed (e.g. printed photo or screen replay).
     * Always resolves to the most restrictive, locked mode.
     */
    data class SpoofSuspected(
        val livenessScore: Float
    ) : FaceMatchResult()

    /** A parent was recognised; waiting for the active liveness challenge (blink / turn) to finish. */
    data class LivenessChallenge(
        val candidateProfile: ProfileEntity,
        val prompt: String
    ) : FaceMatchResult()

    /** The active liveness challenge failed or timed out: treated like an unverified face. */
    data class LivenessFailed(val reason: String) : FaceMatchResult()

    object UnknownFace : FaceMatchResult()
    object NoFaceDetected : FaceMatchResult()
    data class Error(val exception: Throwable) : FaceMatchResult()
}
