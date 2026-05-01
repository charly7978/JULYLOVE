package com.julylove.medical.signal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Tests unitarios para HeartRateFusion
 * Verifica fusión de detectores con datos controlados
 */
class HeartRateFusionTest {
    
    private lateinit var fusion: HeartRateFusion
    
    @Before
    fun setUp() {
        fusion = HeartRateFusion()
    }
    
    @Test
    fun testConsensusDetection() {
        // Ambos detectores coinciden temporalmente
        val timestamp = System.nanoTime()
        val elgendiPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = 800.0,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        val derivativePeak = PeakDetectorDerivative.DerivativePeak(
            timestampNs = timestamp + 10_000_000L, // 10ms diferencia
            rrMs = 800.0,
            confidence = 0.7,
            amplitude = 1.4,
            prominence = 0.25,
            peakWidth = 8,
            slopeRatio = 1.2,
            concavity = 0.5,
            reason = "Pico derivativo bueno"
        )
        
        val fusedBeat = fusion.fuseDetections(elgendiPeak, derivativePeak, 0.8f, timestamp)
        
        assertNotNull("Debería fusionar detecciones con consenso", fusedBeat)
        assertEquals("Debería ser detección por consenso", 
            HeartRateFusion.DetectionMethod.CONSENSUS, fusedBeat?.detectionMethod)
        assertTrue("Confianza combinada debería ser alta", fusedBeat?.confidence!! > 0.6)
    }
    
    @Test
    fun testSingleDetectionWithHighSQI() {
        // Solo un detector con alta confianza y SQI alto
        val timestamp = System.nanoTime()
        val elgendiPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = 850.0,
            confidence = 0.85,
            amplitude = 1.6,
            prominence = 0.35,
            morphologyScore = 0.8,
            reason = "Pico sistólico excelente"
        )
        
        val fusedBeat = fusion.fuseDetections(elgendiPeak, null, 0.7f, timestamp)
        
        assertNotNull("Debería aceptar detección individual con alta confianza", fusedBeat)
        assertEquals("Debería ser detección Elgendi de alta confianza", 
            HeartRateFusion.DetectionMethod.ELGENDI_HIGH_CONF, fusedBeat?.detectionMethod)
    }
    
    @Test
    fun testSingleDetectionRejectedLowSQI() {
        // Solo un detector con baja SQI
        val timestamp = System.nanoTime()
        val elgendiPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = 850.0,
            confidence = 0.8,
            amplitude = 1.6,
            prominence = 0.35,
            morphologyScore = 0.8,
            reason = "Pico sistólico excelente"
        )
        
        val fusedBeat = fusion.fuseDetections(elgendiPeak, null, 0.4f, timestamp)
        
        assertNull("Debería rechazar detección individual con SQI bajo", fusedBeat)
    }
    
    @Test
    fun testTemporalConsistencyValidation() {
        // Probar validación de consistencia temporal
        val timestamp = System.nanoTime()
        
        // Primer latido (establece RR base)
        val firstPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = null, // Primer latido sin RR
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        val firstFused = fusion.fuseDetections(firstPeak, null, 0.8f, timestamp)
        assertNotNull("Primer latido debería ser aceptado", firstFused)
        
        // Segundo latido consistente
        val secondTimestamp = timestamp + 800_000_000L // 800ms RR
        val secondPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = secondTimestamp,
            rrMs = 800.0,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        val secondFused = fusion.fuseDetections(secondPeak, null, 0.8f, secondTimestamp)
        assertNotNull("Segundo latido consistente debería ser aceptado", secondFused)
        
        // Tercer latido inconsistente (demasiado corto)
        val thirdTimestamp = secondTimestamp + 300_000_000L // 300ms RR (demasiado corto)
        val thirdPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = thirdTimestamp,
            rrMs = 300.0,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        val thirdFused = fusion.fuseDetections(thirdPeak, null, 0.8f, thirdTimestamp)
        assertNull("Latido inconsistente debería ser rechazado", thirdFused)
    }
    
    @Test
    fun testNonOverlappingDetections() {
        // Detecciones que no se superponen temporalmente
        val timestamp = System.nanoTime()
        val elgendiPeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = 800.0,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        val derivativePeak = PeakDetectorDerivative.DerivativePeak(
            timestampNs = timestamp + 100_000_000L, // 100ms diferencia (fuera de ventana)
            rrMs = 800.0,
            confidence = 0.7,
            amplitude = 1.4,
            prominence = 0.25,
            peakWidth = 8,
            slopeRatio = 1.2,
            concavity = 0.5,
            reason = "Pico derivativo bueno"
        )
        
        val fusedBeat = fusion.fuseDetections(elgendiPeak, derivativePeak, 0.8f, timestamp)
        
        assertNull("Debería rechazar detecciones no superpuestas", fusedBeat)
    }
    
    @Test
    fun testAverageBpmCalculation() {
        // Probar cálculo de BPM promedio
        val timestamp = System.nanoTime()
        
        // Agregar varios latidos con RR consistentes
        val rrIntervals = listOf(800.0, 820.0, 790.0, 810.0) // ~75 BPM
        
        for ((i, rr) in rrIntervals.withIndex()) {
            val beatTimestamp = timestamp + (i * rr * 1_000_000).toLong()
            val peak = PeakDetectorElgendi.ElgendiPeak(
                timestampNs = beatTimestamp,
                rrMs = rr,
                confidence = 0.8,
                amplitude = 1.5,
                prominence = 0.3,
                morphologyScore = 0.7,
                reason = "Pico sistólico excelente"
            )
            
            fusion.fuseDetections(peak, null, 0.8f, beatTimestamp)
        }
        
        val avgBpm = fusion.getAverageBpm()
        assertNotNull("Debería calcular BPM promedio", avgBpm)
        assertTrue("BPM debería estar en rango razonable", avgBpm!! in 70.0..80.0)
    }
    
    @Test
    fun testRRVariability() {
        // Probar cálculo de variabilidad RR
        val timestamp = System.nanoTime()
        
        // Agregar latidos con variabilidad controlada
        val rrIntervals = listOf(800.0, 850.0, 750.0, 900.0, 700.0) // Mayor variabilidad
        
        for ((i, rr) in rrIntervals.withIndex()) {
            val beatTimestamp = timestamp + (i * rr * 1_000_000).toLong()
            val peak = PeakDetectorElgendi.ElgendiPeak(
                timestampNs = beatTimestamp,
                rrMs = rr,
                confidence = 0.8,
                amplitude = 1.5,
                prominence = 0.3,
                morphologyScore = 0.7,
                reason = "Pico sistólico excelente"
            )
            
            fusion.fuseDetections(peak, null, 0.8f, beatTimestamp)
        }
        
        val variability = fusion.getRRVariability()
        assertTrue("Debería calcular variabilidad", variability >= 0.0)
        assertTrue("Variabilidad debería ser razonable", variability < 50.0) // < 50%
    }
    
    @Test
    fun testSuspiciousPauseDetection() {
        // Probar detección de pausas sospechosas
        val timestamp = System.nanoTime()
        
        // Latidos normales
        val normalRR = 800.0
        for (i in 0 until 3) {
            val beatTimestamp = timestamp + (i * normalRR * 1_000_000).toLong()
            val peak = PeakDetectorElgendi.ElgendiPeak(
                timestampNs = beatTimestamp,
                rrMs = normalRR,
                confidence = 0.8,
                amplitude = 1.5,
                prominence = 0.3,
                morphologyScore = 0.7,
                reason = "Pico sistólico excelente"
            )
            
            fusion.fuseDetections(peak, null, 0.8f, beatTimestamp)
        }
        
        // Pausa larga
        val pauseTimestamp = timestamp + (3 * normalRR * 1_500_000).toLong() // 50% más largo
        val pausePeak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = pauseTimestamp,
            rrMs = normalRR * 1.5,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        fusion.fuseDetections(pausePeak, null, 0.8f, pauseTimestamp)
        
        assertTrue("Debería detectar pausa sospechosa", fusion.detectSuspiciousPause())
    }
    
    @Test
    fun testFusionStatistics() {
        // Probar estadísticas de fusión
        val timestamp = System.nanoTime()
        
        // Agregar varias detecciones
        for (i in 0 until 5) {
            val beatTimestamp = timestamp + (i * 800_000_000L)
            val elgendiPeak = PeakDetectorElgendi.ElgendiPeak(
                timestampNs = beatTimestamp,
                rrMs = 800.0,
                confidence = 0.8,
                amplitude = 1.5,
                prominence = 0.3,
                morphologyScore = 0.7,
                reason = "Pico sistólico excelente"
            )
            
            fusion.fuseDetections(elgendiPeak, null, 0.8f, beatTimestamp)
        }
        
        val stats = fusion.getFusionStats()
        assertTrue("Debería tener detecciones Elgendi", stats.totalElgendiDetections > 0)
        assertTrue("Debería tener detecciones fusionadas", stats.totalFusedDetections > 0)
        assertTrue("Tasa de fusión debería ser razonable", stats.fusionRate >= 0.0)
    }
    
    @Test
    fun testReset() {
        // Agregar datos y resetear
        val timestamp = System.nanoTime()
        val peak = PeakDetectorElgendi.ElgendiPeak(
            timestampNs = timestamp,
            rrMs = 800.0,
            confidence = 0.8,
            amplitude = 1.5,
            prominence = 0.3,
            morphologyScore = 0.7,
            reason = "Pico sistólico excelente"
        )
        
        fusion.fuseDetections(peak, null, 0.8f, timestamp)
        
        // Verificar que hay datos
        val statsBefore = fusion.getFusionStats()
        assertTrue("Debería tener detecciones antes de reset", statsBefore.totalElgendiDetections > 0)
        
        // Resetear
        fusion.reset()
        
        // Verificar que se limpió
        val statsAfter = fusion.getFusionStats()
        assertEquals("Reset debería limpiar detecciones", 0, statsAfter.totalElgendiDetections)
        assertEquals("Reset debería limpiar fusiones", 0, statsAfter.totalFusedDetections)
        assertEquals("Reset debería limpiar historial RR", 0, statsAfter.currentRRHistorySize)
    }
}
