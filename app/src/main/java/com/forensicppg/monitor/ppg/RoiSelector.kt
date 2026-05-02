package com.forensicppg.monitor.ppg

/**
 * Selector de ROI centrado sobre el fotograma: la posición física esperada es que
 * la yema cubra lente + flash traseros; geométricamente coincide con el centro
 * del campo en la mayoría de dispositivos móviles compactos.
 */
class RoiSelector(
    private val centerFraction: Double = 0.6
) {
    data class Roi(val x: Int, val y: Int, val width: Int, val height: Int)

    fun pickRoi(frameWidth: Int, frameHeight: Int, fractionOverride: Double? = null): Roi {
        val frac = (fractionOverride ?: centerFraction).coerceIn(0.35, 0.82)
        val w = (frameWidth * frac).toInt().coerceAtLeast(16)
        val h = (frameHeight * frac).toInt().coerceAtLeast(16)
        val x = ((frameWidth - w) / 2).coerceAtLeast(0)
        val y = ((frameHeight - h) / 2).coerceAtLeast(0)
        return Roi(x, y, w, h)
    }
}
