package com.julylove.medical.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MotionSensorController: Controlador de sensores inerciales para detección de movimiento
 * Integra acelerómetro, giroscopio y vector de rotación si están disponibles
 * Proporciona métricas de movimiento para filtrado de artefactos en PPG
 */
class MotionSensorController(private val context: Context) : SensorEventListener {
    
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // Sensores disponibles
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null
    
    // Estados de sensores
    private var isAccelerometerActive = false
    private var isGyroscopeActive = false
    private var isRotationVectorActive = false
    
    // Buffers de datos
    private val accelerometerBuffer = mutableListOf<MotionSample>()
    private val gyroscopeBuffer = mutableListOf<MotionSample>()
    private val rotationBuffer = mutableListOf<MotionSample>()
    
    // Configuración
    private val maxBufferSize = 100
    private val updateIntervalMs = 50L  // 20 Hz actualización
    private val handler = Handler(Looper.getMainLooper())
    
    // Métricas de movimiento
    private var currentMotionIntensity = 0f
    private var currentAccelerationMagnitude = 0f
    private var currentRotationRate = 0f
    private var motionScore = 0f
    
    // Callback para notificación de movimiento
    interface MotionListener {
        fun onMotionUpdate(motionData: MotionData)
    }
    
    var listener: MotionListener? = null
    
    data class MotionSample(
        val timestampNs: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float
    )
    
    data class MotionData(
        val timestampNs: Long,
        val accelerationMagnitude: Float,
        val rotationRate: Float,
        val motionIntensity: Float,
        val motionScore: Float,
        val isMoving: Boolean,
        val accelerationSample: MotionSample?,
        val gyroscopeSample: MotionSample?,
        val rotationSample: MotionSample?
    )
    
    init {
        initializeSensors()
    }
    
    private fun initializeSensors() {
        // Inicializar acelerómetro
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // Inicializar giroscopio
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        // Inicializar vector de rotación
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    
    /**
     * Inicia la captura de datos de sensores
     */
    fun start() {
        startAccelerometer()
        startGyroscope()
        startRotationVector()
        
        // Iniciar actualizaciones periódicas
        startPeriodicUpdates()
    }
    
    /**
     * Detiene la captura de datos de sensores
     */
    fun stop() {
        stopAccelerometer()
        stopGyroscope()
        stopRotationVector()
        
        // Detener actualizaciones periódicas
        stopPeriodicUpdates()
        
        // Limpiar buffers
        clearBuffers()
    }
    
    private fun startAccelerometer() {
        accelerometer?.let { sensor ->
            val success = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isAccelerometerActive = success
        }
    }
    
    private fun startGyroscope() {
        gyroscope?.let { sensor ->
            val success = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isGyroscopeActive = success
        }
    }
    
    private fun startRotationVector() {
        rotationVector?.let { sensor ->
            val success = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isRotationVectorActive = success
        }
    }
    
    private fun stopAccelerometer() {
        if (isAccelerometerActive) {
            sensorManager.unregisterListener(this, accelerometer)
            isAccelerometerActive = false
        }
    }
    
    private fun stopGyroscope() {
        if (isGyroscopeActive) {
            sensorManager.unregisterListener(this, gyroscope)
            isGyroscopeActive = false
        }
    }
    
    private fun stopRotationVector() {
        if (isRotationVectorActive) {
            sensorManager.unregisterListener(this, rotationVector)
            isRotationVectorActive = false
        }
    }
    
    // SensorEventListener implementation
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        val timestamp = event.timestamp
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        
        val sample = MotionSample(timestamp, x, y, z, magnitude)
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                addToBuffer(accelerometerBuffer, sample)
                currentAccelerationMagnitude = magnitude
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                addToBuffer(gyroscopeBuffer, sample)
                currentRotationRate = magnitude
            }
            
            Sensor.TYPE_ROTATION_VECTOR -> {
                addToBuffer(rotationBuffer, sample)
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No implementado para esta aplicación
    }
    
    private fun addToBuffer(buffer: MutableList<MotionSample>, sample: MotionSample) {
        buffer.add(sample)
        while (buffer.size > maxBufferSize) {
            buffer.removeAt(0)
        }
    }
    
    private fun startPeriodicUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                updateMotionMetrics()
                handler.postDelayed(this, updateIntervalMs)
            }
        }
        handler.post(updateRunnable)
    }
    
    private fun stopPeriodicUpdates() {
        handler.removeCallbacksAndMessages(null)
    }
    
    private fun updateMotionMetrics() {
        val timestamp = System.nanoTime()
        
        // Calcular intensidad de movimiento reciente
        currentMotionIntensity = calculateMotionIntensity()
        
        // Calcular motion score (0-1)
        motionScore = calculateMotionScore()
        
        // Determinar si está en movimiento
        val isMoving = currentMotionIntensity > MOTION_THRESHOLD
        
        // Obtener muestras más recientes
        val latestAccel = accelerometerBuffer.lastOrNull()
        val latestGyro = gyroscopeBuffer.lastOrNull()
        val latestRot = rotationBuffer.lastOrNull()
        
        // Crear datos de movimiento
        val motionData = MotionData(
            timestampNs = timestamp,
            accelerationMagnitude = currentAccelerationMagnitude,
            rotationRate = currentRotationRate,
            motionIntensity = currentMotionIntensity,
            motionScore = motionScore,
            isMoving = isMoving,
            accelerationSample = latestAccel,
            gyroscopeSample = latestGyro,
            rotationSample = latestRot
        )
        
        // Notificar al listener
        listener?.onMotionUpdate(motionData)
    }
    
    private fun calculateMotionIntensity(): Float {
        if (accelerometerBuffer.size < 3) return 0f
        
        // Calcular variación de aceleración en ventana reciente
        val recentSamples = accelerometerBuffer.takeLast(20)
        if (recentSamples.size < 3) return 0f
        
        val meanMagnitude = recentSamples.map { it.magnitude }.average().toFloat()
        var variance = 0f
        
        for (sample in recentSamples) {
            val diff = sample.magnitude - meanMagnitude
            variance += diff * diff
        }
        
        val stdDev = sqrt(variance / recentSamples.size)
        
        // Combinar con rotación si está disponible
        val rotationContribution = if (gyroscopeBuffer.isNotEmpty()) {
            val recentGyro = gyroscopeBuffer.takeLast(20)
            val avgRotation = recentGyro.map { it.magnitude }.average().toFloat()
            avgRotation * 0.3f // Ponderación menor para rotación
        } else 0f
        
        return (stdDev + rotationContribution).coerceIn(0f, 20f)
    }
    
    private fun calculateMotionScore(): Float {
        // Score normalizado 0-1 basado en umbrales
        return when {
            currentMotionIntensity < MOTION_THRESHOLD -> 0f
            currentMotionIntensity < MOTION_MODERATE -> 0.3f
            currentMotionIntensity < MOTION_HIGH -> 0.6f
            currentMotionIntensity < MOTION_EXTREME -> 0.8f
            else -> 1f
        }
    }
    
    /**
     * Obtiene estadísticas de movimiento recientes
     */
    fun getMotionStats(): MotionStats {
        val recentAccel = accelerometerBuffer.takeLast(30)
        val recentGyro = gyroscopeBuffer.takeLast(30)
        
        return MotionStats(
            isAccelerometerAvailable = accelerometer != null,
            isGyroscopeAvailable = gyroscope != null,
            isRotationVectorAvailable = rotationVector != null,
            currentMotionIntensity = currentMotionIntensity,
            currentMotionScore = motionScore,
            isMoving = currentMotionIntensity > MOTION_THRESHOLD,
            accelerationBuffer = recentAccel.size,
            gyroscopeBuffer = recentGyro.size,
            averageAcceleration = if (recentAccel.isNotEmpty()) {
                recentAccel.map { it.magnitude }.average().toFloat()
            } else 0f,
            peakAcceleration = if (recentAccel.isNotEmpty()) {
                recentAccel.maxOfOrNull { it.magnitude } ?: 0f
            } else 0f
        )
    }
    
    /**
     * Detecta patrones de movimiento específicos
     */
    fun detectMotionPatterns(): MotionPatterns {
        if (accelerometerBuffer.size < 10) {
            return MotionPatterns()
        }
        
        val recent = accelerometerBuffer.takeLast(30)
        
        // Detectar vibración
        val vibrationScore = detectVibration(recent)
        
        // Detectar movimiento brusco
        val suddenMovementScore = detectSuddenMovement(recent)
        
        // Detectar tendencia de movimiento
        val movementTrend = detectMovementTrend(recent)
        
        return MotionPatterns(
            vibrationScore = vibrationScore,
            suddenMovementScore = suddenMovementScore,
            movementTrend = movementTrend,
            isStable = vibrationScore < 0.3f && suddenMovementScore < 0.3f
        )
    }
    
    private fun detectVibration(samples: List<MotionSample>): Float {
        if (samples.size < 5) return 0f
        
        // Calcular frecuencia de cambios de dirección
        var directionChanges = 0
        var lastDirection = 0
        
        for (i in 1 until samples.size) {
            val currentDirection = if (samples[i].magnitude > samples[i-1].magnitude) 1 else -1
            if (currentDirection != lastDirection && lastDirection != 0) {
                directionChanges++
            }
            lastDirection = currentDirection
        }
        
        val changeRate = directionChanges.toFloat() / samples.size
        return (changeRate * 10f).coerceIn(0f, 1f)
    }
    
    private fun detectSuddenMovement(samples: List<MotionSample>): Float {
        if (samples.size < 3) return 0f
        
        var maxDelta = 0f
        
        for (i in 1 until samples.size) {
            val delta = abs(samples[i].magnitude - samples[i-1].magnitude)
            maxDelta = maxOf(maxDelta, delta)
        }
        
        return (maxDelta / 10f).coerceIn(0f, 1f)
    }
    
    private fun detectMovementTrend(samples: List<MotionSample>): Float {
        if (samples.size < 5) return 0f
        
        val firstHalf = samples.take(samples.size / 2)
        val secondHalf = samples.drop(samples.size / 2)
        
        val firstMean = firstHalf.map { it.magnitude }.average()
        val secondMean = secondHalf.map { it.magnitude }.average()
        
        val trend = abs(secondMean - firstMean) / firstMean.coerceAtLeast(1.0)
        return (trend * 5.0).coerceIn(0.0, 1.0).toFloat()
    }
    
    private fun clearBuffers() {
        accelerometerBuffer.clear()
        gyroscopeBuffer.clear()
        rotationBuffer.clear()
        currentMotionIntensity = 0f
        currentAccelerationMagnitude = 0f
        currentRotationRate = 0f
        motionScore = 0f
    }
    
    /**
     * Obtiene la intensidad de movimiento actual
     */
    fun getCurrentMotionIntensity(): Double {
        return currentAccelerationMagnitude.toDouble()
    }
    
    /**
     * Verifica disponibilidad de sensores
     */
    fun getSensorAvailability(): SensorAvailability {
        return SensorAvailability(
            hasAccelerometer = accelerometer != null,
            hasGyroscope = gyroscope != null,
            hasRotationVector = rotationVector != null,
            accelerometerActive = isAccelerometerActive,
            gyroscopeActive = isGyroscopeActive,
            rotationVectorActive = isRotationVectorActive
        )
    }
    
    companion object {
        // Umbrales de movimiento (ajustables según pruebas)
        private const val MOTION_THRESHOLD = 0.5f      // Umbral mínimo para considerar movimiento
        private const val MOTION_MODERATE = 2.0f       // Movimiento moderado
        private const val MOTION_HIGH = 5.0f           // Movimiento alto
        private const val MOTION_EXTREME = 10.0f       // Movimiento extremo
    }
    
    data class MotionStats(
        val isAccelerometerAvailable: Boolean,
        val isGyroscopeAvailable: Boolean,
        val isRotationVectorAvailable: Boolean,
        val currentMotionIntensity: Float,
        val currentMotionScore: Float,
        val isMoving: Boolean,
        val accelerationBuffer: Int,
        val gyroscopeBuffer: Int,
        val averageAcceleration: Float,
        val peakAcceleration: Float
    )
    
    data class MotionPatterns(
        val vibrationScore: Float = 0f,
        val suddenMovementScore: Float = 0f,
        val movementTrend: Float = 0f,
        val isStable: Boolean = true
    )
    
    data class SensorAvailability(
        val hasAccelerometer: Boolean,
        val hasGyroscope: Boolean,
        val hasRotationVector: Boolean,
        val accelerometerActive: Boolean,
        val gyroscopeActive: Boolean,
        val rotationVectorActive: Boolean
    )
}
