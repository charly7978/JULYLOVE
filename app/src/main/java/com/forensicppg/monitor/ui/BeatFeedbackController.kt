package com.forensicppg.monitor.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.forensicppg.monitor.domain.ConfirmedBeat

/**
 * Beep + vibración al confirmar un latido.
 *
 * **Robusto a fallos del OEM**: en muchos teléfonos `ToneGenerator` lanza
 * `RuntimeException` durante la construcción si la app no tiene foco de
 * audio o el stream está ocupado. Antes ese crash llegaba inmediatamente
 * al inicializar el ViewModel y mataba la app sin ver UI siquiera. Ahora
 * la construcción del ToneGenerator está envuelta en try/catch y sucede
 * de forma **lazy** sólo cuando se quiere reproducir el primer beep.
 */
class BeatFeedbackController(private val context: android.content.Context) {

    @Volatile private var toneGenerator: ToneGenerator? = null
    @Volatile private var toneGeneratorFailed = false

    private val vibrator: Vibrator? =
        runCatching { ContextCompat.getSystemService(context, Vibrator::class.java) }.getOrNull()

    private var lastEmittedNs = 0L

    private fun ensureToneGenerator(): ToneGenerator? {
        if (toneGeneratorFailed) return null
        toneGenerator?.let { return it }
        return try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 65)
            toneGenerator = tg
            tg
        } catch (e: RuntimeException) {
            Log.w("BeatFeedback", "ToneGenerator no disponible: ${e.message}")
            toneGeneratorFailed = true
            null
        }
    }

    fun onConfirmedBeat(beat: ConfirmedBeat, audioEnabled: Boolean, vibrationEnabled: Boolean) {
        if (beat.confidence < 0.45) return
        val ts = beat.timestampNs
        if (kotlin.math.abs(ts - lastEmittedNs) < 260_000_000L && lastEmittedNs != 0L) return
        lastEmittedNs = ts

        if (audioEnabled) {
            try {
                ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_ACK, 75)
            } catch (e: RuntimeException) {
                Log.w("BeatFeedback", "tono falló: ${e.message}")
            }
        }
        if (!vibrationEnabled) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(52, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(52)
            }
        } catch (e: RuntimeException) {
            Log.w("BeatFeedback", "vibración falló: ${e.message}")
        }
    }

    fun releaseQuietly() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
