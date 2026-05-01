package com.julylove.medical.signal

import java.util.*

/**
 * Real-time Peak Detection using an adaptive threshold and refractory period.
 */
class PeakDetectionEngine(private val sampleRate: Float) {
    private var lastPeakTimestamp: Long = 0
    private val refractoryPeriodMs = 300L // Min time between beats (200 BPM max)
    
    private var localMax = -Float.MAX_VALUE
    private var localMin = Float.MAX_VALUE
    private var threshold = 0f
    
    // Window for threshold adaptation
    private val windowSize = (sampleRate * 2).toInt() // 2 seconds window
    private val signalWindow: Queue<Float> = LinkedList()

    fun process(value: Float, timestamp: Long): Boolean {
        signalWindow.add(value)
        if (signalWindow.size > windowSize) {
            signalWindow.poll()
        }

        // Update threshold based on window max/min
        val maxInWindow = signalWindow.maxOrNull() ?: value
        val minInWindow = signalWindow.minOrNull() ?: value
        threshold = minInWindow + (maxInWindow - minInWindow) * 0.6f

        var isPeak = false
        
        // Basic peak detection logic
        if (value > threshold && (timestamp - lastPeakTimestamp) > refractoryPeriodMs) {
            // Check if we are at a local maximum
            if (value > localMax) {
                localMax = value
            } else if (value < localMax * 0.95f) {
                // We passed the peak
                isPeak = true
                lastPeakTimestamp = timestamp
                localMax = -Float.MAX_VALUE
            }
        }
        
        return isPeak
    }
}
