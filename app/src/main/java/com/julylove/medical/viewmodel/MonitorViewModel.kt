package com.julylove.medical.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julylove.medical.camera.Camera2Controller
import com.julylove.medical.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MonitorViewModel(private val cameraController: Camera2Controller) : ViewModel(), Camera2Controller.OnFrameAvailableListener {

    data class MonitorState(
        val bpm: Int = 0,
        val spo2: Int = 0,
        val fingerState: FingerDetectionEngine.FingerState = FingerDetectionEngine.FingerState.NO_DEDO,
        val rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
        val sqi: SignalQualityIndexEngine.QualityLevel = SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE,
        val ppgSamples: List<PPGSample> = emptyList(),
        val isMeasuring: Boolean = false,
        val technicalData: TechnicalData = TechnicalData()
    )

    data class TechnicalData(
        val fps: Int = 0,
        val rmssd: Double = 0.0,
        val signalConfidence: Float = 0f
    )

    private val _uiState = MutableStateFlow(MonitorState())
    val uiState = _uiState.asStateFlow()

    private val ppgBuffer = mutableListOf<PPGSample>()
    private val maxBufferSize = 300 // 5 seconds at 60fps

    // Pipeline Components
    private val fingerDetector = FingerDetectionEngine()
    private val detrender = DetrendingFilter(30)
    private val bandpass = HeartRateBandpassFilter(60f)
    private val peakDetector = PeakDetectionEngine(60f)
    private val rhythmEngine = RhythmAnalysisEngine()
    private val sqiEngine = SignalQualityIndexEngine()

    private var lastPeakTime = 0L
    private var frameCount = 0
    private var lastFpsTimestamp = 0L

    init {
        cameraController.listener = this
    }

    fun toggleMeasurement() {
        if (_uiState.value.isMeasuring) {
            cameraController.stop()
            _uiState.value = _uiState.value.copy(isMeasuring = false)
        } else {
            cameraController.start()
            _uiState.value = _uiState.value.copy(isMeasuring = true)
        }
    }

    override fun onFrame(red: Float, green: Float, blue: Float, timestamp: Long) {
        val fingerState = fingerDetector.analyze(red, green, blue)
        
        // Signal Processing
        val rawValue = green // Green is often best for PPG on smartphones
        val detrended = detrender.filter(rawValue)
        val filtered = bandpass.filter(detrended)
        
        val isPeak = peakDetector.process(filtered, timestamp)
        val (sqiLevel, sqiScore) = sqiEngine.calculateSQI(filtered, fingerState == FingerDetectionEngine.FingerState.SENAL_VALIDA)

        if (isPeak && sqiLevel != SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE) {
            if (lastPeakTime != 0L) {
                val ppi = timestamp - lastPeakTime
                val bpm = (60000 / ppi).toInt()
                
                val rhythm = rhythmEngine.addInterval(ppi)
                
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(
                        bpm = if (bpm in 40..200) bpm else _uiState.value.bpm,
                        rhythmStatus = rhythm,
                        technicalData = _uiState.value.technicalData.copy(rmssd = rhythmEngine.getRMSSD())
                    )
                }
            }
            lastPeakTime = timestamp
        }

        // Update Waveform Buffer
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
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    technicalData = _uiState.value.technicalData.copy(fps = fps)
                )
            }
        }

        // Update UI State periodically or on critical changes
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                fingerState = fingerState,
                sqi = sqiLevel,
                ppgSamples = ppgBuffer.toList(), // Snapshot
                technicalData = _uiState.value.technicalData.copy(signalConfidence = sqiScore)
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.stop()
    }
}
