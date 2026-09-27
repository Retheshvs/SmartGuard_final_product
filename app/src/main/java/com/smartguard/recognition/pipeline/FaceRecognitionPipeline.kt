package com.smartguard.recognition.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.policy.UserRole
import com.smartguard.recognition.adaptive.FaceProfileLearner
import com.smartguard.recognition.detector.FaceDetectorManager
import com.smartguard.recognition.embedding.FaceEmbedder
import com.smartguard.recognition.gate.ConfirmationGate
import com.smartguard.recognition.liveness.ActiveLivenessChallenge
import com.smartguard.recognition.liveness.LivenessCapture
import com.smartguard.recognition.liveness.LivenessManager
import com.smartguard.recognition.matcher.FaceMatcher
import com.smartguard.recognition.model.FaceMatchResult
import com.smartguard.util.SgLog
import kotlin.math.abs

/**
 * Complete On-Device Face Recognition Pipeline.
 * Detection -> quality gate -> passive liveness -> alignment + embedding -> cosine/margin matching
 * -> confirmation gate -> result (unknown faces -> Guest mode). Liveness is passive (no prompt): every
 * frame that counts toward a match must also pass the anti-spoof model.
 *
 * Asymmetric decision rule, tuned for real unlock conditions (first frames are often blurry,
 * tilted or partly out of frame):
 *  - ACCEPT quickly: [ConfirmationGate.IDENTITY_CONFIRMATION_COUNT] consistent matching frames.
 *  - REJECT slowly: only after [REJECTIONS_BEFORE_UNKNOWN] good-quality frames all fail to match.
 *  - Poor-quality frames are skipped rather than counted against the person.
 *
 * One pipeline instance == one verification attempt: the enrolled gallery is decrypted once and
 * cached for the attempt instead of hitting the Keystore on every frame.
 */
class FaceRecognitionPipeline(
    private val context: Context,
    private val repository: ProfileRepository,
    /** Detect face contours so a screen can draw the live face mesh (costs a little speed). */
    showFaceMesh: Boolean = false,
    /**
     * Ask a recognised PARENT for a blink / head-turn before confirming. Off: it made unlocking slower
     * and asked people to act; the passive anti-spoof model covers photos and screens without a prompt.
     */
    private val activeLivenessForParents: Boolean = false
) {

    companion object {
        private const val TAG = "SG-Recog"
        const val REJECTIONS_BEFORE_UNKNOWN = 4
        private const val MIN_FACE_WIDTH_PX = 70
        private const val MAX_YAW = 35f
        private const val MAX_PITCH = 30f
        private const val EYES_CLOSED = 0.15f
        /**
         * Consecutive spoof-looking frames needed before rejecting (~0.5 s). The first frames after the
         * screen turns on are often mis-exposed and score low for real faces; a photo scores low on all.
         */
        private const val SPOOF_FRAMES_TO_REJECT = 5
        /** Frames allowed to find a clear frontal face after the challenge. */
        private const val FINAL_FRAME_TRIES = 12
    }

    private val faceDetector = FaceDetectorManager(withContours = showFaceMesh)

    /** Called for every frame with the primary face (or null) and the frame size, for visualisation. */
    @Volatile
    var onFace: ((face: Face?, frameWidth: Int, frameHeight: Int) -> Unit)? = null
    private val faceEmbedder = FaceEmbedder(context)
    private val faceMatcher = FaceMatcher()
    private val learner = FaceProfileLearner(repository)
    private val confirmationGate = ConfirmationGate(ConfirmationGate.IDENTITY_CONFIRMATION_COUNT)
    private val livenessManager = LivenessManager(context)

    val isLivenessEnforced: Boolean get() = livenessManager.isModelLoaded
    val isEmbeddingModelLoaded: Boolean get() = faceEmbedder.isModelLoaded
    val embedderName: String? get() = faceEmbedder.modelName

    // Per-attempt state.
    private var gallery: Pair<Map<Long, ProfileEntity>, List<ProfileRepository.DecryptedEmbedding>>? = null
    private var rejectStreak = 0
    private var spoofFrames = 0
    private var challenge: ActiveLivenessChallenge? = null
    private var challengeProfile: ProfileEntity? = null
    private var challengePassed = false
    private var finalFrameMisses = 0

    // Lifecycle guard: close() must never free native interpreters while a frame is mid-inference.
    private val lock = Any()
    private var busy = false
    private var closeRequested = false
    private var closed = false

    /**
     * Evaluates a camera frame bitmap against enrolled profiles.
     * After [close] it returns [FaceMatchResult.NoFaceDetected], which never changes session state.
     */
    suspend fun processFrame(bitmap: Bitmap): FaceMatchResult {
        synchronized(lock) {
            if (closed || closeRequested) return FaceMatchResult.NoFaceDetected
            busy = true
        }
        try {
            return processFrameInternal(bitmap)
        } finally {
            val releaseNow = synchronized(lock) {
                busy = false
                closeRequested && !closed
            }
            if (releaseNow) releaseResources()
        }
    }

    private suspend fun loadGallery(): Pair<Map<Long, ProfileEntity>, List<ProfileRepository.DecryptedEmbedding>> {
        gallery?.let { return it }
        val profiles = repository.getAllProfiles().associateBy { it.id }
        val embeddings = repository.getAllDecryptedEmbeddings()
        Log.i(TAG, "Gallery: ${profiles.size} profiles, ${embeddings.size} embeddings, dims=${embeddings.map { it.vector.size }.distinct()}")
        return (profiles to embeddings).also { gallery = it }
    }

    private suspend fun processFrameInternal(bitmap: Bitmap): FaceMatchResult {
        return try {
            val faces = faceDetector.detectFaces(bitmap)
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            onFace?.invoke(face, bitmap.width, bitmap.height)

            // A parent's challenge in progress: detection only, no embedding, until it's done.
            challenge?.let { return continueChallenge(it, bitmap, face) }

            if (face == null) {
                confirmationGate.reset()
                return FaceMatchResult.NoFaceDetected
            }

            qualityProblem(face)?.let { hint ->
                // Don't count a bad frame against the person; just ask them to adjust.
                return FaceMatchResult.LowQualityFrame(hint)
            }

            val liveness = livenessManager.analyze(bitmap, face)
            if (liveness.enforced) {
                LivenessCapture.save(context, bitmap, face.boundingBox, liveness.score, face.headEulerAngleX, face.headEulerAngleY)
            }
            if (liveness.enforced && !liveness.isLive) {
                // Skip the frame without losing recognition progress: real faces give the odd
                // spoof-looking frame (blur, glare), a photo gives nothing but.
                SgLog.i(TAG, "live=%.2f pitch=%.0f yaw=%.0f spoof-looking frame (%d in a row)"
                    .format(liveness.score, face.headEulerAngleX, face.headEulerAngleY, spoofFrames + 1))
                // One blurry frame can look fake; only consecutive ones are treated as a spoof.
                return if (++spoofFrames >= SPOOF_FRAMES_TO_REJECT) {
                    FaceMatchResult.SpoofSuspected(liveness.score)
                } else {
                    FaceMatchResult.LowQualityFrame("Hold still")
                }
            }
            spoofFrames = 0

            val (profilesMap, embeddings) = loadGallery()
            if (embeddings.isEmpty() || profilesMap.isEmpty()) {
                return FaceMatchResult.UnknownFace
            }

            val candidate = faceEmbedder.extractEmbedding(bitmap, face)
            val evaluation = faceMatcher.findBestMatch(candidate, embeddings, profilesMap)
            logEvaluation(evaluation.result, liveness, face)

            when (val r = evaluation.result) {
                is FaceMatchResult.PendingMatch -> {
                    rejectStreak = 0
                    val gated = confirmationGate.processEvaluation(r)
                    if (gated is FaceMatchResult.ConfirmedMatch && needsActiveLiveness(gated.profile)) {
                        startChallenge(gated.profile)
                    } else {
                        if (gated is FaceMatchResult.ConfirmedMatch) {
                            // Profiles grow with the person: maybe keep this near-certain, live sample.
                            learner.consider(gated.profile, candidate, r.similarityScore, r.marginToRunnerUp, liveness.enforced)
                        }
                        gated
                    }
                }
                is FaceMatchResult.LowConfidence,
                is FaceMatchResult.MarginFailed,
                FaceMatchResult.UnknownFace -> {
                    confirmationGate.reset()
                    rejectStreak++
                    val top = (r as? FaceMatchResult.LowConfidence)?.topScore
                        ?: (r as? FaceMatchResult.MarginFailed)?.topScore ?: 0f
                    if (rejectStreak < REJECTIONS_BEFORE_UNKNOWN) {
                        FaceMatchResult.Scanning(top, rejectStreak, REJECTIONS_BEFORE_UNKNOWN)
                    } else {
                        val reason = if (r is FaceMatchResult.MarginFailed) "ambiguous look-alike" else "best score %.2f".format(top)
                        FaceMatchResult.NoMatch(reason)
                    }
                }
                else -> r
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            confirmationGate.reset()
            FaceMatchResult.Error(e)
        }
    }

    // ------------------------------------------------------------------ active liveness (parents only)

    private fun needsActiveLiveness(profile: ProfileEntity) =
        activeLivenessForParents && UserRole.fromString(profile.role) == UserRole.ADULT

    private fun startChallenge(profile: ProfileEntity): FaceMatchResult {
        val c = ActiveLivenessChallenge.random(SystemClock.elapsedRealtime())
        challenge = c
        challengeProfile = profile
        challengePassed = false
        finalFrameMisses = 0
        SgLog.i(TAG, "Parent ${profile.name} recognised; liveness challenge: ${c.type}")
        return FaceMatchResult.LivenessChallenge(profile, c.type.prompt)
    }

    private fun endChallenge() {
        challenge = null
        challengeProfile = null
        challengePassed = false
        confirmationGate.reset()
    }

    private suspend fun continueChallenge(c: ActiveLivenessChallenge, bitmap: Bitmap, face: Face?): FaceMatchResult {
        val profile = challengeProfile ?: return FaceMatchResult.NoFaceDetected.also { endChallenge() }
        val now = SystemClock.elapsedRealtime()
        if (!challengePassed) {
            when (val state = c.update(face?.let { sampleOf(it, now) }, now)) {
                is ActiveLivenessChallenge.State.InProgress -> return FaceMatchResult.LivenessChallenge(profile, state.prompt)
                is ActiveLivenessChallenge.State.Failed -> {
                    SgLog.i(TAG, "Liveness challenge failed for ${profile.name}: ${state.reason}")
                    endChallenge()
                    return FaceMatchResult.LivenessFailed(state.reason)
                }
                ActiveLivenessChallenge.State.Passed -> challengePassed = true
            }
        }

        // Challenge done: re-check identity once, on a clear frontal frame, so nobody can swap in.
        if (face == null || qualityProblem(face) != null) {
            if (++finalFrameMisses > FINAL_FRAME_TRIES) {
                endChallenge()
                return FaceMatchResult.LivenessFailed("Couldn't see the face clearly after the check")
            }
            return FaceMatchResult.LivenessChallenge(profile, "Look at the screen")
        }
        val liveness = livenessManager.analyze(bitmap, face)
        if (liveness.enforced && !liveness.isLive) {
            endChallenge()
            return FaceMatchResult.SpoofSuspected(liveness.score)
        }
        val (profilesMap, embeddings) = loadGallery()
        val evaluation = faceMatcher.findBestMatch(faceEmbedder.extractEmbedding(bitmap, face), embeddings, profilesMap).result
        endChallenge()
        return if (evaluation is FaceMatchResult.PendingMatch && evaluation.candidateProfile.id == profile.id) {
            SgLog.i(TAG, "Liveness challenge passed by ${profile.name} (score %.2f)".format(evaluation.similarityScore))
            FaceMatchResult.ConfirmedMatch(profile, evaluation.similarityScore, evaluation.marginToRunnerUp, confirmationGate.requiredConsecutiveMatches)
        } else {
            SgLog.i(TAG, "Liveness challenge: final frame didn't match ${profile.name}")
            FaceMatchResult.LivenessFailed("The face changed during the check")
        }
    }

    private fun sampleOf(face: Face, now: Long): ActiveLivenessChallenge.Sample {
        val box = face.boundingBox
        return ActiveLivenessChallenge.Sample(
            timeMs = now,
            yaw = face.headEulerAngleY,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            centerX = box.exactCenterX(),
            centerY = box.exactCenterY(),
            faceWidth = box.width().toFloat()
        )
    }

    /** Returns a user-facing hint if the frame is too poor to judge, else null. */
    private fun qualityProblem(face: Face): String? {
        if (face.boundingBox.width() < MIN_FACE_WIDTH_PX) return "Move the phone closer to your face"
        if (abs(face.headEulerAngleY) > MAX_YAW || abs(face.headEulerAngleX) > MAX_PITCH) return "Look straight at the screen"
        val l = face.leftEyeOpenProbability
        val r = face.rightEyeOpenProbability
        if (l != null && r != null && l < EYES_CLOSED && r < EYES_CLOSED) return "Keep your eyes open"
        return null
    }

    private fun logEvaluation(result: FaceMatchResult, liveness: LivenessManager.LivenessResult, face: Face) {
        val detail = when (result) {
            is FaceMatchResult.PendingMatch ->
                "candidate=${result.candidateProfile.name} score=%.3f margin=%.3f".format(result.similarityScore, result.marginToRunnerUp)
            is FaceMatchResult.LowConfidence ->
                "LOW top=%.3f < %.2f".format(result.topScore, result.requiredThreshold)
            is FaceMatchResult.MarginFailed ->
                "AMBIGUOUS ${result.topCandidate.name} vs ${result.runnerUpCandidate.name} top=%.3f margin=%.3f"
                    .format(result.topScore, result.margin)
            else -> result.javaClass.simpleName
        }
        val live = if (liveness.enforced) "%.2f".format(liveness.score) else "off"
        SgLog.i(TAG, "live=$live pitch=%.0f yaw=%.0f $detail".format(face.headEulerAngleX, face.headEulerAngleY))
    }

    /** Starts a fresh attempt (keeps the cached gallery only for the same pipeline instance). */
    fun resetGate() {
        confirmationGate.reset()
        livenessManager.reset()
        endChallenge()
        spoofFrames = 0
        rejectStreak = 0
    }

    /** Releases models now, or as soon as the in-flight frame (if any) finishes. */
    fun close() {
        val releaseNow = synchronized(lock) {
            closeRequested = true
            !busy && !closed
        }
        if (releaseNow) releaseResources()
    }

    private fun releaseResources() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        faceDetector.close()
        faceEmbedder.close()
        livenessManager.close()
    }
}
