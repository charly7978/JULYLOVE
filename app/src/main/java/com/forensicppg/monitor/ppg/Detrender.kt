package com.forensicppg.monitor.ppg

/**
 * Detrending por media móvil con sustracción. Sirve para eliminar la línea
 * base (DC y deriva lenta por variaciones de presión del dedo, torch, etc.)
 * antes del bandpass. Es O(1) por muestra.
 */
class Detrender(windowSamples: Int) {
    private val buffer = DoubleArray(windowSamples.coerceAtLeast(2))
    private val size = buffer.size
    private var index = 0
    private var filled = 0
    private var sum = 0.0

    fun process(sample: Double): Double {
        val old = buffer[index]
        buffer[index] = sample
        sum += sample - old
        index = (index + 1) % size
        if (filled < size) filled++
        val mean = sum / filled
        return sample - mean
    }

    fun reset() {
        for (i in 0 until size) buffer[i] = 0.0
        index = 0
        filled = 0
        sum = 0.0
    }
}
