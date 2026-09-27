package com.smartguard.recognition.liveness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Passive liveness / anti-spoof: is this a real face, or a printed photo / a face on a screen?
 *
 * Runs a MiniFASNetV2-SE model ([MODEL_FILE_NAME], facenox/face-antispoof-onnx, Apache-2.0, converted
 * to TFLite) on every good-quality recognition frame: a square crop around the face, judged on skin
 * texture vs. print / screen (moiré, glare) patterns.
 *
 * Efficient by design: ~0.4M-parameter network on a 128×128 crop; the crop is drawn straight into a
 * reused bitmap and the input buffer is reused, so a frame allocates nothing.
 *
 * Model contract: input 1×128×128×3 (NHWC) or 1×3×128×128 (NCHW), RGB in [0,1]; output two logits
 * [real, spoof]. Live score = sigmoid(real − spoof), live if ≥ [LIVE_THRESHOLD]. If the model is missing, passive scoring is off
 * ([isModelLoaded] false) and recognition still works.
 */
class LivenessManager(context: Context) {

    companion object {
        const val MODEL_FILE_NAME = "antispoof_minifasnetv2se.tflite"

        /*
         * Tuned on 128 real front-camera frames from this phone (straight, low, tilted, backlit, dim,
         * blurry; two people) plus photo-on-a-phone attacks:
         *   crop 1.5× / P≥0.50 (model default): 52% of real frames live, 79% of real checks pass in 5 frames
         *   crop 1.3× / P≥0.12 (logit ≥ −2):    84% of real frames live, 97% of real checks pass in 5 frames
         * Every attack frame stayed at logit ≤ −6.8 (P ≤ 0.001), far below the threshold either way.
         */
        private const val CROP_SCALE = 1.3f
        /** P(real) needed to call a frame live (= real−spoof logit ≥ −2). */
        const val LIVE_THRESHOLD = 0.12f
    }

    data class LivenessResult(
        val isLive: Boolean,
        val score: Float,
        val enforced: Boolean // true only when the model produced the decision
    )

    private var interpreter: Interpreter? = null
    private var inputSize = 128
    private var channelsFirst = false
    private lateinit var input: ByteBuffer
    private lateinit var pixels: IntArray
    private lateinit var crop: Bitmap
    private lateinit var canvas: Canvas
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val output = Array(1) { FloatArray(2) }
    private val src = Rect()
    private val dst = RectF()

    init {
        try {
            val afd = context.assets.openFd(MODEL_FILE_NAME)
            val buffer = FileInputStream(afd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
            val interp = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            val shape = interp.getInputTensor(0).shape()
            channelsFirst = shape.size == 4 && shape[1] == 3
            inputSize = if (channelsFirst) shape[2] else shape[1]
            input = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
            pixels = IntArray(inputSize * inputSize)
            crop = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            canvas = Canvas(crop)
            interpreter = interp
        } catch (e: Exception) {
            interpreter = null
        }
    }

    val isModelLoaded: Boolean get() = interpreter != null

    fun analyze(bitmap: Bitmap, face: Face): LivenessResult {
        val model = interpreter ?: return LivenessResult(isLive = true, score = 1f, enforced = false)
        return try {
            val score = score(model, bitmap, face.boundingBox)
            LivenessResult(isLive = score >= LIVE_THRESHOLD, score = score, enforced = true)
        } catch (e: Exception) {
            LivenessResult(isLive = true, score = 1f, enforced = false)
        }
    }

    private fun score(model: Interpreter, bitmap: Bitmap, box: Rect): Float {
        // Square crop around the face, CROP_SCALE × its larger side. Never pad with black: a dark border around a
        // face looks exactly like a phone's bezel to the model (the phone held low puts the face near the
        // frame edge). Instead slide the square back inside the frame, shrinking it only if it can't fit.
        val side = minOf(maxOf(box.width(), box.height()) * CROP_SCALE, bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (box.exactCenterX() - side / 2f).coerceIn(0f, bitmap.width - side)
        val top = (box.exactCenterY() - side / 2f).coerceIn(0f, bitmap.height - side)
        src.set(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
        dst.set(0f, 0f, inputSize.toFloat(), inputSize.toFloat())
        canvas.drawBitmap(bitmap, src, dst, paint)

        crop.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        input.rewind()
        if (channelsFirst) {
            for (shift in intArrayOf(16, 8, 0)) for (p in pixels) input.putFloat(((p shr shift) and 0xFF) / 255f)
        } else {
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
        }
        input.rewind()
        model.run(input, output)
        val (real, spoof) = output[0]
        return (1.0 / (1.0 + exp((spoof - real).toDouble()))).toFloat()
    }

    fun reset() = Unit

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
