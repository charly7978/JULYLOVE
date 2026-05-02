package com.forensicppg.monitor.ppg

import kotlin.math.max
import kotlin.math.min

/**
 * Preprocesamiento completo del canal PPG seleccionado (típicamente rojo).
 *
 * Etapas:
 *   1. Detrender con ventana ~4 s.
 *   2. Bandpass Butterworth 0.5–4 Hz.
 *   3. Cómputo de AC/DC y perfusion index.
 */
class PpgPreprocessor(
    private val sampleRate: Double,
    lowHz: Double = 0.5,
    highHz: Double = 4.0
) {
    private val detrender = Detrender(windowSamples = (sampleRate * 4.0).toInt().coerceAtLeast(30))
    private val band = BandpassFilter(sampleRate, lowHz = lowHz, highHz = highHz)

    private var dcEma: Double = 0.0
    private var initialized = false
    private var acMax: Double = Double.NEGATIVE_INFINITY
    private var acMin: Double = Double.POSITIVE_INFINITY
    private var acBufferIndex = 0
    private val acBuffer = DoubleArray((sampleRate * 2.0).toInt().coerceAtLeast(30))

    fun reset() {
        detrender.reset()
        band.reset()
        dcEma = 0.0
        initialized = false
        acMax = Double.NEGATIVE_INFINITY
        acMin = Double.POSITIVE_INFINITY
        acBufferIndex = 0
        for (i in acBuffer.indices) acBuffer[i] = 0.0
    }

    data class Output(
        val detrended: Double,
        val filtered: Double,
        val dc: Double,
        val ac: Double,
        val perfusionIndex: Double
    )

    fun process(rawSample: Double): Output {
        if (!initialized) {
            dcEma = rawSample
            initialized = true
        } else {
            // Media móvil exponencial para DC (más larga que el bandpass).
            val alphaDc = 1.0 / (sampleRate * 2.0)
            dcEma += alphaDc * (rawSample - dcEma)
        }
        val detrended = detrender.process(rawSample)
        val filtered = band.process(detrended)

        acBuffer[acBufferIndex] = filtered
        acBufferIndex = (acBufferIndex + 1) % acBuffer.size

        // Recalculo min/max sobre el buffer real (O(N) cada ~2 s).
        if (acBufferIndex == 0) {
            var mx = Double.NEGATIVE_INFINITY
            var mn = Double.POSITIVE_INFINITY
            for (v in acBuffer) {
                mx = max(mx, v)
                mn = min(mn, v)
            }
            acMax = mx
            acMin = mn
        }
        val ac = (acMax - acMin).coerceAtLeast(0.0)
        val dc = dcEma.coerceAtLeast(1.0)
        val pi = 100.0 * (ac / dc)
        return Output(detrended, filtered, dc, ac, pi.coerceIn(0.0, 50.0))
    }
}
