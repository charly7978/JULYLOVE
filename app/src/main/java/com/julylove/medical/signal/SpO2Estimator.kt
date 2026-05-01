package com.julylove.medical.signal

import kotlin.math.sqrt

/**
 * Spo2Estimator: Ratio-of-Ratios (RoR) implementation for PPG SpO2 estimation.
 * Formula: R = (AC_red / DC_red) / (AC_green / DC_green)
 * SpO2 = A - B * R
 */
class Spo2Estimator {

    data class CalibrationProfile(
        val name: String,
        val a: Double,
        val b: Double
    )

    companion object {
        val DEFAULT_PROFILE = CalibrationProfile("Generic", 110.0, 25.0)
    }

    private var activeProfile = DEFAULT_PROFILE
    
    // AC/DC Tracking for RoR calculation
    private val redBuffer = mutableListOf<Float>()
    private val greenBuffer = mutableListOf<Float>()
    private val windowSize = 150 // ~2-3 seconds

    data class Spo2Result(
        val spo2: Float,
        val confidence: Double,
        val status: String
    )

    fun process(
        red: Float, green: Float, blue: Float,
        sqi: Float
    ): Spo2Result {
        redBuffer.add(red)
        greenBuffer.add(green)
        if (redBuffer.size > windowSize) {
            redBuffer.removeAt(0)
            greenBuffer.removeAt(0)
        }

        if (sqi < 0.5f || redBuffer.size < windowSize) {
            return Spo2Result(0f, 0.0, "ESTABILIZANDO")
        }

        val redAc = (redBuffer.maxOrNull() ?: 0f) - (redBuffer.minOrNull() ?: 0f)
        val redDc = redBuffer.average().toFloat()
        
        val greenAc = (greenBuffer.maxOrNull() ?: 0f) - (greenBuffer.minOrNull() ?: 0f)
        val greenDc = greenBuffer.average().toFloat()

        if (redDc < 1f || greenDc < 1f || greenAc < 0.01f) {
            return Spo2Result(0f, 0.0, "SIGNAL_LOW")
        }

        val rRatio = (redAc / redDc) / (greenAc / greenDc)
        val spo2 = (activeProfile.a - activeProfile.b * rRatio).toFloat().coerceIn(70f, 100f)
        
        val status = when {
            spo2 < 90f -> "HIPOXIA CRÍTICA"
            spo2 < 94f -> "HIPOXIA LEVE"
            else -> "NORMAL"
        }

        return Spo2Result(spo2, sqi.toDouble(), status)
    }

    fun setProfileForDevice(model: String) {
        // Device specific calibration can be added here
        activeProfile = DEFAULT_PROFILE
    }

    fun setProfile(profile: CalibrationProfile) {
        activeProfile = profile
    }
}
