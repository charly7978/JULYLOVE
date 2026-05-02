package com.julylove.medical.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julylove.medical.camera.Camera2PpgController
import com.julylove.medical.camera.PpgCameraFrame
import com.julylove.medical.signal.*
import com.julylove.medical.haptics.BeatFeedbackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

class MonitorViewModel(
    private val context: Context,
    private val cameraController: Camera2PpgController
) : ViewModel(), Camera2PpgController.OnFrameAvailableListener {

    data class MonitorUiState(
        val cameraRunning: Boolean = false,
        val isMeasuring: Boolean = false,
        val validityState: PpgValidityState = PpgValidityState.MEASURING_RAW_OPTICAL,
        val ppgSamples: List<PPGSample> = emptyList(),
        val bpm: Int = 0,
        val bpmConfidence: Float = 0f,
        val spo2: Float = 0f,
        val spo2Status: String = "ESTABILIZANDO",
        val rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
        val fps: Int = 0,
        val latencyMs: Long = 0,
        val signalValid: Boolean = false,
        val fingerPresent: Boolean = false,
        val beepEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true
    )

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState = _uiState.asStateFlow()

    // Pipeline Principal (Arquitectura Reconstruida)
    private val signalBuffer = PpgSignalBuffer(300)
    private val bandpass = ButterworthBandpass(30f)
    private val classifier = PpgPhysiologyClassifier()
    private val peakDetector = PpgPeakDetector(30f)
    private val bpmEstimator = BpmEstimator()
    private val spo2Estimator = Spo2Estimator()
    private val rhythmAnalyzer = RhythmAnalysisEngine()
    private val haptics = BeatFeedbackController(context)

    private val ppgSampleHistory = mutableListOf<PPGSample>()
    private val maxHistorySize = 300
    private var lastFrameTime = 0L

    init {
        cameraController.listener = this
    }

    fun toggleMeasurement() {
        if (_uiState.value.isMeasuring) {
            stopMeasurement()
        } else {
            startMeasurement()
        }
    }

    private fun startMeasurement() {
        resetPipeline()
        cameraController.start()
        _uiState.value = _uiState.value.copy(isMeasuring = true, cameraRunning = true)
    }

    private fun stopMeasurement() {
        cameraController.stop()
        _uiState.value = _uiState.value.copy(isMeasuring = false, cameraRunning = false)
    }

    private fun resetPipeline() {
        signalBuffer.clear()
        classifier.reset()
        peakDetector.reset()
        bpmEstimator.reset()
        rhythmAnalyzer.reset()
        ppgSampleHistory.clear()
    }

    override fun onFrame(frame: PpgCameraFrame, timestampNs: Long) {
        val now = System.currentTimeMillis()
        val latency = if (lastFrameTime > 0) now - lastFrameTime else 0
        lastFrameTime = now

        // 1. Extraer PpgFrame
        val ppgFrame = PpgFrame(
            timestampNs = timestampNs,
            fpsEstimate = cameraController.actualFps.toFloat(),
            avgRed = frame.redSrgb,
            avgGreen = frame.greenSrgb,
            avgBlue = frame.blueSrgb,
            redDc = frame.redSrgb,
            greenDc = frame.greenSrgb,
            blueDc = frame.blueSrgb,
            redAc = 0f, greenAc = 0f, blueAc = 0f,
            saturationRatio = if (frame.redSrgb > 250) 1f else 0f,
            darknessRatio = if (frame.redSrgb < 10) 1f else 0f,
            skinLikelihood = 1f,
            redDominance = frame.redSrgb / frame.greenSrgb.coerceAtLeast(1f),
            greenPulseCandidate = frame.greenSrgb,
            textureScore = 1f, motionScore = 0f,
            rawOpticalSignal = frame.greenSrgb,
            normalizedSignal = frame.greenSrgb / 255f
        )

        // 2. Filtrado y Buffering
        signalBuffer.push(ppgFrame)
        val filtered = bandpass.filter(ppgFrame.rawOpticalSignal)

        // 3. Clasificación (REGLA DE ORO)
        val state = classifier.classify(ppgFrame)
        val isPhysiological = state == PpgValidityState.PPG_VALID

        var currentBpm = 0
        var currentSpo2 = 0f
        var currentSpo2Status = "BUSCANDO..."
        var rhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING
        var isPeak = false
        var confidence = 0f

        if (isPhysiological) {
            // Detección de picos solo si es válido
            val peak = peakDetector.process(filtered, timestampNs)
            val fused = bpmEstimator.estimate(peak, 0.8f, timestampNs)

            if (fused != null) {
                isPeak = true
                confidence = fused.confidence.toFloat()
                haptics.trigger()
                
                val rrMs = fused.rrMs?.toLong() ?: 0L
                if (rrMs > 0) {
                    val rhythm = rhythmAnalyzer.addIntervalDetailed(rrMs, timestampNs)
                    currentBpm = rhythm.bpm
                    rhythmStatus = rhythm.status
                }
            } else {
                currentBpm = bpmEstimator.getAverageBpm()
            }

            val spo2Result = spo2Estimator.process(frame.redSrgb, frame.greenSrgb, frame.blueSrgb, 0.8f)
            currentSpo2 = spo2Result.spo2
            currentSpo2Status = spo2Result.status
        }

        // 4. Muestra UI
        val sample = PPGSample(
            timestamp = timestampNs,
            redMean = frame.redSrgb,
            greenMean = frame.greenSrgb,
            blueMean = frame.blueSrgb,
            filteredValue = if (state == PpgValidityState.PPG_VALID || state == PpgValidityState.PPG_CANDIDATE) filtered else 0f,
            isPeak = isPeak,
            sqi = if (isPhysiological) 0.8f else 0.1f,
            fingerDetected = state != PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL,
            confidence = confidence
        )

        ppgSampleHistory.add(sample)
        if (ppgSampleHistory.size > maxHistorySize) ppgSampleHistory.removeAt(0)

        // 5. Emitir Estado Atómico
        _uiState.value = _uiState.value.copy(
            validityState = state,
            ppgSamples = ppgSampleHistory.toList(),
            bpm = currentBpm,
            spo2 = currentSpo2,
            spo2Status = currentSpo2Status,
            rhythmStatus = rhythmStatus,
            fps = cameraController.actualFps,
            latencyMs = latency,
            signalValid = isPhysiological,
            fingerPresent = sample.fingerDetected
        )
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.stop()
        haptics.release()
    }

    fun toggleBeep(enabled: Boolean) {
        haptics.beepEnabled = enabled
        _uiState.value = _uiState.value.copy(beepEnabled = enabled)
    }

    fun toggleVibration(enabled: Boolean) {
        haptics.vibrationEnabled = enabled
        _uiState.value = _uiState.value.copy(vibrationEnabled = enabled)
    }
}
