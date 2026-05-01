package com.julylove.medical.signal

import kotlin.math.log10

/**
 * SpO2 Estimator using the Ratio-of-Ratios (RoR) method.
 * Formula: R = (AC_red / DC_red) / (AC_ir / DC_ir)
 * For smartphone PPG, we use Red and Blue or Green channels as proxies.
 * SpO2 % = A - B * R, where A and B are calibration constants.
 */
class SpO2Estimator {

    data class SpO2Result(
        val spo2: Float,
        val confidence: Float,
        val status: String
    )

    data class CalibrationProfile(
        val name: String,
        val a: Float,
        val b: Float,
        val useGreenAsIr: Boolean = true
    )

    companion object {
        val DEFAULT_PROFILE = CalibrationProfile("Generic", 110f, 25f)
        
        // Example device-specific profiles (hypothetical)
        val PROFILES = mapOf(
            "Pixel 6" to CalibrationProfile("Pixel 6", 112f, 28f),
            "Samsung S21" to CalibrationProfile("Samsung S21", 108f, 22f),
            "Generic" to DEFAULT_PROFILE
        )
    }

    private var activeProfile = DEFAULT_PROFILE
    private val redBuffer = mutableListOf<Float>()
    private val irBuffer = mutableListOf<Float>() 
    private val windowSize = 150 // ~2.5 seconds at 60fps

    fun setProfileForDevice(model: String) {
        activeProfile = PROFILES[model] ?: DEFAULT_PROFILE
    }

    fun process(red: Float, green: Float, blue: Float, sqi: Float): SpO2Result {
        redBuffer.add(red)
        irBuffer.add(if (activeProfile.useGreenAsIr) green else blue)

        if (redBuffer.size > windowSize) {
            redBuffer.removeAt(0)
            irBuffer.removeAt(0)
        }

        if (redBuffer.size < windowSize || sqi < 0.6f) {
            return SpO2Result(0f, 0f, "CALIBRANDO / CALIDAD BAJA")
        }

        val rRatio = calculateRatio(redBuffer, irBuffer)
        
        // Linear empirical model: SpO2 = A - B*R
        val spo2 = (activeProfile.a - activeProfile.b * rRatio).coerceIn(70f, 100f)
        
        return SpO2Result(
            spo2 = spo2,
            confidence = sqi,
            status = when {
                spo2 < 90 -> "HIPOXIA CRÍTICA"
                spo2 < 94 -> "HIPOXIA LEVE"
                else -> "NORMAL"
            }
        )
    }

    private fun calculateRatio(red: List<Float>, ir: List<Float>): Float {
        val dcRed = red.average().toFloat()
        val dcIr = ir.average().toFloat()

        if (dcRed == 0f || dcIr == 0f) return 1f

        // AC is estimated as the standard deviation or Peak-to-Peak
        val acRed = calculateAC(red, dcRed)
        val acIr = calculateAC(ir, dcIr)

        return (acRed / dcRed) / (acIr / dcIr)
    }

    private fun calculateAC(buffer: List<Float>, dc: Float): Float {
        // RMS of the AC component
        var sumSq = 0f
        for (value in buffer) {
            val ac = value - dc
            sumSq += ac * ac
        }
        return kotlin.math.sqrt(sumSq / buffer.size)
    }
    
    fun calibrate(actualSpO2: Float, currentRatio: Float) {
        // Simple linear adjustment (Implementation of more complex calibration would go here)
    }
}
