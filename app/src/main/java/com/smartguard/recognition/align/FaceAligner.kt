package com.smartguard.recognition.align

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.max

/**
 * Eye-based face alignment: rotates the face so the eyes are level, then takes a square crop
 * centred on the face and resamples it to [outSize] x [outSize] in a single bilinear pass.
 *
 * Embedding models (MobileFaceNet / FaceNet) are trained on aligned crops; feeding them tilted,
 * off-centre boxes noticeably lowers genuine-match similarity.
 */
object FaceAligner {

    private const val MAX_ROLL_DEGREES = 45.0
    // Slight margin around ML Kit's box so the chin/forehead are not clipped (MTCNN-style square).
    private const val BOX_SCALE = 1.15f

    fun align(src: Bitmap, face: Face, outSize: Int): Bitmap {
        val box = face.boundingBox
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val side = max(box.width(), box.height()).toFloat().coerceAtLeast(1f) * BOX_SCALE

        val rollDegrees = eyeRollDegrees(face)

        val scale = outSize / side
        val matrix = Matrix().apply {
            postTranslate(-cx, -cy)          // face centre -> origin
            postRotate(-rollDegrees)         // level the eyes
            postScale(scale, scale)          // face side -> outSize
            postTranslate(outSize / 2f, outSize / 2f)
        }

        val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Angle of the line between the two eyes, ordered left-to-right in image space. */
    private fun eyeRollDegrees(face: Face): Float {
        val a = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val b = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (a == null || b == null) {
            // Fall back to ML Kit's head roll estimate.
            return face.headEulerAngleZ.coerceIn(-MAX_ROLL_DEGREES.toFloat(), MAX_ROLL_DEGREES.toFloat())
        }
        val (p1, p2) = if (a.x <= b.x) a to b else b to a
        val deg = Math.toDegrees(atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble()))
        return deg.coerceIn(-MAX_ROLL_DEGREES, MAX_ROLL_DEGREES).toFloat()
    }
}
