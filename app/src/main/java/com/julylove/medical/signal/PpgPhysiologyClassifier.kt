package com.julylove.medical.signal

import kotlin.math.sqrt

/**
 * PpgPhysiologyClassifier - El "Guardián Fisiológico".
 * Decide si la señal óptica capturada tiene características de tejido humano vivo y pulsátil.
 */
class PpgPhysiologyClassifier {

    private val windowSize = 180 // ~3s a 60fps
    private val greenBuffer = mutableListOf<Float>()
    private val redBuffer = mutableListOf<Float>()

    fun classify(frame: PpgFrame): PpgValidityState {
        // 1. Verificaciones Crudas Instantáneas
        if (frame.saturationRatio > 0.8f) return PpgValidityState.SATURATED
        if (frame.avgRed < 15f) return PpgValidityState.LOW_PERFUSION
        
        // 2. Modelo Cromático (Piel bajo flash LED blanco)
        // El tejido humano transiluminado es predominantemente rojo, pero el canal verde tiene el pulso.
        if (frame.redDominance < 1.05f || frame.redDominance > 6.0f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        greenBuffer.add(frame.avgGreen)
        redBuffer.add(frame.avgRed)
        if (greenBuffer.size > windowSize) greenBuffer.removeAt(0)
        if (redBuffer.size > windowSize) redBuffer.removeAt(0)

        if (greenBuffer.size < windowSize) return PpgValidityState.SEARCHING_PPG

        // 3. Análisis de Variación Temporal (AC/DC)
        val cvG = calculateCV(greenBuffer)
        val cvR = calculateCV(redBuffer)
        
        // Objetos estáticos (sábanas, paredes) tienen CV bajísimo (< 0.001)
        if (cvG < 0.0008f && cvR < 0.0008f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }
        
        // 4. Correlación Rojo-Verde
        // En PPG real, R y G suelen estar altamente correlacionados (pulso de hemoglobina)
        val correlation = calculateCorrelation(redBuffer, greenBuffer)
        if (correlation < 0.2f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        // 5. Estabilidad Espectral (Simplificada por Cruces por Cero)
        val crossings = countZeroCrossings(greenBuffer)
        // Para 3s, esperamos entre 4 y 15 cruces (40-150 BPM)
        if (crossings !in 3..18) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        return PpgValidityState.PPG_VALID
    }

    private fun calculateCV(buffer: List<Float>): Float {
        val mean = buffer.average().toFloat().coerceAtLeast(1f)
        val std = sqrt(buffer.map { (it - mean) * (it - mean) }.average().toFloat())
        return std / mean
    }

    private fun calculateCorrelation(a: List<Float>, b: List<Float>): Float {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val va = a[i] - ma
            val vb = b[i] - mb
            num += va * vb
            da += va * va
            db += vb * vb
        }
        val den = sqrt(da * db)
        return if (den > 0) (num / den).toFloat() else 0f
    }

    private fun countZeroCrossings(buffer: List<Float>): Int {
        val avg = buffer.average().toFloat()
        var count = 0
        for (i in 1 until buffer.size) {
            if ((buffer[i-1] - avg) * (buffer[i] - avg) < 0) count++
        }
        return count
    }

    fun reset() {
        greenBuffer.clear()
        redBuffer.clear()
    }
}
