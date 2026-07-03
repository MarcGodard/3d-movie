package com.marc.parallax3d.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * Tablet tilt -> head-equivalent offset. High-frequency half of the fusion:
 * instant response, no camera latency. Static tilt decays to zero via
 * high-pass in HeadPoseFusion, so holding the tablet crooked is neutral.
 */
class GyroTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    // Sensor axes are fixed to the device's natural (portrait) orientation; PlayerActivity
    // is locked landscape, so remap once here or pitch/roll come out swapped.
    private val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        .defaultDisplay.rotation
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    // Tilt in radians, landscape-mapped. Read by fusion at render rate.
    @Volatile var tiltX = 0f  // roll around long axis -> horizontal parallax
    @Volatile var tiltY = 0f  // pitch -> vertical parallax
    @Volatile var timestampNs = 0L

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> rotation.copyInto(remapped)
        }
        SensorManager.getOrientation(remapped, orientation)
        // orientation: [azimuth, pitch, roll]
        tiltY = orientation[1]
        tiltX = orientation[2]
        timestampNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
