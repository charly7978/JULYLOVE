package com.forensicppg.monitor.ppg

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ZLO efectivo aplicado tras YUV→RGB sobre ROI digital (orden Wang 2023, adaptado campo).
 */
class SensorZloStore(private val context: Context) {

    private val path: File by lazy {
        File(context.filesDir, "forensic_ppg_sensor_zlo.json").also {
            if (!it.exists()) it.writeText("{}")
        }
    }

    private fun key(deviceModel: String, cameraId: String): String = "${deviceModel.trim()}|cid=${cameraId.trim()}"

    fun save(
        deviceModel: String,
        cameraId: String,
        r: Double,
        g: Double,
        b: Double,
        framesUsed: Int,
        fromInstrumentedCapture: Boolean
    ) {
        val entry = JSONObject().apply {
            put("deviceModel", deviceModel)
            put("cameraId", cameraId)
            put("zloR", r)
            put("zloG", g)
            put("zloB", b)
            put("savedAtMs", System.currentTimeMillis())
            put("framesUsed", framesUsed)
            put("fromCapture", fromInstrumentedCapture)
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

    fun load(deviceModel: String, cameraId: String): StoredZlo? = synchronized(path) {
        try {
            if (!path.exists()) return@synchronized null
            JSONObject(path.readText())
                .optJSONObject(key(deviceModel, cameraId))
                ?: return null
            val j = JSONObject(path.readText()).getJSONObject(key(deviceModel, cameraId))
            StoredZlo(
                zloR = j.getDouble("zloR"),
                zloG = j.getDouble("zloG"),
                zloB = j.getDouble("zloB"),
                captured = j.optBoolean("fromCapture"),
                framesUsed = j.optInt("framesUsed", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(deviceModel: String, cameraId: String) {
        synchronized(path) {
            if (!path.exists()) return@synchronized
            val merged = try {
                JSONObject(path.readText())
            } catch (_: Exception) {
                return@synchronized
            }
            merged.remove(key(deviceModel, cameraId))
            path.writeText(merged.toString(2))
        }
    }

    data class StoredZlo(
        val zloR: Double,
        val zloG: Double,
        val zloB: Double,
        val captured: Boolean,
        val framesUsed: Int
    )
}
