package com.smartguard.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the live face mesh the on-device model sees: face oval, eyebrows, eyes, nose and lips as
 * glowing lines with dots at every contour point, tracking the face in real time.
 *
 * Points arrive in camera-frame coordinates (upright, not mirrored). The preview behind this view is
 * a centre-cropped, MIRRORED front-camera image, so points are scaled to fill, centred and flipped.
 * Positions ease towards each new frame so the mesh glides instead of jumping at ~10 fps.
 */
class FaceMeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Closed shapes (oval, eyes, lips) are drawn as loops; the rest as open lines. */
    private val contourTypes = listOf(
        FaceContour.FACE to true,
        FaceContour.LEFT_EYEBROW_TOP to false,
        FaceContour.RIGHT_EYEBROW_TOP to false,
        FaceContour.LEFT_EYE to true,
        FaceContour.RIGHT_EYE to true,
        FaceContour.NOSE_BRIDGE to false,
        FaceContour.NOSE_BOTTOM to false,
        FaceContour.UPPER_LIP_TOP to false,
        FaceContour.LOWER_LIP_BOTTOM to false
    )

    private var target: List<Pair<List<PointF>, Boolean>> = emptyList()
    private var current: List<Pair<MutableList<PointF>, Boolean>> = emptyList()
    private val path = Path()

    init {
        setMeshColor(0xFF3563E9.toInt())
    }

    fun setMeshColor(color: Int) {
        glowPaint.color = color
        glowPaint.alpha = 50
        linePaint.color = color
        linePaint.alpha = 210
        dotPaint.color = color
        invalidate()
    }

    /** Must be called on the main thread. Pass null when no face is in view. */
    fun setFace(face: Face?, frameWidth: Int, frameHeight: Int) {
        if (face == null || width == 0 || height == 0) {
            animate().alpha(0f).setDuration(250).start()
            return
        }
        val size = width.toFloat()
        val scale = max(size / frameWidth, height.toFloat() / frameHeight)
        val dx = (size - frameWidth * scale) / 2f
        val dy = (height - frameHeight * scale) / 2f

        val mapped = contourTypes.mapNotNull { (type, closed) ->
            val pts = face.getContour(type)?.points ?: return@mapNotNull null
            if (pts.isEmpty()) return@mapNotNull null
            pts.map { p -> PointF(size - (p.x * scale + dx), p.y * scale + dy) } to closed
        }
        if (mapped.isEmpty()) return

        target = mapped
        // Snap if the shape changed (first face, or a different set of contours).
        if (current.size != mapped.size || current.zip(mapped).any { (c, m) -> c.first.size != m.first.size }) {
            current = mapped.map { (pts, closed) -> pts.map { PointF(it.x, it.y) }.toMutableList() to closed }
        }
        if (alpha < 1f) animate().alpha(1f).setDuration(200).start()
        postInvalidateOnAnimation()
    }

    /** Brief bright pulse used when the person is recognised. */
    fun flash() {
        animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (current.isEmpty()) return

        var moving = false
        current.forEachIndexed { i, (pts, closed) ->
            val goal = target.getOrNull(i)?.first
            if (goal != null && goal.size == pts.size) {
                pts.forEachIndexed { j, p ->
                    val g = goal[j]
                    val nx = p.x + (g.x - p.x) * 0.35f
                    val ny = p.y + (g.y - p.y) * 0.35f
                    if (abs(nx - p.x) > 0.3f || abs(ny - p.y) > 0.3f) moving = true
                    p.set(nx, ny)
                }
            }
            path.reset()
            pts.forEachIndexed { j, p -> if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (closed) path.close()
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
            val r = 1.6f * density
            pts.forEach { canvas.drawCircle(it.x, it.y, r, dotPaint) }
        }
        if (moving) postInvalidateOnAnimation()
    }
}
