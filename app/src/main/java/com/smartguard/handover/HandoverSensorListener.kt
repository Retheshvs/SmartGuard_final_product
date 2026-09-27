package com.smartguard.handover

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-power accelerometer listener that recognises a PICK-UP / HAND-OVER: the phone was resting
 * (still) for a few seconds and then moves sharply. Ordinary movement while someone is already
 * holding and using the phone does not trigger it, so identity checks don't fire repeatedly.
 */
class HandoverSensorListener(
    context: Context,
    private val onPickUpDetected: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastMagnitude = SensorManager.GRAVITY_EARTH
    private var shakeMagnitude = 0f
    private var lastMovementMs = System.currentTimeMillis()
    private var lastTriggerMs = 0L

    companion object {
        private const val STILL_JITTER = 0.35f       // m/s^2 change still counted as "resting"
        private const val PICK_UP_THRESHOLD = 3.5f   // smoothed acceleration change for a pick-up
        private const val MIN_REST_BEFORE_PICKUP_MS = 3_000L
        private const val DEBOUNCE_MS = 3_000L
    }

    fun startListening() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = magnitude - lastMagnitude
        lastMagnitude = magnitude
        shakeMagnitude = shakeMagnitude * 0.9f + delta

        val now = System.currentTimeMillis()
        val restedFor = now - lastMovementMs

        if (shakeMagnitude > PICK_UP_THRESHOLD) {
            if (restedFor >= MIN_REST_BEFORE_PICKUP_MS && now - lastTriggerMs > DEBOUNCE_MS) {
                lastTriggerMs = now
                onPickUpDetected()
            }
        }
        if (abs(delta) > STILL_JITTER) lastMovementMs = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
