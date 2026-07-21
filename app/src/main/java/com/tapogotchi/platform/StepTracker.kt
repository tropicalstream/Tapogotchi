package com.tapogotchi.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Step counting with graceful degradation (the WanderQuest-proven chain):
 *   TYPE_STEP_DETECTOR -> TYPE_STEP_COUNTER -> accelerometer peak detection.
 * Steps while wearing the glasses WALK YOUR PET — the one thing no
 * pocket tamagotchi could ever know about you.
 */
class StepTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var onStep: ((delta: Long) -> Unit)? = null

    private var counterBaseline = -1L
    private var emaGravity = 9.81f
    private var emaSignal = 0f
    private var lastStepMs = 0L
    private var aboveThreshold = false

    fun start() {
        val stepPermission = context.checkSelfPermission(
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            stepPermission && detector != null ->
                sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_UI)
            stepPermission && counter != null ->
                sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI)
            accel != null ->
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        counterBaseline = -1
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> onStep?.invoke(1)
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0].toLong()
                if (counterBaseline < 0) counterBaseline = total
                val delta = total - counterBaseline
                if (delta > 0) {
                    counterBaseline = total
                    onStep?.invoke(delta)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val mag = sqrt(x * x + y * y + z * z)
                emaGravity += 0.02f * (mag - emaGravity)
                val residual = mag - emaGravity
                emaSignal += 0.35f * (residual - emaSignal)
                val now = SystemClock.uptimeMillis()
                if (!aboveThreshold && emaSignal > 1.6f) {
                    aboveThreshold = true
                    if (now - lastStepMs > 320L) {
                        lastStepMs = now
                        onStep?.invoke(1)
                    }
                } else if (aboveThreshold && emaSignal < 0.7f) {
                    aboveThreshold = false
                }
                if (abs(residual) > 40f) emaSignal = 0f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
}
