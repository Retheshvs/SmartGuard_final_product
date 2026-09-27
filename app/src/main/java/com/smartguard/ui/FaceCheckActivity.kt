package com.smartguard.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityFaceCheckBinding
import com.smartguard.policy.SessionController
import com.smartguard.recognition.FrameConverter
import com.smartguard.recognition.liveness.ActiveLivenessChallenge
import com.smartguard.recognition.model.FaceMatchResult
import com.smartguard.recognition.pipeline.FaceRecognitionPipeline
import com.smartguard.util.SgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The identity check shown at unlock / "Switch user".
 *
 * Visual language: the camera sits in a circle with a scan line sweeping across it; the ring around
 * it is the state — blue while checking, filling as frames confirm the match, amber when the person
 * needs to adjust, green on success. The verdict pops in as a badge with a haptic tap, then the screen
 * closes itself. Dismissing it before a verdict applies the fail-safe restricted mode.
 */
class FaceCheckActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIGGER = "trigger"
        private const val TAG = "SG-FaceCheck"
        private const val TIMEOUT_MS = 8000L
        /** Extra time after a parent's liveness challenge ends, for the final identity frame. */
        private const val CHALLENGE_GRACE_MS = 3000L
        private const val RESULT_DISPLAY_MS = 1400L

        /** True while a check is on screen (the unlock path must not stack a second one). */
        @Volatile
        var isRunning = false
            private set
    }

    private var trigger = "manual"
    private val sawFace = AtomicBoolean(false)
    private val keyguard by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }

    private lateinit var binding: ActivityFaceCheckBinding
    private var pipeline: FaceRecognitionPipeline? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var timeoutJob: Job? = null
    private var scanAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityFaceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        isRunning = true
        trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "manual"
        startScanAnimation()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showFinal(SessionController.applyFailSafe("Camera permission missing"))
            return
        }

        lifecycleScope.launch {
            val hasProfiles = withContext(Dispatchers.IO) {
                SmartGuardApp.instance.profileRepository.getAllProfiles().isNotEmpty()
            }
            if (!hasProfiles) {
                finished.set(true)
                stopScanAnimation()
                show("No faces added yet", "Open SmartGuard and add the parent's face first.", R.color.accent_amber)
                delay(RESULT_DISPLAY_MS)
                finish()
                return@launch
            }
            pipeline = withContext(Dispatchers.Default) {
                FaceRecognitionPipeline(applicationContext, SmartGuardApp.instance.profileRepository, showFaceMesh = true)
            }.also { pipe ->
                // Live mesh: the scan line "searches" until a face is found, then the mesh locks on.
                pipe.onFace = { face, w, h ->
                    if (face != null) sawFace.set(true)
                    binding.root.post {
                        if (!finished.get()) {
                            binding.faceMesh.setFace(face, w, h)
                            binding.scanLine.animate().alpha(if (face == null) 0.8f else 0f).setDuration(200).start()
                        }
                    }
                }
            }
            startCamera()
        }
    }

    // ------------------------------------------------------------------ camera + recognition

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (finished.get() || !processing.compareAndSet(false, true)) proxy.close() else analyze(proxy)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)

                armTimeout(TIMEOUT_MS)
            } catch (e: Exception) {
                SgLog.w(TAG, "Camera start failed: ${e.message}")
                showFinal(SessionController.applyFailSafe("Camera error"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun armTimeout(ms: Long) {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(ms)
            if (canCloseQuietly()) closeQuietly("no face in view on the lock screen")
            else showFinal(SessionController.applyFailSafe("No confident match in time"))
        }
    }

    private var challengeShown = false

    private fun analyze(proxy: ImageProxy) {
        val bitmap = try {
            FrameConverter.toUprightBitmap(proxy)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        val pipe = pipeline
        if (bitmap == null || pipe == null) {
            processing.set(false)
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val result = pipe.processFrame(bitmap)
            val outcome = SessionController.resolve(result)
            withContext(Dispatchers.Main) {
                if (outcome != null) {
                    showFinal(outcome)
                } else {
                    showProgress(result)
                    processing.set(false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ visuals

    private fun showProgress(result: FaceMatchResult) {
        if (finished.get()) return
        when (result) {
            is FaceMatchResult.PendingMatch -> {
                show("Hold still", "Recognising ${result.candidateProfile.name}…", R.color.primary)
                setRingProgress(result.currentCount * 100 / result.requiredCount.coerceAtLeast(1), R.color.primary)
            }
            is FaceMatchResult.LivenessChallenge -> {
                if (!challengeShown) {
                    // Parent recognised: give the challenge its own time and a nudge to act.
                    challengeShown = true
                    armTimeout(ActiveLivenessChallenge.TIMEOUT_MS + CHALLENGE_GRACE_MS)
                    binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                show(result.prompt, "Quick check that it's really ${result.candidateProfile.name}", R.color.accent_cyan)
                setRingIndeterminate(R.color.accent_cyan)
            }
            is FaceMatchResult.Scanning -> {
                show("Checking…", "Keep looking at the screen", R.color.primary)
                setRingIndeterminate(R.color.primary)
            }
            is FaceMatchResult.LowQualityFrame -> {
                show("Adjust a little", result.hint, R.color.accent_amber)
                setRingIndeterminate(R.color.accent_amber)
            }
            else -> {
                show("Look at the screen", "Hold the phone at eye level", R.color.primary)
                setRingIndeterminate(R.color.primary)
            }
        }
    }

    private fun show(title: String, detail: String, colorRes: Int) {
        binding.tvStatus.text = title
        binding.tvDetail.text = detail
        val color = ContextCompat.getColor(this, colorRes)
        binding.scanLine.setBackgroundColor(color)
        binding.faceMesh.setMeshColor(color)
    }

    private fun setRingIndeterminate(colorRes: Int) {
        val ring = binding.ring
        ring.setIndicatorColor(ContextCompat.getColor(this, colorRes))
        if (!ring.isIndeterminate) {
            ring.visibility = View.INVISIBLE
            ring.isIndeterminate = true
            ring.visibility = View.VISIBLE
        }
    }

    private fun setRingProgress(percent: Int, colorRes: Int) {
        val ring = binding.ring
        ring.setIndicatorColor(ContextCompat.getColor(this, colorRes))
        if (ring.isIndeterminate) {
            ring.visibility = View.INVISIBLE
            ring.isIndeterminate = false
            ring.visibility = View.VISIBLE
        }
        ring.setProgressCompat(percent.coerceIn(0, 100), true)
    }

    private var pulseAnimator: ObjectAnimator? = null

    private fun startScanAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.pulseRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.16f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.16f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f)
        ).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        binding.cameraCircle.post {
            val travel = (binding.cameraCircle.height - binding.scanLine.height).toFloat().coerceAtLeast(1f)
            scanAnimator = ObjectAnimator.ofFloat(binding.scanLine, View.TRANSLATION_Y, 0f, travel).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopScanAnimation() {
        scanAnimator?.cancel()
        pulseAnimator?.cancel()
        binding.pulseRing.animate().alpha(0f).setDuration(200).start()
        binding.scanLine.animate().alpha(0f).setDuration(200).start()
    }

    private fun showFinal(outcome: SessionController.Outcome) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        stopScanAnimation()
        SgLog.i(TAG, "Verdict ($trigger): ${outcome.message}")

        // Green for a recognised person; amber for age-based guest rules; red when locked.
        val colorRes = when {
            outcome.matched -> R.color.accent_emerald
            outcome.guest -> R.color.accent_amber
            else -> R.color.accent_rose
        }
        setRingProgress(100, colorRes)
        binding.faceMesh.setMeshColor(ContextCompat.getColor(this, colorRes))
        if (outcome.matched) binding.faceMesh.flash()
        binding.tvStatus.text = outcome.title
        binding.tvDetail.text = outcome.detail
        // Verdict slides up into place.
        listOf(binding.tvStatus, binding.tvDetail).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f * resources.displayMetrics.density
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L * i).setDuration(320).start()
        }

        binding.resultBadge.setImageResource(if (outcome.matched) R.drawable.ic_check_circle else R.drawable.ic_warning)
        binding.resultBadge.setColorFilter(ContextCompat.getColor(this, colorRes))
        binding.resultBadge.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(2.2f))
            .setDuration(350)
            .start()
        haptic(outcome.matched)

        lifecycleScope.launch {
            delay(RESULT_DISPLAY_MS)
            // Couldn't verify anyone (not a guest): with Device Owner, lock the screen right away.
            if (!outcome.matched && !outcome.guest) {
                com.smartguard.deviceowner.DeviceRules.lockNow(applicationContext)
                finish()
            } else {
                finishAndUnlock()
            }
        }
    }

    /**
     * Screen-on check with nothing to verify (phone woken by a notification, lying on a desk): close
     * without changing the session, and make sure the unlock itself is still checked.
     */
    private fun canCloseQuietly() = trigger == "screen on" && !sawFace.get() && keyguard.isKeyguardLocked

    private fun closeQuietly(why: String) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        stopScanAnimation()
        SgLog.i(TAG, "Closed without a verdict: $why")
        com.smartguard.accessibility.SmartGuardAccessibilityService.instance?.rearmForUnlock()
        finish()
    }

    /** After a verdict on a swipe lock screen, open the phone straight away (face-unlock feel). */
    private fun finishAndUnlock() {
        if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) {
            keyguard.requestDismissKeyguard(this, null)
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new check was requested while the previous one is only showing its result: start over.
        if (finished.get()) {
            setIntent(intent)
            recreate()
        }
    }

    private fun haptic(success: Boolean) {
        val constant = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && success -> HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        binding.root.performHapticFeedback(constant)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStop() {
        super.onStop()
        // Leaving before a verdict (back/home/screen off) must not bypass the check.
        if (canCloseQuietly()) {
            closeQuietly("screen turned off before a face was seen")
        } else if (finished.compareAndSet(false, true)) {
            timeoutJob?.cancel()
            SessionController.applyFailSafe("Check dismissed")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scanAnimator?.cancel()
        pulseAnimator?.cancel()
        pipeline?.onFace = null
        pipeline?.close()
        cameraExecutor.shutdown()
    }
}
