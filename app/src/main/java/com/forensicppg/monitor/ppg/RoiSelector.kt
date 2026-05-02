package com.forensicppg.monitor.ppg

/**
 * Selector de ROI simple pero robusto: usa el 60% central del frame como ROI
 * primario y permite subdivisión en tiles para la evaluación por tile. No
 * genera ROI aleatorio: su salida depende sólo del tamaño del frame.
 */
class RoiSelector(
    private val centerFraction: Double = 0.6
) {
    data class Roi(val x: Int, val y: Int, val width: Int, val height: Int)

    fun pickRoi(frameWidth: Int, frameHeight: Int): Roi {
        val w = (frameWidth * centerFraction).toInt().coerceAtLeast(16)
        val h = (frameHeight * centerFraction).toInt().coerceAtLeast(16)
        val x = ((frameWidth - w) / 2).coerceAtLeast(0)
        val y = ((frameHeight - h) / 2).coerceAtLeast(0)
        return Roi(x, y, w, h)
    }

    fun splitIntoTiles(roi: Roi, tilesPerSide: Int): List<Roi> {
        val out = ArrayList<Roi>(tilesPerSide * tilesPerSide)
        val tileW = roi.width / tilesPerSide
        val tileH = roi.height / tilesPerSide
        for (ty in 0 until tilesPerSide) {
            for (tx in 0 until tilesPerSide) {
                out += Roi(roi.x + tx * tileW, roi.y + ty * tileH, tileW, tileH)
            }
        }
        return out
    }
}
