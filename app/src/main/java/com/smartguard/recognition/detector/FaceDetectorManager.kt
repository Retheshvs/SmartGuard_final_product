package com.smartguard.recognition.detector

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages ML Kit Face Detection.
 * Hard Constraint: Runs 100% on-device with zero network access.
 */
class FaceDetectorManager(withContours: Boolean = false) {

    private val options = FaceDetectorOptions.Builder()
        // FAST is ample for a face at arm's length and roughly halves per-frame latency at unlock.
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        // Contours (face oval, eyes, brows, lips, nose) only when a screen visualises them.
        .setContourMode(if (withContours) FaceDetectorOptions.CONTOUR_MODE_ALL else FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(options)

    /**
     * Detects faces asynchronously from a Bitmap.
     */
    suspend fun detectFaces(bitmap: Bitmap): List<Face> = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                continuation.resume(faces)
            }
            .addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
    }

    fun close() {
        detector.close()
    }
}
