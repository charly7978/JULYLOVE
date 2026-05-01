package com.julylove.medical.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julylove.medical.camera.Camera2Controller
import com.julylove.medical.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MonitorViewModel(
    private val context: Context,
    private val cameraController: Camera2Controller
) : ViewModel(), Camera2Controller.OnFrameAvailableListener {

    data class MonitorState(
        val bpm: Int = 0,
        val spo2: Float = 0f,
        val spo2Status: String = "ESPERANDO SEÑAL",
        val fingerState: FingerDetectionEngine.FingerState = FingerDetectionEngine.FingerState.NO_DEDO,
        val rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
        val sqiLevel: SignalQualityIndexEngine.QualityLevel = SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE,
        val ppgSamples: List<PPGSample> = emptyList(),
        val isMeasuring: Boolean = false,
        val isMoving: Boolean = false,
        val technicalData: TechnicalData = TechnicalData()
    )

    data class TechnicalData(
        val fps: Int = 0,
        val rmssd: Double = 0.0,
        val pnn50: Double = 0.0,
        val cv: Double = 0.0,
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
    
    private val exportService = ExportService(context)

    // Pipeline Components
    private val fingerDetector = FingerDetectionEngine()
    private val detrender = DetrendingFilter(30)
    private val butterworth = ButterworthBandpass(60f)
    private val smoother = SavitzkyGolayFilter()
    private val peakDetector = PeakDetectionEngine(60f)
    private val rhythmEngine = RhythmAnalysisEngine()
    private val sqiEngine = SignalQualityIndexEngine()
    private val spo2Estimator = SpO2Estimator()
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
            val session = MeasurementSession(
                id = sessionId,
                timestamp = System.currentTimeMillis(),
                deviceModel = android.os.Build.MODEL,
                averageBpm = if (ppiHistory.isNotEmpty()) (60000 / ppiHistory.average()).toInt() else 0,
                averageSpo2 = _uiState.value.spo2,
                finalRhythmStatus = _uiState.value.rhythmStatus,
                samples = ppgBuffer.toList()
            )
            viewModelScope.launch {
                exportService.exportSession(session)
            }
        }
        
        _uiState.value = _uiState.value.copy(isMeasuring = false)
    }

    override fun onFrame(red: Float, green: Float, blue: Float, timestamp: Long) {
        val fingerState = fingerDetector.analyze(red, green, blue)
        val isMoving = motionDetector.isMoving
        
        // Signal Processing Pipeline
        val rawValue = green 
        val detrended = detrender.filter(rawValue)
        val bandpassed = butterworth.filter(detrended)
        val filtered = smoother.filter(bandpassed)
        
        val (sqiLevel, sqiScore) = sqiEngine.calculateSQI(
            filteredValue = filtered,
            redDC = red,
            greenDC = green,
            isFingerDetected = fingerState == FingerDetectionEngine.FingerState.SENAL_VALIDA,
            isMoving = isMoving
        )

        val isPeak = if (sqiLevel != SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE) {
            peakDetector.process(filtered, timestamp)
        } else false

        if (isPeak) {
            if (lastPeakTime != 0L) {
                val ppi = timestamp - lastPeakTime
                if (ppi in 300..2000) { // Physiological range
                    ppiHistory.add(ppi)
                    if (ppiHistory.size > 60) ppiHistory.removeAt(0)
                    
                val (bpm, rhythm, rmssd, pnn50, cv) = rhythmEngine.addIntervalDetailed(ppi)
                
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(
                        bpm = bpm,
                        rhythmStatus = rhythm,
                        technicalData = _uiState.value.technicalData.copy(
                            rmssd = rmssd,
                            pnn50 = pnn50,
                            cv = cv
                        )
                    )
                }
            }
        }
        lastPeakTime = timestamp
    }

        // SpO2 Estimation
        val spo2Result = spo2Estimator.process(red, green, blue, sqiScore)

        // Update Waveform Buffer for UI
        val sample = PPGSample(
            timestamp = timestamp,
            redMean = red,
            greenMean = green,
            blueMean = blue,
            filteredValue = filtered,
            isPeak = isPeak,
            sqi = sqiScore
        )

        ppgBuffer.add(sample)
        if (ppgBuffer.size > maxBufferSize) ppgBuffer.removeAt(0)

        // FPS Calculation
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTimestamp = now
            butterworth.updateCoefficients(fps.toFloat())
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    technicalData = _uiState.value.technicalData.copy(fps = fps)
                )
            }
        }

        // Update UI State
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                fingerState = fingerState,
                sqiLevel = sqiLevel,
                spo2 = spo2Result.spo2,
                spo2Status = spo2Result.status,
                ppgSamples = ppgBuffer.toList(),
                isMoving = isMoving,
                technicalData = _uiState.value.technicalData.copy(
                    signalConfidence = sqiScore,
                    rmssd = rhythmEngine.getRMSSD(),
                    redDC = red,
                    greenDC = green,
                    motionIntensity = motionDetector.motionIntensity
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.stop()
    }
}
