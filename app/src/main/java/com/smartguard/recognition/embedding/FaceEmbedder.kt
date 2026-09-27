package com.smartguard.recognition.embedding

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.smartguard.recognition.align.FaceAligner
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Produces an L2-normalized face embedding from a detected face.
 *
 * Primary path: a MobileFaceNet-class TFLite model bundled in assets. Input/output shapes are read
 * from the model itself (e.g. 1x112x112x3 -> 1x192), and a fixed batch dimension (some exports
 * use batch=2) is handled by replicating the input.
 *
 * Fallback path (no model): a zero-centred texture/gradient descriptor on the aligned face. It is
 * far weaker than a real model and is only meant to keep the app functional; the UI warns when it
 * is active. Its dimension ([FALLBACK_DIM]) differs from every real model so the two are never mixed.
 */
class FaceEmbedder(private val context: Context) {

    companion object {
        private const val TAG = "SG-Embed"
        /** Asset names tried in order (asset lookup is case-sensitive inside the APK). */
        private val MODEL_CANDIDATES = listOf("MobileFaceNet.tflite", "mobilefacenet.tflite", "facenet.tflite")

        private const val FALLBACK_SIZE = 63
        private const val FALLBACK_GRID = 7
        const val FALLBACK_DIM = FALLBACK_GRID * FALLBACK_GRID * 3 // 147
    }

    private var interpreter: Interpreter? = null
    private var inputBatch = 1
    private var inputSize = 112
    private var outputDim = FALLBACK_DIM

    /** Asset name of the loaded model, or null when running on the fallback descriptor. */
    var modelName: String? = null
        private set

    /** True when a real embedding model is bundled; false uses the geometry fallback. */
    val isModelLoaded: Boolean get() = interpreter != null

    /** Dimension of vectors this embedder produces (used to avoid mixing embedding spaces). */
    val embeddingDim: Int get() = if (interpreter != null) outputDim else FALLBACK_DIM

    init {
        initInterpreter()
    }

    private fun initInterpreter() {
        for (name in MODEL_CANDIDATES) {
            try {
                val afd = context.assets.openFd(name)
                val buffer = FileInputStream(afd.fileDescriptor).use { input ->
                    input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
                val interp = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })

                val inShape = interp.getInputTensor(0).shape()   // [b, h, w, c]
                val outShape = interp.getOutputTensor(0).shape() // [b, dim]
                inputBatch = inShape[0].coerceAtLeast(1)
                inputSize = inShape[1]
                outputDim = outShape.last()

                interpreter = interp
                modelName = name
                Log.i(TAG, "Loaded $name input=${inShape.contentToString()} output=${outShape.contentToString()}")
                return
            } catch (e: Exception) {
                // Try next candidate.
            }
        }
        Log.w(TAG, "No face-embedding model in assets; using weak fallback descriptor ($FALLBACK_DIM-d)")
    }

    /** Extracts an L2-normalized embedding for [face] found in [bitmap]. */
    fun extractEmbedding(bitmap: Bitmap, face: Face): FloatArray {
        val model = interpreter
        val raw = if (model != null) {
            val aligned = FaceAligner.align(bitmap, face, inputSize)
            runModel(model, aligned)
        } else {
            val aligned = FaceAligner.align(bitmap, face, FALLBACK_SIZE)
            fallbackDescriptor(aligned)
        }
        return l2Normalize(raw)
    }

    private fun runModel(model: Interpreter, face: Bitmap): FloatArray {
        val pixelsPerImage = inputSize * inputSize
        val input = ByteBuffer.allocateDirect(inputBatch * pixelsPerImage * 3 * 4).order(ByteOrder.nativeOrder())

        val pixels = IntArray(pixelsPerImage)
        face.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Replicate the same image across a fixed batch dimension if the model requires one.
        repeat(inputBatch) {
            for (p in pixels) {
                input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128.0f)
                input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128.0f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128.0f)
            }
        }
        input.rewind()

        val output = Array(inputBatch) { FloatArray(outputDim) }
        model.run(input, output)
        return output[0]
    }

    /**
     * Fallback descriptor: per-cell mean intensity + horizontal/vertical gradient energy on a 7x7
     * grid of the aligned grayscale face. Each feature group is standardized (zero mean, unit
     * variance) so cosine similarity measures pattern correlation rather than overall brightness.
     */
    private fun fallbackDescriptor(face: Bitmap): FloatArray {
        val n = FALLBACK_SIZE
        val px = IntArray(n * n)
        face.getPixels(px, 0, n, 0, 0, n, n)
        val gray = FloatArray(n * n) { i ->
            val p = px[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }

        val cells = FALLBACK_GRID * FALLBACK_GRID
        val mean = FloatArray(cells)
        val gx = FloatArray(cells)
        val gy = FloatArray(cells)
        val cell = n / FALLBACK_GRID

        for (cy in 0 until FALLBACK_GRID) for (cx in 0 until FALLBACK_GRID) {
            val idx = cy * FALLBACK_GRID + cx
            var s = 0f; var sx = 0f; var sy = 0f; var count = 0
            for (y in cy * cell until (cy + 1) * cell) for (x in cx * cell until (cx + 1) * cell) {
                val v = gray[y * n + x]
                s += v
                if (x + 1 < n) sx += abs(gray[y * n + x + 1] - v)
                if (y + 1 < n) sy += abs(gray[(y + 1) * n + x] - v)
                count++
            }
            mean[idx] = s / count
            gx[idx] = sx / count
            gy[idx] = sy / count
        }

        return standardize(mean) + standardize(gx) + standardize(gy)
    }

    private fun standardize(v: FloatArray): FloatArray {
        val m = v.average().toFloat()
        var varSum = 0f
        for (x in v) varSum += (x - m) * (x - m)
        val sd = sqrt(varSum / v.size).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { (v[it] - m) / sd }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquare = 0f
        for (v in vector) sumSquare += v * v
        val norm = sqrt(sumSquare).coerceAtLeast(1e-6f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
