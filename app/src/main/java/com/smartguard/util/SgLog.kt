package com.smartguard.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logcat + (debug builds only) a small on-device log file.
 *
 * vivo/iQOO sometimes silences logcat entirely for an app process that was started in the
 * background (e.g. by the system binding the accessibility service), which made enforcement
 * decisions impossible to verify. In debuggable builds every line is also appended to
 * files/sg_debug.log (capped at ~256 KB), readable with:
 *   adb exec-out run-as com.smartguard cat files/sg_debug.log
 */
object SgLog {

    private const val MAX_BYTES = 256 * 1024
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        file = if (debuggable) File(context.filesDir, "sg_debug.log") else null
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val f = file ?: return
        val line = "${fmt.format(Date())} $level $tag: $msg\n"
        io.execute {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    // Keep the newest half.
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2))
                }
                f.appendText(line)
            } catch (e: Exception) {
                // never let debug logging break the app
            }
        }
    }
}
