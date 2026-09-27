package com.smartguard.enrollment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.face.Face
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.databinding.ActivityEnrollmentBinding
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.UserRole
import com.smartguard.recognition.FrameConverter
import com.smartguard.recognition.detector.FaceDetectorManager
import com.smartguard.recognition.embedding.FaceEmbedder
import com.smartguard.util.SgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sign

/**
 * Hands-free, guided face enrollment.
 *
 * The person follows five prompts — straight, one side, the other side, chin up, chin down — and each
 * sample is captured automatically once the head holds that pose for two consecutive frames. Varied
 * angles make recognition robust at unlock, when faces are rarely perfectly frontal. A "Capture now"
 * button remains as a manual fallback.
 *
 * Frames use the same FrameConverter + alignment + embedder as verification; embeddings are encrypted
 * with the Android Keystore before storage. Parent-gated once any profile exists.
 */
class EnrollmentActivity : AppCompatActivity() {

    companion object {
        /** When set, re-captures the face of an existing profile instead of creating a new one. */
        const val EXTRA_PROFILE_ID = "extra_reenroll_profile_id"
        private const val TAG = "SG-Enroll"
        private const val MIN_FACE_WIDTH_PX = 110
        private const val FRAME_INTERVAL_MS = 200L
        private const val STABLE_FRAMES = 2
    }

    /** One guided pose. [matches] gets yaw/pitch plus the signs recorded by earlier steps. */
    private inner class Pose(val instruction: String, val matches: (yaw: Float, pitch: Float) -> Boolean)

    private var firstYawSign = 0f
    private var firstPitchSign = 0f

    private val poses by lazy {
        listOf(
            Pose("Look straight at the camera") { y, p -> abs(y) < 10 && abs(p) < 10 },
            Pose("Turn your head a little to one side") { y, p -> abs(y) in 12f..32f && abs(p) < 15 },
            Pose("Now a little to the other side") { y, p -> abs(y) in 12f..32f && abs(p) < 15 && sign(y) != firstYawSign },
            Pose("Tilt your chin up a little") { y, p -> abs(p) in 7f..25f && abs(y) < 15 },
            Pose("And a little down") { y, p -> abs(p) in 7f..25f && abs(y) < 15 && sign(p) != firstPitchSign }
        )
    }

    private lateinit var binding: ActivityEnrollmentBinding
    private val detectorManager = FaceDetectorManager()
    private lateinit var embedder: FaceEmbedder
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val capturedVectors: MutableList<FloatArray> = Collections.synchronizedList(mutableListOf())
    private val manualCapture = AtomicBoolean(false)
    private var stableCount = 0
    private var lastFrameMs = 0L
    private val done: Boolean get() = capturedVectors.size >= poses.size

    private val reenrollProfileId: Long by lazy { intent.getLongExtra(EXTRA_PROFILE_ID, -1L) }
    private var reenrollProfile: ProfileEntity? = null
    private var firstTimeSetup = false

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera access is needed to add a face", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        embedder = FaceEmbedder(this)

        buildStepDots()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCaptureSample.setOnClickListener { if (!done) manualCapture.set(true) }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        binding.etProfileName.doAfterTextChanged { updateSaveButton() }
        binding.roleGroup.addOnButtonCheckedListener { _, _, _ -> updateSaveButton() }
        renderStep()

        checkParentGatingAndInit()
    }

    // ------------------------------------------------------------------ gating

    private fun checkParentGatingAndInit() {
        lifecycleScope.launch {
            val repository = SmartGuardApp.instance.profileRepository
            val (profileCount, existing) = withContext(Dispatchers.IO) {
                repository.getAllProfiles().size to
                    (if (reenrollProfileId > 0) repository.getProfileById(reenrollProfileId) else null)
            }
            val isParent = SmartGuardApp.instance.sessionState.value.activeRole == UserRole.ADULT

            when {
                existing != null && isParent -> {
                    reenrollProfile = existing
                    binding.tvTitle.text = "Update ${existing.name}'s face"
                    binding.etProfileName.setText(existing.name)
                    binding.etProfileName.isEnabled = false
                    checkRole(UserRole.fromString(existing.role))
                    setRoleButtonsEnabled(false)
                }
                profileCount == 0 -> {
                    firstTimeSetup = true
                    binding.tvTitle.text = "Add the parent's face"
                    checkRole(UserRole.ADULT)
                    setRoleButtonsEnabled(false)
                }
                isParent -> checkRole(UserRole.CHILD)
                else -> {
                    Toast.makeText(this@EnrollmentActivity, "Only a verified parent can add faces", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
            updateSaveButton()
            if (ContextCompat.checkSelfPermission(this@EnrollmentActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun checkRole(role: UserRole) {
        binding.roleGroup.check(
            when (role) {
                UserRole.TEEN -> R.id.btnRoleTeen
                UserRole.ADULT -> R.id.btnRoleParent
                else -> R.id.btnRoleChild
            }
        )
    }

    private fun setRoleButtonsEnabled(enabled: Boolean) {
        binding.btnRoleChild.isEnabled = enabled
        binding.btnRoleTeen.isEnabled = enabled
        binding.btnRoleParent.isEnabled = enabled
    }

    private fun selectedRole(): UserRole = when (binding.roleGroup.checkedButtonId) {
        R.id.btnRoleTeen -> UserRole.TEEN
        R.id.btnRoleParent -> UserRole.ADULT
        else -> UserRole.CHILD
    }

    // ------------------------------------------------------------------ camera + auto capture

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
                analysis.setAnalyzer(cameraExecutor) { proxy -> onFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                SgLog.w(TAG, "Camera start failed: ${e.message}")
                Toast.makeText(this, "Couldn't start the camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the camera thread; one frame at a time, throttled. */
    private fun onFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (done || (!manualCapture.get() && now - lastFrameMs < FRAME_INTERVAL_MS)) {
            proxy.close()
            return
        }
        lastFrameMs = now

        val bitmap = try {
            FrameConverter.toUprightBitmap(proxy)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (bitmap == null) return

        val faces = try {
            runBlocking { detectorManager.detectFaces(bitmap) }
        } catch (e: Exception) {
            emptyList()
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val problem = qualityProblem(face, faces.size)
        if (problem != null) {
            stableCount = 0
            runOnUiThread { binding.tvHint.text = problem }
            return
        }
        face!!

        val stepIndex = capturedVectors.size
        val forced = manualCapture.getAndSet(false)
        val inPose = poses[stepIndex].matches(face.headEulerAngleY, face.headEulerAngleX)
        stableCount = if (inPose) stableCount + 1 else 0

        if (forced || stableCount >= STABLE_FRAMES) {
            stableCount = 0
            // Remember which way the first side / tilt went so the next step asks for the other one.
            if (stepIndex == 1) firstYawSign = sign(face.headEulerAngleY)
            if (stepIndex == 3) firstPitchSign = sign(face.headEulerAngleX)
            val vector = embedder.extractEmbedding(bitmap, face)
            capturedVectors.add(vector)
            SgLog.i(TAG, "Sample ${capturedVectors.size}/${poses.size} (${if (forced) "manual" else "auto"}) yaw=${face.headEulerAngleY} pitch=${face.headEulerAngleX}")
            runOnUiThread { onSampleCaptured() }
        } else {
            runOnUiThread { binding.tvHint.text = if (inPose) "Hold it…" else "Captures automatically. No need to tap." }
        }
    }

    private fun qualityProblem(face: Face?, count: Int): String? = when {
        face == null -> "Fit your face inside the circle"
        count > 1 -> "Only the person being added should be in view"
        face.boundingBox.width() < MIN_FACE_WIDTH_PX -> "Move a little closer"
        (face.leftEyeOpenProbability ?: 1f) < 0.3f && (face.rightEyeOpenProbability ?: 1f) < 0.3f -> "Keep your eyes open"
        else -> null
    }

    // ------------------------------------------------------------------ visuals

    private fun buildStepDots() {
        val size = (10 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        repeat(5) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@EnrollmentActivity, R.color.ring_track))
                }
            }
            binding.stepDots.addView(dot)
        }
    }

    private fun renderStep() {
        val count = capturedVectors.size
        for (i in 0 until binding.stepDots.childCount) {
            val color = when {
                i < count -> R.color.accent_emerald
                i == count -> R.color.primary
                else -> R.color.ring_track
            }
            (binding.stepDots.getChildAt(i).background as GradientDrawable).setColor(ContextCompat.getColor(this, color))
        }
        binding.ringCapture.setProgressCompat(count * 100 / poses.size, true)
        if (done) {
            binding.tvStatus.text = "Face captured"
            binding.tvHint.text = "Enter a name and choose who this is, then save."
            binding.btnCaptureSample.visibility = View.GONE
        } else {
            binding.tvStatus.text = poses[count].instruction
        }
    }

    private fun onSampleCaptured() {
        binding.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        )
        renderStep()
        if (done) {
            binding.doneBadge.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(2.2f)).setDuration(350).start()
            updateSaveButton()
        }
    }

    private fun updateSaveButton() {
        val name = binding.etProfileName.text?.toString()?.trim().orEmpty()
        binding.btnSaveProfile.isEnabled = done && name.isNotEmpty()
        binding.btnSaveProfile.text = when {
            !done -> "Capture the face to continue"
            name.isEmpty() -> "Enter a name to save"
            reenrollProfile != null -> "Update ${name}'s face"
            else -> "Save $name"
        }
    }

    // ------------------------------------------------------------------ save

    private fun saveProfile() {
        val name = binding.etProfileName.text.toString().trim()
        if (name.isEmpty() || !done) return
        binding.btnSaveProfile.isEnabled = false
        val vectors = synchronized(capturedVectors) { capturedVectors.toList() }

        reenrollProfile?.let { existing ->
            lifecycleScope.launch(Dispatchers.IO) {
                SmartGuardApp.instance.profileRepository.replaceProfileEmbeddings(existing.id, vectors)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnrollmentActivity, "${existing.name}'s face updated", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            return
        }

        val role = if (firstTimeSetup) UserRole.ADULT else selectedRole()
        val budgetMinutes = when (role) {
            UserRole.CHILD -> 60
            UserRole.TEEN -> 120
            else -> 0 // unlimited
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = SmartGuardApp.instance.profileRepository
            val draft = ProfileEntity(
                name = name,
                role = role.name,
                screenTimeBudgetMinutes = budgetMinutes,
                allowedPackagesJson = "[]",
                // Restricted-apps model: new children start with social media and browsers restricted.
                blockedPackagesJson = KidsPolicy.toJson(KidsPolicy.defaultBlockedFor(role)),
                isOwner = role == UserRole.ADULT
            )
            val id = repository.insertProfile(draft)
            repository.saveProfileEmbeddings(id, vectors)
            val saved = draft.copy(id = id)

            // Enrolling a parent (e.g. first-time setup) starts a parent session right away.
            if (role == UserRole.ADULT) {
                SmartGuardApp.instance.updateSessionState(
                    SmartGuardApp.instance.sessionState.value.copy(
                        activeProfile = saved,
                        activeRole = UserRole.ADULT,
                        screenTimeBudgetMinutes = 0,
                        remainingScreenTimeSeconds = 999_999L,
                        isSessionLocked = false
                    )
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@EnrollmentActivity, "$name added", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        Thread {
            cameraExecutor.awaitTermination(3, TimeUnit.SECONDS)
            detectorManager.close()
            embedder.close()
        }.start()
    }
}
