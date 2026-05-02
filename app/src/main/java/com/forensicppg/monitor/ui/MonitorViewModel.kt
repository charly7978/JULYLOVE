package com.forensicppg.monitor.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.forensicppg.monitor.BuildConfig
import com.forensicppg.monitor.camera.Camera2PpgController
import com.forensicppg.monitor.camera.CameraCapabilities
import com.forensicppg.monitor.camera.CameraSessionConfig
import com.forensicppg.monitor.domain.ConfirmedBeat
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.VitalReading
import com.forensicppg.monitor.forensic.AuditTrail
import com.forensicppg.monitor.forensic.MeasurementEvent
import com.forensicppg.monitor.forensic.MeasurementSession
import com.forensicppg.monitor.forensic.SessionExporter
import com.forensicppg.monitor.pipeline.PpgPipeline
import com.forensicppg.monitor.ppg.CalibrationPoint
import com.forensicppg.monitor.ppg.CalibrationProfile
import com.forensicppg.monitor.ppg.DeviceCalibrationManager
import com.forensicppg.monitor.sensors.MotionArtifactEstimator
import com.forensicppg.monitor.sensors.MotionSensorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val camera = Camera2PpgController(application)
    private val motionController = MotionSensorController(application)
    private val motionEstimator = MotionArtifactEstimator()
    private val calibrationManager = DeviceCalibrationManager(application)
    private var beatFeedback = BeatFeedbackController(application)

    private var pipeline: PpgPipeline? = null
    private var session: MeasurementSession? = null
    private var auditTrail: AuditTrail? = null
    private var processingJob: Job? = null
    private var motionJob: Job? = null

    private val _reading = MutableStateFlow(VitalReading())
    val reading: StateFlow<VitalReading> = _reading.asStateFlow()

    private val _samples = MutableSharedFlow<PpgSample>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val samples = _samples

    private val _beats = MutableSharedFlow<ConfirmedBeat>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val beats = _beats

    private val _cameraConfig = MutableStateFlow<CameraSessionConfig?>(null)
    val cameraConfig: StateFlow<CameraSessionConfig?> = _cameraConfig.asStateFlow()

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private val _motionScore = MutableStateFlow(0.0)
    val motionScore: StateFlow<Double> = _motionScore.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    private val _calibrationProfile = MutableStateFlow<CalibrationProfile?>(null)
    val calibrationProfile: StateFlow<CalibrationProfile?> = _calibrationProfile.asStateFlow()

    private val pendingCalibrationPoints = mutableListOf<CalibrationPoint>()

    private val _feedbackAudioOn = MutableStateFlow(true)
    val feedbackAudioOn: StateFlow<Boolean> = _feedbackAudioOn.asStateFlow()

    private val _feedbackVibrationOn = MutableStateFlow(true)
    val feedbackVibrationOn: StateFlow<Boolean> = _feedbackVibrationOn.asStateFlow()

    fun setFeedbackAudio(enabled: Boolean) {
        _feedbackAudioOn.value = enabled
    }

    fun setFeedbackVibration(enabled: Boolean) {
        _feedbackVibrationOn.value = enabled
    }

    fun start() {
        if (_running.value) return
        if (!camera.hasCameraPermission()) return

        beatFeedback.releaseQuietly()
        beatFeedback = BeatFeedbackController(getApplication())

        _running.value = true
        viewModelScope.launch {
            try {
                val cfg = camera.start(targetFps = 30)
                _cameraConfig.value = cfg
                _capabilities.value = camera.currentCapabilities()
                val tf = cfg.targetFpsRange.second.coerceAtLeast(15)

                val s = MeasurementSession(
                    sessionId = UUID.randomUUID().toString(),
                    startEpochMs = System.currentTimeMillis(),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidSdk = Build.VERSION.SDK_INT,
                    appVersion = BuildConfig.VERSION_NAME,
                    algorithmVersion = "ppg-monitor-v2"
                )
                s.cameraId = cfg.cameraId
                s.physicalCameraId = cfg.physicalCameraId
                s.torchEnabled = cfg.torchEnabled
                s.manualControlApplied = cfg.manualControlApplied
                s.exposureTimeNs = cfg.manualExposureNs
                s.iso = cfg.manualIso
                s.frameDurationNs = cfg.manualFrameDurationNs
                s.targetFps = tf

                session = s
                val trail = AuditTrail(s).also { auditTrail = it }
                trail.log(
                    System.nanoTime(), MeasurementEvent.Kind.SESSION_START,
                    "camera=${cfg.cameraId} fps=$tf manual=${cfg.manualControlApplied}"
                )

                val pipe = PpgPipeline(sampleRateHz = tf.toDouble(), auditTrail = trail)
                pipe.reset()
                pipeline = pipe

                _calibrationProfile.value = calibrationManager.findProfile(cfg.cameraId, null)

                motionJob = motionController.stream()
                    .onEach { sample -> _motionScore.value = motionEstimator.push(sample) }
                    .launchIn(viewModelScope)

                processingJob = camera.frameFlow
                    .onEach { raw ->
                        val motion = _motionScore.value
                        val acq = PpgPipeline.AcquisitionMetrics(
                            frameDrops = camera.frameDropCount(),
                            measuredFpsHz = camera.measuredFpsEmaHz(),
                            jitterMs = camera.frameJitterEmaMs(),
                            torchEnabled = cfg.torchEnabled,
                            manualSensorApplied = cfg.manualControlApplied,
                            targetFpsHint = tf
                        )
                        val step = withContext(Dispatchers.Default) {
                            pipe.process(raw, motion, _calibrationProfile.value, acq)
                        }

                        session?.let { ses ->
                            ses.framesTotal++
                            ses.framesAccepted++
                            ses.samples += step.sample

                            step.confirmedBeat?.let { b ->
                                ses.beats += b
                                _beats.tryEmit(b)
                                viewModelScope.launch(Dispatchers.Main) {
                                    beatFeedback.onConfirmedBeat(
                                        b,
                                        _feedbackAudioOn.value,
                                        _feedbackVibrationOn.value
                                    )
                                }
                            }
                        }

                        _samples.tryEmit(step.sample)
                        _reading.value = step.reading
                        _fps.value = camera.measuredFpsEmaHz()
                    }
                    .launchIn(viewModelScope)
            } catch (e: Exception) {
                _running.value = false
                auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.ERROR, e.toString())
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        motionJob?.cancel()
        motionJob = null

        val jitterSnap = camera.frameJitterEmaMs()
        val fpsSnap = camera.measuredFpsEmaHz()

        camera.stop()
        pipeline = null

        _running.value = false
        session?.let { s ->
            s.endEpochMs = System.currentTimeMillis()
            val readings = s.samples.map { it.sqi }
            s.finalSqiMean = if (readings.isNotEmpty()) readings.average() else null
            if (fpsSnap > 8.49) {
                s.fpsActualMean = fpsSnap
            } else if (_fps.value > 8.49) {
                s.fpsActualMean = _fps.value
            }
            s.fpsJitterMs = jitterSnap
            auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.SESSION_END,
                "samples=${s.samples.size} beats=${s.beats.size}")
        }
    }

    fun exportCurrentSession() {
        val ses = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = SessionExporter(getApplication()).export(ses)
                auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.EXPORT,
                    "dir=${out.rootDir.absolutePath} hash=${out.integrityHashHex}")
                _exportMessage.value = "Exportado a ${out.rootDir.absolutePath}\nSHA-256=${out.integrityHashHex}"
            } catch (e: Exception) {
                _exportMessage.value = "Error exportando: ${e.message}"
            }
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }

    fun captureCalibrationPoint(referenceSpo2: Double) {
        val pipe = pipeline ?: return
        val r = _reading.value
        if (referenceSpo2 !in 72.5..104.9) return
        val ratio =
            pipe.spo2Estimator.snapshotLastRatio()?.takeIf { it in 0.28..9.49 } ?: return
        if (r.validityState.ordinal < PpgValidityState.PPG_VALID.ordinal) return
        if (r.sqi < 0.52 || r.motionScore > 0.28 || r.perfusionIndex < 52.9) return
        pendingCalibrationPoints += CalibrationPoint(
            capturedAtMs = System.currentTimeMillis(),
            referenceSpo2 = referenceSpo2,
            ratioOfRatios = ratio,
            sqi = r.sqi,
            perfusionIndex = (r.perfusionIndex.coerceAtMost(122.93) / 122.93).coerceAtLeast(0.32),
            motionScore = r.motionScore
        )
    }

    fun applyCalibration(notes: String = "") {
        val cfg = _cameraConfig.value ?: return
        val saved = calibrationManager.fit(
            cameraId = cfg.cameraId,
            physicalId = cfg.physicalCameraId,
            exposureTimeNs = cfg.manualExposureNs,
            iso = cfg.manualIso,
            frameDurationNs = cfg.manualFrameDurationNs,
            torchIntensity = null,
            points = pendingCalibrationPoints.toList(),
            notes = notes
        )
        if (saved != null) {
            _calibrationProfile.value = saved
            session?.calibrationProfileId = saved.profileId
            auditTrail?.log(
                System.nanoTime(), MeasurementEvent.Kind.CALIBRATION_APPLIED,
                "perfil=${saved.profileId} pts=${saved.calibrationSamples}"
            )
            pendingCalibrationPoints.clear()
        }
    }

    fun pendingCalibrationCount(): Int = pendingCalibrationPoints.size

    override fun onCleared() {
        stop()
        beatFeedback.releaseQuietly()
        super.onCleared()
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MonitorViewModel(app) as T
                }
            }
    }
}
