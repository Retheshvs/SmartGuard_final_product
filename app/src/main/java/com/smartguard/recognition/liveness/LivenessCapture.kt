package com.smartguard.recognition.liveness

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/**
 * DEBUG BUILDS ONLY, OFF BY DEFAULT: saves the frames the liveness model judged, so its threshold and
 * crop can be tuned offline on real front-camera images. Switched on by creating [FLAG] (the debug
 * probe does this) and never active in a release build, so "no images stored" holds for real users.
 *
 * Writes files/liveness_capture/<n>_<score>.jpg (frame, max 480 px) plus one line per frame in
 * frames.csv: file, score, face box (in the saved image), pitch, yaw. Capped at [MAX_FRAMES].
 */
object LivenessCapture {

    private const val FLAG = "liveness_capture.on"
    private const val DIR = "liveness_capture"
    private const val MAX_FRAMES = 400
    private const val MAX_SIDE = 480f

    private var count = -1

    fun isOn(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            File(context.filesDir, FLAG).exists()

    fun setOn(context: Context, on: Boolean) {
        val flag = File(context.filesDir, FLAG)
        if (on) flag.createNewFile() else flag.delete()
    }

    @Synchronized
    fun save(context: Context, frame: Bitmap, box: Rect, score: Float, pitch: Float, yaw: Float) {
        if (!isOn(context)) return
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        if (count < 0) count = dir.list()?.count { it.endsWith(".jpg") } ?: 0
        if (count >= MAX_FRAMES) return
        val k = minOf(1f, MAX_SIDE / maxOf(frame.width, frame.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(frame, (frame.width * k).toInt(), (frame.height * k).toInt(), true) else frame
        val name = "%04d_%.2f.jpg".format(count++, score)
        FileOutputStream(File(dir, name)).use { small.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        File(dir, "frames.csv").appendText(
            "%s,%.4f,%d,%d,%d,%d,%.1f,%.1f,%d\n".format(
                name, score, (box.left * k).toInt(), (box.top * k).toInt(), (box.right * k).toInt(), (box.bottom * k).toInt(),
                pitch, yaw, System.currentTimeMillis()
            )
        )
        if (small !== frame) small.recycle()
    }
}
