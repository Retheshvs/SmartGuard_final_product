package com.smartguard.recognition.matcher

import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.recognition.model.FaceMatchResult

/**
 * Computes Cosine Similarity between face embeddings and enforces margin rules against runner-up candidates.
 * Hard Constraint: Fail-safe default — if top match fails margin rule or similarity threshold,
 * returns MarginFailed or LowConfidence which resolves to restrictive mode.
 */
class FaceMatcher {

    companion object {
        // Minimum cosine similarity for a match. For L2-normalized MobileFaceNet embeddings this is
        // squared-L2 distance < 0.8 (dist = 2 - 2cos), the operating point the model's authors use.
        const val SIMILARITY_THRESHOLD = 0.60f
        const val MARGIN_THRESHOLD = 0.05f     // Top match margin over runner-up candidate
    }

    /**
     * Evaluates candidate face vector against all decrypted embeddings in DB.
     */
    fun findBestMatch(
        candidateVector: FloatArray,
        decryptedEmbeddings: List<ProfileRepository.DecryptedEmbedding>,
        profilesMap: Map<Long, ProfileEntity>
    ): MatchEvaluation {
        // Only compare vectors from the same embedding space. Enrollments made with a different
        // model (e.g. the fallback descriptor before MobileFaceNet was bundled) are ignored.
        val comparable = decryptedEmbeddings.filter { it.vector.size == candidateVector.size }

        if (comparable.isEmpty() || profilesMap.isEmpty()) {
            return MatchEvaluation(FaceMatchResult.UnknownFace, null)
        }

        // Group similarity scores by profile ID (compute max similarity across all enrolled face samples for that profile)
        val profileScores = mutableMapOf<Long, Float>()
        for (embedding in comparable) {
            val score = cosineSimilarity(candidateVector, embedding.vector)
            val currentMax = profileScores[embedding.profileId] ?: -1.0f
            if (score > currentMax) {
                profileScores[embedding.profileId] = score
            }
        }

        // Sort profiles by top score descending
        val sortedScores = profileScores.entries.sortedByDescending { it.value }
        if (sortedScores.isEmpty()) {
            return MatchEvaluation(FaceMatchResult.UnknownFace, null)
        }

        val topEntry = sortedScores[0]
        val topProfile = profilesMap[topEntry.key] ?: return MatchEvaluation(FaceMatchResult.UnknownFace, null)
        val topScore = topEntry.value

        // Check overall similarity threshold
        if (topScore < SIMILARITY_THRESHOLD) {
            return MatchEvaluation(
                result = FaceMatchResult.LowConfidence(
                    topScore = topScore,
                    requiredThreshold = SIMILARITY_THRESHOLD
                ),
                topProfile = topProfile
            )
        }

        // Check Margin Rule against Runner-up Candidate (if multiple profiles exist)
        if (sortedScores.size > 1) {
            val runnerUpEntry = sortedScores[1]
            val runnerUpProfile = profilesMap[runnerUpEntry.key]
            val runnerUpScore = runnerUpEntry.value
            val margin = topScore - runnerUpScore

            if (margin < MARGIN_THRESHOLD && runnerUpProfile != null) {
                return MatchEvaluation(
                    result = FaceMatchResult.MarginFailed(
                        topCandidate = topProfile,
                        runnerUpCandidate = runnerUpProfile,
                        topScore = topScore,
                        margin = margin
                    ),
                    topProfile = topProfile
                )
            }

            return MatchEvaluation(
                result = FaceMatchResult.PendingMatch(
                    candidateProfile = topProfile,
                    similarityScore = topScore,
                    currentCount = 1,
                    marginToRunnerUp = margin
                ),
                topProfile = topProfile,
                margin = margin
            )
        }

        // Single profile enrolled and passed similarity threshold.
        // With no runner-up, margin is reported as the top score itself.
        return MatchEvaluation(
            result = FaceMatchResult.PendingMatch(
                candidateProfile = topProfile,
                similarityScore = topScore,
                currentCount = 1,
                marginToRunnerUp = topScore
            ),
            topProfile = topProfile,
            margin = topScore
        )
    }

    /**
     * Calculates Cosine Similarity between two L2-normalized vectors.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val len = minOf(v1.size, v2.size)
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until len) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        val denom = (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)).coerceAtLeast(1e-6f)
        return dotProduct / denom
    }
}

data class MatchEvaluation(
    val result: FaceMatchResult,
    val topProfile: ProfileEntity?,
    val margin: Float = 0f
)
