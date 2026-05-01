package com.julylove.medical.signal

/**
 * PpgPeakDetector: Real-time peak detection with physiological constraints.
 * Detects systolic peaks in the filtered PPG signal.
 */
class PpgPeakDetector(private val sampleRate: Float) {
    private var lastPeakTimestampNs: Long = 0
    private val minRefractoryNs = 350_000_000L // ~170 BPM max
    
    private val windowSize = (sampleRate * 1.5).toInt()
    private val signalWindow = mutableListOf<Float>()
    
    private var lastValue = 0f
    private var isRising = false

    data class BeatEvent(
        val timestampNs: Long,
        val rrMs: Double?,
        val confidence: Double,
        val amplitude: Double
    )

    fun process(value: Float, timestampNs: Long): BeatEvent? {
        signalWindow.add(value)
        if (signalWindow.size > windowSize) signalWindow.removeAt(0)
        if (signalWindow.size < 10) return null

        val maxInWindow = signalWindow.maxOrNull() ?: 1f
        val minInWindow = signalWindow.minOrNull() ?: 0f
        val range = maxInWindow - minInWindow
        
        // Threshold adaptivo: 70% del rango actual
        val threshold = minInWindow + range * 0.7f

        val slope = value - lastValue
        val wasRising = isRising
        isRising = slope > 0
        
        var event: BeatEvent? = null

        // Systolic peak detection (transition from rising to falling above threshold)
        if (wasRising && !isRising && value > threshold && range > 0.1f) {
            val timeSinceLast = timestampNs - lastPeakTimestampNs
            
            if (timeSinceLast > minRefractoryNs) {
                val rrMs = if (lastPeakTimestampNs > 0) timeSinceLast / 1_000_000.0 else null
                event = BeatEvent(
                    timestampNs = timestampNs,
                    rrMs = rrMs,
                    confidence = (value / maxInWindow).toDouble().coerceIn(0.0, 1.0),
                    amplitude = range.toDouble()
                )
                lastPeakTimestampNs = timestampNs
            }
        }

        lastValue = value
        return event
    }
    
    fun reset() {
        signalWindow.clear()
        lastPeakTimestampNs = 0
    }
}
