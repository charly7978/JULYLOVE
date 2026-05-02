package com.julylove.medical.signal

import kotlin.math.*

/**
 * ForensicPpgProcessor - Procesador integral PPG para análisis médico-forense
 * 
 * Este procesador combina:
 * - Procesamiento clínico de señal (ClinicalSignalProcessor)
 * - Detección avanzada de picos (ClinicalPeakDetector)
 * - Análisis morfológico completo
 * - Detección REAL de dedo (NO simula nunca)
 * 
 * PRINCIPIOS FUNDAMENTALES:
 * 1. NO SIMULA: Cuando no hay dedo, la señal es plana
 * 2. Detección basada en perfusión real, no thresholds arbitrarios
 * 3. Extracción de puntos fiduciales completos
 * 4. Visualización de ondas reales con valles, mesetas, picos
 */
class ForensicPpgProcessor {
    
    private val signalProcessor = ClinicalSignalProcessor()
    private val peakDetector = ClinicalPeakDetector()
    
    // Historial de muestras para visualización
    private val sampleHistory = ArrayDeque<ForensicSample>(512)
    private val peakHistory = ArrayDeque<ClinicalPeakDetector.DetectedPeak>(50)
    
    data class ForensicSample(
        val timestampNs: Long,
        val rawValue: Float,
        val dcComponent: Float,
        val acComponent: Float,
        val filteredValue: Float,
        val fingerDetected: Boolean,
        val signalQuality: Float,
        val perfusionIndex: Float,
        val systolicPeak: Boolean,
        val dicroticNotch: Boolean,
        val valley: Boolean,
        val redChannel: Float,
        val greenChannel: Float,
        val blueChannel: Float
    )
    
    data class ForensicResult(
        val samples: List<ForensicSample>,
        val peaks: List<ClinicalPeakDetector.DetectedPeak>,
        val fingerPresent: Boolean,
        val signalQuality: Float,
        val heartRate: Float,
        val hrv: Float,
        val validBeats: Int,
        val abnormalBeats: Int,
        val waveformQuality: WaveformQuality
    )
    
    enum class WaveformQuality {
        EXCELLENT,      // Señal clara, todos los puntos fiduciales visibles
        GOOD,           // Señal buena, picos claros
        ACCEPTABLE,     // Señal usable pero con artefactos menores
        POOR,           // Señal débil, dificultad para detectar picos
        NO_FINGER,      // No hay dedo detectado
        MOTION_ARTIFACT // Artefacto de movimiento severo
    }
    
    /**
     * Procesa un frame de cámara PPG usando el nuevo modelo de 3 puertas
     */
    fun processFrame(
        timestampNs: Long,
        redValue: Float,
        greenValue: Float,
        blueValue: Float
    ): ForensicResult {
        
        // 1. ACQUISITION GATE (Siempre abierta si hay señal cruda)
        val rawPpg = greenValue * 0.6f + redValue * 0.3f + blueValue * 0.1f
        
        // 2. PROCESSING GATE (Basada en evidencia óptica real, no morfología o BPM)
        val opticalEvidence = rawPpg > 0.01f && rawPpg < 1.0f 
        val clinicalResult = signalProcessor.process(
            rawValue = rawPpg,
            timestampNs = timestampNs,
            redValue = redValue,
            greenValue = greenValue,
            blueValue = blueValue
        )
        
        // 3. PUBLICATION GATE (Solo publica si hay evidencia pulsátil real y morfológica)
        val hasCardiacEvidence = clinicalResult.isValid && clinicalResult.systolicPeak
        
        val detectedPeaks = if (hasCardiacEvidence) {
            peakDetector.process(clinicalResult)
        } else {
            emptyList()
        }
        
        // Almacenar muestra forense (siempre, para análisis de diagnóstico)
        val forensicSample = ForensicSample(
            timestampNs = timestampNs,
            rawValue = rawPpg,
            dcComponent = clinicalResult.dcComponent,
            acComponent = clinicalResult.acComponent,
            filteredValue = clinicalResult.filteredValue,
            fingerDetected = clinicalResult.fingerDetected,
            signalQuality = clinicalResult.signalQuality,
            perfusionIndex = clinicalResult.perfusionIndex,
            systolicPeak = clinicalResult.systolicPeak,
            dicroticNotch = clinicalResult.dicroticNotch,
            valley = clinicalResult.valley,
            redChannel = redValue,
            greenChannel = greenValue,
            blueChannel = blueValue
        )
        
        sampleHistory.add(forensicSample)
        while (sampleHistory.size > 512) sampleHistory.removeFirst()
        
        detectedPeaks.forEach { peak ->
            if (peak.type == ClinicalPeakDetector.PeakType.SYSTOLIC) {
                peakHistory.add(peak)
            }
        }
        
        // Calcular calidad de forma de onda
        val waveformQuality = calculateWaveformQuality(clinicalResult, detectedPeaks, sampleHistory.toList())
        
        // Si no pasa el gate de publicación, devolvemos resultados sin datos clínicos visibles
        return if (hasCardiacEvidence && waveformQuality != WaveformQuality.POOR) {
            ForensicResult(
                samples = sampleHistory.toList(),
                peaks = peakHistory.toList(),
                fingerPresent = clinicalResult.fingerDetected,
                signalQuality = clinicalResult.signalQuality,
                heartRate = peakDetector.getAverageHeartRate(),
                hrv = peakDetector.getHRV(),
                validBeats = peakHistory.count(),
                abnormalBeats = 0,
                waveformQuality = waveformQuality
            )
        } else {
            // Estado de diagnóstico: capturando pero sin publicar
            ForensicResult(
                samples = sampleHistory.toList(),
                peaks = emptyList(),
                fingerPresent = clinicalResult.fingerDetected,
                signalQuality = clinicalResult.signalQuality,
                heartRate = 0f,
                hrv = 0f,
                validBeats = 0,
                abnormalBeats = 0,
                waveformQuality = WaveformQuality.POOR
            )
        }
    }
    
    /**
     * Calcula calidad de la forma de onda para visualización
     */
    private fun calculateWaveformQuality(
        clinicalResult: ClinicalResult,
        peaks: List<ClinicalPeakDetector.DetectedPeak>,
        samples: List<ForensicSample>
    ): WaveformQuality {
        
        if (!clinicalResult.fingerDetected) {
            return WaveformQuality.NO_FINGER
        }
        
        if (clinicalResult.signalQuality < 0.2f) {
            return WaveformQuality.POOR
        }
        
        // Verificar si hay movimiento excesivo
        if (samples.size >= 30) {
            val recent = samples.takeLast(30)
            val variances = recent.map { it.acComponent }.let { acValues ->
                val mean = acValues.average()
                acValues.map { (it - mean).pow(2) }.average()
            }
            
            // Movimiento excesivo = varianza muy alta
            if (variances > 0.1) {
                return WaveformQuality.MOTION_ARTIFACT
            }
        }
        
        // Verificar presencia de puntos fiduciales
        val systolicPeaks = peaks.count { it.type == ClinicalPeakDetector.PeakType.SYSTOLIC }
        val notches = peaks.count { it.type == ClinicalPeakDetector.PeakType.NOTCH_DICROTIC }
        val valleys = peaks.count { it.type == ClinicalPeakDetector.PeakType.VALLEY }
        
        val hasCompleteFiducials = systolicPeaks >= 3 && notches >= 2 && valleys >= 2
        
        return when {
            clinicalResult.signalQuality > 0.8f && hasCompleteFiducials -> WaveformQuality.EXCELLENT
            clinicalResult.signalQuality > 0.6f && systolicPeaks >= 3 -> WaveformQuality.GOOD
            clinicalResult.signalQuality > 0.4f -> WaveformQuality.ACCEPTABLE
            else -> WaveformQuality.POOR
        }
    }
    
    /**
     * Obtiene el último resultado sin procesar nuevo frame
     */
    fun getCurrentResult(): ForensicResult {
        val validBeats = peakHistory.count { it.confidence > 0.6f }
        val abnormalBeats = peakHistory.count { 
            it.confidence < 0.4f || 
            peakDetector.classifyBeatMorphology(it) != ClinicalPeakDetector.BeatMorphologyClass.NORMAL 
        }
        
        return ForensicResult(
            samples = sampleHistory.toList(),
            peaks = peakHistory.toList(),
            fingerPresent = sampleHistory.lastOrNull()?.fingerDetected ?: false,
            signalQuality = sampleHistory.lastOrNull()?.signalQuality ?: 0f,
            heartRate = peakDetector.getAverageHeartRate(),
            hrv = peakDetector.getHRV(),
            validBeats = validBeats,
            abnormalBeats = abnormalBeats,
            waveformQuality = WaveformQuality.NO_FINGER
        )
    }
    
    fun reset() {
        signalProcessor.reset()
        peakDetector.reset()
        sampleHistory.clear()
        peakHistory.clear()
    }
}
