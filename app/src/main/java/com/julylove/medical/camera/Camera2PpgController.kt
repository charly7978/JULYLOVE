package com.julylove.medical.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.*

/**
 * Camera2PpgController: Professional PPG acquisition using manual sensor control.
 * Ensures stable torch, fixed exposure, and high-frequency YUV sampling.
 */
class Camera2PpgController(private val context: Context) {
    private var cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    interface OnFrameAvailableListener {
        fun onFrame(red: Float, green: Float, blue: Float, timestamp: Long)
    }

    var listener: OnFrameAvailableListener? = null

    // Telemetry
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
        stopBackgroundThread()
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
        // High frame rate and small size for fast processing (160x120 is enough for mean RGB)
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
                val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                requestBuilder.addTarget(surface)
                
                // Force Torch
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                
                // Manual Exposure Control (if supported)
                val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
                val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val hasManualControl = availableCapabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

                if (hasManualControl) {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L) // 10ms
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                } else {
                    Log.w("Camera2Ppg", "Manual sensor control not supported, using auto exposure with torch.")
                }
                
                // Optimization: Disable features that add latency
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Infinity focus
                
                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Camera2Ppg", "Failed to configure capture session")
            }
        }, backgroundHandler)
    }

    private fun processImage(image: android.media.Image) {
        val timestamp = image.timestamp // Nanoseconds
        
        // Calculate FPS
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
        
        // ROI: Central 50% area (25% margin each side)
        val startX = width / 4
        val startY = height / 4
        val roiWidth = width / 2
        val roiHeight = height / 2

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
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

                // YUV to RGB conversion
                val r = (yp + 1.402f * (vp - 128)).coerceIn(0f, 255f).toInt()
                val g = (yp - 0.344136f * (up - 128) - 0.714136f * (vp - 128)).coerceIn(0f, 255f).toInt()
                val b = (yp + 1.772f * (up - 128)).coerceIn(0f, 255f).toInt()

                rSum += r
                gSum += g
                bSum += b
                pixelCount++
            }
        }

        if (pixelCount > 0) {
            listener?.onFrame(
                rSum.toFloat() / pixelCount,
                gSum.toFloat() / pixelCount,
                bSum.toFloat() / pixelCount,
                timestamp // Pass the hardware timestamp
            )
        }
    }
}
