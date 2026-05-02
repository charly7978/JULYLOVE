package com.forensicppg.monitor.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.forensicppg.monitor.domain.ConfirmedBeat

/**
 * Beep y vibración únicamente para [ConfirmedBeat] con confianza mínima fisiológica.
 */
class BeatFeedbackController(private val context: android.content.Context) {

    private val toneGenerator: ToneGenerator =
        ToneGenerator(AudioManager.STREAM_ALARM, 65)

    private val vibrator: Vibrator? =
        ContextCompat.getSystemService(context, Vibrator::class.java)

    private var lastEmittedNs = 0L

    /** No invocar en bucle rápido: evita repetición si el mismo tiempo de ventana llega duplicado. */
    fun onConfirmedBeat(beat: ConfirmedBeat, audioEnabled: Boolean, vibrationEnabled: Boolean) {
        if (beat.confidence < 0.52) return
        val ts = beat.timestampNs
        if (kotlin.math.abs(ts - lastEmittedNs) < 260_000_000L && lastEmittedNs != 0L) return
        lastEmittedNs = ts

        if (audioEnabled) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 75)
        }
        if (!vibrationEnabled || vibrator == null || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(52, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(52)
        }
    }

    fun releaseQuietly() {
        runCatching { toneGenerator.release() }
    }
}
