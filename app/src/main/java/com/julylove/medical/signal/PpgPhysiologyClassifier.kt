package com.julylove.medical.signal

import com.julylove.medical.camera.PpgCameraFrame

/**
 * Gatekeeper: cromático + pulsátil temporal + rejilla espacial 4×4 (cobertura dedo‑lente, textura‑presión).
 * Objetivos: rechazar objeto rojo puntual, halo parcial abierto (“ventana”), tapa táctil muy uniforme‑plástico.
 */
class PpgPhysiologyClassifier {

    private val signalBuffer = mutableListOf<Float>()
    private val windowSize = 150

    fun reset() {
        signalBuffer.clear()
    }

    fun classify(
        filteredValue: Float,
        frame: PpgCameraFrame,
        isMoving: Boolean,
    ): PpgValidityState {
        val redMean = frame.redSrgb
        val greenMean = frame.greenSrgb
        val blueMean = frame.blueSrgb

        if (redMean > 252f) return PpgValidityState.SATURATED
        if (redMean < 30f) return PpgValidityState.LOW_PERFUSION

        val isChromaticLikelyHuman = redMean > (greenMean * 1.5f) && greenMean >= (blueMean * 0.9f)
        if (!isChromaticLikelyHuman) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL

        // Cobertura espacial cuadrántica — dedo descentrado suele producir vignette marcado intra‑ROI.
        if (frame.quadrantBalanceRed < 0.55f && redMean > 45f) {
            return PpgValidityState.SEARCHING_PPG
        }

        // Superficie demasiado homogénea (pinta roja mate, funda nítida puntual sobre lente abierto).
        if (frame.interBlockGradient < 1.8f && frame.blockLumaStd < 2.8f && redMean > 90f && frame.quadrantBalanceRed > 0.92f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        signalBuffer.add(filteredValue)
        if (signalBuffer.size > windowSize) signalBuffer.removeAt(0)

        if (signalBuffer.size < windowSize) return PpgValidityState.SEARCHING_PPG

        val dc = redMean
        val ac = calculateRMS(signalBuffer)
        val perfusionIndex = (ac / dc.coerceAtLeast(1f)) * 100

        if (perfusionIndex < 0.045f) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL

        if (isMoving) return PpgValidityState.MOTION_ARTIFACT

        val crossings = countZeroCrossings(signalBuffer)
        if (crossings !in 3..22) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL

        return PpgValidityState.PPG_VALID
    }

    private fun calculateRMS(buffer: List<Float>): Float {
        val avg = buffer.average().toFloat()
        var sumSq = 0f
        for (v in buffer) {
            val diff = v - avg
            sumSq += diff * diff
        }
        return kotlin.math.sqrt(sumSq / buffer.size)
    }

    private fun countZeroCrossings(buffer: List<Float>): Int {
        var count = 0
        val avg = buffer.average().toFloat()
        for (i in 1 until buffer.size) {
            val prev = buffer[i - 1] - avg
            val curr = buffer[i] - avg
            if (prev * curr < 0) count++
        }
        return count
    }
}
