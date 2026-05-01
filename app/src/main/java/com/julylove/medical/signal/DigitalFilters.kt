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
 * Savitzky-Golay filter for smoothing signals without losing peak height.
 * This is a causal implementation (uses past samples).
 */
class SavitzkyGolayFilter {
    // Coefficients for a 5-point quadratic/cubic smoothing filter (causal-ish)
    // For a truly causal real-time filter, we use the "end-point" coefficients.
    private val window = mutableListOf<Float>()
    private val coeffs = floatArrayOf(-0.086f, 0.343f, 0.486f, 0.343f, -0.086f)

    fun filter(value: Float): Float {
        window.add(value)
        if (window.size < 5) return value
        if (window.size > 5) window.removeAt(0)

        var filtered = 0f
        for (i in 0 until 5) {
            filtered += window[i] * coeffs[i]
        }
        return filtered
    }
}

/**
 * Advanced Bandpass Filter using IIR (Butterworth 2nd order approximation)
 */
class ButterworthBandpass(private val sampleRate: Float) {
    // Frequencies: 0.5 Hz to 4.0 Hz
    // Coefficients calculated for 60Hz sample rate
    // Formula: y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
    
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    // Coeffs for 60Hz (typical Camera2 rate)
    private var b = floatArrayOf(0.0036f, 0f, -0.0036f)
    private var a = floatArrayOf(1f, -1.9112f, 0.9150f)

    init {
        updateCoefficients(sampleRate)
    }

    fun updateCoefficients(rate: Float) {
        // In a real medical app, we'd use a bilinear transform to calculate these on the fly
        // for the actual measured FPS. For now, we use pre-calculated sets.
        if (rate < 45) {
            // 30Hz Coeffs
            b = floatArrayOf(0.0134f, 0f, -0.0134f)
            a = floatArrayOf(1f, -1.8227f, 0.8372f)
        } else {
            // 60Hz Coeffs
            b = floatArrayOf(0.0036f, 0f, -0.0036f)
            a = floatArrayOf(1f, -1.9112f, 0.9150f)
        }
    }

    fun filter(x0: Float): Float {
        val y0 = b[0] * x0 + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2 = x1; x1 = x0
        y2 = y1; y1 = y0
        return y0
    }
}
