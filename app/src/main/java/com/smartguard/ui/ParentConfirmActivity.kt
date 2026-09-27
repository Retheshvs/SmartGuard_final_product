package com.smartguard.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
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
import com.smartguard.policy.UserRole
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
 * "Confirm it's you" — a parent face check that authorises ONE settings change.
 *
 * Returns RESULT_OK only if the face is confidently matched to an enrolled PARENT. A child, a guest or
 * an unknown face is refused. Unlike the unlock check, this never changes who is using the phone.
 * Same visual language as the face check (live mesh, ring, scan line), reusing its layout.
 */
class ParentConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "reason"
        /** Result extra: id of the parent profile that confirmed. */
        const val RESULT_PROFILE_ID = "profile_id"
        private const val TAG = "SG-ParentAuth"
        private const val TIMEOUT_MS = 10_000L

        fun intent(context: Context, reason: String) =
            Intent(context, ParentConfirmActivity::class.java).putExtra(EXTRA_REASON, reason)
    }

    private lateinit var binding: ActivityFaceCheckBinding
    private var pipeline: FaceRecognitionPipeline? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var timeoutJob: Job? = null
    private var scanAnimator: ObjectAnimator? = null
    private var reason = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityFaceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        setResult(RESULT_CANCELED)

        reason = intent.getStringExtra(EXTRA_REASON) ?: "Change SmartGuard settings"
        show("Confirm it's you", "$reason needs a parent's face", R.color.primary)
        startScan()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            deny("Camera access is needed to confirm")
            return
        }
        lifecycleScope.launch {
            pipeline = withContext(Dispatchers.Default) {
                FaceRecognitionPipeline(applicationContext, SmartGuardApp.instance.profileRepository, showFaceMesh = true)
            }.also { pipe ->
                pipe.onFace = { face, w, h ->
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
                deny("Camera error")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var challengeShown = false

    private fun armTimeout(ms: Long) {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(ms)
            deny("Couldn't confirm a parent's face in time")
        }
    }

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
            withContext(Dispatchers.Main) {
                when (result) {
                    is FaceMatchResult.ConfirmedMatch ->
                        if (UserRole.fromString(result.profile.role) == UserRole.ADULT) approve(result.profile.name, result.profile.id)
                        else deny("${result.profile.name} can't change settings. A parent must confirm.")
                    is FaceMatchResult.NoMatch -> deny("Face not recognised as a parent")
                    is FaceMatchResult.SpoofSuspected -> deny("That looked like a photo or a screen, not a real face")
                    is FaceMatchResult.LivenessFailed -> deny(result.reason)
                    is FaceMatchResult.LivenessChallenge -> {
                        if (!challengeShown) {
                            challengeShown = true
                            armTimeout(ActiveLivenessChallenge.TIMEOUT_MS + 3_000L)
                            binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        show(result.prompt, "Quick check that it's really ${result.candidateProfile.name}", R.color.accent_cyan)
                        processing.set(false)
                    }
                    FaceMatchResult.UnknownFace, is FaceMatchResult.Error -> deny("No parent face is enrolled")
                    is FaceMatchResult.PendingMatch -> {
                        show("Hold still", "Confirming ${result.candidateProfile.name}…", R.color.primary)
                        processing.set(false)
                    }
                    is FaceMatchResult.LowQualityFrame -> {
                        show("Adjust a little", result.hint, R.color.accent_amber)
                        processing.set(false)
                    }
                    else -> processing.set(false)
                }
            }
        }
    }

    private var approvedProfileId = -1L

    private fun approve(name: String, profileId: Long) {
        if (!finished.compareAndSet(false, true)) return
        approvedProfileId = profileId
        SgLog.i(TAG, "Approved by $name: $reason")
        finishWith(true, "Confirmed", "$name · $reason", R.color.accent_emerald)
    }

    private fun deny(why: String) {
        if (!finished.compareAndSet(false, true)) return
        SgLog.i(TAG, "Refused ($why): $reason")
        finishWith(false, "Not allowed", why, R.color.accent_rose)
    }

    private fun finishWith(ok: Boolean, title: String, detail: String, colorRes: Int) {
        timeoutJob?.cancel()
        scanAnimator?.cancel()
        binding.scanLine.animate().alpha(0f).setDuration(150).start()
        show(title, detail, colorRes)
        binding.ring.setIndicatorColor(ContextCompat.getColor(this, colorRes))
        if (binding.ring.isIndeterminate) {
            binding.ring.visibility = View.INVISIBLE
            binding.ring.isIndeterminate = false
            binding.ring.visibility = View.VISIBLE
        }
        binding.ring.setProgressCompat(100, true)
        binding.resultBadge.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        binding.resultBadge.setColorFilter(ContextCompat.getColor(this, colorRes))
        binding.resultBadge.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.2f)).setDuration(300).start()
        binding.root.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ok -> HapticFeedbackConstants.CONFIRM
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.LONG_PRESS
            }
        )
        setResult(if (ok) RESULT_OK else RESULT_CANCELED, Intent().putExtra(RESULT_PROFILE_ID, approvedProfileId))
        lifecycleScope.launch {
            delay(if (ok) 700L else 1600L)
            finish()
        }
    }

    private fun show(title: String, detail: String, colorRes: Int) {
        binding.tvStatus.text = title
        binding.tvDetail.text = detail
        val color = ContextCompat.getColor(this, colorRes)
        binding.scanLine.setBackgroundColor(color)
        binding.faceMesh.setMeshColor(color)
    }

    private fun startScan() {
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

    override fun onDestroy() {
        super.onDestroy()
        scanAnimator?.cancel()
        pipeline?.onFace = null
        pipeline?.close()
        cameraExecutor.shutdown()
    }
}
