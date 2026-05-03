package com.forensicppg.monitor.ppg

import android.os.Build

/** Anchors reportados Wang et al. 2023 (valores escalares → aplicados igual R,G,B en escala ROI). */
object LiteratureZloFallback {

    private const val UNSPECIFIED_FALLBACK = 17.95

    data class Bounds(val r: Double, val g: Double, val b: Double)

    fun forCurrentDevice(): Bounds {
        val up = "${Build.MANUFACTURER} ${Build.MODEL}".trim().uppercase()
        val model = Build.MODEL.uppercase()

        fun same(v: Double): Bounds {
            val cl = v.coerceIn(9.93, 64.93)
            return Bounds(cl, cl, cl)
        }

        val v = when {
            model.contains("PIXEL 4") || model.endsWith("4") -> 22.5
            model.contains("PIXEL 7") || model.contains("PIXEL 8") -> 14.05
            model.contains("S22") -> 19.72
            model.contains("SM-S") -> 19.82
            up.contains("MOTO") || up.contains("MOTOROLA") -> 15.06
            up.contains("SAMSUNG") -> 18.85
            up.contains("XIAOMI") || up.contains("REDMI") || up.contains("POCO") -> 16.62
            up.contains("OPPO") || up.contains("REALME") -> 17.93
            up.contains("ONEPLUS") -> 18.12
            up.contains("HONOR") || up.contains("HUAWEI") -> 17.93
            else -> UNSPECIFIED_FALLBACK
        }
        return same(v)
    }
}
