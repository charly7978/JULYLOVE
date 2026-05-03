package com.forensicppg.monitor.forensic

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura cualquier excepción no atrapada en el proceso y la escribe a
 *
 *     /sdcard/Android/data/<package>/files/crash-<timestamp>.txt
 *
 * Esto da diagnóstico real cuando la app cierra inmediatamente al abrir
 * (los OEMs muchas veces se comen el toast del sistema). El usuario puede
 * recuperar el archivo desde Files / cualquier explorador.
 *
 * Activamos el handler ANTES de tocar cualquier API que pueda crashear
 * (ToneGenerator, Camera2, SensorManager).
 */
object CrashLogger {

    @Volatile private var installed = false
    @Volatile private var lastReport: String? = null

    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(app, thread, error)
                lastReport = report
                writeReport(app, report)
                Log.e("ForensicPPG-Crash", report)
            } catch (_: Throwable) {
                // Si fallamos al loguear, no propagar otra excepción.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrashReport(): String? = lastReport

    fun lastCrashFile(app: Application): File? {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return null
        return files.maxByOrNull { it.lastModified() }
    }

    private fun buildReport(app: Application, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        pw.println("=== Monitor PPG Forense — crash report ===")
        pw.println("timestamp: $ts")
        pw.println("packageName: ${app.packageName}")
        pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("os: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("thread: ${thread.name}")
        pw.println()
        error.printStackTrace(pw)
        var cause: Throwable? = error.cause
        while (cause != null && cause !== error) {
            pw.println()
            pw.println("Caused by:")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        pw.flush()
        return sw.toString()
    }

    private fun writeReport(app: Application, report: String) {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "crash-$ts.txt")
        out.writeText(report)
    }
}
