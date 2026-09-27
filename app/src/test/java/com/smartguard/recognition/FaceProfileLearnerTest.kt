package com.smartguard.recognition

import com.smartguard.recognition.adaptive.FaceProfileLearner
import com.smartguard.recognition.adaptive.FaceProfileLearner.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The guard rails that decide whether a recognised face sample is learned. Pure JVM. */
class FaceProfileLearnerTest {

    private fun decide(score: Float, margin: Float = 0.3f, live: Boolean = true) =
        FaceProfileLearner.decide(score, margin, live)

    @Test
    fun confidentNewLookIsLearned() = assertEquals(Decision.LEARN, decide(0.80f))

    @Test
    fun ordinaryMatchIsNotConfidentEnough() = assertEquals(Decision.NOT_CONFIDENT, decide(0.65f))

    @Test
    fun lookAlikeSiblingIsNeverLearned() = assertEquals(Decision.TOO_CLOSE_TO_OTHERS, decide(0.85f, margin = 0.10f))

    @Test
    fun alreadyKnownLookAddsNothing() = assertEquals(Decision.NOTHING_NEW, decide(0.95f))

    @Test
    fun withoutAntiSpoofNothingIsLearned() = assertEquals(Decision.NO_LIVENESS, decide(0.80f, live = false))

    @Test
    fun boundaries() {
        assertEquals(Decision.LEARN, decide(FaceProfileLearner.MIN_SCORE, margin = FaceProfileLearner.MIN_MARGIN))
        assertEquals(Decision.NOTHING_NEW, decide(FaceProfileLearner.MAX_SCORE))
    }
}
