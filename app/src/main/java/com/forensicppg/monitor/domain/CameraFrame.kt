package com.forensicppg.monitor.domain

/**
 * Datos agregados de un frame real de la cámara.
 *
 * Sólo contiene métricas estadísticas por canal (R/G/B) y metadata de captura
 * real. No se guarda el bitmap completo por frame para controlar la latencia
 * y el uso de memoria.
 */
data class CameraFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val cameraId: String,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    val redMean: Double,
    val greenMean: Double,
    val blueMean: Double,
    val redAcDc: Double,
    val greenAcDc: Double,
    val blueAcDc: Double,
    val clipHighRatio: Double,
    val clipLowRatio: Double,
    val roiCoverage: Double,
    val roiVariance: Double
) {
    val redGreenRatio: Double get() = if (greenMean > 1.0) redMean / greenMean else 0.0
    val redBlueRatio: Double get() = if (blueMean > 1.0) redMean / blueMean else 0.0
    val greenBlueRatio: Double get() = if (blueMean > 1.0) greenMean / blueMean else 0.0
}
