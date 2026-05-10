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
import com.forensicppg.monitor.forensic.RoiAuditEvent
import com.forensicppg.monitor.pipeline.PpgPipeline
import com.forensicppg.monitor.ppg.CalibrationPoint
import com.forensicppg.monitor.ppg.CalibrationProfile
import com.forensicppg.monitor.ppg.DeviceCalibrationManager
import com.forensicppg.monitor.ppg.SensorZloStore
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
import java.util.concurrent.atomic.AtomicBoolean

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val camera = Camera2PpgController(application)
    private val motionController = MotionSensorController(application)
    private val motionEstimator = MotionArtifactEstimator()
    private val calibrationManager = DeviceCalibrationManager(application)
    private val sensorZloStore = SensorZloStore(application)
    private var beatFeedback = BeatFeedbackController(application)

    private var pipeline: PpgPipeline? = null
    private var session: MeasurementSession? = null
    private var auditTrail: AuditTrail? = null
    private var processingJob: Job? = null
    private var motionJob: Job? = null

    private val zloHarvestActive = AtomicBoolean(false)
    private val zloHarvestLock = Any()
    private val zloHarvestRgb = mutableListOf<Triple<Double, Double, Double>>()

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

    private val _sensorZloStatus = MutableStateFlow("")
    val sensorZloStatus: StateFlow<String> = _sensorZloStatus.asStateFlow()

    private val _roiAuditTail = MutableStateFlow<List<RoiAuditEvent>>(emptyList())
    val roiAuditTail: StateFlow<List<RoiAuditEvent>> = _roiAuditTail.asStateFlow()

    private var lastRoiAuditEventCount = 0

    fun setFeedbackAudio(enabled: Boolean) {
        _feedbackAudioOn.value = enabled
    }

    fun setFeedbackVibration(enabled: Boolean) {
        _feedbackVibrationOn.value = enabled
    }

    private fun applyResolvedSensorZlo(cameraId: String) {
        val dm = calibrationManager.currentDeviceModel()
        val persisted = sensorZloStore.load(dm, cameraId)
        when {
            persisted != null -> {
                camera.configureSensorZlo(persisted.zloR, persisted.zloG, persisted.zloB, "persistido")
                _sensorZloStatus.value =
                    "ZLO persistido (${if (persisted.captured) "captura oscura" else "archivo"}) " +
                        "mediana R=${"%.2f".format(persisted.zloR)} G=${"%.2f".format(persisted.zloG)} " +
                        "B=${"%.2f".format(persisted.zloB)} [n=${persisted.framesUsed}]"
            }

            else -> {
                camera.configureSensorZlo(0.0, 0.0, 0.0, "sin_zlo_use_captura_oscura")
                _sensorZloStatus.value =
                    "Sin ZLO guardado — pulsá «Captura ZLO oscuro» con tapa opaca (LED+lente) " +
                        "o los offsets literatura **no** se usan en runtime."
            }
        }
    }

    /** Inicia colección (~2 s quietos): tapón opaco mismo flash táctico, sin pulso fisiológico. */
    fun startSensorZloDarkHarvest() {
        if (!_running.value) return
        zloHarvestActive.set(true)
        synchronized(zloHarvestLock) { zloHarvestRgb.clear() }
        _sensorZloStatus.value =
            "ZLO oscuro: mantenga lámina opaca FIRME sobre LED+lente sin movimiento; esperando muestras…"
        auditTrail?.log(System.nanoTime(), MeasurementEvent.Kind.ZLO_CAPTURE_START, "ZLO_CAPTURE_START")
    }

    fun abortSensorZloDarkHarvest() {
        zloHarvestActive.set(false)
        synchronized(zloHarvestLock) { zloHarvestRgb.clear() }
        _sensorZloStatus.value = "Captura ZLO cancelada."
    }

    /** Borra ZLO persistido; runtime vuelve a offsets nulos hasta nueva captura. */
    fun clearPersistedSensorZlo() {
        val cid = _cameraConfig.value?.cameraId ?: return
        sensorZloStore.clear(calibrationManager.currentDeviceModel(), cid)
        applyResolvedSensorZlo(cid)
        snapshotSensorZloToSession()
    }

    fun start() {
        if (_running.value) return
        if (!camera.hasCameraPermission()) return

        beatFeedback.releaseQuietly()
        beatFeedback = BeatFeedbackController(getApplication())

        _running.value = true
        lastRoiAuditEventCount = 0
        _roiAuditTail.value = emptyList()
        viewModelScope.launch {
            try {
                val cfg = camera.start(targetFps = 30)
                _cameraConfig.value = cfg
                _capabilities.value = camera.currentCapabilities()
                val tf = cfg.targetFpsRange.second.coerceAtLeast(15)

                applyResolvedSensorZlo(cfg.cameraId)

                val s = MeasurementSession(
                    sessionId = UUID.randomUUID().toString(),
                    startEpochMs = System.currentTimeMillis(),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidSdk = Build.VERSION.SDK_INT,
                    appVersion = BuildConfig.VERSION_NAME,
                    algorithmVersion = "ppg-monitor-v3"
                )
                s.cameraId = cfg.cameraId
                s.physicalCameraId = cfg.physicalCameraId
                s.torchEnabled = cfg.torchEnabled
                s.manualControlApplied = cfg.manualControlApplied
                s.exposureTimeNs = cfg.manualExposureNs
                s.iso = cfg.manualIso
                s.frameDurationNs = cfg.manualFrameDurationNs
                s.targetFps = tf
                s.ispAcquisitionSummary = cfg.ispAcquisitionSummary
                s.roiGeometryPresetId = "auto_centroid"

                session = s
                snapshotSensorZloToSession()
                val trail = AuditTrail(s).also { auditTrail = it }
                trail.log(
                    System.nanoTime(), MeasurementEvent.Kind.SESSION_START,
                    "camera=${cfg.cameraId} fps=$tf manual=${cfg.manualControlApplied} isp=${cfg.ispAcquisitionSummary}"
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
                        harvestSensorZloIfNeeded(raw)

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

                            val roiN = synchronized(ses.roiAuditEvents) { ses.roiAuditEvents.size }
                            if (roiN != lastRoiAuditEventCount) {
                                lastRoiAuditEventCount = roiN
                                _roiAuditTail.value = synchronized(ses.roiAuditEvents) {
                                    ses.roiAuditEvents.takeLast(16)
                                }
                            }

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

    private fun harvestSensorZloIfNeeded(raw: PpgSample) {
        if (!zloHarvestActive.get()) return
        if (raw.clippingHighRatio > 0.105 || raw.clippingLowRatio > 0.31) return
        if (raw.motionScoreOptical > 0.069) return
        synchronized(zloHarvestLock) {
            zloHarvestRgb +=
                Triple(
                    raw.roiMeanPreZloRed,
                    raw.roiMeanPreZloGreen,
                    raw.roiMeanPreZloBlue
                )
            if (zloHarvestRgb.size > 320) zloHarvestRgb.removeAt(0)
            val nReq = 56
            if (zloHarvestRgb.size >= nReq) finalizeZloHarvest(nReq)
        }
    }

    private fun finalizeZloHarvest(used: Int) {
        synchronized(zloHarvestLock) {
            val chunk = zloHarvestRgb.takeLast(used)
            val mr = median(chunk.map { it.first })
            val mg = median(chunk.map { it.second })
            val mb = median(chunk.map { it.third })
            val cid = _cameraConfig.value?.cameraId ?: return
            sensorZloStore.save(
                deviceModel = calibrationManager.currentDeviceModel(),
                cameraId = cid,
                r = mr,
                g = mg,
                b = mb,
                framesUsed = used,
                fromInstrumentedCapture = true
            )
            camera.configureSensorZlo(mr, mg, mb, "captura_oscura_campo")
            zloHarvestActive.set(false)
            zloHarvestRgb.clear()
            _sensorZloStatus.value =
                "ZLO médiana guardada (${"%.2f".format(mr)}, ${"%.2f".format(mg)}, ${"%.2f".format(mb)}) " +
                    "sobre $used fotogramas estáticos oscuros."
            auditTrail?.log(
                System.nanoTime(),
                MeasurementEvent.Kind.ZLO_CAPTURE_OK,
                "ZLO_CAPTURE_OK R=${mr} G=${mg} B=${mb} n=$used"
            )
            snapshotSensorZloToSession()
        }
    }

    private fun snapshotSensorZloToSession() {
        val z = camera.currentSensorZlo()
        session?.apply {
            sensorZloR = z.r.takeIf { it >= 1e-6 }
            sensorZloG = z.g.takeIf { it >= 1e-6 }
            sensorZloB = z.b.takeIf { it >= 1e-6 }
            zloSourceNote = z.sourceBrief.take(96)
        }
    }

    private fun median(vals: List<Double>): Double {
        if (vals.isEmpty()) return 0.0
        val s = vals.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        motionJob?.cancel()
        motionJob = null
        zloHarvestActive.set(false)

        val jitterSnap = camera.frameJitterEmaMs()
        val fpsSnap = camera.measuredFpsEmaHz()

        camera.stop()
        pipeline = null

        _running.value = false
        session?.let { s ->
            synchronized(s.roiAuditEvents) {
                _roiAuditTail.value = s.roiAuditEvents.takeLast(16)
                lastRoiAuditEventCount = s.roiAuditEvents.size
            }
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
        } ?: run {
            _roiAuditTail.value = emptyList()
            lastRoiAuditEventCount = 0
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
        if (r.sqi < 0.52 || r.motionScore > 0.18 || r.perfusionIndex < 52.9) return
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
