package com.julylove.medical.signal

import kotlin.math.abs

/**
 * HeartRateFusion: Fusión inteligente de detectores de picos PPG
 * Combina resultados de Elgendi y Derivative con reglas clínicas
 * 
 * Estrategia:
 * 1. Consenso: Ambos detectores coinciden temporalmente
 * 2. Confianza: Un detector con alta confianza + SQI alto
 * 3. Consistencia: Validación con RR intervals previos
 * 4. Rechazo: Detecciones conflictivas o de baja calidad
 */
class HeartRateFusion {
    
    // Ventanas temporales para fusión (en nanosegundos)
    private val fusionWindowNs: Long = 50_000_000L  // 50ms para coincidencia temporal
    private val maxRRVariationNs: Long = 200_000_000L // 200ms variación máxima
    
    // Historial de RR intervals para validación
    private val rrHistory = mutableListOf<Long>()
    private val maxHistorySize = 10
    
    // Contadores para estadísticas
    private var totalElgendiDetections = 0
    private var totalDerivativeDetections = 0
    private var totalFusedDetections = 0
    private var totalRejections = 0
    
    data class FusedBeat(
        val timestampNs: Long,
        val rrMs: Double?,
        val bpmInstant: Double?,
        val confidence: Double,
        val amplitude: Double,
        val detectionMethod: DetectionMethod,
        val elgendiData: PeakDetectorElgendi.ElgendiPeak?,
        val derivativeData: PeakDetectorDerivative.DerivativePeak?,
        val fusionReason: String,
        val qualityFlags: Set<QualityFlag>
    )
    
    enum class DetectionMethod {
        CONSENSUS,        // Ambos detectores coinciden
        ELGENDI_HIGH_CONF, // Solo Elgendi con alta confianza
        DERIVATIVE_HIGH_CONF, // Solo Derivative con alta confianza
        SINGLE_VALIDATED  // Un detector validado por historial
    }
    
    enum class QualityFlag {
        HIGH_CONFIDENCE,
        CONSISTENT_RR,
        GOOD_MORPHOLOGY,
        LOW_NOISE,
        TEMPORAL_ISOLATED
    }
    
    /**
     * Fusiona detecciones de ambos métodos
     */
    fun fuseDetections(
        elgendiPeak: PeakDetectorElgendi.ElgendiPeak?,
        derivativePeak: PeakDetectorDerivative.DerivativePeak?,
        signalQualityIndex: Float,
        timestampNs: Long
    ): FusedBeat? {
        
        // Contabilizar detecciones
        elgendiPeak?.let { totalElgendiDetections++ }
        derivativePeak?.let { totalDerivativeDetections++ }
        
        // Caso 1: Ambos detectores encontraron pico
        if (elgendiPeak != null && derivativePeak != null) {
            return handleConsensusDetection(elgendiPeak, derivativePeak, signalQualityIndex)
        }
        
        // Caso 2: Solo un detector encontró pico
        elgendiPeak?.let { 
            return handleSingleDetection(it, null, signalQualityIndex, timestampNs)
        }
        
        derivativePeak?.let {
            return handleSingleDetection(null, it, signalQualityIndex, timestampNs)
        }
        
        return null
    }
    
    private fun handleConsensusDetection(
        elgendi: PeakDetectorElgendi.ElgendiPeak,
        derivative: PeakDetectorDerivative.DerivativePeak,
        sqi: Float
    ): FusedBeat? {
        
        // Verificar coincidencia temporal
        val timeDiff = abs(elgendi.timestampNs - derivative.timestampNs)
        if (timeDiff > fusionWindowNs) {
            totalRejections++
            return null
        }
        
        // Combinar métricas
        val combinedConfidence = (elgendi.confidence + derivative.confidence) / 2.0
        val combinedAmplitude = (elgendi.amplitude + derivative.amplitude) / 2.0
        
        // Validar con historial RR
        val rrMs = elgendi.rrMs ?: derivative.rrMs
        val rrConsistent = validateRRConsistency(rrMs)
        
        // Calcular BPM instantáneo
        val bpmInstant = if (rrMs != null && rrMs > 0) 60000.0 / rrMs else null
        
        // Generar flags de calidad
        val qualityFlags = mutableSetOf<QualityFlag>()
        if (combinedConfidence > 0.7) qualityFlags.add(QualityFlag.HIGH_CONFIDENCE)
        if (rrConsistent) qualityFlags.add(QualityFlag.CONSISTENT_RR)
        if (elgendi.morphologyScore > 0.6 && derivative.slopeRatio > 0.8) {
            qualityFlags.add(QualityFlag.GOOD_MORPHOLOGY)
        }
        
        // Actualizar historial RR
        rrMs?.let { updateRRHistory(it.toLong()) }
        
        totalFusedDetections++
        
        return FusedBeat(
            timestampNs = elgendi.timestampNs,
            rrMs = rrMs,
            bpmInstant = bpmInstant,
            confidence = combinedConfidence,
            amplitude = combinedAmplitude,
            detectionMethod = DetectionMethod.CONSENSUS,
            elgendiData = elgendi,
            derivativeData = derivative,
            fusionReason = "Consenso temporal y morfológico",
            qualityFlags = qualityFlags
        )
    }
    
    private fun handleSingleDetection(
        elgendi: PeakDetectorElgendi.ElgendiPeak?,
        derivative: PeakDetectorDerivative.DerivativePeak?,
        sqi: Float,
        timestampNs: Long
    ): FusedBeat? {
        
        val peak = elgendi ?: derivative!!
        val isElgendi = elgendi != null
        
        // Requerir alta confianza para detección individual
        if (peak.confidence < 0.65) {
            totalRejections++
            return null
        }
        
        // Requerir SQI alto para detección individual
        if (sqi < 0.6) {
            totalRejections++
            return null
        }
        
        // Validar con historial RR
        val rrConsistent = validateRRConsistency(peak.rrMs)
        
        // Validación morfológica adicional
        val morphologyValid = if (isElgendi) {
            elgendi.morphologyScore > 0.5 && elgendi.prominence > 0.08
        } else {
            derivative.peakWidth in 3..15 && derivative.slopeRatio in 0.8..2.2
        }
        
        if (!morphologyValid) {
            totalRejections++
            return null
        }
        
        // Calcular BPM instantáneo
        val bpmInstant = if (peak.rrMs != null && peak.rrMs > 0) {
            60000.0 / peak.rrMs
        } else null
        
        // Generar flags de calidad
        val qualityFlags = mutableSetOf<QualityFlag>()
        if (peak.confidence > 0.75) qualityFlags.add(QualityFlag.HIGH_CONFIDENCE)
        if (rrConsistent) qualityFlags.add(QualityFlag.CONSISTENT_RR)
        if (sqi > 0.7) qualityFlags.add(QualityFlag.LOW_NOISE)
        
        // Determinar método de detección
        val detectionMethod = when {
            isElgendi && peak.confidence > 0.8 -> DetectionMethod.ELGENDI_HIGH_CONF
            !isElgendi && peak.confidence > 0.8 -> DetectionMethod.DERIVATIVE_HIGH_CONF
            rrConsistent -> DetectionMethod.SINGLE_VALIDATED
            else -> {
                totalRejections++
                return null
            }
        }
        
        // Actualizar historial RR
        peak.rrMs?.let { updateRRHistory(it.toLong()) }
        
        totalFusedDetections++
        
        return FusedBeat(
            timestampNs = peak.timestampNs,
            rrMs = peak.rrMs,
            bpmInstant = bpmInstant,
            confidence = peak.confidence,
            amplitude = peak.amplitude,
            detectionMethod = detectionMethod,
            elgendiData = elgendi,
            derivativeData = derivative,
            fusionReason = if (isElgendi) "Elgendi alta confianza" else "Derivativo alta confianza",
            qualityFlags = qualityFlags
        )
    }
    
    private fun validateRRConsistency(rrMs: Double?): Boolean {
        if (rrMs == null || rrHistory.isEmpty()) return true
        
        val rrNs = (rrMs * 1_000_000).toLong()
        
        // Verificar que el nuevo RR sea consistente con el historial
        for (historicalRr in rrHistory.takeLast(5)) {
            val diff = abs(rrNs - historicalRr)
            if (diff > maxRRVariationNs) {
                return false
            }
        }
        
        return true
    }
    
    private fun updateRRHistory(rrNs: Long) {
        rrHistory.add(rrNs)
        while (rrHistory.size > maxHistorySize) {
            rrHistory.removeAt(0)
        }
    }
    
    /**
     * Calcula BPM promedio de las últimas detecciones fusionadas
     */
    fun getAverageBpm(windowSize: Int = 5): Double? {
        if (rrHistory.size < 2) return null
        
        val recentRr = rrHistory.takeLast(windowSize)
        val avgRrMs = recentRr.average() / 1_000_000.0
        
        return if (avgRrMs > 0) 60000.0 / avgRrMs else null
    }
    
    /**
     * Calcula variabilidad del latido (CV de RR intervals)
     */
    fun getRRVariability(): Double {
        if (rrHistory.size < 3) return 0.0
        
        val mean = rrHistory.average()
        var variance = 0.0
        
        for (rr in rrHistory) {
            val diff = rr - mean
            variance += diff * diff
        }
        
        val std = kotlin.math.sqrt(variance / rrHistory.size)
        return if (mean > 0) (std / mean) * 100.0 else 0.0 // Coeficiente de variación en %
    }
    
    /**
     * Detecta pausas sospechosas
     */
    fun detectSuspiciousPause(): Boolean {
        if (rrHistory.size < 3) return false
        
        val latestRr = rrHistory.last()
        val avgRr = rrHistory.dropLast(1).average()
        
        // Pausa si último RR es 50% más largo que el promedio
        return latestRr > avgRr * 1.5
    }
    
    /**
     * Reinicia estado de fusión
     */
    fun reset() {
        rrHistory.clear()
        totalElgendiDetections = 0
        totalDerivativeDetections = 0
        totalFusedDetections = 0
        totalRejections = 0
    }
    
    /**
     * Obtiene estadísticas de fusión
     */
    fun getFusionStats(): FusionStats {
        val totalDetections = totalElgendiDetections + totalDerivativeDetections
        val fusionRate = if (totalDetections > 0) {
            (totalFusedDetections.toDouble() / totalDetections) * 100.0
        } else 0.0
        
        return FusionStats(
            totalElgendiDetections = totalElgendiDetections,
            totalDerivativeDetections = totalDerivativeDetections,
            totalFusedDetections = totalFusedDetections,
            totalRejections = totalRejections,
            fusionRate = fusionRate,
            avgRRVariability = getRRVariability(),
            currentRRHistorySize = rrHistory.size
        )
    }
    
    data class FusionStats(
        val totalElgendiDetections: Int,
        val totalDerivativeDetections: Int,
        val totalFusedDetections: Int,
        val totalRejections: Int,
        val fusionRate: Double,
        val avgRRVariability: Double,
        val currentRRHistorySize: Int
    )
}
