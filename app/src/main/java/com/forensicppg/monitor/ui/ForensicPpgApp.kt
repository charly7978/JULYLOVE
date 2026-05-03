package com.forensicppg.monitor.ui

import android.app.Application
import com.forensicppg.monitor.forensic.CrashLogger

/**
 * Subclase de [Application] que instala el [CrashLogger] global ANTES de
 * que cualquier ViewModel / Activity ejecute código. Sin esto, los
 * crashes en `onCreate` de la Activity (campos lazy del ViewModel,
 * inicialización de ToneGenerator, etc.) nunca quedan registrados y
 * la app desaparece sin dejar rastro.
 */
class ForensicPpgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
