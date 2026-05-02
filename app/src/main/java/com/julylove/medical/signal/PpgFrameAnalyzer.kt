package com.julylove.medical.signal

import android.media.Image
import com.julylove.medical.camera.PpgCameraFrame
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PpgFrameAnalyzer - Analizador espacial de frames.
 * Convierte un Image (YUV_420_888) en un PpgFrame con métricas ópticas.
 */
class PpgFrameAnalyzer {
    
    private var lastFrameTime: Long = 0
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentFps = 0f

    fun analyze(image: Image, timestampNs: Long): PpgFrame {
        // Calcular FPS
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            currentFps = frameCount.toFloat()
            frameCount = 0
            lastFpsTimestamp = now
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val width = image.width
        val height = image.height
        
        // ROI Central (35% a 65%)
        val startX = (width * 0.35).toInt()
        val startY = (height * 0.35).toInt()
        val roiWidth = (width * 0.3).toInt()
        val roiHeight = (height * 0.3).toInt()

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var satPixels = 0
        var darkPixels = 0
        var pixelCount = 0

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (y in startY until startY + roiHeight step 2) {
            for (x in startX until startX + roiWidth step 2) {
                val yIndex = y * yRowStride + x
                val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                val yp = yBuffer.get(yIndex).toInt() and 0xFF
                val up = uBuffer.get(uvIndex).toInt() and 0xFF
                val vp = vBuffer.get(uvIndex).toInt() and 0xFF

                val r = (yp + 1.402f * (vp - 128)).coerceIn(0f, 255f)
                val g = (yp - 0.344136f * (up - 128) - 0.714136f * (vp - 128)).coerceIn(0f, 255f)
                val b = (yp + 1.772f * (up - 128)).coerceIn(0f, 255f)

                if (r > 250) satPixels++
                if (r < 10) darkPixels++

                rSum += r.toLong()
                gSum += g.toLong()
                bSum += b.toLong()
                pixelCount++
            }
        }

        val avgR = if (pixelCount > 0) rSum.toFloat() / pixelCount else 0f
        val avgG = if (pixelCount > 0) gSum.toFloat() / pixelCount else 0f
        val avgB = if (pixelCount > 0) bSum.toFloat() / pixelCount else 0f

        val redDominance = avgR / (avgG + avgB).coerceAtLeast(1f)
        
        return PpgFrame(
            timestampNs = timestampNs,
            fpsEstimate = currentFps,
            avgRed = avgR,
            avgGreen = avgG,
            avgBlue = avgB,
            redDc = avgR,
            greenDc = avgG,
            blueDc = avgB,
            redAc = 0f, // Se calculará en el buffer temporal
            greenAc = 0f,
            blueAc = 0f,
            saturationRatio = satPixels.toFloat() / pixelCount.coerceAtLeast(1),
            darknessRatio = darkPixels.toFloat() / pixelCount.coerceAtLeast(1),
            skinLikelihood = calculateSkinLikelihood(avgR, avgG, avgB),
            redDominance = redDominance,
            greenPulseCandidate = avgG,
            textureScore = 1f, // TODO: Implementar análisis de textura
            motionScore = 0f,
            rawOpticalSignal = avgG,
            normalizedSignal = avgG / 255f
        )
    }

    private fun calculateSkinLikelihood(r: Float, g: Float, b: Float): Float {
        if (r < 40 || r < g || r < b) return 0f
        val total = r + g + b
        if (total == 0f) return 0f
        val rg = r / g.coerceAtLeast(1f)
        return if (rg in 1.2f..4.5f) 0.8f else 0.2f
    }
}
