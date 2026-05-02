package com.julylove.medical.signal

import kotlin.math.*

/**
 * PPGMorphologyAnalyzer - Analizador de morfología de ondas PPG
 * 
 * Detecta características morfológicas de la onda PPG:
 * - Picos sistólicos y diastólicos
 * - Notch dicrotico
 * - Valles
 * - Anomalías de forma
 */
class PPGMorphologyAnalyzer {
    
    data class MorphologyResult(
        val waveType: WaveType,
        val abnormalities: List<PPGAbnormality>,
        val peakCount: Int,
        val valleyCount: Int,
        val notchCount: Int,
        val averageSystolicAmplitude: Float,
        val averageDiastolicAmplitude: Float,
        val pulseWaveVelocity: Float  // Proxy basado en timing
    )
    
    private val history = ArrayDeque<Sample>(200)
    private val detectedPeaks = ArrayDeque<Peak>(20)
    
    data class Sample(
        val timestampNs: Long,
        val value: Float,
        val isPeak: Boolean = false,
        val isValley: Boolean = false,
        val hasNotch: Boolean = false
    )
    
    data class Peak(
        val timestampNs: Long,
        val amplitude: Float,
        val type: PeakType
    )
    
    enum class PeakType {
        SYSTOLIC,
        DIASTOLIC,
        NOTCH
    }
    
    fun analyzeWaveformPoint(
        filteredValue: Float,
        timestampNs: Long,
        isSystolicPeak: Boolean,
        isValley: Boolean,
        hasDicroticNotch: Boolean
    ): MorphologyResult {
        
        // Almacenar muestra
        history.add(Sample(
            timestampNs = timestampNs,
            value = filteredValue,
            isPeak = isSystolicPeak,
            isValley = isValley,
            hasNotch = hasDicroticNotch
        ))
        
        if (history.size > 200) history.removeFirst()
        
        // Detectar picos
        if (isSystolicPeak) {
            detectedPeaks.add(Peak(timestampNs, filteredValue, PeakType.SYSTOLIC))
        }
        
        // Limpiar picos antiguos
        val cutoff = timestampNs - 5_000_000_000L
        while (detectedPeaks.isNotEmpty() && detectedPeaks.first().timestampNs < cutoff) {
            detectedPeaks.removeFirst()
        }
        
        // Analizar morfología
        val waveType = classifyWaveform()
        val abnormalities = detectAbnormalities()
        
        return MorphologyResult(
            waveType = waveType,
            abnormalities = abnormalities,
            peakCount = history.count { it.isPeak },
            valleyCount = history.count { it.isValley },
            notchCount = history.count { it.hasNotch },
            averageSystolicAmplitude = calculateAverageSystolicAmplitude(),
            averageDiastolicAmplitude = calculateAverageDiastolicAmplitude(),
            pulseWaveVelocity = estimatePulseWaveVelocity()
        )
    }
    
    private fun classifyWaveform(): WaveType {
        if (history.size < 20) return WaveType.NO_SIGNAL
        
        val peaks = history.count { it.isPeak }
        val valleys = history.count { it.isValley }
        val notches = history.count { it.hasNotch }
        
        return when {
            peaks == 0 -> WaveType.NO_SIGNAL
            notches >= peaks * 0.5f -> WaveType.NORMAL_TRIFFASIC  // Sistólico, notch, diastólico
            valleys >= peaks * 0.7f -> WaveType.NORMAL_BIFFASIC    // Sistólico + valle
            peaks > valleys * 2 -> WaveType.ARTIFACT_RAPID_PEAKS  // Picos demasiado frecuentes
            else -> WaveType.ABNORMAL_MORPHOLOGY
        }
    }
    
    private fun detectAbnormalities(): List<PPGAbnormality> {
        val abnormalities = mutableListOf<PPGAbnormality>()
        
        if (history.size < 10) return abnormalities
        
        // Detectar ausencia de notch dicrotico
        val peaks = history.filter { it.isPeak }
        val notches = history.filter { it.hasNotch }
        
        if (peaks.size >= 3 && notches.size < peaks.size * 0.3f) {
            abnormalities.add(PPGAbnormality.MISSING_DICROTIC_NOTCH)
        }
        
        // Detectar picos ausentes
        if (peaks.isEmpty() && history.size > 60) {
            abnormalities.add(PPGAbnormality.NO_DETECTABLE_PEAKS)
        }
        
        // Detectar variabilidad excesiva
        val amplitudes: List<Float> = peaks.map { it.value }
        if (amplitudes.size >= 3) {
            val mean: Double = amplitudes.average()
            val squaredDiffs: List<Double> = amplitudes.map { 
                val diff = it - mean
                diff * diff
            }
            val variance: Double = squaredDiffs.average()
            val cv = kotlin.math.sqrt(variance) / mean
            
            if (cv > 0.3) {
                abnormalities.add(PPGAbnormality.HIGH_AMPLITUDE_VARIABILITY)
            }
        }
        
        return abnormalities
    }
    
    private fun calculateAverageSystolicAmplitude(): Float {
        val systolicPeaks = detectedPeaks.filter { it.type == PeakType.SYSTOLIC }
        return if (systolicPeaks.isNotEmpty()) {
            val amplitudes: List<Float> = systolicPeaks.map { it.amplitude }
            amplitudes.average().toFloat()
        } else 0f
    }
    
    private fun calculateAverageDiastolicAmplitude(): Float {
        val diastolicPeaks = detectedPeaks.filter { it.type == PeakType.DIASTOLIC }
        return if (diastolicPeaks.isNotEmpty()) {
            val amplitudes: List<Float> = diastolicPeaks.map { it.amplitude }
            amplitudes.average().toFloat()
        } else 0f
    }
    
    private fun estimatePulseWaveVelocity(): Float {
        // Proxy simple basado en tiempo sistólico-diastólico
        // En una implementación real usaría la distancia entre notch y pico diastólico
        return 0f  // Placeholder
    }
    
    fun reset() {
        history.clear()
        detectedPeaks.clear()
    }
}
