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
    @Volatile private var liveReport: String? = null
    private val liveListeners = mutableListOf<(String) -> Unit>()
    @Volatile private var appRef: Application? = null

    fun install(app: Application) {
        if (installed) return
        installed = true
        appRef = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(app, thread, error, fatal = true)
                lastReport = report
                writeReport(app, report, prefix = "crash-")
                Log.e("ForensicPPG-Crash", report)
            } catch (_: Throwable) {
                // Si fallamos al loguear, no propagar otra excepción.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Loguea una excepción NO FATAL (capturada por handlers de coroutines /
     * onClick) sin matar el proceso. Notifica observadores de UI para que
     * el usuario vea el problema en pantalla en lugar de cerrarse en silencio.
     */
    fun reportNonFatal(tag: String, error: Throwable) {
        val app = appRef ?: return
        val report = buildReport(app, Thread.currentThread(), error, fatal = false, tag = tag)
        liveReport = report
        try {
            writeReport(app, report, prefix = "nonfatal-")
        } catch (_: Throwable) { /* ignore */ }
        Log.w("ForensicPPG-NonFatal", report)
        synchronized(liveListeners) {
            liveListeners.toList()
        }.forEach { runCatching { it(report) } }
    }

    fun addLiveListener(cb: (String) -> Unit) {
        synchronized(liveListeners) { liveListeners += cb }
    }

    fun removeLiveListener(cb: (String) -> Unit) {
        synchronized(liveListeners) { liveListeners -= cb }
    }

    fun lastCrashReport(): String? = lastReport
    fun lastNonFatalReport(): String? = liveReport

    fun lastCrashFile(app: Application): File? {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val files = dir.listFiles { f ->
            f.name.startsWith("crash-") || f.name.startsWith("nonfatal-")
        } ?: return null
        return files.maxByOrNull { it.lastModified() }
    }

    private fun buildReport(
        app: Application,
        thread: Thread,
        error: Throwable,
        fatal: Boolean,
        tag: String = ""
    ): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        pw.println("=== Monitor PPG Forense — ${if (fatal) "CRASH FATAL" else "no-fatal"} ===")
        pw.println("timestamp: $ts")
        pw.println("packageName: ${app.packageName}")
        pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("os: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("thread: ${thread.name}")
        if (tag.isNotEmpty()) pw.println("tag: $tag")
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

    private fun writeReport(app: Application, report: String, prefix: String) {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "$prefix$ts.txt")
        out.writeText(report)
    }
}
