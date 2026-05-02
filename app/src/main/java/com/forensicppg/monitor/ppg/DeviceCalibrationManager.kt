package com.forensicppg.monitor.ppg

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persiste y aplica perfiles de calibración por (deviceModel + cameraId +
 * physicalCameraId + exposure/iso buckets). Sin perfil válido, el pipeline
 * JAMÁS devuelve un número absoluto de SpO₂.
 */
class DeviceCalibrationManager(private val context: Context) {

    private val file: File by lazy {
        File(context.filesDir, "forensic_ppg_calibration_profiles.json").also {
            if (!it.exists()) it.writeText("[]")
        }
    }

    private val algorithmVersion = "spo2-ppg-v1"

    fun currentDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun findProfile(cameraId: String, physicalId: String?): CalibrationProfile? {
        val all = loadAll()
        val model = currentDeviceModel()
        return all.firstOrNull {
            it.deviceModel == model && it.cameraId == cameraId && it.physicalCameraId == physicalId &&
                it.algorithmVersion == algorithmVersion
        }
    }

    /**
     * Calibra con una lista de puntos; requiere al menos 3 puntos válidos.
     * Ajusta A y B mediante mínimos cuadrados sobre la ecuación
     *   Spo2 = A - B * ratioOfRatios.
     */
    fun fit(
        cameraId: String,
        physicalId: String?,
        exposureTimeNs: Long?,
        iso: Int?,
        frameDurationNs: Long?,
        torchIntensity: Double?,
        points: List<CalibrationPoint>,
        notes: String = ""
    ): CalibrationProfile? {
        val valid = points.filter { it.sqi >= 0.5 && it.motionScore < 0.3 && it.perfusionIndex > 0.3 }
        if (valid.size < 3) return null
        val n = valid.size.toDouble()
        val sumX = valid.sumOf { it.ratioOfRatios }
        val sumY = valid.sumOf { it.referenceSpo2 }
        val sumXY = valid.sumOf { it.ratioOfRatios * it.referenceSpo2 }
        val sumXX = valid.sumOf { it.ratioOfRatios * it.ratioOfRatios }
        val denom = n * sumXX - sumX * sumX
        if (kotlin.math.abs(denom) < 1e-9) return null
        val slope = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n
        val profile = CalibrationProfile(
            profileId = java.util.UUID.randomUUID().toString(),
            deviceModel = currentDeviceModel(),
            cameraId = cameraId,
            physicalCameraId = physicalId,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            frameDurationNs = frameDurationNs,
            torchIntensity = torchIntensity,
            coefficientA = intercept,
            coefficientB = -slope,
            createdAtMs = System.currentTimeMillis(),
            algorithmVersion = algorithmVersion,
            calibrationSamples = valid.size,
            minPerfusionIndex = valid.minOf { it.perfusionIndex },
            notes = notes
        )
        save(profile)
        return profile
    }

    fun save(profile: CalibrationProfile) {
        val all = loadAll().toMutableList()
        all.removeAll {
            it.deviceModel == profile.deviceModel && it.cameraId == profile.cameraId &&
                it.physicalCameraId == profile.physicalCameraId &&
                it.algorithmVersion == profile.algorithmVersion
        }
        all += profile
        val arr = JSONArray()
        for (p in all) arr.put(toJson(p))
        file.writeText(arr.toString())
    }

    fun loadAll(): List<CalibrationProfile> {
        return try {
            val txt = file.readText()
            if (txt.isBlank()) return emptyList()
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun toJson(p: CalibrationProfile): JSONObject = JSONObject().apply {
        put("profileId", p.profileId)
        put("deviceModel", p.deviceModel)
        put("cameraId", p.cameraId)
        put("physicalCameraId", p.physicalCameraId ?: JSONObject.NULL)
        put("exposureTimeNs", p.exposureTimeNs ?: JSONObject.NULL)
        put("iso", p.iso ?: JSONObject.NULL)
        put("frameDurationNs", p.frameDurationNs ?: JSONObject.NULL)
        put("torchIntensity", p.torchIntensity ?: JSONObject.NULL)
        put("coefficientA", p.coefficientA)
        put("coefficientB", p.coefficientB)
        put("createdAtMs", p.createdAtMs)
        put("algorithmVersion", p.algorithmVersion)
        put("calibrationSamples", p.calibrationSamples)
        put("minPerfusionIndex", p.minPerfusionIndex)
        put("notes", p.notes)
    }

    private fun fromJson(o: JSONObject): CalibrationProfile = CalibrationProfile(
        profileId = o.getString("profileId"),
        deviceModel = o.getString("deviceModel"),
        cameraId = o.getString("cameraId"),
        physicalCameraId = o.optString("physicalCameraId").takeIf { it.isNotEmpty() && it != "null" },
        exposureTimeNs = if (o.isNull("exposureTimeNs")) null else o.getLong("exposureTimeNs"),
        iso = if (o.isNull("iso")) null else o.getInt("iso"),
        frameDurationNs = if (o.isNull("frameDurationNs")) null else o.getLong("frameDurationNs"),
        torchIntensity = if (o.isNull("torchIntensity")) null else o.getDouble("torchIntensity"),
        coefficientA = o.getDouble("coefficientA"),
        coefficientB = o.getDouble("coefficientB"),
        createdAtMs = o.getLong("createdAtMs"),
        algorithmVersion = o.optString("algorithmVersion", "spo2-ppg-v1"),
        calibrationSamples = o.optInt("calibrationSamples", 0),
        minPerfusionIndex = o.optDouble("minPerfusionIndex", 0.0),
        notes = o.optString("notes", "")
    )
}
