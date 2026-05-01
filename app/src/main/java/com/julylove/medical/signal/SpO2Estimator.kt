package com.julylove.medical.signal

import kotlin.math.sqrt

/**
 * Spo2Estimator: Ratio-of-Ratios (RoR) sobre pulsos AC/DC en canal rojo vs verde.
 * R ≈ (ACr/Dcr)/(ACg/Dcg); SpO2 ≈ a − b·R con (a,b) calibración por cohorte/dispositivo.
 * Limitaciones: sin LED IR dedicado como pulsioximetría clínica → MAEs mayores; estudios DL reportan órdenes ~5% MAE.
 * QC: coeficientes de variación alto o SQI bajo ⇒ no exponer número (valor 0 y estado cualitativo).
 */
class Spo2Estimator {

    data class CalibrationProfile(
        val name: String,
        val a: Double,
        val b: Double
    )

    companion object {
        /** Perfil sin calibración por dispositivo / cohorte: no se expone % en UI (evita “medición” ficticia). */
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
        if (activeProfile.name == DEFAULT_PROFILE.name) {
            return Spo2Result(0f, 0.0, "SpO₂: requiere calibración por dispositivo (RoR no mostrado)")
        }
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

        val redCv = coefficientOfVariation(redBuffer)
        val greenCv = coefficientOfVariation(greenBuffer)
        if (rRatio.isNaN() || rRatio.isInfinite() || redCv > 0.22f || greenCv > 0.22f) {
            return Spo2Result(0f, sqi.toDouble(), "SEÑAL_INESTABLE")
        }

        val spo2 = (activeProfile.a - activeProfile.b * rRatio).toFloat().coerceIn(70f, 100f)

        if (sqi < 0.55f) {
            return Spo2Result(0f, sqi.toDouble(), "QC: SEÑAL DÉBIL")
        }

        val status = when {
            spo2 < 90f -> "HIPOXIA CRÍTICA"
            spo2 < 94f -> "HIPOXIA LEVE"
            else -> "NORMAL"
        }

        return Spo2Result(spo2, sqi.toDouble(), status)
    }

    fun setProfileForDevice(model: String) {
        activeProfile = DEFAULT_PROFILE
    }

    fun setProfile(profile: CalibrationProfile) {
        activeProfile = profile
    }

    private fun coefficientOfVariation(buf: List<Float>): Float {
        if (buf.size < 8) return 1f
        val mean = buf.average().toFloat().coerceAtLeast(1e-3f)
        var s = 0f
        for (v in buf) {
            val d = v - mean
            s += d * d
        }
        return sqrt(s / buf.size) / mean
    }
}
