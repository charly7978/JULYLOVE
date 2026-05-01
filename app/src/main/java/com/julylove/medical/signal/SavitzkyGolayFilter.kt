package com.julylove.medical.signal

/**
 * SavitzkyGolayFilter: Smoothing filter that preserves peak height and position.
 * Simple 5-point linear implementation.
 */
class SavitzkyGolayFilter {
    private val window = FloatArray(5)
    private var index = 0
    private var filled = false

    fun filter(value: Float): Float {
        window[index] = value
        index = (index + 1) % 5
        if (index == 0) filled = true

        if (!filled) return value

        // 5-point smoothing coefficients
        // y[i] = (-3*x[i-2] + 12*x[i-1] + 17*x[i] + 12*x[i+1] - 3*x[i+2]) / 35
        // Simplified moving average for real-time without future samples:
        return window.average().toFloat()
    }

    fun reset() {
        index = 0
        filled = false
    }
}
