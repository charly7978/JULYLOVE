package com.forensicppg.monitor.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import com.forensicppg.monitor.domain.CameraFrame
import com.forensicppg.monitor.ppg.PpgFrameExtractor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controlador principal de Camera2. Se encarga de:
 *
 *  - Seleccionar la mejor cámara trasera vía MultiCameraSelector.
 *  - Abrir la cámara y configurar una sesión con ImageReader YUV_420_888.
 *  - Aplicar control manual (exposure/ISO/frame-duration) y torch.
 *  - Emitir frames procesados como CameraFrame por un SharedFlow con drop-oldest.
 *
 * El controller es monohilo lógico: todo el IO ocurre en un HandlerThread
 * dedicado, y las llamadas públicas no bloquean el hilo principal.
 */
class CameraController(
    private val context: Context
) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val extractor = PpgFrameExtractor()
    private val frames = MutableSharedFlow<CameraFrame>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val captureResults = MutableSharedFlow<CaptureResultSummary>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var activeConfig: CameraSessionConfig? = null
    private var activeCaps: CameraCapabilities? = null
    private val executor = Executors.newSingleThreadExecutor()

    val frameFlow: Flow<CameraFrame> get() = frames
    val captureResultFlow: Flow<CaptureResultSummary> get() = captureResults

    fun currentConfig(): CameraSessionConfig? = activeConfig
    fun currentCapabilities(): CameraCapabilities? = activeCaps

    data class CaptureResultSummary(
        val timestampNs: Long,
        val exposureTimeNs: Long?,
        val iso: Int?,
        val frameDurationNs: Long?,
        val aeState: Int?,
        val awbState: Int?,
        val afState: Int?
    )

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun start(targetFps: Int = 30): CameraSessionConfig {
        check(hasCameraPermission()) { "Falta permiso CAMERA" }
        startBackgroundThread()
        val caps = MultiCameraSelector.selectBest(context)
            ?: throw IllegalStateException("No hay cámara trasera disponible")
        activeCaps = caps

        val selectedSize = chooseSize(caps)
        val fpsRange = chooseFpsRange(caps, targetFps)
        val reader = ImageReader.newInstance(selectedSize.width, selectedSize.height, ImageFormat.YUV_420_888, 4)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val ts = img.timestamp
                val frame = extractor.extract(
                    image = img,
                    cameraId = caps.cameraId,
                    timestampNs = ts,
                    exposureTimeNs = activeConfig?.manualExposureNs,
                    iso = activeConfig?.manualIso,
                    frameDurationNs = activeConfig?.manualFrameDurationNs
                )
                if (frame != null) frames.tryEmit(frame)
            } finally {
                img.close()
            }
        }, backgroundHandler)

        val cam = openCamera(caps.cameraId)
        device = cam

        val session = createSession(cam, reader)
        this.session = session

        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(reader.surface)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        val manualTarget = ManualExposureController.computeTarget(caps, targetFps)
        val manualApplied = ManualExposureController.apply(builder, caps, manualTarget)
        val torchApplied = TorchController.apply(builder, caps, enabled = true)

        val config = CameraSessionConfig(
            cameraId = caps.cameraId,
            physicalCameraId = null,
            previewSize = selectedSize,
            targetFpsRange = fpsRange.lower to fpsRange.upper,
            format = ImageFormat.YUV_420_888,
            torchEnabled = torchApplied,
            manualControlApplied = manualApplied,
            manualExposureNs = manualTarget?.exposureTimeNs,
            manualIso = manualTarget?.iso,
            manualFrameDurationNs = manualTarget?.frameDurationNs,
            aeLocked = caps.supportsAeLock,
            awbLocked = caps.supportsAwbLock
        )
        activeConfig = config

        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                captureResults.tryEmit(
                    CaptureResultSummary(
                        timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                        frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                        aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                        awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                        afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    )
                )
            }
        }, backgroundHandler)

        return config
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        session = null
        device = null
        imageReader = null
        activeConfig = null
        activeCaps = null
        stopBackgroundThread()
    }

    private fun chooseSize(caps: CameraCapabilities): Size {
        val preferred = caps.supportedYuvSizes
            .filter { it.width in 320..1280 && it.height in 240..960 }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (preferred.isNotEmpty()) return preferred[preferred.size / 2]
        return caps.supportedYuvSizes.firstOrNull() ?: Size(640, 480)
    }

    private fun chooseFpsRange(caps: CameraCapabilities, target: Int): Range<Int> {
        val ranges = caps.availableFpsRanges
        val exact = ranges.firstOrNull { it.lower == target && it.upper == target }
        if (exact != null) return exact
        val containsTarget = ranges.firstOrNull { it.lower <= target && it.upper >= target }
        if (containsTarget != null) return containsTarget
        val best = ranges.maxByOrNull { it.upper }
        return best ?: Range(15, 30)
    }

    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont: CancellableContinuation<CameraDevice> ->
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error: $error"))
                    }
                }, backgroundHandler)
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            }
        }

    private suspend fun createSession(
        cam: CameraDevice,
        reader: ImageReader
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("No se pudo configurar sesión"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    executor,
                    cb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(reader.surface), cb, backgroundHandler)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("ForensicPPG.CameraThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500) } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @Suppress("unused")
    private fun levelDesc(chars: CameraCharacteristics): String =
        CameraCapabilities.levelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: 0)

    companion object {
        private const val TAG = "ForensicCam"
        init { Log.i(TAG, "CameraController cargado") }
    }
}
