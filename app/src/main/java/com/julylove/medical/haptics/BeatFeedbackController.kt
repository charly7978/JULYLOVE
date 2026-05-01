package com.julylove.medical.haptics

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * La creación síncrona de [ToneGenerator] provoca crashes en startup en muchos OEM/emuladores
 * (motor de audio aún indisponible, política STREAM_NOTIFICATION, volumen rutas, etc.).
 * Aquí sólo construimos un generador ante el primer uso, con captura robusta de fallos.
 */
class BeatFeedbackController(context: Context) {

    companion object {
        private const val TAG = "BeatFeedback"
    }

    private var toneGenerator: ToneGenerator? = null

    private val vibrator: Vibrator? = run {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator unavailable", e)
            null
        }
    }

    var beepEnabled: Boolean = true
    var vibrationEnabled: Boolean = true

    private fun obtainToneGenerator(): ToneGenerator? {
        if (toneGenerator != null) return toneGenerator
        return try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { toneGenerator = it }
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed — beep disabled", e)
            toneGenerator = null
            null
        }
    }

    fun trigger() {
        if (beepEnabled) {
            obtainToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
        }
        if (vibrationEnabled && vibrator != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibrate skipped", e)
            }
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
            // ignore
        }
        toneGenerator = null
    }
}
