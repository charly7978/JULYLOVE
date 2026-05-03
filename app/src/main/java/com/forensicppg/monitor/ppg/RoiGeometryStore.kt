package com.forensicppg.monitor.ppg

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Persistencia de preset ROI/LED por dispositivo + cameraId (misma convención que ZLO). */
class RoiGeometryStore(private val context: Context) {

    private val path: File by lazy {
        File(context.filesDir, "forensic_ppg_roi_geometry.json").also {
            if (!it.exists()) it.writeText("{}")
        }
    }

    private fun key(deviceModel: String, cameraId: String): String =
        "${deviceModel.trim()}|cid=${cameraId.trim()}"

    fun save(deviceModel: String, cameraId: String, preset: RoiGeometryPreset) {
        val entry = JSONObject().apply {
            put("presetId", preset.presetId)
            put("savedAtMs", System.currentTimeMillis())
        }
        synchronized(path) {
            val merged = try {
                if (path.exists()) JSONObject(path.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            merged.put(key(deviceModel, cameraId), entry)
            path.writeText(merged.toString(2))
        }
    }

    fun loadPresetId(deviceModel: String, cameraId: String): String? =
        synchronized(path) {
            try {
                if (!path.exists()) return@synchronized null
                val merged = JSONObject(path.readText())
                val inner = merged.optJSONObject(key(deviceModel, cameraId)) ?: return@synchronized null
                inner.optString("presetId", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
}
