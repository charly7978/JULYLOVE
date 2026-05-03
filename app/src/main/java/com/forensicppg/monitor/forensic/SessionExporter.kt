package com.forensicppg.monitor.forensic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exporta la sesión completa a JSON + CSVs en el directorio público de la app
 * (`filesDir/export/<sessionId>/`). Devuelve las rutas resultantes.
 *
 * El archivo JSON incluye:
 *   - Toda la metadata técnica (cámara, exposición, ISO, fps, etc.)
 *   - Los eventos de auditoría.
 *   - Un campo `integrityHash` con el SHA-256 del contenido serializado sin
 *     esa clave, para detectar alteraciones.
 */
class SessionExporter(private val context: Context) {

    data class Exported(
        val rootDir: File,
        val jsonFile: File,
        val samplesCsv: File,
        val beatsCsv: File,
        val eventsCsv: File,
        val reportTxt: File,
        val integrityHashHex: String
    )

    fun export(session: MeasurementSession): Exported {
        val root = File(context.filesDir, "export/${session.sessionId}")
        if (!root.exists()) root.mkdirs()

        val samplesCsv = File(root, "samples.csv")
        val beatsCsv = File(root, "beats.csv")
        val eventsCsv = File(root, "events.csv")
        val jsonFile = File(root, "session.json")
        val reportTxt = File(root, "report.txt")

        writeSamples(samplesCsv, session)
        writeBeats(beatsCsv, session)
        writeEvents(eventsCsv, session)
        writeReport(reportTxt, session)

        val jsonNoHash = sessionJson(session).toString(2)
        val hash = IntegrityHasher.sha256Hex(jsonNoHash)
        val finalJson = JSONObject(jsonNoHash).also { it.put("integrityHash", hash) }
        jsonFile.writeText(finalJson.toString(2))

        return Exported(root, jsonFile, samplesCsv, beatsCsv, eventsCsv, reportTxt, hash)
    }

    private fun sessionJson(s: MeasurementSession): JSONObject {
        val o = JSONObject()
        o.put("sessionId", s.sessionId)
        o.put("startEpochMs", s.startEpochMs)
        o.put("endEpochMs", s.endEpochMs ?: JSONObject.NULL)
        o.put("deviceModel", s.deviceModel)
        o.put("androidSdk", s.androidSdk)
        o.put("appVersion", s.appVersion)
        o.put("algorithmVersion", s.algorithmVersion)
        o.put("cameraId", s.cameraId)
        o.put("physicalCameraId", s.physicalCameraId ?: JSONObject.NULL)
        o.put("torchEnabled", s.torchEnabled)
        o.put("manualControlApplied", s.manualControlApplied)
        o.put("exposureTimeNs", s.exposureTimeNs ?: JSONObject.NULL)
        o.put("iso", s.iso ?: JSONObject.NULL)
        o.put("frameDurationNs", s.frameDurationNs ?: JSONObject.NULL)
        o.put("targetFps", s.targetFps)
        o.put("fpsActualMean", s.fpsActualMean)
        o.put("fpsJitterMs", s.fpsJitterMs)
        o.put("framesTotal", s.framesTotal)
        o.put("framesAccepted", s.framesAccepted)
        o.put("framesRejected", s.framesRejected)
        o.put("ispAcquisitionSummary", s.ispAcquisitionSummary ?: JSONObject.NULL)
        o.put("sensorZloR", if (s.sensorZloR != null) s.sensorZloR else JSONObject.NULL)
        o.put("sensorZloG", if (s.sensorZloG != null) s.sensorZloG else JSONObject.NULL)
        o.put("sensorZloB", if (s.sensorZloB != null) s.sensorZloB else JSONObject.NULL)
        o.put("zloSourceNote", s.zloSourceNote ?: JSONObject.NULL)
        o.put("roiGeometryPresetId", s.roiGeometryPresetId ?: JSONObject.NULL)
        o.put("calibrationProfileId", s.calibrationProfileId ?: JSONObject.NULL)
        o.put("finalBpmMean", s.finalBpmMean ?: JSONObject.NULL)
        o.put("finalBpmSdnn", s.finalBpmSdnn ?: JSONObject.NULL)
        o.put("finalSpo2Mean", s.finalSpo2Mean ?: JSONObject.NULL)
        o.put("finalSqiMean", s.finalSqiMean ?: JSONObject.NULL)
        val eventsArr = JSONArray()
        synchronized(s.events) {
            for (e in s.events) {
                eventsArr.put(JSONObject().apply {
                    put("timestampNs", e.timestampNs)
                    put("kind", e.kind.name)
                    put("details", e.details)
                })
            }
        }
        o.put("events", eventsArr)
        o.put("sampleCount", s.samples.size)
        o.put("beatCount", s.beats.size)
        return o
    }

    private fun writeSamples(f: File, s: MeasurementSession) {
        f.bufferedWriter().use { w ->
            w.write(
                "timestampNs,monotonicRealtimeNs," +
                    "roiMeanPreZloRed,roiMeanPreZloGreen,roiMeanPreZloBlue," +
                    "rawRed,rawGreen,rawBlue,filtPri,display,sqi,motion,opticalMot," +
                    "perfusionGreenPct,clipH,clipL,lowLight,roiWd,roiHt\n"
            )
            for (sm in s.samples) {
                val rs = sm.roiStats
                w.write(
                    "${sm.timestampNs},${sm.monotonicRealtimeNs}," +
                        "${sm.roiMeanPreZloRed},${sm.roiMeanPreZloGreen},${sm.roiMeanPreZloBlue}," +
                        "${sm.rawRed},${sm.rawGreen},${sm.rawBlue},${sm.filteredPrimary},${sm.displayWave}," +
                        "${sm.sqi},${sm.motionScore},${sm.motionScoreOptical}," +
                        "${rs.perfusionIndexGreenPct}," +
                        "${sm.clippingHighRatio},${sm.clippingLowRatio},${sm.lowLightSuspected}," +
                        "${rs.roiWidth},${rs.roiHeight}\n"
                )
            }
        }
    }

    private fun writeBeats(f: File, s: MeasurementSession) {
        f.bufferedWriter().use { w ->
            w.write(
                "timestampNs,amplitude,rrMs,bpmApprox,confidence,sourceChannel,sqiSegment,rhythmMarker\n"
            )
            for (b in s.beats) {
                val bpm = b.rrIntervalMs
                    ?.takeIf { it in 278.9..2180.93 }
                    ?.let { 60000.0 / it }
                w.write(
                    "${b.timestampNs},${b.amplitude},${b.rrIntervalMs ?: ""}," +
                        "${if (bpm != null) String.format("%.2f", bpm) else ""}," +
                        "${b.confidence},${b.sourceChannel},${b.sqiSegment},${b.rhythmMarker.name}\n"
                )
            }
        }
    }

    private fun writeEvents(f: File, s: MeasurementSession) {
        f.bufferedWriter().use { w ->
            w.write("timestampNs,kind,details\n")
            synchronized(s.events) {
                for (e in s.events) {
                    w.write("${e.timestampNs},${e.kind.name},\"${e.details.replace("\"", "'")}\"\n")
                }
            }
        }
    }

    private fun writeReport(f: File, s: MeasurementSession) {
        f.writeText(buildString {
            appendLine("== REPORTE FORENSE DE MEDICIÓN PPG ==")
            appendLine("sessionId: ${s.sessionId}")
            appendLine("dispositivo: ${s.deviceModel}")
            appendLine("inicio (ms): ${s.startEpochMs}")
            appendLine("fin (ms): ${s.endEpochMs}")
            appendLine("duración (ms): ${(s.endEpochMs ?: s.startEpochMs) - s.startEpochMs}")
            appendLine("cameraId: ${s.cameraId}")
            appendLine("physicalCameraId: ${s.physicalCameraId}")
            appendLine("control manual: ${s.manualControlApplied}")
            appendLine("FPS real medio: ${"%.2f".format(s.fpsActualMean)}")
            appendLine("jitter (ms): ${"%.2f".format(s.fpsJitterMs)}")
            appendLine("frames total/aceptados/rechazados: ${s.framesTotal}/${s.framesAccepted}/${s.framesRejected}")
            appendLine("latidos registrados: ${s.beats.size}")
            appendLine("BPM medio: ${s.finalBpmMean}")
            appendLine("SDNN (ms): ${s.finalBpmSdnn}")
            appendLine("SpO₂ medio: ${s.finalSpo2Mean}")
            appendLine("SQI medio: ${s.finalSqiMean}")
            appendLine("perfil calibración: ${s.calibrationProfileId ?: "(ninguno, SpO₂ no calibrado)"}")
            appendLine("ISP (sesión): ${s.ispAcquisitionSummary ?: "—"}")
            appendLine(
                "ZLO efectivo — R:${s.sensorZloR ?: "—"} G:${s.sensorZloG ?: "—"} B:${s.sensorZloB ?: "—"} " +
                    "origen:${s.zloSourceNote ?: "—"}"
            )
            appendLine("ROI geometría (preset LED/lente): ${s.roiGeometryPresetId ?: "—"}")
        })
    }
}
