package com.julylove.medical.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service to export measurement sessions to JSON and CSV for forensic/clinical audit.
 */
class ExportService(private val context: Context) {

    fun exportSession(session: MeasurementSession): Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(session.timestamp))
            val fileName = "JULYLOVE_${session.id}_$timestamp.json"
            
            val json = JSONObject().apply {
                put("session_id", session.id)
                put("timestamp", session.timestamp)
                put("device", session.deviceModel)
                put("avg_bpm", session.averageBpm)
                put("avg_spo2", session.averageSpo2)
                put("rhythm_status", session.finalRhythmStatus.name)
                
                val samplesArray = JSONArray()
                session.samples.forEach { sample ->
                    samplesArray.put(JSONObject().apply {
                        put("t", sample.timestamp)
                        put("r", sample.redMean)
                        put("g", sample.greenMean)
                        put("b", sample.blueMean)
                        put("f", sample.filteredValue)
                        put("peak", sample.isPeak)
                        put("sqi", sample.sqi)
                    })
                }
                put("samples", samplesArray)
            }

            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileOutputStream(file).use { 
                it.write(json.toString(2).toByteArray()) 
            }
            
            Log.d("ExportService", "Session exported to ${file.absolutePath}")
            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e("ExportService", "Export failed", e)
            null
        }
    }
    
    fun exportToCSV(session: MeasurementSession): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(session.timestamp))
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "JULYLOVE_$timestamp.csv")
            
            file.printWriter().use { out ->
                out.println("Timestamp,Red,Green,Blue,Filtered,IsPeak,SQI")
                session.samples.forEach { s ->
                    out.println("${s.timestamp},${s.redMean},${s.greenMean},${s.blueMean},${s.filteredValue},${if(s.isPeak) 1 else 0},${s.sqi}")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }
}
