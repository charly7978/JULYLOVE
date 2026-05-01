package com.julylove.medical.signal

import kotlin.math.abs

/**
 * ArrhythmiaScreening: Sistema de screening de irregularidades de pulso PPG
 * Implementa reglas clínicas para detección de patrones arrítmicos sospechosos
 * 
 * NOTA: No es diagnóstico médico - es screening de irregularidad de pulso
 * Basado en análisis de RR intervals y patrones temporales
 */
class ArrhythmiaScreening {
    
    // Ventanas de análisis (en número de latidos)
    private val screeningWindow = 20      // 20 latidos para análisis
    private val minBeatsForScreening = 8  // Mínimo para screening válido
    
    // Umbrales clínicos (basados en literatura PPG)
    private val irregularityThreshold = 0.25    // 25% CV para irregularidad significativa
    private val prematureThreshold = 0.75      // 75% del RR promedio para prematuro
    private val pauseThreshold = 1.4           // 140% del RR promedio para pausa
    private val variabilityThreshold = 0.3      // 30% variabilidad para alerta
    
    // Historial de latidos clasificados
    private val beatHistory = mutableListOf<BeatClassifier.ClassifiedBeat>()
    
    // Eventos detectados
    private val arrhythmiaEvents = mutableListOf<ArrhythmiaEvent>()
    
    data class ArrhythmiaEvent(
        val timestampNs: Long,
        val eventType: ArrhythmiaType,
        val severity: Severity,
        val confidence: Double,
        val description: String,
        val supportingData: Map<String, Double>,
        val signalQuality: Float
    )
    
    enum class ArrhythmiaType {
        IRREGULAR_RHYTHM,      // Ritmo irregular persistente
        FREQUENT_PREMATURE,    // Latidos prematuros frecuentes
        SUSPICIOUS_PAUSE,      // Pausas sospechosas
        HIGH_VARIABILITY,      // Variabilidad elevada
        BRADYCARDIA,           // Frecuencia cardíaca baja
        TACHYCARDIA,           // Frecuencia cardíaca alta
        ARRHYTHMIC_PATTERN    // Patrón arrítmico complejo
    }
    
    enum class Severity {
        LOW,      // Leve, monitoreo continuo
        MODERATE, // Moderado, requiere atención
        HIGH,     // Alto, requiere evaluación
        CRITICAL  // Crítico, acción inmediata
    }
    
    /**
     * Realiza screening de arritmias con nuevo latido clasificado
     */
    fun screenForArrhythmias(
        classifiedBeat: BeatClassifier.ClassifiedBeat
    ): ArrhythmiaEvent? {
        
        // Agregar al historial
        addToHistory(classifiedBeat)
        
        // Necesitamos suficientes latidos para screening
        if (beatHistory.size < minBeatsForScreening) {
            return null
        }
        
        // Realizar diferentes tipos de screening
        val events = mutableListOf<ArrhythmiaEvent>()
        
        // 1. Screening de irregularidad de ritmo
        checkIrregularRhythm(events)
        
        // 2. Screening de latidos prematuros frecuentes
        checkFrequentPrematureBeats(events)
        
        // 3. Screening de pausas sospechosas
        checkSuspiciousPauses(events)
        
        // 4. Screening de alta variabilidad
        checkHighVariability(events)
        
        // 5. Screening de bradicardia/taquicardia
        checkHeartRateExtremes(events)
        
        // 6. Screening de patrones complejos
        checkComplexPatterns(events)
        
        // Retornar evento más significativo si existe
        return events.maxByOrNull { 
            when (it.severity) {
                Severity.CRITICAL -> 4.0
                Severity.HIGH -> 3.0
                Severity.MODERATE -> 2.0
                Severity.LOW -> 1.0
            } + it.confidence
        }
    }
    
    private fun checkIrregularRhythm(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(screeningWindow)
        val validRrs = recentBeats.mapNotNull { it.rrMs }.filter { it > 0 }
        
        if (validRrs.size < 6) return
        
        // Calcular coeficiente de variación
        val mean = validRrs.average()
        if (mean == 0.0) return
        
        var variance = 0.0
        for (rr in validRrs) {
            val diff = rr - mean
            variance += diff * diff
        }
        
        val cv = kotlin.math.sqrt(variance / validRrs.size) / mean
        
        if (cv > irregularityThreshold) {
            val severity = when {
                cv > 0.4 -> Severity.HIGH
                cv > 0.3 -> Severity.MODERATE
                else -> Severity.LOW
            }
            
            val event = ArrhythmiaEvent(
                timestampNs = recentBeats.last().timestampNs,
                eventType = ArrhythmiaType.IRREGULAR_RHYTHM,
                severity = severity,
                confidence = (cv / irregularityThreshold).coerceIn(0.5, 1.0),
                description = "Ritmo irregular detectado (CV = ${(cv * 100).toInt()}%)",
                supportingData = mapOf(
                    "coefficient_variation" to cv,
                    "mean_rr" to mean,
                    "std_rr" to kotlin.math.sqrt(variance / validRrs.size)
                ),
                signalQuality = recentBeats.last().signalQuality
            )
            
            events.add(event)
        }
    }
    
    private fun checkFrequentPrematureBeats(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(screeningWindow)
        val prematureBeats = recentBeats.count { 
            it.beatType == BeatClassifier.BeatType.SUSPECT_PREMATURE 
        }
        
        val prematureRatio = prematureBeats.toDouble() / recentBeats.size
        
        if (prematureRatio > 0.15) { // Más del 15% prematuros
            val severity = when {
                prematureRatio > 0.3 -> Severity.HIGH
                prematureRatio > 0.2 -> Severity.MODERATE
                else -> Severity.LOW
            }
            
            val event = ArrhythmiaEvent(
                timestampNs = recentBeats.last().timestampNs,
                eventType = ArrhythmiaType.FREQUENT_PREMATURE,
                severity = severity,
                confidence = (prematureRatio / 0.15).coerceIn(0.5, 1.0),
                description = "Latidos prematuros frecuentes (${(prematureRatio * 100).toInt()}%)",
                supportingData = mapOf(
                    "premature_ratio" to prematureRatio,
                    "premature_count" to prematureBeats.toDouble(),
                    "total_beats" to recentBeats.size.toDouble()
                ),
                signalQuality = recentBeats.last().signalQuality
            )
            
            events.add(event)
        }
    }
    
    private fun checkSuspiciousPauses(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(screeningWindow)
        val pauseBeats = recentBeats.count { 
            it.beatType == BeatClassifier.BeatType.SUSPECT_PAUSE 
        }
        
        if (pauseBeats >= 2) { // 2 o más pausas en ventana
            val severity = when {
                pauseBeats >= 4 -> Severity.HIGH
                pauseBeats >= 3 -> Severity.MODERATE
                else -> Severity.LOW
            }
            
            val event = ArrhythmiaEvent(
                timestampNs = recentBeats.last().timestampNs,
                eventType = ArrhythmiaType.SUSPICIOUS_PAUSE,
                severity = severity,
                confidence = (pauseBeats / 2.0).coerceIn(0.5, 1.0),
                description = "Pausas sospechosas detectadas ($pauseBeats en últimos ${recentBeats.size} latidos)",
                supportingData = mapOf(
                    "pause_count" to pauseBeats.toDouble(),
                    "window_size" to recentBeats.size.toDouble()
                ),
                signalQuality = recentBeats.last().signalQuality
            )
            
            events.add(event)
        }
    }
    
    private fun checkHighVariability(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(screeningWindow)
        val validRrs = recentBeats.mapNotNull { it.rrMs }.filter { it > 0 }
        
        if (validRrs.size < 6) return
        
        // Calcular rango de RR
        val minRr = validRrs.minOrNull() ?: return
        val maxRr = validRrs.maxOrNull() ?: return
        val rangeRatio = (maxRr - minRr) / maxRr
        
        if (rangeRatio > variabilityThreshold) {
            val severity = when {
                rangeRatio > 0.5 -> Severity.HIGH
                rangeRatio > 0.4 -> Severity.MODERATE
                else -> Severity.LOW
            }
            
            val event = ArrhythmiaEvent(
                timestampNs = recentBeats.last().timestampNs,
                eventType = ArrhythmiaType.HIGH_VARIABILITY,
                severity = severity,
                confidence = (rangeRatio / variabilityThreshold).coerceIn(0.5, 1.0),
                description = "Alta variabilidad de intervalos RR (rango = ${(rangeRatio * 100).toInt()}%)",
                supportingData = mapOf(
                    "range_ratio" to rangeRatio,
                    "min_rr" to minRr,
                    "max_rr" to maxRr
                ),
                signalQuality = recentBeats.last().signalQuality
            )
            
            events.add(event)
        }
    }
    
    private fun checkHeartRateExtremes(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(10)
        val validRrs = recentBeats.mapNotNull { it.rrMs }.filter { it > 0 }
        
        if (validRrs.size < 3) return
        
        val avgRr = validRrs.average()
        val avgBpm = if (avgRr > 0) 60000.0 / avgRr else 0.0
        
        when {
            avgBpm < 40 && avgBpm > 0 -> { // Bradicardia severa
                val event = ArrhythmiaEvent(
                    timestampNs = recentBeats.last().timestampNs,
                    eventType = ArrhythmiaType.BRADYCARDIA,
                    severity = Severity.HIGH,
                    confidence = 0.8,
                    description = "Bradicardia detectada (${avgBpm.toInt()} BPM)",
                    supportingData = mapOf(
                        "average_bpm" to avgBpm,
                        "average_rr" to avgRr
                    ),
                    signalQuality = recentBeats.last().signalQuality
                )
                events.add(event)
            }
            
            avgBpm > 140 -> { // Taquicardia severa
                val event = ArrhythmiaEvent(
                    timestampNs = recentBeats.last().timestampNs,
                    eventType = ArrhythmiaType.TACHYCARDIA,
                    severity = Severity.HIGH,
                    confidence = 0.8,
                    description = "Taquicardia detectada (${avgBpm.toInt()} BPM)",
                    supportingData = mapOf(
                        "average_bpm" to avgBpm,
                        "average_rr" to avgRr
                    ),
                    signalQuality = recentBeats.last().signalQuality
                )
                events.add(event)
            }
        }
    }
    
    private fun checkComplexPatterns(events: MutableList<ArrhythmiaEvent>) {
        val recentBeats = beatHistory.takeLast(screeningWindow)
        
        // Contar diferentes tipos de latidos anómalos
        val abnormalTypes = recentBeats.mapNotNull { beat ->
            when (beat.beatType) {
                BeatClassifier.BeatType.SUSPECT_PREMATURE -> "premature"
                BeatClassifier.BeatType.SUSPECT_PAUSE -> "pause"
                BeatClassifier.BeatType.SUSPECT_MISSED -> "missed"
                BeatClassifier.BeatType.IRREGULAR -> "irregular"
                else -> null
            }
        }.distinct()
        
        // Si hay 3 o más tipos diferentes de anormalidades
        if (abnormalTypes.size >= 3 && recentBeats.size >= 15) {
            val event = ArrhythmiaEvent(
                timestampNs = recentBeats.last().timestampNs,
                eventType = ArrhythmiaType.ARRHYTHMIC_PATTERN,
                severity = Severity.MODERATE,
                confidence = 0.7,
                description = "Patrón arrítmico complejo detectado",
                supportingData = mapOf(
                    "abnormal_types_count" to abnormalTypes.size.toDouble(),
                    "abnormal_types" to abnormalTypes.size.toDouble()
                ),
                signalQuality = recentBeats.last().signalQuality
            )
            
            events.add(event)
        }
    }
    
    private fun addToHistory(beat: BeatClassifier.ClassifiedBeat) {
        beatHistory.add(beat)
        
        // Mantener tamaño máximo del historial
        while (beatHistory.size > screeningWindow * 2) {
            beatHistory.removeAt(0)
        }
    }
    
    /**
     * Obtiene resumen de eventos recientes
     */
    fun getRecentEventsSummary(windowMs: Long = 60000L): EventsSummary {
        val cutoffTime = System.nanoTime() - windowMs * 1_000_000L
        val recentEvents = arrhythmiaEvents.filter { it.timestampNs > cutoffTime }
        
        val typeCounts = recentEvents.groupingBy { it.eventType }.eachCount()
        val severityCounts = recentEvents.groupingBy { it.severity }.eachCount()
        
        return EventsSummary(
            totalEvents = recentEvents.size,
            irregularRhythmEvents = typeCounts[ArrhythmiaType.IRREGULAR_RHYTHM] ?: 0,
            prematureEvents = typeCounts[ArrhythmiaType.FREQUENT_PREMATURE] ?: 0,
            pauseEvents = typeCounts[ArrhythmiaType.SUSPICIOUS_PAUSE] ?: 0,
            variabilityEvents = typeCounts[ArrhythmiaType.HIGH_VARIABILITY] ?: 0,
            bradycardiaEvents = typeCounts[ArrhythmiaType.BRADYCARDIA] ?: 0,
            tachycardiaEvents = typeCounts[ArrhythmiaType.TACHYCARDIA] ?: 0,
            complexPatternEvents = typeCounts[ArrhythmiaType.ARRHYTHMIC_PATTERN] ?: 0,
            highSeverityEvents = severityCounts[Severity.HIGH] ?: 0 + (severityCounts[Severity.CRITICAL] ?: 0),
            moderateSeverityEvents = severityCounts[Severity.MODERATE] ?: 0,
            lowSeverityEvents = severityCounts[Severity.LOW] ?: 0
        )
    }
    
    /**
     * Determina si hay riesgo de arritmia significativa
     */
    fun hasSignificantRisk(): Boolean {
        val summary = getRecentEventsSummary()
        
        return when {
            summary.highSeverityEvents >= 1 -> true
            summary.moderateSeverityEvents >= 3 -> true
            summary.totalEvents >= 5 -> true
            summary.irregularRhythmEvents >= 2 -> true
            else -> false
        }
    }
    
    /**
     * Configura umbrales de screening
     */
    fun setIrregularityThreshold(threshold: Double) {
        irregularityThreshold = threshold.coerceIn(0.1, 0.5)
    }
    
    fun setPrematureThreshold(threshold: Double) {
        prematureThreshold = threshold.coerceIn(0.5, 0.9)
    }
    
    fun setPauseThreshold(threshold: Double) {
        pauseThreshold = threshold.coerceIn(1.2, 2.0)
    }
    
    /**
     * Reinicia estado de screening
     */
    fun reset() {
        beatHistory.clear()
        arrhythmiaEvents.clear()
    }
    
    data class EventsSummary(
        val totalEvents: Int = 0,
        val irregularRhythmEvents: Int = 0,
        val prematureEvents: Int = 0,
        val pauseEvents: Int = 0,
        val variabilityEvents: Int = 0,
        val bradycardiaEvents: Int = 0,
        val tachycardiaEvents: Int = 0,
        val complexPatternEvents: Int = 0,
        val highSeverityEvents: Int = 0,
        val moderateSeverityEvents: Int = 0,
        val lowSeverityEvents: Int = 0
    )
}
