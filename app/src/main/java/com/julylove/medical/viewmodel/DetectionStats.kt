package com.julylove.medical.viewmodel

import com.julylove.medical.signal.PeakDetectorElgendi
import com.julylove.medical.signal.PeakDetectorDerivative
import com.julylove.medical.signal.HeartRateFusion
import com.julylove.medical.signal.BeatClassifier
import com.julylove.medical.signal.ArrhythmiaScreening

/**
 * DetectionStats: Estadísticas combinadas de todos los componentes de detección
 * Proporciona una vista unificada del rendimiento del pipeline de detección
 */
data class DetectionStats(
    val elgendiStats: PeakDetectorElgendi.DetectorStats,
    val derivativeStats: PeakDetectorDerivative.DetectorStats,
    val fusionStats: HeartRateFusion.FusionStats,
    val beatClassifierStats: BeatClassifier.ClassificationStats,
    val arrhythmiaStats: ArrhythmiaScreening.EventsSummary
) {
    /**
     * Obtiene tasa de detección general
     */
    fun getOverallDetectionRate(): Double {
        val totalDetections = elgendiStats.windowShort + derivativeStats.analysisWindow
        return if (totalDetections > 0) {
            (fusionStats.totalFusedDetections.toDouble() / totalDetections) * 100
        } else 0.0
    }
    
    /**
     * Determina calidad general de detección
     */
    fun getDetectionQuality(): DetectionQuality {
        val detectionRate = getOverallDetectionRate()
        val avgConfidence = beatClassifierStats.averageConfidence
        val irregularityRatio = beatClassifierStats.irregularityRatio
        
        return when {
            detectionRate > 80 && avgConfidence > 0.7 && irregularityRatio < 0.1 -> DetectionQuality.EXCELLENT
            detectionRate > 70 && avgConfidence > 0.6 && irregularityRatio < 0.2 -> DetectionQuality.GOOD
            detectionRate > 50 && avgConfidence > 0.4 && irregularityRatio < 0.3 -> DetectionQuality.ACCEPTABLE
            detectionRate > 30 && avgConfidence > 0.2 -> DetectionQuality.MARGINAL
            else -> DetectionQuality.POOR
        }
    }
    
    /**
     * Verifica si hay problemas significativos
     */
    fun hasSignificantIssues(): Boolean {
        return when {
            getOverallDetectionRate() < 30 -> true  // Baja detección
            beatClassifierStats.invalidBeats > beatClassifierStats.normalBeats -> true // Muchos beats inválidos
            arrhythmiaStats.highSeverityEvents >= 2 -> true // Eventos de arritmia de alta severidad
            fusionStats.fusionRate < 20 -> true // Baja tasa de fusión
            else -> false
        }
    }
    
    /**
     * Genera resumen legible
     */
    fun generateSummary(): String {
        return "Detección: ${"%.1f".format(getOverallDetectionRate())}% | " +
               "Confianza: ${"%.2f".format(beatClassifierStats.averageConfidence)} | " +
               "Irregularidad: ${"%.1f".format(beatClassifierStats.irregularityRatio * 100)}% | " +
               "Calidad: ${getDetectionQuality()}"
    }
}

/**
 * Calidad de detección general
 */
enum class DetectionQuality {
    EXCELLENT,
    GOOD,
    ACCEPTABLE,
    MARGINAL,
    POOR
}
