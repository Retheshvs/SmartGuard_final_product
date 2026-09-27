package com.smartguard.recognition

import com.smartguard.recognition.liveness.ActiveLivenessChallenge
import com.smartguard.recognition.liveness.ActiveLivenessChallenge.State
import com.smartguard.recognition.liveness.ActiveLivenessChallenge.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blink / head-turn challenge a parent must pass. Pure JVM. */
class ActiveLivenessChallengeTest {

    private var t = 0L

    private fun frame(yaw: Float = 0f, eyes: Float? = 0.9f, x: Float = 200f, width: Float = 150f) =
        ActiveLivenessChallenge.Sample(t, yaw, eyes, eyes, x, 300f, width)

    private fun ActiveLivenessChallenge.feed(vararg samples: ActiveLivenessChallenge.Sample?): State {
        var s: State = State.InProgress("")
        for (sample in samples) {
            t += 100
            s = update(sample, t)
        }
        return s
    }

    @Test
    fun blinkPasses() {
        val c = ActiveLivenessChallenge(Type.BLINK, 0)
        assertEquals(State.Passed, c.feed(frame(eyes = 0.9f), frame(eyes = 0.1f), frame(eyes = 0.9f)))
    }

    @Test
    fun photoThatNeverBlinksTimesOut() {
        val c = ActiveLivenessChallenge(Type.BLINK, 0)
        val frames = Array(80) { frame(eyes = 0.95f) } // 8 s of open eyes
        assertTrue(c.feed(*frames) is State.Failed)
    }

    @Test
    fun turnAndBackPasses() {
        val c = ActiveLivenessChallenge(Type.TURN, 0)
        assertEquals(State.Passed, c.feed(frame(yaw = 2f), frame(yaw = 15f), frame(yaw = 28f), frame(yaw = 14f), frame(yaw = 4f)))
    }

    @Test
    fun turnWithoutComingBackIsNotEnough() {
        val c = ActiveLivenessChallenge(Type.TURN, 0)
        assertTrue(c.feed(frame(yaw = 0f), frame(yaw = 30f), frame(yaw = 30f)) is State.InProgress)
    }

    @Test
    fun swappingFacesMidChallengeFails() {
        val c = ActiveLivenessChallenge(Type.BLINK, 0)
        val s = c.feed(frame(eyes = 0.9f, x = 200f), frame(eyes = 0.1f, x = 420f)) // jumped 1.5 face widths
        assertTrue(s is State.Failed)
    }

    @Test
    fun faceLeavingTheCameraFails() {
        val c = ActiveLivenessChallenge(Type.BLINK, 0)
        assertTrue(c.feed(frame(), null, null, null, null) is State.Failed)
    }

    @Test
    fun resultIsFinal() {
        val c = ActiveLivenessChallenge(Type.BLINK, 0)
        c.feed(frame(eyes = 0.9f), frame(eyes = 0.1f), frame(eyes = 0.9f))
        assertEquals(State.Passed, c.feed(null, null, null, null, null))
    }
}
