package com.julylove.medical.signal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Uses Accelerometer and Gyroscope to detect motion artifacts.
 * If the device is moving significantly, PPG signal is likely contaminated.
 */
class MotionArtifactDetector(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private var accelerationTotal = 0f
    
    var isMoving = false
        private set
    
    var motionIntensity = 0f
        private set

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            lastAcceleration = currentAcceleration
            currentAcceleration = sqrt(x * x + y * y + z * z)
            val delta = Math.abs(currentAcceleration - lastAcceleration)
            accelerationTotal = accelerationTotal * 0.9f + delta * 0.1f
            
            motionIntensity = accelerationTotal
            isMoving = accelerationTotal > 0.5f // Threshold for "significant" movement
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
