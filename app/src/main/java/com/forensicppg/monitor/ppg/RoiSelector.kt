package com.forensicppg.monitor.ppg

/**
 * Selector de ROI centrado sobre el fotograma, con ligero sesgo vertical: en muchos
 * terminales el módulo cámara/flash está ligeramente hacia **arriba** del centro óptico
 * del sensor; mover el ROI ayuda cuando el usuario apoya mal el centro geométrico.
 */
class RoiSelector(
    private val centerFraction: Double = 0.6
) {
    data class Roi(val x: Int, val y: Int, val width: Int, val height: Int)

    fun pickRoi(
        frameWidth: Int,
        frameHeight: Int,
        fractionOverride: Double? = null,
        verticalBiasFracOfHeight: Double = 0.0,
        horizontalBiasFracOfWidth: Double = 0.0
    ): Roi {
        val frac = (fractionOverride ?: centerFraction).coerceIn(0.35, 0.82)
        val w = (frameWidth * frac).toInt().coerceAtLeast(16)
        val h = (frameHeight * frac).toInt().coerceAtLeast(16)
        val xBase = (frameWidth - w) / 2
        val xBias = (frameWidth.toDouble() * horizontalBiasFracOfWidth).toInt()
        val x = (xBase + xBias).coerceIn(0, kotlin.math.max(0, frameWidth - w))
        val yBase = (frameHeight - h) / 2
        val yBias = (frameHeight.toDouble() * verticalBiasFracOfHeight).toInt()
        val y = (yBase + yBias).coerceIn(0, kotlin.math.max(0, frameHeight - h))
        return Roi(x, y, w, h)
    }
}
