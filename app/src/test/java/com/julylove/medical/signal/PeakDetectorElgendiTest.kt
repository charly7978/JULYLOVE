package com.julylove.medical.signal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Tests unitarios para PeakDetectorElgendi
 * Verifica detección de picos con datos sintéticos controlados (no aleatorios)
 */
class PeakDetectorElgendiTest {
    
    private lateinit var detector: PeakDetectorElgendi
    private val sampleRate = 60f // 60 Hz
    
    @Before
    fun setUp() {
        detector = PeakDetectorElgendi(sampleRate)
    }
    
    @Test
    fun testInitialization() {
        val stats = detector.getStats()
        assertEquals(sampleRate, stats.sampleRate, 0.01f)
        assertTrue(stats.windowShort > 0)
        assertTrue(stats.windowLong > 0)
        assertTrue(stats.windowLong > stats.windowShort)
    }
    
    @Test
    fun testSampleRateUpdate() {
        detector.updateSampleRate(30f)
        val stats = detector.getStats()
        assertEquals(30f, stats.sampleRate, 0.01f)
    }
    
    @Test
    fun testBasicPeakDetection() {
        // Crear señal PPG sintética con picos conocidos
        val syntheticSignal = generateSyntheticPPG()
        val detectedPeaks = mutableListOf<PeakDetectorElgendi.ElgendiPeak>()
        
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60 // 60 Hz en nanosegundos
        
        for (value in syntheticSignal) {
            val peak = detector.process(value, timestamp)
            peak?.let { detectedPeaks.add(it) }
            timestamp += timeStepNs
        }
        
        // Verificar que se detectaron picos aproximadamente en las posiciones esperadas
        assertTrue("Se deberían detectar picos", detectedPeaks.isNotEmpty())
        assertTrue("Demasiados picos detectados", detectedPeaks.size <= 8)
        
        // Verificar que los picos detectados tienen RR intervals razonables
        detectedPeaks.forEach { peak ->
            if (peak.rrMs != null) {
                assertTrue("RR interval inválido: ${peak.rrMs}", peak.rrMs in 300.0..2000.0)
                assertTrue("Confianza muy baja: ${peak.confidence}", peak.confidence > 0.1)
            }
        }
    }
    
    @Test
    fun testNoPeakInFlatSignal() {
        // Señal plana no debería generar picos
        val flatSignal = FloatArray(300) { 1.0f }
        var detectedPeaks = 0
        
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60
        
        for (value in flatSignal) {
            val peak = detector.process(value, timestamp)
            if (peak != null) detectedPeaks++
            timestamp += timeStepNs
        }
        
        assertEquals("Señal plana no debería generar picos", 0, detectedPeaks)
    }
    
    @Test
    fun testRefractoryPeriod() {
        // Dos picos muy cercanos deberían ser rechazados por período refractario
        val signalWithClosePeaks = generateSignalWithClosePeaks()
        var detectedPeaks = 0
        
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60
        
        for (value in signalWithClosePeaks) {
            val peak = detector.process(value, timestamp)
            if (peak != null) detectedPeaks++
            timestamp += timeStepNs
        }
        
        // Debería detectar solo un pico debido al período refractario
        assertEquals("Período refractario debería rechazar picos cercanos", 1, detectedPeaks)
    }
    
    @Test
    fun testThresholdAdaptation() {
        // Probar diferentes niveles de señal
        val lowAmplitudeSignal = generateSyntheticPPG(amplitude = 0.5f)
        val highAmplitudeSignal = generateSyntheticPPG(amplitude = 2.0f)
        
        var lowPeaks = 0
        var highPeaks = 0
        
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60
        
        // Probar señal baja
        detector.reset()
        for (value in lowAmplitudeSignal) {
            val peak = detector.process(value, timestamp)
            if (peak != null) lowPeaks++
            timestamp += timeStepNs
        }
        
        // Probar señal alta
        detector.reset()
        timestamp = 0L
        for (value in highAmplitudeSignal) {
            val peak = detector.process(value, timestamp)
            if (peak != null) highPeaks++
            timestamp += timeStepNs
        }
        
        // Ambas deberían detectar picos, pero con diferentes confianzas
        assertTrue("Señal baja debería detectar picos", lowPeaks > 0)
        assertTrue("Señal alta debería detectar picos", highPeaks > 0)
    }
    
    @Test
    fun testReset() {
        // Probar que reset limpia el estado
        val signal = generateSyntheticPPG()
        
        // Procesar señal para generar estado
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60
        for (value in signal.take(100)) {
            detector.process(value, timestamp)
            timestamp += timeStepNs
        }
        
        // Resetear
        detector.reset()
        
        // Procesar señal plana - no debería detectar picos después del reset
        val flatSignal = FloatArray(50) { 1.0f }
        var detectedPeaks = 0
        
        for (value in flatSignal) {
            val peak = detector.process(value, timestamp)
            if (peak != null) detectedPeaks++
            timestamp += timeStepNs
        }
        
        assertEquals("Reset debería limpiar estado", 0, detectedPeaks)
    }
    
    @Test
    fun testConfigurationParameters() {
        // Probar configuración de parámetros
        detector.setThresholdFactor(0.5f)
        detector.setMinProminence(0.2f)
        
        val stats = detector.getStats()
        assertEquals(0.5f, stats.thresholdFactor, 0.01f)
        assertEquals(0.2f, stats.minProminence, 0.01f)
    }
    
    @Test
    fun testMorphologyValidation() {
        // Generar señal con buena morfología PPG
        val goodMorphologySignal = generateRealisticPPGMorphology()
        var detectedPeaks = 0
        var goodMorphologyCount = 0
        
        var timestamp = 0L
        val timeStepNs = 1_000_000_000L / 60
        
        for (value in goodMorphologySignal) {
            val peak = detector.process(value, timestamp)
            peak?.let {
                detectedPeaks++
                if (it.morphologyScore > 0.5) goodMorphologyCount++
            }
            timestamp += timeStepNs
        }
        
        assertTrue("Debería detectar picos con buena morfología", detectedPeaks > 0)
        if (detectedPeaks > 0) {
            val morphologyRatio = goodMorphologyCount.toFloat() / detectedPeaks
            assertTrue("Algunos picos deberían tener buena morfología", morphologyRatio > 0.3f)
        }
    }
    
    /**
     * Genera señal PPG sintética con picos conocidos
     */
    private fun generateSyntheticPPG(amplitude: Float = 1.0f): FloatArray {
        val signal = FloatArray(300) // 5 segundos a 60 Hz
        val heartRateBpm = 75.0
        val beatInterval = 60.0 / heartRateBpm * 60 // samples por beat
        
        for (i in signal.indices) {
            val beatPhase = (i % beatInterval) / beatInterval * 2 * Math.PI
            
            // Forma de onda PPG simplificada (sistólico + diastólico)
            val systolic = Math.exp(-Math.pow((beatPhase - 0.2) / 0.15, 2.0))
            val diastolic = 0.3 * Math.exp(-Math.pow((beatPhase - 0.6) / 0.2, 2.0))
            
            signal[i] = (amplitude * (systolic + diastolic) + 0.1).toFloat()
        }
        
        return signal
    }
    
    /**
     * Genera señal con picos muy cercanos para probar período refractario
     */
    private fun generateSignalWithClosePeaks(): FloatArray {
        val signal = FloatArray(120) // 2 segundos
        
        // Pico principal
        for (i in 20 until 30) {
            signal[i] = 2.0f
        }
        
        // Pico muy cercano (debería ser rechazado)
        for (i in 32 until 35) {
            signal[i] = 1.8f
        }
        
        // Otro pico después del período refractario
        for (i in 80 until 90) {
            signal[i] = 2.0f
        }
        
        return signal
    }
    
    /**
     * Genera señal PPG con morfología realista
     */
    private fun generateRealisticPPGMorphology(): FloatArray {
        val signal = FloatArray(360) // 6 segundos a 60 Hz
        val heartRateBpm = 80.0
        val beatInterval = 60.0 / heartRateBpm * 60 // samples por beat
        
        for (i in signal.indices) {
            val beatPhase = (i % beatInterval) / beatInterval * 2 * Math.PI
            
            // Morfología PPG más realista con dicrotismo
            val systolicPeak = Math.exp(-Math.pow((beatPhase - 0.15) / 0.12, 2.0))
            val dicroticNotch = 0.4 * Math.exp(-Math.pow((beatPhase - 0.45) / 0.08, 2.0))
            val diastolicPeak = 0.3 * Math.exp(-Math.pow((beatPhase - 0.55) / 0.15, 2.0))
            
            signal[i] = (systolicPeak + dicroticNotch + diastolicPeak + 0.05).toFloat()
        }
        
        return signal
    }
}
