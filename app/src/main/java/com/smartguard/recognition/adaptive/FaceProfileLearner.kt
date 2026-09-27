package com.smartguard.recognition.adaptive

import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.util.SgLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Face profiles that grow with the child.
 *
 * Children's faces change month by month, so a one-time enrollment slowly stops matching. After a
 * near-certain, live recognition SmartGuard may keep that face sample as a "learned look", so the
 * profile follows the person as they grow (and also fills in angles/lighting the enrollment missed).
 *
 * Guard rails, so it can't drift to someone else or be poisoned:
 *  - only near-certain matches: similarity ≥ [MIN_SCORE] (the match threshold is 0.60)
 *  - clearly this person and nobody else: ≥ [MIN_MARGIN] ahead of every other family member
 *  - only live faces: the passive anti-spoof model must be active (a photo can never be learned)
 *  - only something new: skipped if it already matches a stored sample at ≥ [MAX_SCORE]
 *  - at most one per person every [MIN_INTERVAL_MS]; at most [MAX_LEARNED] kept, oldest replaced first
 *  - the enrolled samples are never changed — they anchor the profile; a parent can reset what was learned
 */
class FaceProfileLearner(private val repository: ProfileRepository) {

    enum class Decision { LEARN, NOT_CONFIDENT, TOO_CLOSE_TO_OTHERS, NOTHING_NEW, NO_LIVENESS }

    companion object {
        private const val TAG = "SG-Learn"
        const val MIN_SCORE = 0.72f
        const val MAX_SCORE = 0.93f
        const val MIN_MARGIN = 0.20f
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val MAX_LEARNED = 12

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Pure rule, unit-tested. [margin] is the lead over the next family member. */
        fun decide(score: Float, margin: Float, livenessChecked: Boolean): Decision = when {
            !livenessChecked -> Decision.NO_LIVENESS
            score < MIN_SCORE -> Decision.NOT_CONFIDENT
            margin < MIN_MARGIN -> Decision.TOO_CLOSE_TO_OTHERS
            score >= MAX_SCORE -> Decision.NOTHING_NEW
            else -> Decision.LEARN
        }
    }

    /**
     * Called after a confirmed match with the confirming frame's embedding. Returns immediately; the
     * encrypted write happens in the background so unlocking isn't slowed.
     */
    fun consider(profile: ProfileEntity, vector: FloatArray, score: Float, margin: Float, livenessChecked: Boolean) {
        if (decide(score, margin, livenessChecked) != Decision.LEARN) return
        val copy = vector.copyOf()
        scope.launch {
            try {
                val last = repository.lastLearnedAt(profile.id) ?: 0L
                if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return@launch
                repository.addLearnedEmbedding(profile.id, copy, MAX_LEARNED)
                SgLog.i(TAG, "Learned a new look for ${profile.name} (similarity %.2f, lead %.2f)".format(score, margin))
            } catch (e: Exception) {
                SgLog.w(TAG, "Couldn't save a learned look: ${e.message}")
            }
        }
    }
}
