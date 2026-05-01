package com.julylove.medical.signal

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * DeviceCalibrationManager: Gestor de perfiles de calibración SpO₂ por dispositivo
 * Almacena, recupera y valida perfiles de calibración para diferentes dispositivos
 */
class DeviceCalibrationManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("spo2_calibration", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val CALIBRATION_PROFILES_KEY = "calibration_profiles"
        private const val ACTIVE_PROFILE_KEY = "active_profile"
        private const val MIN_CALIBRATION_POINTS = 3
        private const val MAX_CALIBRATION_POINTS = 8
        private const val CALIBRATION_VALIDITY_DAYS = 30
    }
    
    /**
     * Guarda un nuevo perfil de calibración
     */
    fun saveCalibrationProfile(profile: DeviceCalibrationProfile): Boolean {
        if (!validateProfile(profile)) {
            return false
        }
        
        val profiles = getAllCalibrationProfiles().toMutableList()
        
        // Reemplazar perfil existente para mismo dispositivo/cámara
        val existingIndex = profiles.indexOfFirst { 
            it.deviceId == profile.deviceId && it.cameraId == profile.cameraId 
        }
        
        if (existingIndex >= 0) {
            profiles[existingIndex] = profile
        } else {
            profiles.add(profile)
        }
        
        // Guardar en SharedPreferences
        val profilesJson = gson.toJson(profiles)
        sharedPreferences.edit()
            .putString(CALIBRATION_PROFILES_KEY, profilesJson)
            .apply()
        
        return true
    }
    
    /**
     * Obtiene perfil de calibración activo para el dispositivo actual
     */
    fun getActiveCalibrationProfile(): DeviceCalibrationProfile? {
        val deviceId = getCurrentDeviceId()
        val cameraId = "back_main" // En implementación real, obtener cámara activa
        
        return getCalibrationProfile(deviceId, cameraId)
    }
    
    /**
     * Obtiene perfil específico para dispositivo y cámara
     */
    fun getCalibrationProfile(deviceId: String, cameraId: String): DeviceCalibrationProfile? {
        val profiles = getAllCalibrationProfiles()
        return profiles.find { 
            it.deviceId == deviceId && it.cameraId == cameraId 
        }
    }
    
    /**
     * Obtiene todos los perfiles de calibración
     */
    fun getAllCalibrationProfiles(): List<DeviceCalibrationProfile> {
        val profilesJson = sharedPreferences.getString(CALIBRATION_PROFILES_KEY, null)
        return if (profilesJson != null) {
            val type = object : TypeToken<List<DeviceCalibrationProfile>>() {}.type
            gson.fromJson(profilesJson, type) ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    /**
     * Elimina un perfil de calibración
     */
    fun deleteCalibrationProfile(deviceId: String, cameraId: String): Boolean {
        val profiles = getAllCalibrationProfiles().toMutableList()
        val removed = profiles.removeAll { 
            it.deviceId == deviceId && it.cameraId == cameraId 
        }
        
        if (removed) {
            val profilesJson = gson.toJson(profiles)
            sharedPreferences.edit()
                .putString(CALIBRATION_PROFILES_KEY, profilesJson)
                .apply()
        }
        
        return removed
    }
    
    /**
     * Valida si un perfil de calibración es válido
     */
    fun validateProfile(profile: DeviceCalibrationProfile): Boolean {
        return when {
            profile.deviceId.isEmpty() -> false
            profile.cameraId.isEmpty() -> false
            profile.pointCount < MIN_CALIBRATION_POINTS -> false
            profile.pointCount > MAX_CALIBRATION_POINTS -> false
            profile.coefficientA.isNaN() || profile.coefficientA.isInfinite() -> false
            profile.coefficientB.isNaN() || profile.coefficientB.isInfinite() -> false
            profile.validRange.first < 70 || profile.validRange.second > 100 -> false
            profile.validRange.first >= profile.validRange.second -> false
            !isProfileValid(profile) -> false // Verificar vigencia
            else -> true
        }
    }
    
    /**
     * Verifica si un perfil está vigente (no ha expirado)
     */
    private fun isProfileValid(profile: DeviceCalibrationProfile): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val creationDate = dateFormat.parse(profile.createdDate)
            val calendar = Calendar.getInstance()
            calendar.time = creationDate ?: return false
            calendar.add(Calendar.DAY_OF_MONTH, CALIBRATION_VALIDITY_DAYS)
            
            Calendar.getInstance().time.before(calendar.time)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Genera un perfil de calibración a partir de puntos de calibración
     */
    fun generateProfileFromPoints(
        deviceId: String,
        cameraId: String,
        calibrationPoints: List<CalibrationPoint>
    ): DeviceCalibrationProfile? {
        if (calibrationPoints.size < MIN_CALIBRATION_POINTS) {
            return null
        }
        
        // Validar puntos
        val validPoints = calibrationPoints.filter { 
            it.referenceSpo2 in 70..100 && 
            it.ratioOfRatios > 0 && 
            it.signalQuality > 0.5f
        }
        
        if (validPoints.size < MIN_CALIBRATION_POINTS) {
            return null
        }
        
        // Realizar regresión lineal: SpO2 = A - B * RoR
        val (coeffA, coeffB) = performLinearRegression(validPoints)
        
        // Determinar rango válido
        val minSpo2 = validPoints.minOfOrNull { it.referenceSpo2 } ?: 90
        val maxSpo2 = validPoints.maxOfOrNull { it.referenceSpo2 } ?: 100
        
        return DeviceCalibrationProfile(
            deviceId = deviceId,
            cameraId = cameraId,
            coefficientA = coeffA,
            coefficientB = coeffB,
            pointCount = validPoints.size,
            createdDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date()),
            validRange = Pair(minSpo2, maxSpo2),
            calibrationPoints = validPoints,
            averageError = calculateAverageError(validPoints, coeffA, coeffB),
            correlationCoefficient = calculateCorrelation(validPoints, coeffA, coeffB)
        )
    }
    
    /**
     * Realiza regresión lineal simple
     */
    private fun performLinearRegression(points: List<CalibrationPoint>): Pair<Double, Double> {
        val n = points.size.toDouble()
        val sumX = points.sumOf { it.ratioOfRatios }
        val sumY = points.sumOf { it.referenceSpo2.toDouble() }
        val sumXY = points.sumOf { it.ratioOfRatios * it.referenceSpo2.toDouble() }
        val sumX2 = points.sumOf { it.ratioOfRatios * it.ratioOfRatios }
        
        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n
        
        return Pair(intercept, -slope) // SpO2 = A - B * RoR
    }
    
    /**
     * Calcula error promedio del perfil
     */
    private fun calculateAverageError(
        points: List<CalibrationPoint>,
        coeffA: Double,
        coeffB: Double
    ): Double {
        val errors = points.map { point ->
            val estimatedSpo2 = coeffA - coeffB * point.ratioOfRatios
            kotlin.math.abs(estimatedSpo2 - point.referenceSpo2)
        }
        return errors.average()
    }
    
    /**
     * Calcula coeficiente de correlación
     */
    private fun calculateCorrelation(
        points: List<CalibrationPoint>,
        coeffA: Double,
        coeffB: Double
    ): Double {
        val n = points.size
        if (n < 3) return 0.0
        
        val estimatedValues = points.map { coeffA - coeffB * it.ratioOfRatios }
        val actualValues = points.map { it.referenceSpo2.toDouble() }
        
        val meanEstimated = estimatedValues.average()
        val meanActual = actualValues.average()
        
        var numerator = 0.0
        var sumSqEstimated = 0.0
        var sumSqActual = 0.0
        
        for (i in points.indices) {
            val diffEstimated = estimatedValues[i] - meanEstimated
            val diffActual = actualValues[i] - meanActual
            
            numerator += diffEstimated * diffActual
            sumSqEstimated += diffEstimated * diffEstimated
            sumSqActual += diffActual * diffActual
        }
        
        val denominator = kotlin.math.sqrt(sumSqEstimated * sumSqActual)
        return if (denominator > 0) numerator / denominator else 0.0
    }
    
    /**
     * Obtiene ID del dispositivo actual
     */
    private fun getCurrentDeviceId(): String {
        return "${android.os.Build.MODEL}_${android.os.Build.MANUFACTURER}_${android.os.Build.ID}"
    }
    
    /**
     * Exporta perfiles de calibración a JSON
     */
    fun exportProfiles(): String {
        val profiles = getAllCalibrationProfiles()
        return gson.toJson(profiles)
    }
    
    /**
     * Importa perfiles de calibración desde JSON
     */
    fun importProfiles(jsonData: String): Boolean {
        return try {
            val type = object : TypeToken<List<DeviceCalibrationProfile>>() {}.type
            val importedProfiles: List<DeviceCalibrationProfile> = gson.fromJson(jsonData, type)
            
            // Validar todos los perfiles importados
            val validProfiles = importedProfiles.filter { validateProfile(it) }
            
            if (validProfiles.isNotEmpty()) {
                val profilesJson = gson.toJson(validProfiles)
                sharedPreferences.edit()
                    .putString(CALIBRATION_PROFILES_KEY, profilesJson)
                    .apply()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Limpia perfiles expirados
     */
    fun cleanupExpiredProfiles(): Int {
        val profiles = getAllCalibrationProfiles()
        val validProfiles = profiles.filter { isProfileValid(it) }
        val removedCount = profiles.size - validProfiles.size
        
        if (removedCount > 0) {
            val profilesJson = gson.toJson(validProfiles)
            sharedPreferences.edit()
                .putString(CALIBRATION_PROFILES_KEY, profilesJson)
                .apply()
        }
        
        return removedCount
    }
    
    /**
     * Obtiene estadísticas de calibración
     */
    fun getCalibrationStats(): CalibrationStats {
        val profiles = getAllCalibrationProfiles()
        val expiredCount = profiles.count { !isProfileValid(it) }
        val validProfiles = profiles.filter { isProfileValid(it) }
        
        val deviceCount = validProfiles.map { it.deviceId }.distinct().size
        val avgPointCount = if (validProfiles.isNotEmpty()) {
            validProfiles.map { it.pointCount }.average()
        } else 0.0
        
        val avgError = if (validProfiles.isNotEmpty()) {
            validProfiles.map { it.averageError }.average()
        } else 0.0
        
        val avgCorrelation = if (validProfiles.isNotEmpty()) {
            validProfiles.map { it.correlationCoefficient }.average()
        } else 0.0
        
        return CalibrationStats(
            totalProfiles = profiles.size,
            validProfiles = validProfiles.size,
            expiredProfiles = expiredCount,
            uniqueDevices = deviceCount,
            averagePointsPerProfile = avgPointCount,
            averageError = avgError,
            averageCorrelation = avgCorrelation
        )
    }
}

/**
 * Perfil de calibración de dispositivo
 */
data class DeviceCalibrationProfile(
    val deviceId: String,
    val cameraId: String,
    val coefficientA: Double,
    val coefficientB: Double,
    val pointCount: Int,
    val createdDate: String,
    val validRange: Pair<Int, Int>,
    val calibrationPoints: List<CalibrationPoint> = emptyList(),
    val averageError: Double = 0.0,
    val correlationCoefficient: Double = 0.0
) {
    /**
     * Calcula SpO2 estimado usando este perfil
     */
    fun estimateSpo2(ratioOfRatios: Double): Float? {
        val spo2 = coefficientA - coefficientB * ratioOfRatios
        return if (spo2 >= validRange.first && spo2 <= validRange.second) {
            spo2.toFloat()
        } else null
    }
    
    /**
     * Verifica si el perfil es aplicable para un valor SpO2
     */
    fun isApplicableFor(spo2: Int): Boolean {
        return spo2 in validRange
    }
    
    /**
     * Obtiene calidad del perfil basada en métricas
     */
    fun getProfileQuality(): ProfileQuality {
        return when {
            correlationCoefficient > 0.95 && averageError < 1.5 -> ProfileQuality.EXCELLENT
            correlationCoefficient > 0.90 && averageError < 2.5 -> ProfileQuality.GOOD
            correlationCoefficient > 0.80 && averageError < 4.0 -> ProfileQuality.ACCEPTABLE
            correlationCoefficient > 0.70 && averageError < 6.0 -> ProfileQuality.MARGINAL
            else -> ProfileQuality.POOR
        }
    }
}

/**
 * Punto de calibración individual
 */
data class CalibrationPoint(
    val referenceSpo2: Int,
    val ratioOfRatios: Double,
    val signalQuality: Float,
    val timestamp: Long,
    val temperature: Float? = null,
    val ambientLight: Float? = null
)

/**
 * Calidad del perfil de calibración
 */
enum class ProfileQuality {
    EXCELLENT,
    GOOD,
    ACCEPTABLE,
    MARGINAL,
    POOR
}

/**
 * Estadísticas de calibración
 */
data class CalibrationStats(
    val totalProfiles: Int,
    val validProfiles: Int,
    val expiredProfiles: Int,
    val uniqueDevices: Int,
    val averagePointsPerProfile: Double,
    val averageError: Double,
    val averageCorrelation: Double
)
