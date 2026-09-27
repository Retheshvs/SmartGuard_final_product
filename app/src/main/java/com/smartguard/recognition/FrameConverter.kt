package com.smartguard.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Single source of truth for turning a CameraX frame into an upright Bitmap.
 *
 * Enrollment and verification MUST preprocess frames identically, otherwise the stored
 * embeddings and the live embeddings live in slightly different spaces and matching degrades.
 * No mirroring is applied: recognition only needs consistency, not a selfie-style view.
 */
object FrameConverter {

    fun toUprightBitmap(imageProxy: ImageProxy): Bitmap {
        // toBitmap() handles row stride / pixel stride padding correctly (a raw
        // copyPixelsFromBuffer() does not, and can crash or skew the image).
        val raw = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return raw

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
}
