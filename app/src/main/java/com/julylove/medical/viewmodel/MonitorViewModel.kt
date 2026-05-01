package com.julylove.medical.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julylove.medical.camera.Camera2PpgController
import com.julylove.medical.signal.*
import com.julylove.medical.data.*
import com.julylove.medical.haptics.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

class MonitorViewModel(
    private val context: Context,
    private val cameraController: Camera2PpgController
) : ViewModel(), Camera2PpgController.OnFrameAvailableListener {

    data class MonitorState(
        val bpm: Int = 0,
        val spo2: Float = 0f,
        val spo2Status: String = "ESPERANDO SEÑAL",
        val validityState: PpgValidityState = PpgValidityState.MEASURING_RAW_OPTICAL,
        val rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
        val ppgSamples: List<PPGSample> = emptyList(),
        val isMeasuring: Boolean = false,
        val technicalData: TechnicalData = TechnicalData(),
        val beepEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true
    )

    data class TechnicalData(
        val fps: Int = 0,
        val rmssd: Double = 0.0,
        val pnn50: Double = 0.0,
        val cv: Double = 0.0,
        val shannonEntropyBits: Double = 0.0,
        val sampleEntropy: Double? = null,
        val signalConfidence: Float = 0f,
        val redDC: Float = 0f,
        val greenDC: Float = 0f,
        val motionIntensity: Float = 0f
    )

    private val _uiState = MutableStateFlow(MonitorState())
    val uiState = _uiState.asStateFlow()

    private val ppgBuffer = mutableListOf<PPGSample>()
    private val ppiHistory = mutableListOf<Long>()
    private val maxBufferSize = 400 // ~6-7 seconds of visualization

    private val opticalRedWindow = mutableListOf<Float>()
    private val opticalGreenWindow = mutableListOf<Float>()
    private val opticalBlueWindow = mutableListOf<Float>()
    private val opticalWindowCap = 64
    
    private val exportService = ExportService(context)

    // Pipeline Components
    private val physiologyClassifier = PpgPhysiologyClassifier()
    private val feedbackController = BeatFeedbackController(context)
    private val detrender = DetrendingFilter(30)
    private val butterworth = ButterworthBandpass(60f)
    private val smoother = SavitzkyGolayFilter()
    private val peakDetector = PeakDetectionEngine(60f)
    private val rhythmEngine = RhythmAnalysisEngine()
    private val spo2Estimator = Spo2Estimator()
    private val motionDetector = MotionArtifactDetector(context)

    private var lastPeakTime = 0L
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var sessionId = ""

    init {
        cameraController.listener = this
        spo2Estimator.setProfileForDevice(android.os.Build.MODEL)
    }

    fun toggleMeasurement() {
        if (_uiState.value.isMeasuring) {
            stopMeasurement()
        } else {
            startMeasurement()
        }
    }

    private fun startMeasurement() {
        sessionId = UUID.randomUUID().toString().take(8).uppercase()
        ppgBuffer.clear()
        ppiHistory.clear()
        opticalRedWindow.clear()
        opticalGreenWindow.clear()
        opticalBlueWindow.clear()
        peakDetector.reset()
        cameraController.start()
        motionDetector.start()
        _uiState.value = _uiState.value.copy(isMeasuring = true, bpm = 0, spo2 = 0f)
    }

    private fun stopMeasurement() {
        cameraController.stop()
        motionDetector.stop()
        
        // Save Session
        if (ppgBuffer.isNotEmpty()) {
            val td = _uiState.value.technicalData
            val session = MeasurementSession(
                id = sessionId,
                timestamp = System.currentTimeMillis(),
                deviceModel = android.os.Build.MODEL,
                averageBpm = if (ppiHistory.isNotEmpty()) (60000 / ppiHistory.average()).toInt() else 0,
                averageSpo2 = _uiState.value.spo2,
                finalRhythmStatus = _uiState.value.rhythmStatus,
                finalRmssd = td.rmssd,
                finalShannonEntropyBits = td.shannonEntropyBits,
                finalSampleEntropy = td.sampleEntropy,
                finalCvPercent = td.cv,
                motionMeanIntensity = td.motionIntensity,
                samples = ppgBuffer.toList()
            )
            viewModelScope.launch {
                exportService.exportSession(session)
            }
        }
        
        _uiState.value = _uiState.value.copy(isMeasuring = false)
    }

    override fun onFrame(red: Float, green: Float, blue: Float, timestamp: Long) {
        val isMoving = motionDetector.isMoving

        pushOpticalWindow(red, green, blue)

        // 1. Signal Processing (dominio temporal antes de clasificar)
        val rawValue = green
        val detrended = detrender.filter(rawValue)
        val bandpassed = butterworth.filter(detrended)
        val filtered = smoother.filter(bandpassed)

        // 2. Physiology Validation (Gatekeeper)
        val validityState = physiologyClassifier.classify(
            filteredValue = filtered,
            redMean = red,
            greenMean = green,
            blueMean = blue,
            isMoving = isMoving
        )

        val perfusionProxy = ((kotlin.math.abs(filtered) + 1e-3f) / red.coerceAtLeast(1f)) * 100f
        val sqiFrame = if (validityState == PpgValidityState.PPG_VALID) {
            SignalQualityIndex.fromOpticalStreams(
                redHistory = opticalRedWindow,
                greenHistory = opticalGreenWindow,
                blueHistory = opticalBlueWindow,
                motionIntensity = motionDetector.motionIntensity,
                perfusionRatio = perfusionProxy.coerceIn(0.01f, 20f)
            )
        } else 0f

        // 3. Peak Detection & Feedback (ONLY IF VALID)
        val isPeak = if (validityState == PpgValidityState.PPG_VALID) {
            val detected = peakDetector.process(filtered, timestamp)
            if (detected) {
                feedbackController.trigger()
            }
            detected
        } else {
            peakDetector.reset()
            false
        }

        // 4. Clinical Metrics (ONLY IF VALID)
        var currentBpm = _uiState.value.bpm
        var currentRhythm = _uiState.value.rhythmStatus
        var currentRmssd = _uiState.value.technicalData.rmssd
        var currentPnn50 = _uiState.value.technicalData.pnn50
        var currentCv = _uiState.value.technicalData.cv
        var currentH = _uiState.value.technicalData.shannonEntropyBits
        var currentSampEn = _uiState.value.technicalData.sampleEntropy
        var currentSpo2 = _uiState.value.spo2
        var currentSpo2Status = _uiState.value.spo2Status

        if (validityState == PpgValidityState.PPG_VALID) {
            if (isPeak) {
                if (lastPeakTime != 0L) {
                    val ppiNs = timestamp - lastPeakTime
                    val ppiMs = ppiNs / 1_000_000L
                    if (ppiMs in 300..2000) {
                        ppiHistory.add(ppiMs)
                        if (ppiHistory.size > 60) ppiHistory.removeAt(0)

                        val detailed = rhythmEngine.addIntervalDetailed(ppiMs, timestamp)
                        currentBpm = detailed.bpm
                        currentRhythm = detailed.status
                        currentRmssd = detailed.rmssd
                        currentPnn50 = detailed.pnn50
                        currentCv = detailed.cv
                        currentH = detailed.shannonEntropyBits
                        currentSampEn = detailed.sampleEntropy
                    }
                }
                lastPeakTime = timestamp
            }

            val spo2Result = spo2Estimator.process(red, green, blue, sqiFrame)
            currentSpo2 = spo2Result.spo2
            currentSpo2Status = spo2Result.status
        } else {
            // Reset filters and metrics based on severity
            if (validityState == PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL || 
                validityState == PpgValidityState.LOW_PERFUSION) {
                currentBpm = 0
                currentSpo2 = 0f
                currentRmssd = 0.0
                currentPnn50 = 0.0
                currentCv = 0.0
                currentH = 0.0
                currentSampEn = null
                currentSpo2Status = if (validityState == PpgValidityState.LOW_PERFUSION) "COLOQUE EL DEDO" else "SIN SEÑAL FISIOLÓGICA"
                ppiHistory.clear()
                rhythmEngine.reset()
                detrender.reset()
                butterworth.reset()
                smoother.reset()
                peakDetector.reset()
            }
        }

        // 5. Update Waveform Buffer
        val sample = PPGSample(
            timestamp = timestamp,
            redMean = red,
            greenMean = green,
            blueMean = blue,
            filteredValue = filtered,
            isPeak = isPeak,
            sqi = sqiFrame
        )

        ppgBuffer.add(sample)
        if (ppgBuffer.size > maxBufferSize) ppgBuffer.removeAt(0)

        // 6. Technical / FPS
        var currentFps = _uiState.value.technicalData.fps
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTimestamp = now
            butterworth.updateCoefficients(currentFps.toFloat())
        }

        // 7. Atomic State Update
        _uiState.value = _uiState.value.copy(
            bpm = currentBpm,
            spo2 = currentSpo2,
            spo2Status = currentSpo2Status,
            validityState = validityState,
            rhythmStatus = currentRhythm,
            ppgSamples = ppgBuffer.toList(),
            technicalData = TechnicalData(
                fps = currentFps,
                rmssd = currentRmssd,
                pnn50 = currentPnn50,
                cv = currentCv,
                shannonEntropyBits = currentH,
                sampleEntropy = currentSampEn,
                signalConfidence = if (validityState == PpgValidityState.PPG_VALID) sqiFrame else 0.15f,
                redDC = red,
                greenDC = green,
                motionIntensity = motionDetector.motionIntensity
            )
        )
    }

    private fun pushOpticalWindow(red: Float, green: Float, blue: Float) {
        opticalRedWindow.add(red)
        opticalGreenWindow.add(green)
        opticalBlueWindow.add(blue)
        while (opticalRedWindow.size > opticalWindowCap) opticalRedWindow.removeAt(0)
        while (opticalGreenWindow.size > opticalWindowCap) opticalGreenWindow.removeAt(0)
        while (opticalBlueWindow.size > opticalWindowCap) opticalBlueWindow.removeAt(0)
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.stop()
        feedbackController.release()
    }

    fun toggleBeep(enabled: Boolean) {
        feedbackController.beepEnabled = enabled
        _uiState.value = _uiState.value.copy(beepEnabled = enabled)
    }

    fun toggleVibration(enabled: Boolean) {
        feedbackController.vibrationEnabled = enabled
        _uiState.value = _uiState.value.copy(vibrationEnabled = enabled)
    }
}
