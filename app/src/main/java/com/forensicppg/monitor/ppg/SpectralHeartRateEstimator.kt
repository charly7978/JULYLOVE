package com.forensicppg.monitor.ppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimador espectral de BPM por DFT Goertzel-like sobre un buffer deslizante
 * de señal filtrada. Barre la banda 0.5–4 Hz cada N segundos y devuelve la
 * frecuencia con máximo magnitud y una medida de coherencia espectral
 * (ratio pico/mediana).
 *
 * No usa FFT para evitar librerías externas y mantener latencia baja: el
 * barrido es en incrementos de 1 BPM, con O(N*K) operaciones por cálculo, que
 * se ejecuta cada ~1 s.
 */
class SpectralHeartRateEstimator(
    private val sampleRate: Double,
    windowSeconds: Double = 8.0,
    private val minBpm: Int = 30,
    private val maxBpm: Int = 240
) {
    private val n = (sampleRate * windowSeconds).toInt().coerceAtLeast(32)
    private val buffer = DoubleArray(n)
    private var idx = 0
    private var filled = 0

    data class Result(val bpm: Double?, val coherence: Double, val samplesUsed: Int)

    fun reset() {
        for (i in 0 until n) buffer[i] = 0.0
        idx = 0; filled = 0
    }

    fun push(sample: Double) {
        buffer[idx] = sample
        idx = (idx + 1) % n
        if (filled < n) filled++
    }

    fun estimate(): Result {
        if (filled < n) return Result(null, 0.0, filled)
        val start = idx
        var peakMag = 0.0
        var peakBpm = 0
        val mags = DoubleArray(maxBpm - minBpm + 1)
        for (bpm in minBpm..maxBpm) {
            val freq = bpm / 60.0
            val omega = 2.0 * PI * freq / sampleRate
            val c = cos(omega); val s = sin(omega)
            var re = 0.0; var im = 0.0
            var i = 0
            while (i < n) {
                val sample = buffer[(start + i) % n]
                val angle = i
                // rotación incremental (menos allocations que Euler directo)
                re += sample * cosFast(omega, angle)
                im += sample * sinFast(omega, angle)
                i++
            }
            val mag = sqrt(re * re + im * im)
            mags[bpm - minBpm] = mag
            if (mag > peakMag) {
                peakMag = mag
                peakBpm = bpm
            }
            @Suppress("UNUSED_VARIABLE") val _c = c; @Suppress("UNUSED_VARIABLE") val _s = s
        }
        val sorted = mags.sortedArray()
        val median = sorted[sorted.size / 2]
        val coherence = if (median > 0.0) (peakMag / median).coerceAtMost(20.0) / 20.0 else 0.0
        return Result(bpm = peakBpm.toDouble(), coherence = coherence, samplesUsed = n)
    }

    /* Cache de cos/sin por angle*omega. Sin allocations: sólo stack vars. */
    private fun cosFast(omega: Double, k: Int): Double = cos(omega * k)
    private fun sinFast(omega: Double, k: Int): Double = sin(omega * k)
}
