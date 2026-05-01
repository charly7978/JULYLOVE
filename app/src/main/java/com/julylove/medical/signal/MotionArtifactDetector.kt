package com.julylove.medical.signal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Acelerómetro + giroscopio: rechazo de artefactos por movimiento relativo del dedo-lente.
 * Coherente con literatura sobre gating de segmentos contaminados en PPG móvil (p. ej. trabajos post-2020 en DL + IMU).
 */
class MotionArtifactDetector(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private var accelerationTotal = 0f

    private var lastGyroMag = 0f
    private var gyroDeltaEma = 0f

    var isMoving = false
        private set

    var motionIntensity = 0f
        private set

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                lastAcceleration = currentAcceleration
                currentAcceleration = sqrt(x * x + y * y + z * z)
                val delta = kotlin.math.abs(currentAcceleration - lastAcceleration)
                accelerationTotal = accelerationTotal * 0.9f + delta * 0.1f
            }

            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val mag = sqrt(gx * gx + gy * gy + gz * gz)
                val d = kotlin.math.abs(mag - lastGyroMag)
                lastGyroMag = mag
                gyroDeltaEma = gyroDeltaEma * 0.85f + d * 0.15f
            }
        }

        val combined = accelerationTotal + gyroDeltaEma * 2.5f
        motionIntensity = combined
        isMoving = combined > 0.55f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
