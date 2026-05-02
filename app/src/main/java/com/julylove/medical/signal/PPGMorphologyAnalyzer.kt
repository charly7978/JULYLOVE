package com.julylove.medical.signal

import kotlin.math.*

/**
 * PPGMorphologyAnalyzer: Analizador de morfología de ondas PPG
 * Detecta características completas de ondas: valles, mesetas, picos, anomalías
 * Basado en estándares médicos y papers académicos de PPG
 */
class PPGMorphologyAnalyzer {
    
    // Parámetros morfológicos basados en literatura médica
    private val minPeakHeight = 0.05f          // Altura mínima de pico
    private val minPeakProminence = 0.03f      // Prominencia mínima
    private val minPeakDistance = 300          // Distancia mínima entre picos (ms)
    private val maxPeakDistance = 2000         // Distancia máxima entre picos (ms)
    
    // Parámetros para detección de anomalías
    private val abnormalAmplitudeThreshold = 2.0f  // Umbral de amplitud anormal
    private val abnormalWidthThreshold = 2.0f     // Umbral de ancho anormal
    private val abnormalShapeThreshold = 0.3f     // Umbral de forma anormal
    
    // Historial para análisis temporal
    private val waveformHistory = mutableListOf<PPGWaveformPoint>()
    private val detectedPeaks = mutableListOf<PPGPeak>()
    private val detectedValleys = mutableListOf<PPGValley>()
    private val morphologyHistory = mutableListOf<WaveformMorphology>()
    
    /**
     * Analiza punto de onda PPG
     */
    fun analyzeWaveformPoint(
        value: Float,
        timestamp: Long,
        isPeak: Boolean = false,
        isValley: Boolean = false
    ): WaveformAnalysisResult {
        
        // Agregar punto al historial
        val point = PPGWaveformPoint(value, timestamp, isPeak, isValley)
        waveformHistory.add(point)
        
        // Mantener tamaño de historial
        if (waveformHistory.size > 500) {
            waveformHistory.removeAt(0)
        }
        
        // Detectar características morfológicas
        val morphology = analyzeMorphology(timestamp)
        morphologyHistory.add(morphology)
        if (morphologyHistory.size > 50) {
            morphologyHistory.removeAt(0)
        }
        
        // Detectar anomalías
        val abnormalities = detectAbnormalities(morphology)
        
        // Clasificar onda
        val waveType = classifyWaveType(morphology)
        
        return WaveformAnalysisResult(
            point = point,
            morphology = morphology,
            waveType = waveType,
            abnormalities = abnormalities,
            signalQuality = calculateSignalQuality(morphology),
            confidence = calculateMorphologyConfidence(morphology),
            timestamp = timestamp
        )
    }
    
    /**
     * Analiza morfología completa de la onda
     */
    private fun analyzeMorphology(timestamp: Long): WaveformMorphology {
        if (waveformHistory.size < 20) {
            return WaveformMorphology() // Morfología vacía
        }
        
        val recentPoints = waveformHistory.takeLast(100)
        
        // Detectar picos y valles
        val peaks = detectPeaks(recentPoints)
        val valleys = detectValleys(recentPoints)
        
        // Analizar características de picos
        val peakCharacteristics = peaks.mapNotNull { analyzePeakCharacteristics(it, recentPoints) }
        
        // Analizar características de valles
        val valleyCharacteristics = valleys.mapNotNull { analyzeValleyCharacteristics(it, recentPoints) }
        
        // Calcular características globales
        val amplitude = calculateAmplitude(recentPoints)
        val frequency = calculateFrequency(peaks)
        val regularity = calculateRegularity(peaks)
        val symmetry = calculateSymmetry(peaks, valleys)
        
        return WaveformMorphology(
            peaks = peakCharacteristics,
            valleys = valleyCharacteristics,
            amplitude = amplitude,
            frequency = frequency,
            regularity = regularity,
            symmetry = symmetry,
            timestamp = timestamp
        )
    }
    
    /**
     * Detecta picos en la señal
     */
    private fun detectPeaks(points: List<PPGWaveformPoint>): List<PPGPeak> {
        val peaks = mutableListOf<PPGPeak>()
        
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val current = points[i]
            val next = points[i + 1]
            
            // Criterio de pico local
            if (current.value > prev.value && current.value > next.value) {
                // Validar altura y prominencia
                val prominence = calculatePeakProminence(i, points)
                if (current.value > minPeakHeight && prominence > minPeakProminence) {
                    
                    // Validar distancia con picos anteriores
                    val lastPeak = peaks.lastOrNull()
                    if (lastPeak == null || 
                        (current.timestamp - lastPeak.timestamp) >= minPeakDistance) {
                        
                        peaks.add(PPGPeak(
                            index = i,
                            value = current.value,
                            timestamp = current.timestamp,
                            prominence = prominence,
                            width = calculatePeakWidth(i, points),
                            amplitude = calculatePeakAmplitude(i, points)
                        ))
                    }
                }
            }
        }
        
        return peaks
    }
    
    /**
     * Detecta valles en la señal
     */
    private fun detectValleys(points: List<PPGWaveformPoint>): List<PPGValley> {
        val valleys = mutableListOf<PPGValley>()
        
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val current = points[i]
            val next = points[i + 1]
            
            // Criterio de valle local
            if (current.value < prev.value && current.value < next.value) {
                
                // Validar distancia con valles anteriores
                val lastValley = valleys.lastOrNull()
                if (lastValley == null || 
                    (current.timestamp - lastValley.timestamp) >= minPeakDistance / 2) {
                    
                    valleys.add(PPGValley(
                        index = i,
                        value = current.value,
                        timestamp = current.timestamp,
                        depth = calculateValleyDepth(i, points),
                        width = calculateValleyWidth(i, points)
                    ))
                }
            }
        }
        
        return valleys
    }
    
    /**
     * Analiza características de un pico
     */
    private fun analyzePeakCharacteristics(peak: PPGPeak, points: List<PPGWaveformPoint>): PeakCharacteristics? {
        if (peak.index < 5 || peak.index >= points.size - 5) return null
        
        val beforePeak = points.subList(peak.index - 5, peak.index)
        val afterPeak = points.subList(peak.index + 1, min(peak.index + 6, points.size))
        
        // Analizar forma del pico
        val riseTime = calculateRiseTime(beforePeak, peak)
        val fallTime = calculateFallTime(peak, afterPeak)
        val sharpness = calculatePeakSharpness(beforePeak, peak, afterPeak)
        
        // Detectar componentes morfológicos
        val hasSystolicPeak = detectSystolicPeak(peak, points)
        val hasDicroticNotch = detectDicroticNotch(peak, points)
        val hasTidalWave = detectTidalWave(peak, points)
        
        return PeakCharacteristics(
            riseTime = riseTime,
            fallTime = fallTime,
            sharpness = sharpness,
            hasSystolicPeak = hasSystolicPeak,
            hasDicroticNotch = hasDicroticNotch,
            hasTidalWave = hasTidalWave,
            morphologyType = classifyPeakMorphology(hasSystolicPeak, hasDicroticNotch, hasTidalWave)
        )
    }
    
    /**
     * Analiza características de un valle
     */
    private fun analyzeValleyCharacteristics(valley: PPGValley, points: List<PPGWaveformPoint>): ValleyCharacteristics? {
        if (valley.index < 5 || valley.index >= points.size - 5) return null
        
        val beforeValley = points.subList(max(0, valley.index - 5), valley.index)
        val afterValley = points.subList(valley.index + 1, min(valley.index + 6, points.size))
        
        // Analizar forma del valle
        val descentSlope = calculateDescentSlope(beforeValley, valley)
        val ascentSlope = calculateAscentSlope(valley, afterValley)
        val flatness = calculateValleyFlatness(beforeValley, valley, afterValley)
        
        return ValleyCharacteristics(
            descentSlope = descentSlope,
            ascentSlope = ascentSlope,
            flatness = flatness,
            valleyType = classifyValleyType(flatness, descentSlope, ascentSlope)
        )
    }
    
    /**
     * Detecta anomalías en la morfología
     */
    private fun detectAbnormalities(morphology: WaveformMorphology): List<PPGAbnormality> {
        val abnormalities = mutableListOf<PPGAbnormality>()
        
        // Anomalías de amplitud
        if (morphology.amplitude > 0) {
            val avgAmplitude = calculateAverageAmplitude()
            if (morphology.amplitude > avgAmplitude * abnormalAmplitudeThreshold) {
                abnormalities.add(PPGAbnormality(
                    type = AbnormalityType.HIGH_AMPLITUDE,
                    severity = AbnormalitySeverity.MODERATE,
                    description = "Amplitud anormalmente alta",
                    value = morphology.amplitude
                ))
            } else if (morphology.amplitude < avgAmplitude / abnormalAmplitudeThreshold) {
                abnormalities.add(PPGAbnormality(
                    type = AbnormalityType.LOW_AMPLITUDE,
                    severity = AbnormalitySeverity.MODERATE,
                    description = "Amplitud anormalmente baja",
                    value = morphology.amplitude
                ))
            }
        }
        
        // Anomalías de frecuencia
        if (morphology.frequency > 0) {
            if (morphology.frequency > 120) {
                abnormalities.add(PPGAbnormality(
                    type = AbnormalityType.TACHYCARDIA,
                    severity = AbnormalitySeverity.HIGH,
                    description = "Frecuencia cardíaca elevada",
                    value = morphology.frequency
                ))
            } else if (morphology.frequency < 40) {
                abnormalities.add(PPGAbnormality(
                    type = AbnormalityType.BRADYCARDIA,
                    severity = AbnormalitySeverity.HIGH,
                    description = "Frecuencia cardíaca baja",
                    value = morphology.frequency
                ))
            }
        }
        
        // Anomalías de regularidad
        if (morphology.regularity < 0.7f) {
            abnormalities.add(PPGAbnormality(
                type = AbnormalityType.IRREGULAR_RHYTHM,
                severity = if (morphology.regularity < 0.4f) AbnormalitySeverity.HIGH else AbnormalitySeverity.MODERATE,
                description = "Ritmo irregular detectado",
                value = morphology.regularity
            ))
        }
        
        // Anomalías morfológicas
        morphology.peaks.forEach { peak ->
            if (peak.characteristics?.morphologyType == PeakMorphologyType.ABNORMAL) {
                abnormalities.add(PPGAbnormality(
                    type = AbnormalityType.ABNORMAL_MORPHOLOGY,
                    severity = AbnormalitySeverity.MODERATE,
                    description = "Morfología de pico anormal",
                    value = peak.value
                ))
            }
        }
        
        return abnormalities
    }
    
    /**
     * Clasifica tipo de onda
     */
    private fun classifyWaveType(morphology: WaveformMorphology): WaveType {
        if (morphology.peaks.isEmpty()) return WaveType.NO_SIGNAL
        
        val avgRegularity = morphology.regularity
        val avgAmplitude = morphology.amplitude
        
        return when {
            avgRegularity > 0.9f && avgAmplitude > 0.05f -> WaveType.NORMAL
            avgRegularity > 0.7f && avgAmplitude > 0.03f -> WaveType.ACCEPTABLE
            avgRegularity > 0.5f -> WaveType.IRREGULAR
            avgAmplitude < 0.02f -> WaveType.WEAK
            else -> WaveType.ABNORMAL
        }
    }
    
    /**
     * Métodos de cálculo auxiliares
     */
    private fun calculatePeakProminence(index: Int, points: List<PPGWaveformPoint>): Float {
        val peakValue = points[index].value
        
        // Encontrar valles a ambos lados
        var leftMin = peakValue
        var rightMin = peakValue
        
        // Buscar mínimo a la izquierda
        for (i in index - 1 downTo 0) {
            if (points[i].value < leftMin) {
                leftMin = points[i].value
            } else if (points[i].value > leftMin) {
                break
            }
        }
        
        // Buscar mínimo a la derecha
        for (i in index + 1 until points.size) {
            if (points[i].value < rightMin) {
                rightMin = points[i].value
            } else if (points[i].value > rightMin) {
                break
            }
        }
        
        val baseLevel = maxOf(leftMin, rightMin)
        return peakValue - baseLevel
    }
    
    private fun calculatePeakWidth(index: Int, points: List<PPGWaveformPoint>): Int {
        val peakValue = points[index].value
        val halfHeight = peakValue - (calculatePeakProminence(index, points) / 2f)
        
        // Buscar cruces con mitad de altura
        var leftIndex = index
        var rightIndex = index
        
        for (i in index - 1 downTo 0) {
            if (points[i].value <= halfHeight) {
                leftIndex = i
                break
            }
        }
        
        for (i in index + 1 until points.size) {
            if (points[i].value <= halfHeight) {
                rightIndex = i
                break
            }
        }
        
        return rightIndex - leftIndex
    }
    
    private fun calculatePeakAmplitude(index: Int, points: List<PPGWaveformPoint>): Float {
        if (index == 0 || index >= points.size - 1) return 0f
        
        val peakValue = points[index].value
        val baseline = (points[index - 1].value + points[index + 1].value) / 2f
        
        return peakValue - baseline
    }
    
    private fun calculateAmplitude(points: List<PPGWaveformPoint>): Float {
        if (points.size < 2) return 0f
        
        val max = points.maxOf { it.value }
        val min = points.minOf { it.value }
        
        return max - min
    }
    
    private fun calculateFrequency(peaks: List<PPGPeak>): Float {
        if (peaks.size < 2) return 0f
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until peaks.size) {
            val interval = peaks[i].timestamp - peaks[i - 1].timestamp
            if (interval in minPeakDistance..maxPeakDistance) {
                intervals.add(interval)
            }
        }
        
        if (intervals.isEmpty()) return 0f
        
        val avgInterval = intervals.average()
        return (60000.0 / avgInterval).toFloat() // Convertir a BPM
    }
    
    private fun calculateRegularity(peaks: List<PPGPeak>): Float {
        if (peaks.size < 3) return 0f
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until peaks.size) {
            val interval = peaks[i].timestamp - peaks[i - 1].timestamp
            if (interval in minPeakDistance..maxPeakDistance) {
                intervals.add(interval)
            }
        }
        
        if (intervals.size < 2) return 0f
        
        val mean = intervals.average()
        val variance = intervals.sumOf { (it - mean).pow(2) } / intervals.size
        val cv = variance.sqrt() / mean
        
        // Regularidad = 1 - coeficiente de variación
        return (1f - cv.toFloat()).coerceIn(0f, 1f)
    }
    
    private fun calculateSymmetry(peaks: List<PPGPeak>, valleys: List<PPGValley>): Float {
        if (peaks.isEmpty() || valleys.isEmpty()) return 0f
        
        // Simplificación: simetría basada en relación picos-valles
        val peakCount = peaks.size.toFloat()
        val valleyCount = valleys.size.toFloat()
        
        return minOf(peakCount, valleyCount) / maxOf(peakCount, valleyCount)
    }
    
    private fun calculateSignalQuality(morphology: WaveformMorphology): Float {
        val amplitudeScore = (morphology.amplitude.coerceIn(0f, 0.1f) / 0.1f)
        val regularityScore = morphology.regularity
        val symmetryScore = morphology.symmetry
        
        return (amplitudeScore * 0.4f + regularityScore * 0.4f + symmetryScore * 0.2f)
    }
    
    private fun calculateMorphologyConfidence(morphology: WaveformMorphology): Float {
        if (morphology.peaks.isEmpty()) return 0f
        
        val peakCount = morphology.peaks.size
        val avgPeakProminence = morphology.peaks.map { it.prominence }.average().toFloat()
        val signalQuality = calculateSignalQuality(morphology)
        
        return (minOf(peakCount / 10f, 1f) * 0.3f + 
                (avgPeakProminence / 0.1f).coerceIn(0f, 1f) * 0.4f + 
                signalQuality * 0.3f)
    }
    
    // Métodos auxiliares para análisis morfológico detallado
    private fun calculateRiseTime(beforePeak: List<PPGWaveformPoint>, peak: PPGPeak): Float {
        if (beforePeak.isEmpty()) return 0f
        
        val startValue = beforePeak.first().value
        val timeDiff = (peak.timestamp - beforePeak.first().timestamp).toFloat()
        val valueDiff = peak.value - startValue
        
        return if (timeDiff > 0) valueDiff / timeDiff else 0f
    }
    
    private fun calculateFallTime(peak: PPGPeak, afterPeak: List<PPGWaveformPoint>): Float {
        if (afterPeak.isEmpty()) return 0f
        
        val endValue = afterPeak.last().value
        val timeDiff = (afterPeak.last().timestamp - peak.timestamp).toFloat()
        val valueDiff = peak.value - endValue
        
        return if (timeDiff > 0) valueDiff / timeDiff else 0f
    }
    
    private fun calculatePeakSharpness(beforePeak: List<PPGWaveformPoint>, peak: PPGPeak, afterPeak: List<PPGWaveformPoint>): Float {
        val riseTime = calculateRiseTime(beforePeak, peak)
        val fallTime = calculateFallTime(peak, afterPeak)
        
        // Sharpness = inversa del tiempo total del pico
        val totalTime = riseTime + fallTime
        return if (totalTime > 0) 1f / totalTime else 0f
    }
    
    private fun detectSystolicPeak(peak: PPGPeak, points: List<PPGWaveformPoint>): Boolean {
        // Simplificación: considerar todos los picos como sistólicos principales
        return true
    }
    
    private fun detectDicroticNotch(peak: PPGPeak, points: List<PPGWaveformPoint>): Boolean {
        // Buscar pequeña depresión después del pico principal
        if (peak.index >= points.size - 10) return false
        
        val afterPeak = points.subList(peak.index + 1, min(peak.index + 11, points.size))
        val peakValue = peak.value
        
        // Buscar valle pequeño después del pico
        for (i in 1 until afterPeak.size) {
            if (afterPeak[i].value < peakValue * 0.9f) {
                return true
            }
        }
        
        return false
    }
    
    private fun detectTidalWave(peak: PPGPeak, points: List<PPGWaveformPoint>): Boolean {
        // Simplificación: detectar onda secundaria
        return detectDicroticNotch(peak, points)
    }
    
    private fun classifyPeakMorphology(hasSystolic: Boolean, hasDicrotic: Boolean, hasTidal: Boolean): PeakMorphologyType {
        return when {
            hasSystolic && !hasDicrotic && !hasTidal -> PeakMorphologyType.NORMAL
            hasSystolic && hasDicrotic && !hasTidal -> PeakMorphologyType.WITH_NOTCH
            hasSystolic && hasDicrotic && hasTidal -> PeakMorphologyType.COMPLEX
            !hasSystolic -> PeakMorphologyType.ABNORMAL
            else -> PeakMorphologyType.NORMAL
        }
    }
    
    private fun calculateValleyDepth(index: Int, points: List<PPGWaveformPoint>): Float {
        val valleyValue = points[index].value
        
        // Encontrar picos a ambos lados
        var leftMax = valleyValue
        var rightMax = valleyValue
        
        for (i in index - 1 downTo 0) {
            if (points[i].value > leftMax) {
                leftMax = points[i].value
            } else if (points[i].value < leftMax) {
                break
            }
        }
        
        for (i in index + 1 until points.size) {
            if (points[i].value > rightMax) {
                rightMax = points[i].value
            } else if (points[i].value < rightMax) {
                break
            }
        }
        
        val peakLevel = minOf(leftMax, rightMax)
        return peakLevel - valleyValue
    }
    
    private fun calculateValleyWidth(index: Int, points: List<PPGWaveformPoint>): Int {
        val valleyValue = points[index].value
        val halfDepth = valleyValue + (calculateValleyDepth(index, points) / 2f)
        
        var leftIndex = index
        var rightIndex = index
        
        for (i in index - 1 downTo 0) {
            if (points[i].value >= halfDepth) {
                leftIndex = i
                break
            }
        }
        
        for (i in index + 1 until points.size) {
            if (points[i].value >= halfDepth) {
                rightIndex = i
                break
            }
        }
        
        return rightIndex - leftIndex
    }
    
    private fun calculateDescentSlope(beforeValley: List<PPGWaveformPoint>, valley: PPGValley): Float {
        if (beforeValley.isEmpty()) return 0f
        
        val startValue = beforeValley.first().value
        val timeDiff = (valley.timestamp - beforeValley.first().timestamp).toFloat()
        val valueDiff = startValue - valley.value
        
        return if (timeDiff > 0) valueDiff / timeDiff else 0f
    }
    
    private fun calculateAscentSlope(valley: PPGValley, afterValley: List<PPGWaveformPoint>): Float {
        if (afterValley.isEmpty()) return 0f
        
        val endValue = afterValley.last().value
        val timeDiff = (afterValley.last().timestamp - valley.timestamp).toFloat()
        val valueDiff = endValue - valley.value
        
        return if (timeDiff > 0) valueDiff / timeDiff else 0f
    }
    
    private fun calculateValleyFlatness(beforeValley: List<PPGWaveformPoint>, valley: PPGValley, afterValley: List<PPGWaveformPoint>): Float {
        val descentSlope = calculateDescentSlope(beforeValley, valley)
        val ascentSlope = calculateAscentSlope(valley, afterValley)
        
        // Flatness = inversa de la suma de pendientes
        val totalSlope = descentSlope + ascentSlope
        return if (totalSlope > 0) 1f / totalSlope else 0f
    }
    
    private fun classifyValleyType(flatness: Float, descentSlope: Float, ascentSlope: Float): ValleyMorphologyType {
        return when {
            flatness > 0.5f -> ValleyMorphologyType.FLAT
            descentSlope > ascentSlope * 1.5f -> ValleyMorphologyType.STEEP_DESCENT
            ascentSlope > descentSlope * 1.5f -> ValleyMorphologyType.STEEP_ASCENT
            else -> ValleyMorphologyType.NORMAL
        }
    }
    
    private fun calculateAverageAmplitude(): Float {
        if (morphologyHistory.size < 5) return 0.05f
        
        return morphologyHistory.takeLast(10)
            .mapNotNull { it.amplitude.takeIf { it > 0 } }
            .average()
            .toFloat()
    }
    
    /**
     * Reinicia el analizador
     */
    fun reset() {
        waveformHistory.clear()
        detectedPeaks.clear()
        detectedValleys.clear()
        morphologyHistory.clear()
    }
    
    /**
     * Obtiene estadísticas actuales
     */
    fun getCurrentStatistics(): MorphologyStatistics {
        return MorphologyStatistics(
            totalPeaks = detectedPeaks.size,
            totalValleys = detectedValleys.size,
            averageAmplitude = calculateAverageAmplitude(),
            currentWaveType = morphologyHistory.lastOrNull()?.waveType ?: WaveType.NO_SIGNAL,
            signalQuality = morphologyHistory.lastOrNull()?.let { calculateSignalQuality(it) } ?: 0f,
            abnormalitiesCount = morphologyHistory.lastOrNull()?.abnormalities?.size ?: 0
        )
    }
}

// Clases de datos para análisis morfológico
data class PPGWaveformPoint(
    val value: Float,
    val timestamp: Long,
    val isPeak: Boolean = false,
    val isValley: Boolean = false
)

data class PPGPeak(
    val index: Int,
    val value: Float,
    val timestamp: Long,
    val prominence: Float,
    val width: Int,
    val amplitude: Float,
    val characteristics: PeakCharacteristics? = null
)

data class PPGValley(
    val index: Int,
    val value: Float,
    val timestamp: Long,
    val depth: Float,
    val width: Int,
    val characteristics: ValleyCharacteristics? = null
)

data class PeakCharacteristics(
    val riseTime: Float,
    val fallTime: Float,
    val sharpness: Float,
    val hasSystolicPeak: Boolean,
    val hasDicroticNotch: Boolean,
    val hasTidalWave: Boolean,
    val morphologyType: PeakMorphologyType
)

data class ValleyCharacteristics(
    val descentSlope: Float,
    val ascentSlope: Float,
    val flatness: Float,
    val valleyType: ValleyMorphologyType
)

data class WaveformMorphology(
    val peaks: List<PPGPeak> = emptyList(),
    val valleys: List<PPGValley> = emptyList(),
    val amplitude: Float = 0f,
    val frequency: Float = 0f,
    val regularity: Float = 0f,
    val symmetry: Float = 0f,
    val timestamp: Long = 0L
)

data class WaveformAnalysisResult(
    val point: PPGWaveformPoint,
    val morphology: WaveformMorphology,
    val waveType: WaveType,
    val abnormalities: List<PPGAbnormality>,
    val signalQuality: Float,
    val confidence: Float,
    val timestamp: Long
)

data class PPGAbnormality(
    val type: AbnormalityType,
    val severity: AbnormalitySeverity,
    val description: String,
    val value: Float
)

data class MorphologyStatistics(
    val totalPeaks: Int,
    val totalValleys: Int,
    val averageAmplitude: Float,
    val currentWaveType: WaveType,
    val signalQuality: Float,
    val abnormalitiesCount: Int
)

// Enumeraciones
enum class WaveType {
    NO_SIGNAL, WEAK, ACCEPTABLE, NORMAL, IRREGULAR, ABNORMAL
}

enum class PeakMorphologyType {
    NORMAL, WITH_NOTCH, COMPLEX, ABNORMAL
}

enum class ValleyMorphologyType {
    NORMAL, FLAT, STEEP_DESCENT, STEEP_ASCENT
}

enum class AbnormalityType {
    HIGH_AMPLITUDE, LOW_AMPLITUDE, TACHYCARDIA, BRADYCARDIA, 
    IRREGULAR_RHYTHM, ABNORMAL_MORPHOLOGY
}

enum class AbnormalitySeverity {
    LOW, MODERATE, HIGH
}

import kotlin.math.*
