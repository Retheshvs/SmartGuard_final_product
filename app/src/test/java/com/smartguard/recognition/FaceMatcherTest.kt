package com.smartguard.recognition

import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.recognition.matcher.FaceMatcher
import com.smartguard.recognition.model.FaceMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Correctness + FAR/FRR-style sanity checks for the on-device matcher.
 * Pure JVM (no Android framework), runnable with `gradlew test`.
 */
class FaceMatcherTest {

    private val matcher = FaceMatcher()

    private fun l2(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun identicalVectorsHaveSimilarityOne() {
        val v = l2(FloatArray(128) { Random.nextFloat() })
        val sim = matcher.cosineSimilarity(v, v)
        assertEquals(1.0f, sim, 1e-3f)
    }

    @Test
    fun orthogonalVectorsHaveSimilarityNearZero() {
        // This test FAILS if the old v1·v1 bug returns; the fixed v1·v2 gives ~0.
        val a = l2(floatArrayOf(1f, 0f, 0f, 0f))
        val b = l2(floatArrayOf(0f, 1f, 0f, 0f))
        val sim = matcher.cosineSimilarity(a, b)
        assertTrue("Expected near-zero similarity, got $sim", sim < 0.1f)
    }

    @Test
    fun differentPeopleDoNotFalselyMatch() {
        val profiles = listOf(
            ProfileEntity(id = 1, name = "A", role = "ADULT", screenTimeBudgetMinutes = 0, allowedPackagesJson = "[]"),
            ProfileEntity(id = 2, name = "B", role = "CHILD", screenTimeBudgetMinutes = 60, allowedPackagesJson = "[]")
        )
        val map = profiles.associateBy { it.id }
        val enrolled = listOf(
            ProfileRepository.DecryptedEmbedding(1, 1, l2(floatArrayOf(1f, 0f, 0f, 0f, 0f))),
            ProfileRepository.DecryptedEmbedding(2, 2, l2(floatArrayOf(0f, 1f, 0f, 0f, 0f)))
        )
        // A probe close to person A should not confuse with person B.
        val probe = l2(floatArrayOf(0.95f, 0.05f, 0f, 0f, 0f))
        val eval = matcher.findBestMatch(probe, enrolled, map)
        assertTrue(eval.result is FaceMatchResult.PendingMatch)
        assertEquals(1L, (eval.result as FaceMatchResult.PendingMatch).candidateProfile.id)
    }

    @Test
    fun farFrrMiniHarness() {
        // Synthetic person centroids; genuine samples are jittered around their centroid.
        val rng = Random(42)
        val dim = 32
        val people = (0 until 5).map { l2(FloatArray(dim) { rng.nextFloat() * 2 - 1 }) }

        fun jitter(base: FloatArray, amt: Float) = l2(FloatArray(dim) { base[it] + (rng.nextFloat() * 2 - 1) * amt })

        var genuineAccepted = 0
        var genuineTotal = 0
        var impostorAccepted = 0
        var impostorTotal = 0
        val threshold = FaceMatcher.SIMILARITY_THRESHOLD

        for (i in people.indices) {
            repeat(20) {
                val probe = jitter(people[i], 0.15f)
                if (matcher.cosineSimilarity(probe, people[i]) >= threshold) genuineAccepted++
                genuineTotal++
                val other = (i + 1) % people.size
                if (matcher.cosineSimilarity(probe, people[other]) >= threshold) impostorAccepted++
                impostorTotal++
            }
        }
        val frr = 1f - genuineAccepted.toFloat() / genuineTotal
        val far = impostorAccepted.toFloat() / impostorTotal
        println("FAR=$far FRR=$frr (threshold=$threshold)")
        // Sane bounds for the synthetic set — proves accept/reject logic is directionally correct.
        assertTrue("FAR too high: $far", far < 0.2f)
        assertTrue("FRR too high: $frr", frr < 0.3f)
    }
}
