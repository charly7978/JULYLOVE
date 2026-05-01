package com.julylove.medical.signal

import java.util.*

/**
 * Advanced real-time Peak Detection for PPG.
 * Uses a combination of adaptive thresholding, slope analysis, and 
 * physiological refractory validation.
 */
class PeakDetectionEngine(private val sampleRate: Float) {
    private var lastPeakTimestamp: Long = 0
    private val minRefractoryMs = 350L // ~170 BPM max
    
    private val signalWindow: Queue<Float> = LinkedList()
    private val windowSize = (sampleRate * 1.5).toInt()
    
    private var lastValue = 0f
    private var isRising = false
    private var possiblePeakValue = -Float.MAX_VALUE
    private var possiblePeakTimestamp = 0L

    fun process(value: Float, timestamp: Long): Boolean {
        signalWindow.add(value)
        if (signalWindow.size > windowSize) signalWindow.poll()
        if (signalWindow.size < 10) return false

        val maxInWindow = signalWindow.maxOrNull() ?: 1f
        val minInWindow = signalWindow.minOrNull() ?: 0f
        val threshold = minInWindow + (maxInWindow - minInWindow) * 0.7f

        val slope = value - lastValue
        val wasRising = isRising
        isRising = slope > 0
        
        var peakDetected = false

        // Detect local maximum (transition from rising to falling)
        if (wasRising && !isRising && value > threshold) {
            val timeSinceLast = timestamp - lastPeakTimestamp
            
            // Validate physiological interval
            if (timeSinceLast > minRefractoryMs) {
                peakDetected = true
                lastPeakTimestamp = timestamp
            }
        }

        lastValue = value
        return peakDetected
    }
    
    fun reset() {
        signalWindow.clear()
        lastPeakTimestamp = 0
    }
}
