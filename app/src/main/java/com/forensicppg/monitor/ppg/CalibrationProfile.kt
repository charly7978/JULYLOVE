package com.forensicppg.monitor.ppg

/**
 * Perfil de calibración de SpO₂ por dispositivo + cámara + parámetros ópticos.
 * Coeficientes provienen de regresión — no valores universales pretendidos clinicamente.
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
    val coefficientC: Double?,
    val createdAtMs: Long,
    val algorithmVersion: String,
    val calibrationSamples: Int,
    val minPerfusionIndex: Double,
    val notes: String
) {

    /** R = ratio-of-ratios AC/DC. Modelo legacy lineal ó polinómico con [coefficientC] si existe. */
    fun apply(ratioOfRatios: Double): Double {
        val raw = if (coefficientC != null) {
            coefficientA + coefficientB * ratioOfRatios + coefficientC!! * ratioOfRatios * ratioOfRatios
        } else {
            coefficientA - coefficientB * ratioOfRatios
        }
        return raw.coerceIn(70.0, 100.0)
    }
}

data class CalibrationPoint(
    val capturedAtMs: Long,
    val referenceSpo2: Double,
    val ratioOfRatios: Double,
    val sqi: Double,
    val perfusionIndex: Double,
    val motionScore: Double
)
