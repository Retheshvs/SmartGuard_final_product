package com.smartguard.handover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.policy.SessionController
import com.smartguard.recognition.FrameConverter
import com.smartguard.recognition.pipeline.FaceRecognitionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invisible, event-driven face verification using a camera foreground service.
 *
 * Android 14+ only lets a camera FGS start from the background for exempt apps (notably a
 * Device Owner). When the start is refused we hand off to the visible [com.smartguard.ui.FaceCheckActivity]
 * instead of crashing. The camera is opened only for the verification window, never continuously.
 */
class VerificationForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START_VERIFICATION = "com.smartguard.action.START_VERIFICATION"
        private const val TAG = "SG-VerifySvc"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "smartguard_verification_channel"
        private const val MAX_VERIFICATION_TIMEOUT_MS = 6000L
    }

    private lateinit var pipeline: FaceRecognitionPipeline
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val isProcessingFrame = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var timeoutJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        pipeline = FaceRecognitionPipeline(applicationContext, SmartGuardApp.instance.profileRepository)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val started = try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Checking who is using the phone…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            )
            true
        } catch (e: Exception) {
            // SecurityException / ForegroundServiceStartNotAllowedException when started from background.
            Log.w(TAG, "Camera FGS not allowed from here (${e.javaClass.simpleName}); using visible face check")
            false
        }

        if (!started) {
            VerificationLauncher.launchVisibleCheck(applicationContext, force = true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_VERIFICATION) {
            startCameraAndVerify()
        }
        return START_NOT_STICKY
    }

    private fun startCameraAndVerify() {
        pipeline.resetGate()
        finished.set(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = SmartGuardApp.instance.profileRepository.getAllProfiles()
            if (profiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    showToast("No face profiles enrolled yet! Tap 'Enroll Face' in the app.")
                    finish(SessionController.applyFailSafe("No profiles enrolled").message)
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                timeoutJob?.cancel()
                timeoutJob = lifecycleScope.launch {
                    delay(MAX_VERIFICATION_TIMEOUT_MS)
                    finish(SessionController.applyFailSafe("Verification timeout").message)
                }

                val future = ProcessCameraProvider.getInstance(this@VerificationForegroundService)
                future.addListener({
                    try {
                        cameraProvider = future.get()
                        bindCameraUseCases()
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera init failed", e)
                        finish(SessionController.applyFailSafe("Camera init error").message)
                    }
                }, ContextCompat.getMainExecutor(this@VerificationForegroundService))
            }
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (finished.get() || !isProcessingFrame.compareAndSet(false, true)) {
                imageProxy.close()
                return@setAnalyzer
            }
            processFrame(imageProxy)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            finish(SessionController.applyFailSafe("Camera bind error").message)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = try {
            FrameConverter.toUprightBitmap(imageProxy)
        } catch (e: Exception) {
            null
        } finally {
            imageProxy.close()
        }

        if (bitmap == null) {
            isProcessingFrame.set(false)
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val result = pipeline.processFrame(bitmap)
            val outcome = SessionController.resolve(result)
            withContext(Dispatchers.Main) {
                if (outcome != null) finish(outcome.message) else isProcessingFrame.set(false)
            }
        }
    }

    private fun finish(message: String) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        showToast(message)
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SmartGuard Identity Verification", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartGuard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_smartguard)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        pipeline.close()
        cameraExecutor.shutdown()
    }
}
