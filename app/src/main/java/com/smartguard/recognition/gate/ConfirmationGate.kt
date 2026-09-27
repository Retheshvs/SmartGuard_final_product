package com.smartguard.recognition.gate

import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.recognition.model.FaceMatchResult

/**
 * Enforces the Identity Confirmation Gate.
 * Fast & accurate handover verification.
 */
class ConfirmationGate(
    val requiredConsecutiveMatches: Int = IDENTITY_CONFIRMATION_COUNT
) {

    companion object {
        // Two consecutive consistent detections required before confirming a handover.
        // Guards against single-frame false positives and momentary look-alike frames.
        const val IDENTITY_CONFIRMATION_COUNT = 2
    }

    private var currentCandidateId: Long? = null
    private var currentConsecutiveCount: Int = 0

    @Synchronized
    fun processEvaluation(
        evalResult: FaceMatchResult.PendingMatch
    ): FaceMatchResult {
        val candidate = evalResult.candidateProfile
        val score = evalResult.similarityScore

        if (currentCandidateId == candidate.id) {
            currentConsecutiveCount++
        } else {
            currentCandidateId = candidate.id
            currentConsecutiveCount = 1
        }

        return if (currentConsecutiveCount >= requiredConsecutiveMatches) {
            val confirmedResult = FaceMatchResult.ConfirmedMatch(
                profile = candidate,
                similarityScore = score,
                marginToRunnerUp = evalResult.marginToRunnerUp,
                confirmationCount = currentConsecutiveCount
            )
            reset()
            confirmedResult
        } else {
            FaceMatchResult.PendingMatch(
                candidateProfile = candidate,
                similarityScore = score,
                currentCount = currentConsecutiveCount,
                requiredCount = requiredConsecutiveMatches
            )
        }
    }

    @Synchronized
    fun reset() {
        currentCandidateId = null
        currentConsecutiveCount = 0
    }
}
