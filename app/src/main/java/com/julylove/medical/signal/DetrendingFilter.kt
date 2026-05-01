package com.julylove.medical.signal

/**
 * DetrendingFilter: Removes slow DC drifts and breathing artifacts.
 * Uses a simple Moving Average Subtraction (High-pass effect).
 */
class DetrendingFilter(private val windowSize: Int = 30) {
    private val buffer = mutableListOf<Float>()
    private var sum = 0f

    fun filter(value: Float): Float {
        buffer.add(value)
        sum += value

        if (buffer.size > windowSize) {
            sum -= buffer.removeAt(0)
        }

        val mean = sum / buffer.size
        return value - mean
    }

    fun reset() {
        buffer.clear()
        sum = 0f
    }
}
