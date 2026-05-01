package com.julylove.medical.signal

import kotlin.math.exp

class LowPassFilter(private val alpha: Float) {
    private var lastValue: Float? = null

    fun filter(value: Float): Float {
        val filtered = lastValue?.let { it + alpha * (value - it) } ?: value
        lastValue = filtered
        return filtered
    }
}

class HighPassFilter(private val alpha: Float) {
    private var lastValue: Float? = null
    private var lastFiltered: Float = 0f

    fun filter(value: Float): Float {
        val filtered = lastValue?.let {
            alpha * (lastFiltered + value - it)
        } ?: 0f
        lastValue = value
        lastFiltered = filtered
        return filtered
    }
}

/**
 * A moving average filter to remove the DC component (Detrending)
 */
class DetrendingFilter(private val windowSize: Int) {
    private val window = mutableListOf<Float>()
    private var sum = 0f

    fun filter(value: Float): Float {
        window.add(value)
        sum += value
        if (window.size > windowSize) {
            sum -= window.removeAt(0)
        }
        val mean = sum / window.size
        return value - mean
    }
}

/**
 * Simple Bandpass Filter for Heart Rate (approx 0.5Hz - 4Hz)
 */
class HeartRateBandpassFilter(sampleRate: Float) {
    // Basic implementation using a high-pass and then a low-pass
    private val hp = HighPassFilter(0.95f) // Adjust alpha based on sample rate
    private val lp = LowPassFilter(0.15f)

    fun filter(value: Float): Float {
        return lp.filter(hp.filter(value))
    }
}
