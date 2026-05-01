package com.julylove.medical.sensors

/**
 * MotionSample: Estructura de datos para muestras de sensores inerciales
 * Contiene información temporal y espacial del movimiento
 */
data class MotionSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float,
    val sensorType: SensorType,
    val accuracy: Int = 0
) {
    /**
     * Calcula la distancia euclidiana a otra muestra
     */
    fun distanceTo(other: MotionSample): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Verifica si la muestra es válida (no contiene NaN o valores infinitos)
     */
    fun isValid(): Boolean {
        return !x.isNaN() && !y.isNaN() && !z.isNaN() && 
               !x.isInfinite() && !y.isInfinite() && !z.isInfinite() &&
               magnitude >= 0f
    }
    
    /**
     * Normaliza la magnitud respecto a un valor de referencia
     */
    fun normalizedMagnitude(reference: Float): Float {
        return if (reference > 0f) magnitude / reference else 0f
    }
}

/**
 * Tipos de sensores inerciales soportados
 */
enum class SensorType {
    ACCELEROMETER,
    GYROSCOPE,
    ROTATION_VECTOR,
    LINEAR_ACCELERATION,
    GRAVITY
}

/**
 * Estadísticas de movimiento para un conjunto de muestras
 */
data class MotionStatistics(
    val sampleCount: Int,
    val averageMagnitude: Float,
    val peakMagnitude: Float,
    val minMagnitude: Float,
    val standardDeviation: Float,
    val variance: Float,
    val rms: Float,
    val peakToPeak: Float
) {
    /**
     * Calcula el coeficiente de variación
     */
    fun coefficientOfVariation(): Float {
        return if (averageMagnitude > 0f) standardDeviation / averageMagnitude else 0f
    }
    
    /**
     * Determina si el movimiento es estable (baja variabilidad)
     */
    fun isStable(threshold: Float = 0.2f): Boolean {
        return coefficientOfVariation() < threshold
    }
}

/**
 * Ventana de tiempo para análisis de movimiento
 */
data class MotionWindow(
    val startTimeNs: Long,
    val endTimeNs: Long,
    val samples: List<MotionSample>,
    val windowType: WindowType = WindowType.SLIDING
) {
    /**
     * Duración de la ventana en milisegundos
     */
    val durationMs: Long get() = (endTimeNs - startTimeNs) / 1_000_000L
    
    /**
     * Filtra muestras por tipo de sensor
     */
    fun filterBySensorType(sensorType: SensorType): List<MotionSample> {
        return samples.filter { it.sensorType == sensorType }
    }
    
    /**
     * Calcula estadísticas para un tipo de sensor específico
     */
    fun calculateStatistics(sensorType: SensorType): MotionStatistics? {
        val filteredSamples = filterBySensorType(sensorType)
        if (filteredSamples.isEmpty()) return null
        
        val magnitudes = filteredSamples.map { it.magnitude }
        val avg = magnitudes.average().toFloat()
        val peak = magnitudes.maxOrNull() ?: 0f
        val min = magnitudes.minOrNull() ?: 0f
        
        var variance = 0f
        for (mag in magnitudes) {
            val diff = mag - avg
            variance += diff * diff
        }
        variance /= magnitudes.size
        
        val stdDev = kotlin.math.sqrt(variance)
        val rms = kotlin.math.sqrt(magnitudes.map { it * it }.average().toFloat())
        val peakToPeak = peak - min
        
        return MotionStatistics(
            sampleCount = filteredSamples.size,
            averageMagnitude = avg,
            peakMagnitude = peak,
            minMagnitude = min,
            standardDeviation = stdDev,
            variance = variance,
            rms = rms,
            peakToPeak = peakToPeak
        )
    }
}

/**
 * Tipos de ventanas de análisis
 */
enum class WindowType {
    SLIDING,    // Ventana deslizante
    FIXED,      // Ventana fija
    EXPONENTIAL // Ventana con ponderación exponencial
}

/**
 * Utilidades para procesamiento de muestras de movimiento
 */
object MotionSampleUtils {
    
    /**
     * Crea estadísticas a partir de una lista de muestras
     */
    fun createStatistics(samples: List<MotionSample>): MotionStatistics? {
        if (samples.isEmpty()) return null
        
        val magnitudes = samples.map { it.magnitude }
        val avg = magnitudes.average().toFloat()
        val peak = magnitudes.maxOrNull() ?: 0f
        val min = magnitudes.minOrNull() ?: 0f
        
        var variance = 0f
        for (mag in magnitudes) {
            val diff = mag - avg
            variance += diff * diff
        }
        variance /= magnitudes.size
        
        val stdDev = kotlin.math.sqrt(variance)
        val rms = kotlin.math.sqrt(magnitudes.map { it * it }.average().toFloat())
        val peakToPeak = peak - min
        
        return MotionStatistics(
            sampleCount = samples.size,
            averageMagnitude = avg,
            peakMagnitude = peak,
            minMagnitude = min,
            standardDeviation = stdDev,
            variance = variance,
            rms = rms,
            peakToPeak = peakToPeak
        )
    }
    
    /**
     * Filtra muestras inválidas
     */
    fun filterValidSamples(samples: List<MotionSample>): List<MotionSample> {
        return samples.filter { it.isValid() }
    }
    
    /**
     * Detecta outliers basados en desviación estándar
     */
    fun removeOutliers(samples: List<MotionSample>, threshold: Float = 2.0f): List<MotionSample> {
        if (samples.size < 3) return samples
        
        val stats = createStatistics(samples) ?: return samples
        val mean = stats.averageMagnitude
        val stdDev = stats.standardDeviation
        
        return samples.filter { sample ->
            val zScore = kotlin.math.abs(sample.magnitude - mean) / stdDev
            zScore <= threshold
        }
    }
    
    /**
     * Interpola muestras faltantes en una secuencia
     */
    fun interpolateMissing(
        samples: List<MotionSample>,
        targetIntervalNs: Long
    ): List<MotionSample> {
        if (samples.size < 2) return samples
        
        val interpolated = mutableListOf<MotionSample>()
        interpolated.add(samples.first())
        
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val gap = curr.timestampNs - prev.timestampNs
            
            if (gap > targetIntervalNs) {
                // Necesita interpolación
                val steps = (gap / targetIntervalNs).toInt()
                for (step in 1 until steps) {
                    val ratio = step.toFloat() / steps
                    val interpolatedSample = MotionSample(
                        timestampNs = prev.timestampNs + (step * targetIntervalNs),
                        x = prev.x + (curr.x - prev.x) * ratio,
                        y = prev.y + (curr.y - prev.y) * ratio,
                        z = prev.z + (curr.z - prev.z) * ratio,
                        magnitude = prev.magnitude + (curr.magnitude - prev.magnitude) * ratio,
                        sensorType = prev.sensorType,
                        accuracy = prev.accuracy
                    )
                    interpolated.add(interpolatedSample)
                }
            }
            interpolated.add(curr)
        }
        
        return interpolated
    }
    
    /**
     * Calcula energía de la señal en ventana
     */
    fun calculateEnergy(samples: List<MotionSample>): Float {
        return samples.map { it.magnitude * it.magnitude }.sum()
    }
    
    /**
     * Detecta cambios abruptos en la señal
     */
    fun detectAbruptChanges(samples: List<MotionSample>, threshold: Float = 3.0f): List<Int> {
        if (samples.size < 2) return emptyList()
        
        val changes = mutableListOf<Int>()
        val stats = createStatistics(samples) ?: return changes
        val mean = stats.averageMagnitude
        val stdDev = stats.standardDeviation
        
        for (i in 1 until samples.size) {
            val diff = kotlin.math.abs(samples[i].magnitude - samples[i - 1].magnitude)
            if (diff > threshold * stdDev) {
                changes.add(i)
            }
        }
        
        return changes
    }
}
