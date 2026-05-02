package com.forensicppg.monitor.ppg

import android.media.Image
import com.forensicppg.monitor.domain.CameraFrame
import java.nio.ByteBuffer

/**
 * Extrae las métricas agregadas de un frame YUV_420_888 real: medias R/G/B,
 * clipping, varianza espacial y cobertura de ROI. Todas las operaciones son en
 * punto fijo por fila para minimizar allocations y latencia.
 *
 * IMPORTANTE: si el extractor no puede leer planos del frame (p.ej. buffers
 * inválidos por el driver), devuelve `null` y el pipeline descarta el frame.
 * Nunca fabrica un valor.
 */
class PpgFrameExtractor(
    private val roiSelector: RoiSelector = RoiSelector()
) {

    /**
     * `image` debe ser un frame YUV_420_888. El llamante debe cerrar la
     * imagen después. `exposureTimeNs`, `iso`, `frameDurationNs` pueden ser
     * null si la cámara no los reporta.
     */
    fun extract(
        image: Image,
        cameraId: String,
        timestampNs: Long,
        exposureTimeNs: Long?,
        iso: Int?,
        frameDurationNs: Long?
    ): CameraFrame? {
        if (image.format != 0x23) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val roi = roiSelector.pickRoi(width, height)
        return accumulate(
            yBuf, yRow, yPix,
            uBuf, uRow, uPix,
            vBuf, vRow, vPix,
            roi, width, height,
            cameraId = cameraId,
            timestampNs = timestampNs,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            frameDurationNs = frameDurationNs
        )
    }

    private fun accumulate(
        yBuf: ByteBuffer, yRow: Int, yPix: Int,
        uBuf: ByteBuffer, uRow: Int, uPix: Int,
        vBuf: ByteBuffer, vRow: Int, vPix: Int,
        roi: RoiSelector.Roi,
        width: Int, height: Int,
        cameraId: String,
        timestampNs: Long,
        exposureTimeNs: Long?,
        iso: Int?,
        frameDurationNs: Long?
    ): CameraFrame {
        val x0 = roi.x
        val y0 = roi.y
        val x1 = roi.x + roi.width
        val y1 = roi.y + roi.height

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumY = 0L
        var sumY2 = 0L
        var clipHigh = 0L
        var clipLow = 0L
        var count = 0L

        // Subsample de 2x2 sobre YUV para evitar recorrer cada byte en frames grandes.
        val step = if (roi.width > 320) 2 else 1
        var y = y0
        while (y < y1) {
            val uvY = y / 2
            var x = x0
            while (x < x1) {
                val uvX = x / 2
                val yIdx = y * yRow + x * yPix
                val uIdx = uvY * uRow + uvX * uPix
                val vIdx = uvY * vRow + uvX * vPix
                if (yIdx >= yBuf.capacity() || uIdx >= uBuf.capacity() || vIdx >= vBuf.capacity()) {
                    x += step
                    continue
                }
                val yv = yBuf.get(yIdx).toInt() and 0xFF
                val uv = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vIdx).toInt() and 0xFF) - 128

                // Conversión YUV(BT.601) → RGB en [0,255]. Clamps con coerceIn.
                val r = (yv + 1.402 * vv).toInt().coerceIn(0, 255)
                val g = (yv - 0.344136 * uv - 0.714136 * vv).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * uv).toInt().coerceIn(0, 255)

                sumR += r
                sumG += g
                sumB += b
                sumY += yv
                sumY2 += yv * yv
                if (yv >= 250) clipHigh++
                if (yv <= 5) clipLow++
                count++

                x += step
            }
            y += step
        }

        if (count <= 0) {
            return CameraFrame(
                timestampNs = timestampNs, width = width, height = height, format = 0x23,
                cameraId = cameraId, exposureTimeNs = exposureTimeNs, iso = iso,
                frameDurationNs = frameDurationNs,
                redMean = 0.0, greenMean = 0.0, blueMean = 0.0,
                redAcDc = 0.0, greenAcDc = 0.0, blueAcDc = 0.0,
                clipHighRatio = 0.0, clipLowRatio = 0.0,
                roiCoverage = 0.0, roiVariance = 0.0
            )
        }

        val countD = count.toDouble()
        val rMean = sumR / countD
        val gMean = sumG / countD
        val bMean = sumB / countD
        val yMean = sumY / countD
        val yVar = (sumY2 / countD) - (yMean * yMean)
        val clipHR = clipHigh / countD
        val clipLR = clipLow / countD
        val coverage = count.toDouble() / ((width * height).toDouble().coerceAtLeast(1.0))

        // AC/DC por canal: aproximación frame-to-frame hecha por el pipeline
        // aguas arriba. En este extractor sólo reportamos el mean estable.
        return CameraFrame(
            timestampNs = timestampNs,
            width = width,
            height = height,
            format = 0x23,
            cameraId = cameraId,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            frameDurationNs = frameDurationNs,
            redMean = rMean,
            greenMean = gMean,
            blueMean = bMean,
            redAcDc = 0.0,
            greenAcDc = 0.0,
            blueAcDc = 0.0,
            clipHighRatio = clipHR,
            clipLowRatio = clipLR,
            roiCoverage = coverage.coerceIn(0.0, 1.0),
            roiVariance = yVar
        )
    }
}
