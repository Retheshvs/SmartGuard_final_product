package com.smartguard.recognition.liveness

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Active liveness: a short random challenge — blink, or turn the head and back — asked only when a face
 * is about to get PARENT rights. A printed photo can't blink and a flat photo can't turn and come back
 * the way a head does, and the random choice defeats a pre-recorded clip.
 *
 * Costs no extra model: it reads what face detection already produces for every frame (eye-open
 * probabilities and head yaw). Pure logic with no Android types, so it is unit-tested directly.
 *
 * It also guards against swapping faces mid-challenge: the face must stay in view and can't jump or
 * change size abruptly between frames. The pipeline re-verifies identity on the final frontal frame.
 */
class ActiveLivenessChallenge(
    val type: Type,
    private val startedAtMs: Long,
    private val timeoutMs: Long = TIMEOUT_MS
) {
    enum class Type(val prompt: String) {
        BLINK("Blink slowly"),
        TURN("Turn your head to one side, then back")
    }

    /** One frame's face measurements (null face = nobody in view). */
    data class Sample(
        val timeMs: Long,
        val yaw: Float,
        val leftEyeOpen: Float?,
        val rightEyeOpen: Float?,
        val centerX: Float,
        val centerY: Float,
        val faceWidth: Float
    )

    sealed class State {
        data class InProgress(val prompt: String) : State()
        object Passed : State()
        data class Failed(val reason: String) : State()
    }

    companion object {
        const val TIMEOUT_MS = 7_000L
        private const val EYES_OPEN = 0.6f
        private const val EYES_CLOSED = 0.3f
        private const val FRONTAL_YAW = 12f
        private const val TURNED_YAW = 22f
        private const val MAX_MISSING_FRAMES = 3
        /** Largest believable move between two analysed frames, in face widths. */
        private const val MAX_JUMP = 0.6f
        private const val MAX_SIZE_CHANGE = 0.35f

        private val random = SecureRandom()

        fun random(nowMs: Long) =
            ActiveLivenessChallenge(if (random.nextBoolean()) Type.BLINK else Type.TURN, nowMs)
    }

    private enum class Step { WAIT_BASELINE, WAIT_ACTION, WAIT_RETURN, DONE }

    private var step = Step.WAIT_BASELINE
    private var last: Sample? = null
    private var missing = 0
    private var result: State? = null

    fun update(sample: Sample?, nowMs: Long): State {
        result?.let { return it }
        if (nowMs - startedAtMs > timeoutMs) return finish(State.Failed("Didn't see the ${type.name.lowercase()} in time"))

        if (sample == null) {
            if (++missing > MAX_MISSING_FRAMES) return finish(State.Failed("Face left the camera"))
            return State.InProgress("Keep your face in the circle")
        }
        missing = 0
        continuityProblem(sample)?.let { return finish(State.Failed(it)) }
        last = sample

        return when (type) {
            Type.BLINK -> blink(sample)
            Type.TURN -> turn(sample)
        }
    }

    private fun blink(s: Sample): State {
        val l = s.leftEyeOpen
        val r = s.rightEyeOpen
        if (l == null || r == null) return State.InProgress("Look at the screen")
        val eyes = (l + r) / 2f
        when (step) {
            Step.WAIT_BASELINE -> if (eyes >= EYES_OPEN) step = Step.WAIT_ACTION
            Step.WAIT_ACTION -> if (eyes <= EYES_CLOSED) step = Step.WAIT_RETURN
            Step.WAIT_RETURN -> if (eyes >= EYES_OPEN) return finish(State.Passed)
            Step.DONE -> Unit
        }
        return State.InProgress(type.prompt)
    }

    private fun turn(s: Sample): State {
        val yaw = abs(s.yaw)
        when (step) {
            Step.WAIT_BASELINE -> if (yaw <= FRONTAL_YAW) step = Step.WAIT_ACTION
            Step.WAIT_ACTION -> if (yaw >= TURNED_YAW) step = Step.WAIT_RETURN
            Step.WAIT_RETURN -> if (yaw <= FRONTAL_YAW) return finish(State.Passed)
            Step.DONE -> Unit
        }
        return State.InProgress(if (step == Step.WAIT_RETURN) "Now look back at the screen" else type.prompt)
    }

    /** A different face swapped in shows up as a jump in position or size between frames. */
    private fun continuityProblem(s: Sample): String? {
        val p = last ?: return null
        val width = maxOf(p.faceWidth, 1f)
        val jump = hypot(s.centerX - p.centerX, s.centerY - p.centerY) / width
        val sizeChange = abs(s.faceWidth - p.faceWidth) / width
        return if (jump > MAX_JUMP || sizeChange > MAX_SIZE_CHANGE) "The face changed during the check" else null
    }

    private fun finish(state: State): State {
        step = Step.DONE
        result = state
        return state
    }
}
