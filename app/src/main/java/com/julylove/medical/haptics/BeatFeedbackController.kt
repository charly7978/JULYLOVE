package com.julylove.medical.haptics

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class BeatFeedbackController(context: Context) {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var beepEnabled: Boolean = true
    var vibrationEnabled: Boolean = true

    fun trigger() {
        if (beepEnabled) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
        }
        if (vibrationEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    }

    fun release() {
        toneGenerator.release()
    }
}
