package com.julylove.medical.signal

import com.julylove.medical.camera.PpgCameraFrame
import kotlin.math.sqrt

/**
 * Discriminación dedo‑piel vs. objeto mate/plástico: el tejido con flash muestra pulsación (>CV)
 * en R y G y correlación positiva R–G; objetos rígidos suelen dar DC casi plano o picos no fisiológicos.
 * Lo espacial (cuadrantes) solo apoya cobertura, no sustituye la prueba temporal.
 */
class PpgPhysiologyClassifier {

    private val signalBuffer = mutableListOf<Float>()
    private val redHistory = mutableListOf<Float>()
    private val greenHistory = mutableListOf<Float>()
    private val windowSize = 150
    private val chromaWindow = 90

    fun reset() {
        signalBuffer.clear()
        redHistory.clear()
        greenHistory.clear()
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
        if (redMean < 22f) return PpgValidityState.LOW_PERFUSION

        redHistory.add(redMean)
        greenHistory.add(greenMean)
        while (redHistory.size > chromaWindow) redHistory.removeAt(0)
        while (greenHistory.size > chromaWindow) greenHistory.removeAt(0)

        // Bandas cromáticas típicas tejido + LED blanco (reflectancia; no piel “bonita”, sino rango fisiológico ancho)
        val rg = redMean / greenMean.coerceAtLeast(4f)
        val gb = greenMean / blueMean.coerceAtLeast(4f)
        // Plástico rojo intenso: R/G muy alto y poco canal azul útil → desconfiar
        if (rg > 4.2f || greenMean < blueMean * 0.82f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }
        if (rg < 1.08f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        // Cobertura parcial lente (no exigir perfección: dedo real suele 0,45–0,95)
        if (frame.quadrantBalanceRed < 0.42f && redMean > 40f) {
            return PpgValidityState.SEARCHING_PPG
        }

        signalBuffer.add(filteredValue)
        if (signalBuffer.size > windowSize) signalBuffer.removeAt(0)

        if (signalBuffer.size < windowSize) return PpgValidityState.SEARCHING_PPG
        if (redHistory.size < chromaWindow || greenHistory.size < chromaWindow) {
            return PpgValidityState.PPG_CANDIDATE
        }

        val dc = redMean.coerceAtLeast(1f)
        val ac = calculateRMS(signalBuffer)
        val perfusionIndex = (ac / dc) * 100f

        // Pulsos reales: perfusión mínima baja un poco para no perder dedos fríos
        if (perfusionIndex < 0.028f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        if (isMoving) return PpgValidityState.MOTION_ARTIFACT

        val cvR = coefficientOfVariation(redHistory)
        val cvG = coefficientOfVariation(greenHistory)
        val corrRG = pearson(redHistory, greenHistory)
        if (cvR < 0.0018f || cvG < 0.0010f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }
        if (corrRG < 0.16f) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        val crossings = countZeroCrossings(signalBuffer)
        // ~40–190 lpm sobre 2,5 s @60fps: órdenes de magnitud de cruces por cero
        if (crossings !in 2..28) {
            return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        }

        return PpgValidityState.PPG_VALID
    }

    private fun coefficientOfVariation(buf: List<Float>): Float {
        if (buf.size < 12) return 0f
        val m = buf.average().toFloat().coerceAtLeast(1f)
        var s = 0f
        for (v in buf) {
            val d = v - m
            s += d * d
        }
        return sqrt(s / buf.size) / m
    }

    private fun pearson(a: List<Float>, b: List<Float>): Float {
        val n = minOf(a.size, b.size)
        if (n < 24) return 0f
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += a[i]
            sy += b[i]
        }
        val mx = sx / n
        val my = sy / n
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val vx = a[i] - mx
            val vy = b[i] - my
            num += vx * vy
            dx += vx * vx
            dy += vy * vy
        }
        val den = sqrt(dx) * sqrt(dy)
        if (den < 1e-9) return 0f
        return (num / den).toFloat().coerceIn(-1f, 1f)
    }

    private fun calculateRMS(buffer: List<Float>): Float {
        val avg = buffer.average().toFloat()
        var sumSq = 0f
        for (v in buffer) {
            val diff = v - avg
            sumSq += diff * diff
        }
        return sqrt(sumSq / buffer.size)
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
