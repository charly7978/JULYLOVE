package com.julylove.medical.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Lux ambientales para modular exposición ISO/tiempo con flash (teléfonos sin calibración clínica de LED IR).
 * Referencia práctica: manuales FCC / estudios de PPG con smartphone (variación lumínica y saturación CMOS).
 */
class AmbientLightMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    @Volatile
    var lux: Float = 80f
        private set

    fun start() {
        light?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            lux = event.values[0].coerceIn(0f, 50_000f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
