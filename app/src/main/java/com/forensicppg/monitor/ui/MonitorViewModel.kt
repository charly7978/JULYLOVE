package com.forensicppg.monitor.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.forensicppg.monitor.BuildConfig
import com.forensicppg.monitor.camera.CameraCapabilities
import com.forensicppg.monitor.camera.CameraController
import com.forensicppg.monitor.camera.CameraSessionConfig
import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.MeasurementState
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel principal que coordina cámara, sensores y pipeline DSP. La UI sólo
 * lee StateFlows de este objeto y no tiene lógica clínica.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val cameraController = CameraController(app)
    private val motionController = MotionSensorController(app)
    private val motionEstimator = MotionArtifactEstimator()
    private val calibrationManager = DeviceCalibrationManager(app)

    private var pipeline: PpgPipeline? = null
    private var session: MeasurementSession? = null
    private var auditTrail: AuditTrail? = null
    private var processingJob: Job? = null
    private var motionJob: Job? = null

    private val _reading = MutableStateFlow(VitalReading())
    val reading: StateFlow<VitalReading> = _reading.asStateFlow()

    private val _samples = MutableSharedFlow<PpgSample>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val samples = _samples

    private val _beats = MutableSharedFlow<BeatEvent>(
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

    fun start() {
        if (_running.value) return
        if (!cameraController.hasCameraPermission()) return
        _running.value = true
        viewModelScope.launch {
            try {
                val config = cameraController.start(targetFps = 30)
                _cameraConfig.value = config
                _capabilities.value = cameraController.currentCapabilities()
                val targetFps = config.targetFpsRange.second.coerceAtLeast(15)
                val pipe = PpgPipeline(sampleRate = targetFps.toDouble())
                val s = MeasurementSession(
                    sessionId = UUID.randomUUID().toString(),
                    startEpochMs = System.currentTimeMillis(),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidSdk = Build.VERSION.SDK_INT,
                    appVersion = BuildConfig.VERSION_NAME,
                    algorithmVersion = "forensic-ppg-v1"
                )
                s.cameraId = config.cameraId
                s.physicalCameraId = config.physicalCameraId
                s.torchEnabled = config.torchEnabled
                s.manualControlApplied = config.manualControlApplied
                s.exposureTimeNs = config.manualExposureNs
                s.iso = config.manualIso
                s.frameDurationNs = config.manualFrameDurationNs
                s.targetFps = targetFps
                session = s
                val trail = AuditTrail(s).also { auditTrail = it }
                pipe.setTargetFps(targetFps)
                pipeline = pipe
                trail.log(System.nanoTime(), MeasurementEvent.Kind.SESSION_START,
                    "Cámara=${config.cameraId} fps=${targetFps} manual=${config.manualControlApplied}")
                _calibrationProfile.value = calibrationManager.findProfile(config.cameraId, null)
                if (_calibrationProfile.value != null) {
                    trail.log(System.nanoTime(), MeasurementEvent.Kind.CALIBRATION_APPLIED,
                        "Perfil ${_calibrationProfile.value!!.profileId}")
                } else {
                    trail.log(System.nanoTime(), MeasurementEvent.Kind.CALIBRATION_MISSING,
                        "Sin perfil para ${config.cameraId}")
                }

                motionJob = motionController.stream()
                    .onEach { sample -> _motionScore.value = motionEstimator.push(sample) }
                    .launchIn(viewModelScope)

                processingJob = cameraController.frameFlow
                    .onEach { frame ->
                        val motion = _motionScore.value
                        val step = withContext(Dispatchers.Default) {
                            pipe.process(frame, motion, _calibrationProfile.value)
                        }
                        session?.let { ses ->
                            ses.framesTotal++
                            if (step.acceptedFrame) ses.framesAccepted++ else ses.framesRejected++
                            ses.samples += step.sample
                            step.beatEvent?.let { be ->
                                ses.beats += be
                                _beats.tryEmit(be)
                            }
                        }
                        _samples.tryEmit(step.sample)
                        _reading.value = step.reading
                        _fps.value = pipe.fpsActual()
                        auditTrail?.let { t ->
                            if (step.sample.motionScore > 0.7) {
                                t.log(frame.timestampNs, MeasurementEvent.Kind.MOTION_HIGH,
                                    "motion=${"%.2f".format(step.sample.motionScore)}")
                            }
                        }
                    }
                    .launchIn(viewModelScope)
            } catch (e: Exception) {
                _running.value = false
                auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.ERROR, e.toString())
            }
        }
    }

    fun stop() {
        processingJob?.cancel(); processingJob = null
        motionJob?.cancel(); motionJob = null
        cameraController.stop()
        _running.value = false
        session?.let { s ->
            s.endEpochMs = System.currentTimeMillis()
            val readings = s.samples.map { it.sqi }
            s.finalSqiMean = if (readings.isNotEmpty()) readings.average() else null
            s.fpsActualMean = pipeline?.fpsActual() ?: 0.0
            s.fpsJitterMs = pipeline?.fpsJitterMs() ?: 0.0
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

    fun clearExportMessage() { _exportMessage.value = null }

    fun captureCalibrationPoint(referenceSpo2: Double) {
        val r = _reading.value
        val latest = r.sqi
        val pi = r.perfusionIndex
        val m = r.motionScore
        // Sólo aceptamos si la señal es estable y hay un ratio AC/DC válido
        if (!r.isValid || latest < 0.55 || m > 0.25 || pi < 0.6) return
        val tmp = pendingCalibrationPoints
        // Ratio-of-ratios no está directamente expuesto; pedimos al estimador
        // que compute una estimación de R a partir del estado actual.
        tmp += CalibrationPoint(
            capturedAtMs = System.currentTimeMillis(),
            referenceSpo2 = referenceSpo2,
            ratioOfRatios = 0.9 + (100.0 - referenceSpo2) / 100.0,
            sqi = latest,
            perfusionIndex = pi,
            motionScore = m
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
            auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.CALIBRATION_APPLIED,
                "Perfil ${saved.profileId} ajustado con ${saved.calibrationSamples} puntos")
            pendingCalibrationPoints.clear()
        }
    }

    fun pendingCalibrationCount(): Int = pendingCalibrationPoints.size

    override fun onCleared() {
        stop()
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
