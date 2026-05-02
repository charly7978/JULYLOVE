package com.forensicppg.monitor.ppg

/**
 * Perfil de calibración de SpO₂ por dispositivo + cámara + parámetros ópticos.
 *
 * Nunca se genera ni asume un perfil por default. La app exige que el
 * usuario realice la rutina de calibración contra un oxímetro de referencia
 * antes de mostrar un número absoluto de SpO₂.
 */
data class CalibrationProfile(
    val profileId: String,
    val deviceModel: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    val torchIntensity: Double?,
    val coefficientA: Double,
    val coefficientB: Double,
    val createdAtMs: Long,
    val algorithmVersion: String,
    val calibrationSamples: Int,
    val minPerfusionIndex: Double,
    val notes: String
) {
    /** SpO₂ = A − B × R, donde R = (AC_r/DC_r) / (AC_b/DC_b). Sólo se invoca
     *  si existe un CalibrationProfile válido. */
    fun apply(ratioOfRatios: Double): Double {
        val raw = coefficientA - coefficientB * ratioOfRatios
        return raw.coerceIn(70.0, 100.0)
    }
}

/**
 * Punto de calibración: lectura de SpO₂ real medida por un oxímetro externo
 * (referencia) vs la ratio-of-ratios calculada por el pipeline en ese instante.
 */
data class CalibrationPoint(
    val capturedAtMs: Long,
    val referenceSpo2: Double,
    val ratioOfRatios: Double,
    val sqi: Double,
    val perfusionIndex: Double,
    val motionScore: Double
)
