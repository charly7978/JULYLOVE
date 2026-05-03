package com.forensicppg.monitor.ppg

import kotlin.math.max

/**
 * Detector de picos PPG según Elgendi (2013), "Optimal Systolic Peak Detection
 * in Photoplethysmograms". Trabaja sobre la señal filtrada (banda estrecha
 * 0.5–4 Hz) cuadrada, con dos ventanas móviles (short ≈ duración de latido,
 * long ≈ varios latidos) y un umbral adaptativo basado en la señal offset.
 *
 * Implementación streaming: mantiene ring buffers de tamaño fijo para las dos
 * ventanas y exige período refractario fisiológico (≥ 250 ms).
 */
class PeakDetectorElgendi(
    sampleRateHz: Double,
    shortWindowMs: Long = 111L,
    longWindowMs: Long = 667L,
    private val offsetBeta: Double = 0.02,
    private val refractoryMs: Long = 280L,
    minProminence: Double = 1e-5
) {
    private val fs = sampleRateHz
    private val shortN = max(3, (sampleRateHz * shortWindowMs / 1000.0).toInt())
    private val longN = max(shortN + 2, (sampleRateHz * longWindowMs / 1000.0).toInt())

    private val shortBuf = DoubleArray(shortN)
    private val longBuf = DoubleArray(longN)
    private var shortSum = 0.0
    private var longSum = 0.0
    private var shortIdx = 0
    private var longIdx = 0
    private var shortFilled = 0
    private var longFilled = 0

    private val refractoryN = (refractoryMs.toDouble() * sampleRateHz / 1000.0).toInt().coerceAtLeast(3)
    private var refractoryCountdown = 0

    private val minProm = minProminence
    private var inBlock = false
    private var blockPeakValue = 0.0
    private var blockPeakIndex = -1L
    private var blockPeakTimestampNs = 0L
    private var sampleIndex = 0L

    data class Detection(val sampleIndex: Long, val amplitude: Double, val timestampNs: Long)

    fun reset() {
        for (i in shortBuf.indices) shortBuf[i] = 0.0
        for (i in longBuf.indices) longBuf[i] = 0.0
        shortSum = 0.0; longSum = 0.0
        shortIdx = 0; longIdx = 0
        shortFilled = 0; longFilled = 0
        refractoryCountdown = 0
        inBlock = false
        blockPeakValue = 0.0
        blockPeakIndex = -1L
        blockPeakTimestampNs = 0L
        sampleIndex = 0L
    }

    /**
     * Alimenta una muestra filtrada. Devuelve una detección (pico sistólico)
     * si se cerró un bloque que superó los thresholds adaptativos, o null.
     */
    fun feed(filtered: Double, timestampNs: Long): Detection? {
        val squared = if (filtered > 0.0) filtered * filtered else 0.0

        // Ventana short (≈ duración de pico)
        val oldShort = shortBuf[shortIdx]
        shortBuf[shortIdx] = squared
        shortSum += squared - oldShort
        shortIdx = (shortIdx + 1) % shortN
        if (shortFilled < shortN) shortFilled++

        // Ventana long (≈ varios latidos)
        val oldLong = longBuf[longIdx]
        longBuf[longIdx] = squared
        longSum += squared - oldLong
        longIdx = (longIdx + 1) % longN
        if (longFilled < longN) longFilled++

        val maPeak = shortSum / shortFilled
        val maBeat = longSum / longFilled
        val threshold = maBeat * (1.0 + offsetBeta)

        if (refractoryCountdown > 0) refractoryCountdown--

        var result: Detection? = null
        if (maPeak > threshold) {
            if (!inBlock) {
                inBlock = true
                blockPeakValue = filtered
                blockPeakIndex = sampleIndex
                blockPeakTimestampNs = timestampNs
            } else if (filtered > blockPeakValue) {
                blockPeakValue = filtered
                blockPeakIndex = sampleIndex
                blockPeakTimestampNs = timestampNs
            }
        } else if (inBlock) {
            if (refractoryCountdown <= 0 && blockPeakValue > minProm) {
                result = Detection(blockPeakIndex, blockPeakValue, blockPeakTimestampNs)
                refractoryCountdown = refractoryN
            }
            inBlock = false
            blockPeakValue = 0.0
            blockPeakIndex = -1L
        }
        sampleIndex++
        return result
    }

    @Suppress("unused")
    val shortWindowSamples: Int get() = shortN
    @Suppress("unused")
    val longWindowSamples: Int get() = longN
    @Suppress("unused")
    val sampleRate: Double get() = fs
}
