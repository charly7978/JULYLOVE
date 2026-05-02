package com.julylove.medical.signal

import java.util.ArrayDeque

/**
 * PpgSignalBuffer - Orquestador de ventanas de tiempo.
 * Mantiene la historia de señales ópticas para permitir filtrado y análisis espectral.
 */
class PpgSignalBuffer(private val capacity: Int = 600) { // ~10s @ 60fps
    
    private val timeBuffer = ArrayDeque<Long>(capacity)
    private val rawRedBuffer = ArrayDeque<Float>(capacity)
    private val rawGreenBuffer = ArrayDeque<Float>(capacity)
    private val rawBlueBuffer = ArrayDeque<Float>(capacity)
    
    fun push(frame: PpgFrame) {
        if (timeBuffer.size >= capacity) {
            timeBuffer.removeFirst()
            rawRedBuffer.removeFirst()
            rawGreenBuffer.removeFirst()
            rawBlueBuffer.removeFirst()
        }
        timeBuffer.addLast(frame.timestampNs)
        rawRedBuffer.addLast(frame.avgRed)
        rawGreenBuffer.addLast(frame.avgGreen)
        rawBlueBuffer.addLast(frame.avgBlue)
    }
    
    fun getGreenSignal(): List<Float> = rawGreenBuffer.toList()
    fun getRedSignal(): List<Float> = rawRedBuffer.toList()
    fun getTimeStamps(): List<Long> = timeBuffer.toList()
    
    fun getWindowSize(): Int = timeBuffer.size
    
    fun isFull(): Boolean = timeBuffer.size >= capacity
    
    fun clear() {
        timeBuffer.clear()
        rawRedBuffer.clear()
        rawGreenBuffer.clear()
        rawBlueBuffer.clear()
    }
}
