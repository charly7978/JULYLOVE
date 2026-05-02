package com.forensicppg.monitor.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * Wrapper del acelerómetro (+ giroscopio si está). Produce un Flow caliente con
 * muestras reales, sin generar valores si el sensor no está disponible.
 */
class MotionSensorController(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasAccelerometer: Boolean get() = accel != null
    val hasGyroscope: Boolean get() = gyro != null

    /** Flow frío de muestras combinadas (acelerómetro obligatorio + giroscopio opcional). */
    fun stream(): Flow<MotionSample> = callbackFlow {
        if (accel == null) {
            close(); return@callbackFlow
        }
        var lastAx = 0f; var lastAy = 0f; var lastAz = 0f
        var lastGyro: Float? = null
        var lastTs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAx = event.values[0]
                        lastAy = event.values[1]
                        lastAz = event.values[2]
                        lastTs = event.timestamp
                        trySend(MotionSample(lastTs, lastAx, lastAy, lastAz, lastGyro))
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val gx = event.values[0]; val gy = event.values[1]; val gz = event.values[2]
                        lastGyro = sqrt(gx * gx + gy * gy + gz * gz)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        gyro?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
