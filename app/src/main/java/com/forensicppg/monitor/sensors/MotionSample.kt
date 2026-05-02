package com.forensicppg.monitor.sensors

/**
 * Muestra instantánea del acelerómetro. Los valores son m/s² sin gravedad
 * restada (datos crudos). Se computa la magnitud como sqrt(x²+y²+z²).
 */
data class MotionSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gyroMag: Float? = null
) {
    val accelMagnitude: Float
        get() = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
}
