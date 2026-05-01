package com.julylove.medical.signal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Tests unitarios para SignalQualityIndex
 * Verifica cálculo de calidad de señal con datos controlados
 */
class SignalQualityIndexTest {
    
    @Test
    fun testHighQualitySignal() {
        // Señal de alta calidad - estable y sin movimiento
        val redHistory = generateStableSignal(mean = 100f, std = 2f, size = 20)
        val greenHistory = generateStableSignal(mean = 90f, std = 2f, size = 20)
        val blueHistory = generateStableSignal(mean = 80f, std = 2f, size = 20)
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertTrue("Señal estable debería tener SQI alto", sqi > 0.7f)
        assertTrue("SQI no debería exceder 1.0", sqi <= 1.0f)
    }
    
    @Test
    fun testLowQualitySignalHighNoise() {
        // Señal de baja calidad - alta variabilidad
        val redHistory = generateNoisySignal(mean = 100f, std = 20f, size = 20)
        val greenHistory = generateNoisySignal(mean = 90f, std = 20f, size = 20)
        val blueHistory = generateNoisySignal(mean = 80f, std = 20f, size = 20)
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertTrue("Señal ruidosa debería tener SQI bajo", sqi < 0.5f)
        assertTrue("SQI no debería ser negativo", sqi >= 0f)
    }
    
    @Test
    fun testMotionArtifactPenalty() {
        // Señal estable pero con movimiento
        val redHistory = generateStableSignal(mean = 100f, std = 2f, size = 20)
        val greenHistory = generateStableSignal(mean = 90f, std = 2f, size = 20)
        val blueHistory = generateStableSignal(mean = 80f, std = 2f, size = 20)
        val highMotionIntensity = 1.5f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = highMotionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertTrue("Alto movimiento debería penalizar SQI", sqi < 0.6f)
    }
    
    @Test
    fun testLowPerfusionPenalty() {
        // Señal estable pero con baja perfusión
        val redHistory = generateStableSignal(mean = 100f, std = 2f, size = 20)
        val greenHistory = generateStableSignal(mean = 90f, std = 2f, size = 20)
        val blueHistory = generateStableSignal(mean = 80f, std = 2f, size = 20)
        val motionIntensity = 0.1f
        val lowPerfusionRatio = 0.5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = lowPerfusionRatio
        )
        
        assertTrue("Baja perfusión debería penalizar SQI", sqi < 0.5f)
    }
    
    @Test
    fun testInsufficientData() {
        // Datos insuficientes
        val shortHistory = generateStableSignal(mean = 100f, std = 2f, size = 5)
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = shortHistory,
            greenHistory = shortHistory,
            blueHistory = shortHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertEquals("Datos insuficientes deberían dar SQI = 0", 0f, sqi)
    }
    
    @Test
    fun testEmptySignals() {
        // Señales vacías
        val emptyHistory = emptyList<Float>()
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = emptyHistory,
            greenHistory = emptyHistory,
            blueHistory = emptyHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertEquals("Señales vacías deberían dar SQI = 0", 0f, sqi)
    }
    
    @Test
    fun testConstantSignal() {
        // Señal constante (sin variabilidad)
        val constantHistory = FloatArray(20) { 100f }.toList()
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = constantHistory,
            greenHistory = constantHistory,
            blueHistory = constantHistory,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        // Señal constante debería tener alta estabilidad pero baja perfusión
        assertTrue("Señal constante debería tener SQI moderado", sqi > 0.3f)
        assertTrue("SQI no debería ser perfecto para señal constante", sqi < 0.9f)
    }
    
    @Test
    fun testExtremeMotion() {
        // Movimiento extremo
        val redHistory = generateStableSignal(mean = 100f, std = 2f, size = 20)
        val greenHistory = generateStableSignal(mean = 90f, std = 2f, size = 20)
        val blueHistory = generateStableSignal(mean = 80f, std = 2f, size = 20)
        val extremeMotionIntensity = 5f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = extremeMotionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        assertTrue("Movimiento extremo debería dar SQI muy bajo", sqi < 0.2f)
    }
    
    @Test
    fun testOptimalConditions() {
        // Condiciones óptimas
        val redHistory = generateStableSignal(mean = 120f, std = 1f, size = 30)
        val greenHistory = generateStableSignal(mean = 100f, std = 1f, size = 30)
        val blueHistory = generateStableSignal(mean = 80f, std = 1f, size = 30)
        val noMotionIntensity = 0f
        val optimalPerfusionRatio = 8f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = noMotionIntensity,
            perfusionRatio = optimalPerfusionRatio
        )
        
        assertTrue("Condiciones óptimas deberían dar SQI excelente", sqi > 0.85f)
        assertEquals("SQI máximo debería ser 1.0", 1.0f, sqi)
    }
    
    @Test
    fun testDifferentChannelStability() {
        // Canales con diferente estabilidad
        val stableRed = generateStableSignal(mean = 100f, std = 1f, size = 20)
        val noisyGreen = generateNoisySignal(mean = 90f, std = 15f, size = 20)
        val stableBlue = generateStableSignal(mean = 80f, std = 1f, size = 20)
        val motionIntensity = 0.1f
        val perfusionRatio = 5f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = stableRed,
            greenHistory = noisyGreen,
            blueHistory = stableBlue,
            motionIntensity = motionIntensity,
            perfusionRatio = perfusionRatio
        )
        
        // Un canal inestable debería afectar el SQI general
        assertTrue("Un canal inestable debería reducir SQI", sqi < 0.7f)
        assertTrue("SQI no debería ser extremadamente bajo", sqi > 0.3f)
    }
    
    @Test
    fun testBoundaryConditions() {
        // Condiciones de frontera
        val redHistory = generateStableSignal(mean = 100f, std = 2f, size = 8) // Mínimo tamaño
        val greenHistory = generateStableSignal(mean = 90f, std = 2f, size = 8)
        val blueHistory = generateStableSignal(mean = 80f, std = 2f, size = 8)
        val maxMotionIntensity = 2f
        val maxPerfusionRatio = 20f
        
        val sqi = SignalQualityIndex.fromOpticalStreams(
            redHistory = redHistory,
            greenHistory = greenHistory,
            blueHistory = blueHistory,
            motionIntensity = maxMotionIntensity,
            perfusionRatio = maxPerfusionRatio
        )
        
        assertTrue("SQI debería estar en rango válido", sqi in 0f..1f)
    }
    
    /**
     * Genera señal estable con baja variabilidad
     */
    private fun generateStableSignal(mean: Float, std: Float, size: Int): List<Float> {
        return (0 until size).map { i ->
            // Variación controlada usando seno para evitar aleatoriedad
            val variation = std * 0.5f * kotlin.math.sin(2 * Math.PI * i / 10).toFloat()
            (mean + variation).coerceAtLeast(0f)
        }
    }
    
    /**
     * Genera señal ruidosa con alta variabilidad
     */
    private fun generateNoisySignal(mean: Float, std: Float, size: Int): List<Float> {
        return (0 until size).map { i ->
            // Variación controlada usando múltiples frecuencias
            val variation1 = std * 0.7f * kotlin.math.sin(2 * Math.PI * i / 3).toFloat()
            val variation2 = std * 0.5f * kotlin.math.sin(2 * Math.PI * i / 7).toFloat()
            val variation3 = std * 0.3f * kotlin.math.sin(2 * Math.PI * i / 11).toFloat()
            (mean + variation1 + variation2 + variation3).coerceAtLeast(0f)
        }
    }
}
