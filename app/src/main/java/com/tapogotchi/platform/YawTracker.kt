package com.tapogotchi.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Light 3-DoF yaw for the pet's room-presence parallax: turn your head and
 * the little one slides the other way, then lazily drifts back to center —
 * it reads as a creature floating in your space, not a sticker on glass.
 * (The lazy re-anchor means no re-center gesture is ever needed.)
 */
class YawTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotMat = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var yawDeg = 0f
    @Volatile private var anchorDeg = 0f
    private var hasData = false

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        hasData = false
    }

    /** Parallax offset in "pet pixels", clamped; eases home over ~4 s. */
    fun parallaxPx(maxPx: Float): Float {
        if (!hasData) return 0f
        var d = yawDeg - anchorDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        // lazy re-anchor: the room follows you eventually
        anchorDeg += 0.015f * d
        return (-d / 25f).coerceIn(-1f, 1f) * maxPx
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        SensorManager.getOrientation(rotMat, orientation)
        val newYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (!hasData) {
            anchorDeg = newYaw
            hasData = true
        }
        var d = newYaw - yawDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        yawDeg += 0.3f * d
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
