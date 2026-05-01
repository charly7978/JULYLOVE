package com.julylove.medical.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Camera2 PPG: alta frecuencia de muestreo (30–60 fps típicos), tiempo de captura con
 * [android.media.Image.getTimestamp] para alinear intervalos RR sin depender solo de reloj UI.
 * Flash continuo + AE manual cuando el SOC lo permite; retono por lux ambiental (sensor aparte).
 */
class Camera2PpgController(private val context: Context) {
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var activeCameraId: String? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    var ambientLux: Float = 80f

    private var lastAppliedIso: Int = -1
    private var lastLuxRetuneMs: Long = 0L

    interface OnFrameAvailableListener {
        fun onFrame(frame: PpgCameraFrame, timestampNs: Long)
    }

    var listener: OnFrameAvailableListener? = null

    var actualFps: Int = 0
        private set
    private var frameCount = 0
    private var lastFpsTimestamp = 0L

    fun start() {
        startBackgroundThread()
        openCamera()
    }

    fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        lastAppliedIso = -1
        stopBackgroundThread()
    }

    /** Invocar cuando cambie el fotosensor; aplana picos de saturación en exterior brillante. */
    fun notifyAmbientLux(lux: Float) {
        ambientLux = lux
        backgroundHandler?.post { maybeRetuneExposureFromLux() }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("PpgCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("Camera2Ppg", "Thread interrupted", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        activeCameraId = cameraId

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun createCaptureSession() {
        imageReader = ImageReader.newInstance(160, 120, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            processImage(image)
            image.close()
        }, backgroundHandler)

        val surface = imageReader!!.surface
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                lastAppliedIso = -1
                applyPreviewRequest(defaultIsoForCurrentLux())
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Camera2Ppg", "Failed to configure capture session")
            }
        }, backgroundHandler)
    }

    private fun defaultIsoForCurrentLux(): Int = isoFromLux(ambientLux)

    private fun isoFromLux(lux: Float): Int {
        val v = lux.coerceIn(0f, 10_000f)
        return when {
            v < 20f -> 580
            v < 150f -> 450
            v < 450f -> 380
            else -> 300
        }.coerceIn(100, 1600)
    }

    private fun applyPreviewRequest(iso: Int) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val cid = activeCameraId ?: return

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cid)
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasManual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)

            if (hasManual) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                lastAppliedIso = iso
            } else {
                Log.w("Camera2Ppg", "Manual sensor control not supported, using auto exposure with torch.")
            }

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("Camera2Ppg", "applyPreviewRequest failed", e)
        }
    }

    private fun maybeRetuneExposureFromLux() {
        val cid = activeCameraId ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cid)
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasManual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (!hasManual) return

        val now = System.currentTimeMillis()
        if (now - lastLuxRetuneMs < 450) return
        lastLuxRetuneMs = now

        val targetIso = isoFromLux(ambientLux)
        if (lastAppliedIso != -1 && kotlin.math.abs(targetIso - lastAppliedIso) < 45) return

        applyPreviewRequest(targetIso)
    }

    private fun processImage(image: android.media.Image) {
        val timestamp = image.timestamp

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            actualFps = frameCount
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

        val startX = width / 4
        val startY = height / 4
        val roiWidth = width / 2
        val roiHeight = height / 2

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var pixelCount = 0

        val grid = 4
        val blockYSum = LongArray(grid * grid)
        val blockRSum = LongArray(grid * grid)
        val blockCnt = IntArray(grid * grid)

        val quadRSum = LongArray(4)
        val quadCnt = IntArray(4)

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (y in startY until startY + roiHeight step 2) {
            for (x in startX until startX + roiWidth step 2) {
                val bx = (((x - startX) * grid) / roiWidth).coerceIn(0, grid - 1)
                val by = (((y - startY) * grid) / roiHeight).coerceIn(0, grid - 1)
                val bIdx = by * grid + bx

                val quadrant = when {
                    bx < grid / 2 && by < grid / 2 -> 0
                    bx >= grid / 2 && by < grid / 2 -> 1
                    bx < grid / 2 && by >= grid / 2 -> 2
                    else -> 3
                }

                val yIndex = y * yRowStride + x
                val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                val yp = yBuffer.get(yIndex).toInt() and 0xFF
                val up = uBuffer.get(uvIndex).toInt() and 0xFF
                val vp = vBuffer.get(uvIndex).toInt() and 0xFF

                val r = (yp + 1.402f * (vp - 128)).coerceIn(0f, 255f).toInt()
                val g = (yp - 0.344136f * (up - 128) - 0.714136f * (vp - 128)).coerceIn(0f, 255f).toInt()
                val b = (yp + 1.772f * (up - 128)).coerceIn(0f, 255f).toInt()

                rSum += r
                gSum += g
                bSum += b
                pixelCount++

                blockCnt[bIdx]++
                blockYSum[bIdx] += yp
                blockRSum[bIdx] += r

                quadRSum[quadrant] += r
                quadCnt[quadrant]++
            }
        }

        if (pixelCount > 0) {
            val blockMean = FloatArray(grid * grid) { i ->
                if (blockCnt[i] <= 0) 0f else blockYSum[i].toFloat() / blockCnt[i]
            }
            var bmSum = 0f
            for (m in blockMean) bmSum += m
            val bmAvg = bmSum / (grid * grid).toFloat()
            var bmVarAcc = 0f
            for (m in blockMean) {
                val d = m - bmAvg
                bmVarAcc += d * d
            }
            val blockStd = kotlin.math.sqrt(bmVarAcc / (grid * grid).toFloat())

            var interGrad = 0f
            for (by in 0 until grid) {
                for (bx in 0 until grid) {
                    val i = by * grid + bx
                    val mi = blockMean[i]
                    if (bx < grid - 1) interGrad += kotlin.math.abs(mi - blockMean[i + 1])
                    if (by < grid - 1) interGrad += kotlin.math.abs(mi - blockMean[(by + 1) * grid + bx])
                }
            }

            val qMean = FloatArray(4) { q ->
                if (quadCnt[q] <= 0) 1f else quadRSum[q].toFloat() / quadCnt[q]
            }
            val qrMin = qMean.minOrNull()?.coerceAtLeast(1f) ?: 1f
            val qrMax = qMean.maxOrNull()?.coerceAtLeast(qrMin) ?: qrMin
            val quadrantBalance = qrMin / qrMax

            val frame = PpgCameraFrame(
                redSrgb = rSum.toFloat() / pixelCount,
                greenSrgb = gSum.toFloat() / pixelCount,
                blueSrgb = bSum.toFloat() / pixelCount,
                quadrantBalanceRed = quadrantBalance.coerceIn(0f, 1f),
                blockLumaStd = blockStd,
                interBlockGradient = interGrad
            )
            listener?.onFrame(frame, timestamp)
        }
    }
}
