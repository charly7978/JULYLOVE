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
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import com.forensicppg.monitor.domain.ExposureDiagnostics
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.ppg.AppliedSensorZlo
import com.forensicppg.monitor.ppg.PpgFrameAnalyzer
import com.forensicppg.monitor.ppg.RoiGeometryPreset
import com.forensicppg.monitor.ppg.PpgAcquisitionTuning.FRAME_DROP_GAP_MULTIPLIER
import com.forensicppg.monitor.ppg.PpgAcquisitionTuning.IMAGE_READER_MAX_IMAGES
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Camera2 + YUV + torch; FPS/drops/jitter reales ([Image.timestamp] efectivo tras adquisición). */
class Camera2PpgController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val analyzer = PpgFrameAnalyzer()
    private val frames = MutableSharedFlow<PpgSample>(
        replay = 0, extraBufferCapacity = 96, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var activeConfig: CameraSessionConfig? = null
    private var activeCaps: CameraCapabilities? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val drops = AtomicLong(0)

    private var liveExposureNs: Long? = null
    private var liveIso: Int? = null
    private var liveFrameDurationNs: Long? = null

    private var prevImageTs: Long? = null
    private var fpsEmaHz: Double = 0.0
    private var jitterEmaMs: Double = 0.0

    private var nominalFrameNs: Long = 33_333_333L

    val frameFlow: Flow<PpgSample> get() = frames

    fun currentConfig(): CameraSessionConfig? = activeConfig
    fun currentCapabilities(): CameraCapabilities? = activeCaps

    fun frameDropCount(): Long = drops.get()
    fun measuredFpsEmaHz(): Double = fpsEmaHz
    fun frameJitterEmaMs(): Double = jitterEmaMs

    fun configureSensorZlo(red: Double, green: Double, blue: Double, sourceBrief: String) {
        analyzer.configureSensorZlo(red, green, blue, sourceBrief)
    }

    fun currentSensorZlo(): AppliedSensorZlo = analyzer.currentSensorZlo()

    fun configureRoiGeometryPreset(preset: RoiGeometryPreset) {
        analyzer.configureRoiGeometryPreset(preset)
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun resetStats() {
        drops.set(0)
        prevImageTs = null
        fpsEmaHz = 0.0
        jitterEmaMs = 0.0
    }

    private fun chooseSize(caps: CameraCapabilities): Size {
        val pref = caps.supportedYuvSizes
            .filter { it.width in 320..1280 && it.height in 240..960 }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (pref.isNotEmpty()) return pref[pref.size / 2]
        return caps.supportedYuvSizes.firstOrNull() ?: Size(640, 480)
    }

    private fun chooseFpsRange(caps: CameraCapabilities, target: Int): Range<Int> {
        val ranges = caps.availableFpsRanges
        ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        ranges.firstOrNull { it.lower <= target && it.upper >= target }?.let { return it }
        return ranges.maxByOrNull { it.upper } ?: Range(15, 30)
    }

    private fun startBg() {
        if (bgThread != null) return
        bgThread = HandlerThread("ppg-camera2").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBg() {
        bgThread?.quitSafely()
        try {
            bgThread?.join(500)
        } catch (_: InterruptedException) {
        }
        bgThread = null
        bgHandler = null
    }

    @SuppressLint("MissingPermission")
    suspend fun start(targetFps: Int = 30): CameraSessionConfig {
        check(hasCameraPermission()) { "Permiso CAMERA" }
        resetStats()
        startBg()

        val caps = MultiCameraSelector.selectBest(context)
            ?: throw IllegalStateException("Sin cámara trasera")

        activeCaps = caps
        nominalFrameNs = (1_000_000_000L / targetFps.coerceAtLeast(15)).coerceIn(8_333_333L, 66_666_666L)

        val size = chooseSize(caps)
        val fpsRange = chooseFpsRange(caps, targetFps)
        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            IMAGE_READER_MAX_IMAGES
        )
        imageReader = reader

        val cam = openCameraSuspended(caps.cameraId)
        device = cam

        val session = suspendCreateSession(cam, reader)
        captureSession = session

        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(reader.surface)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        val manualTarget = ManualExposureController.computeTarget(caps, targetFps)
        val manualApplied = ManualExposureController.apply(builder, caps, manualTarget)
        val torchOn = TorchController.apply(builder, caps, enabled = true)

        val characteristics =
            runCatching { cameraManager.getCameraCharacteristics(caps.cameraId) }.getOrNull()
        val ispSummary =
            if (characteristics != null) {
                CapturePipelineTuner.applyBestEffort(characteristics, builder)
            } else {
                "isp_sin_characteristics"
            }

        liveExposureNs = manualTarget?.exposureTimeNs
        liveIso = manualTarget?.iso
        liveFrameDurationNs = manualTarget?.frameDurationNs

        activeConfig = CameraSessionConfig(
            cameraId = caps.cameraId,
            physicalCameraId = null,
            previewSize = size,
            targetFpsRange = fpsRange.lower to fpsRange.upper,
            format = ImageFormat.YUV_420_888,
            torchEnabled = torchOn,
            manualControlApplied = manualApplied,
            manualExposureNs = manualTarget?.exposureTimeNs,
            manualIso = manualTarget?.iso,
            manualFrameDurationNs = manualTarget?.frameDurationNs,
            aeLocked = caps.supportsAeLock,
            awbLocked = caps.supportsAwbLock,
            ispAcquisitionSummary = ispSummary
        )

        val fpsChosen = fpsRange.upper.coerceAtLeast(fpsRange.lower).coerceAtLeast(15)
        nominalFrameNs = (
            activeConfig!!.manualFrameDurationNs
                ?: (1_000_000_000L / fpsChosen).coerceIn(12_500_000L, 166_666_666L)
            ).coerceIn(8_333_333L, 166_666_666L)

        reader.setOnImageAvailableListener({ ir ->
            val cfg = activeConfig ?: return@setOnImageAvailableListener
            val hwNote =
                if (!cfg.manualControlApplied) "Control sensor manual incompleto (AE/ISO en modo degradado)"
                else null
            while (true) {
                val img = try {
                    ir.acquireNextImage()
                } catch (_: IllegalStateException) {
                    break
                } catch (_: Throwable) {
                    break
                }
                try {
                    val ts = img.timestamp
                    prevImageTs?.let { p ->
                        val gapMs = abs(ts - p) / 1_000_000.0
                        jitterEmaMs = if (jitterEmaMs == 0.0) gapMs else 0.91 * jitterEmaMs + 0.09 * gapMs
                        val instFps = 1000.0 / gapMs.coerceAtLeast(0.52)
                        fpsEmaHz = if (fpsEmaHz == 0.0) instFps else 0.90 * fpsEmaHz + 0.10 * instFps
                        val nominalMs = nominalFrameNs / 1_000_000.0 * FRAME_DROP_GAP_MULTIPLIER
                        if (gapMs > nominalMs.coerceAtLeast(18.5)) drops.incrementAndGet()
                    }
                    prevImageTs = ts

                    val diag = ExposureDiagnostics(
                        exposureTimeNs = liveExposureNs ?: cfg.manualExposureNs,
                        iso = liveIso ?: cfg.manualIso,
                        frameDurationNs = liveFrameDurationNs ?: cfg.manualFrameDurationNs,
                        torchEnabled = cfg.torchEnabled,
                        hardwareLimitNote = hwNote,
                        aeLocked = cfg.aeLocked,
                        awbLocked = cfg.awbLocked,
                        ispAcquisitionSummary = cfg.ispAcquisitionSummary
                    )

                    analyzer.analyze(img, SystemClock.elapsedRealtimeNanos(), diag)?.let {
                        frames.tryEmit(it)
                    }
                } finally {
                    img.close()
                }
            }
        }, bgHandler)

        session.setRepeatingRequest(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    liveExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: liveExposureNs
                    liveIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: liveIso
                    liveFrameDurationNs =
                        result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: liveFrameDurationNs
                }
            },
            bgHandler
        )

        return activeConfig!!
    }

    fun stop() {
        resetStats()
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        device = null
        imageReader = null
        activeConfig = null
        activeCaps = null
        stopBg()
    }

    private suspend fun openCameraSuspended(id: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(
                    id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) {
                            if (cont.isActive) cont.resume(c)
                        }

                        override fun onDisconnected(c: CameraDevice) {
                            c.close()
                            if (cont.isActive)
                                cont.resumeWithException(IllegalStateException("Cámara desconectada"))
                        }

                        override fun onError(c: CameraDevice, error: Int) {
                            c.close()
                            if (cont.isActive)
                                cont.resumeWithException(IllegalStateException("camera err $error"))
                        }
                    },
                    bgHandler
                )
            } catch (se: SecurityException) {
                cont.resumeWithException(se)
            }
        }

    private suspend fun suspendCreateSession(cam: CameraDevice, reader: ImageReader): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(s)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive)
                        cont.resumeWithException(IllegalStateException("No se configuró sesión"))
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
                cam.createCaptureSession(listOf(reader.surface), cb, bgHandler)
            }
        }
}
