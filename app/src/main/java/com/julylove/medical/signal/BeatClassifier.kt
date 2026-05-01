package com.julylove.medical.signal

import kotlin.math.abs

/**
 * BeatClassifier: Clasificación de latidos PPG para detección de anomalías
 * Analiza morfología, temporización y consistencia de latidos
 * 
 * Categorías:
 * - NORMAL: Latido fisiológicamente típico
 * - SUSPECT_PREMATURE: Latido prematuro sospechoso
 * - SUSPECT_PAUSE: Pausa sospechosa entre latidos
 * - SUSPECT_MISSED: Latido perdido sospechoso
 * - IRREGULAR: Patrón irregular persistente
 * - INVALID_SIGNAL: Señal no confiable para clasificación
 */
class BeatClassifier {
    
    // Ventanas temporales para análisis (en milisegundos)
    private val analysisWindowMs = 15000L  // 15 segundos de historial
    private val minNormalRR = 300L        // 200 BPM máximo
    private val maxNormalRR = 2000L       // 30 BPM mínimo
    
    // Historial de latidos para análisis
    private val beatHistory = mutableListOf<ClassifiedBeat>()
    private val maxHistorySize = 50
    
    // Umbrales de clasificación
    private var prematureThreshold = 0.8   // 80% del RR promedio
    private var pauseThreshold = 1.3       // 130% del RR promedio
    private var irregularityThreshold = 0.15 // 15% CV para irregularidad
    
    data class ClassifiedBeat(
        val timestampNs: Long,
        val rrMs: Double?,
        val beatType: BeatType,
        val confidence: Double,
        val morphologyScore: Double,
        val temporalScore: Double,
        val consistencyScore: Double,
        val classificationReason: String,
        val signalQuality: Float
    )
    
    enum class BeatType {
        NORMAL,
        SUSPECT_PREMATURE,
        SUSPECT_PAUSE,
        SUSPECT_MISSED,
        IRREGULAR,
        INVALID_SIGNAL
    }
    
    /**
     * Clasifica un latido basado en características y contexto
     */
    fun classifyBeat(
        fusedBeat: HeartRateFusion.FusedBeat,
        signalQualityIndex: Float
    ): ClassifiedBeat {
        
        // Si SQI es muy bajo, no clasificar
        if (signalQualityIndex < 0.3) {
            return createInvalidBeat(
                fusedBeat.timestampNs,
                fusedBeat.rrMs,
                "Señal de muy baja calidad (SQI < 0.3)",
                signalQualityIndex
            )
        }
        
        // Calcular scores individuales
        val morphologyScore = calculateMorphologyScore(fusedBeat)
        val temporalScore = calculateTemporalScore(fusedBeat)
        val consistencyScore = calculateConsistencyScore(fusedBeat)
        
        // Clasificación principal basada en temporización
        val beatType = classifyByTemporalPattern(fusedBeat, signalQualityIndex)
        
        // Calcular confianza general
        val confidence = calculateOverallConfidence(
            morphologyScore,
            temporalScore,
            consistencyScore,
            signalQualityIndex
        )
        
        // Generar razón de clasificación
        val reason = generateClassificationReason(
            beatType,
            morphologyScore,
            temporalScore,
            consistencyScore,
            fusedBeat
        )
        
        val classifiedBeat = ClassifiedBeat(
            timestampNs = fusedBeat.timestampNs,
            rrMs = fusedBeat.rrMs,
            beatType = beatType,
            confidence = confidence,
            morphologyScore = morphologyScore,
            temporalScore = temporalScore,
            consistencyScore = consistencyScore,
            classificationReason = reason,
            signalQuality = signalQualityIndex
        )
        
        // Agregar al historial
        addToHistory(classifiedBeat)
        
        return classifiedBeat
    }
    
    private fun calculateMorphologyScore(fusedBeat: HeartRateFusion.FusedBeat): Double {
        var score = 0.0
        var factors = 0
        
        // Score de Elgendi
        fusedBeat.elgendiData?.let { elgendi ->
            score += elgendi.morphologyScore.toDouble()
            factors++
        }
        
        // Score de derivativo (relación de pendientes)
        fusedBeat.derivativeData?.let { derivative ->
            val slopeScore = when {
                derivative.slopeRatio in 0.8..2.2 -> 1.0
                derivative.slopeRatio in 0.6..2.5 -> 0.7
                else -> 0.3
            }
            score += slopeScore
            factors++
        }
        
        // Score de confianza de detección
        score += fusedBeat.confidence
        factors++
        
        return if (factors > 0) score / factors else 0.0
    }
    
    private fun calculateTemporalScore(fusedBeat: HeartRateFusion.FusedBeat): Double {
        val rrMs = fusedBeat.rrMs ?: return 0.0
        
        return when {
            rrMs < minNormalRR -> 0.2  // Demasiado rápido
            rrMs > maxNormalRR -> 0.2  // Demasiado lento
            rrMs >= 600L && rrMs <= 1000L -> 1.0 // Rango normal (60-100 BPM)
            rrMs >= 500L && rrMs <= 1200L -> 0.8 // Rango aceptable (50-120 BPM)
            else -> 0.5
        }
    }
    
    private fun calculateConsistencyScore(fusedBeat: HeartRateFusion.FusedBeat): Double {
        if (beatHistory.size < 3) return 0.5 // Sin historial suficiente
        
        val recentBeats: List<BeatClassifier.ClassifiedBeat> = beatHistory.takeLast(5)
        val rrMs = fusedBeat.rrMs ?: return 0.0
        
        // Calcular desviación respecto al promedio reciente
        val avgRr = recentBeats.mapNotNull { it.rrMs }.average()
        if (avgRr == 0.0) return 0.5
        
        val deviation = abs(rrMs - avgRr) / avgRr
        
        return when {
            deviation < 0.1 -> 1.0   // Muy consistente
            deviation < 0.2 -> 0.8  // Consistente
            deviation < 0.3 -> 0.6  // Moderadamente consistente
            deviation < 0.5 -> 0.3  // Inconsistente
            else -> 0.1             // Muy inconsistente
        }
    }
    
    private fun classifyByTemporalPattern(
        fusedBeat: HeartRateFusion.FusedBeat,
        sqi: Float
    ): BeatType {
        
        val rrMs = fusedBeat.rrMs ?: return BeatType.INVALID_SIGNAL
        
        // Si SQI es bajo, marcar como inválido
        if (sqi < 0.4) {
            return BeatType.INVALID_SIGNAL
        }
        
        // Necesitamos historial para clasificación temporal
        if (beatHistory.size < 2) {
            return BeatType.NORMAL
        }
        
        val recentRrs = beatHistory.takeLast(5).mapNotNull { it.rrMs }
        if (recentRrs.isEmpty()) {
            return BeatType.NORMAL
        }
        
        val avgRr = recentRrs.average()
        val rrRatio = if (avgRr > 0) rrMs / avgRr else 1.0
        
        // Clasificación basada en patrón temporal
        return when {
            rrMs < minNormalRR -> BeatType.INVALID_SIGNAL
            rrMs > maxNormalRR -> BeatType.INVALID_SIGNAL
            
            rrRatio < prematureThreshold -> {
                if (sqi > 0.6) BeatType.SUSPECT_PREMATURE 
                else BeatType.INVALID_SIGNAL
            }
            
            rrRatio > pauseThreshold -> {
                if (sqi > 0.6) BeatType.SUSPECT_PAUSE
                else BeatType.INVALID_SIGNAL
            }
            
            isPatternIrregular(recentRrs) -> BeatType.IRREGULAR
            
            else -> BeatType.NORMAL
        }
    }
    
    private fun isPatternIrregular(recentRrs: List<Double>): Boolean {
        if (recentRrs.size < 3) return false
        
        // Calcular coeficiente de variación
        val mean = recentRrs.average()
        if (mean == 0.0) return false
        
        var variance = 0.0
        for (rr in recentRrs) {
            val diff = rr - mean
            variance += diff * diff
        }
        
        val cv = kotlin.math.sqrt(variance / recentRrs.size) / mean
        return cv > irregularityThreshold
    }
    
    private fun calculateOverallConfidence(
        morphologyScore: Double,
        temporalScore: Double,
        consistencyScore: Double,
        sqi: Float
    ): Double {
        // Pesos para cada factor
        val morphologyWeight = 0.3
        val temporalWeight = 0.3
        val consistencyWeight = 0.2
        val sqiWeight = 0.2
        
        val weightedScore = (
            morphologyScore * morphologyWeight +
            temporalScore * temporalWeight +
            consistencyScore * consistencyWeight +
            sqi * sqiWeight
        )
        
        return weightedScore.coerceIn(0.0, 1.0)
    }
    
    private fun generateClassificationReason(
        beatType: BeatType,
        morphologyScore: Double,
        temporalScore: Double,
        consistencyScore: Double,
        fusedBeat: HeartRateFusion.FusedBeat
    ): String {
        
        val rrMs = fusedBeat.rrMs ?: return "Sin intervalo RR"
        
        return when (beatType) {
            BeatType.NORMAL -> {
                when {
                    morphologyScore > 0.8 && temporalScore > 0.8 -> "Latido normal excelente"
                    morphologyScore > 0.6 && temporalScore > 0.6 -> "Latido normal bueno"
                    else -> "Latido normal aceptable"
                }
            }
            
            BeatType.SUSPECT_PREMATURE -> {
                val bpm = 60000.0 / rrMs
                "Latido prematuro sospechoso (${bpm.toInt()} BPM instantáneo)"
            }
            
            BeatType.SUSPECT_PAUSE -> {
                val bpm = 60000.0 / rrMs
                "Pausa sospechosa (${bpm.toInt()} BPM instantáneo)"
            }
            
            BeatType.SUSPECT_MISSED -> {
                "Posible latido perdido (intervalo extendido)"
            }
            
            BeatType.IRREGULAR -> {
                "Patrón irregular detectado (CV > ${(irregularityThreshold * 100).toInt()}%)"
            }
            
            BeatType.INVALID_SIGNAL -> {
                when {
                    morphologyScore < 0.3 -> "Morfología atípica"
                    temporalScore < 0.3 -> "Temporización anómala"
                    consistencyScore < 0.3 -> "Inconsistencia con historial"
                    else -> "Señal no confiable para clasificación"
                }
            }
        }
    }
    
    private fun createInvalidBeat(
        timestampNs: Long,
        rrMs: Double?,
        reason: String,
        sqi: Float
    ): ClassifiedBeat {
        return ClassifiedBeat(
            timestampNs = timestampNs,
            rrMs = rrMs,
            beatType = BeatType.INVALID_SIGNAL,
            confidence = 0.1,
            morphologyScore = 0.1,
            temporalScore = 0.1,
            consistencyScore = 0.1,
            classificationReason = reason,
            signalQuality = sqi
        )
    }
    
    private fun addToHistory(beat: ClassifiedBeat) {
        beatHistory.add(beat)
        
        // Mantener tamaño del historial y ventana temporal
        val cutoffTime = System.nanoTime() - analysisWindowMs * 1_000_000L
        beatHistory.removeAll { it.timestampNs < cutoffTime }
        
        // Limitar tamaño máximo
        while (beatHistory.size > maxHistorySize) {
            beatHistory.removeAt(0)
        }
    }
    
    /**
     * Obtiene estadísticas de clasificación reciente
     */
    fun getClassificationStats(): ClassificationStats {
        if (beatHistory.isEmpty()) {
            return ClassificationStats()
        }
        
        val recentBeats = beatHistory.takeLast(20)
        val typeCounts = recentBeats.groupingBy { it.beatType }.eachCount()
        val avgConfidence = recentBeats.map { it.confidence }.average()
        
        return ClassificationStats(
            totalBeats = recentBeats.size,
            normalBeats = typeCounts[BeatType.NORMAL] ?: 0,
            prematureBeats = typeCounts[BeatType.SUSPECT_PREMATURE] ?: 0,
            pauseBeats = typeCounts[BeatType.SUSPECT_PAUSE] ?: 0,
            missedBeats = typeCounts[BeatType.SUSPECT_MISSED] ?: 0,
            irregularBeats = typeCounts[BeatType.IRREGULAR] ?: 0,
            invalidBeats = typeCounts[BeatType.INVALID_SIGNAL] ?: 0,
            averageConfidence = avgConfidence,
            irregularityRatio = if (recentBeats.isNotEmpty()) {
                (typeCounts[BeatType.IRREGULAR]?.toDouble() ?: 0.0) / recentBeats.size
            } else 0.0
        )
    }
    
    /**
     * Detecta tendencia de irregularidad
     */
    fun hasIrregularTrend(): Boolean {
        if (beatHistory.size < 10) return false
        
        val recentBeats = beatHistory.takeLast(10)
        val irregularCount = recentBeats.count { 
            it.beatType == BeatType.IRREGULAR || 
            it.beatType == BeatType.SUSPECT_PREMATURE ||
            it.beatType == BeatType.SUSPECT_PAUSE
        }
        
        return irregularCount >= 4 // 40% o más irregulares
    }
    
    /**
     * Configura umbrales de clasificación
     */
    fun setPrematureThreshold(threshold: Double) {
        prematureThreshold = threshold.coerceIn(0.5, 0.95)
    }
    
    fun setPauseThreshold(threshold: Double) {
        pauseThreshold = threshold.coerceIn(1.1, 2.0)
    }
    
    fun setIrregularityThreshold(threshold: Double) {
        irregularityThreshold = threshold.coerceIn(0.05, 0.3)
    }
    
    /**
     * Reinicia estado del clasificador
     */
    fun reset() {
        beatHistory.clear()
    }
    
    data class ClassificationStats(
        val totalBeats: Int = 0,
        val normalBeats: Int = 0,
        val prematureBeats: Int = 0,
        val pauseBeats: Int = 0,
        val missedBeats: Int = 0,
        val irregularBeats: Int = 0,
        val invalidBeats: Int = 0,
        val averageConfidence: Double = 0.0,
        val irregularityRatio: Double = 0.0
    )
}
