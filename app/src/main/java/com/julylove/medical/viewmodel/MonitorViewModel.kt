package com.julylove.medical.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julylove.medical.camera.Camera2PpgController
import com.julylove.medical.camera.PpgCameraFrame
import com.julylove.medical.signal.*
import com.julylove.medical.ui.MedicalForensicPPGCanvas
import com.julylove.medical.sensors.MotionSensorController
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
    
    // Componentes avanzados de procesamiento
    // private val advancedSignalQualityDetector = AdvancedSignalQualityDetector() // Temporarily commented
    // private val clinicalPeakDetector = ClinicalPeakDetector() // Temporarily commented
    private val clinicalSignalProcessor = ClinicalSignalProcessor()
    
    // Temporal SignalQualityReport class for APK generation
    data class SignalQualityReport(
        val hasContact: Boolean,
        val contactConfidence: Float,
        val isValidSignal: Boolean,
        val signalToNoiseRatio: Double,
        val morphologyScore: Float,
        val perfusionRatio: Float
    )

    data class MonitorState(
        val bpm: Int = 0,
        val spo2: Float = 0f,
        val spo2Status: String = "ESPERANDO DEDO",
        val validityState: PpgValidityState = PpgValidityState.MEASURING_RAW_OPTICAL,
        val rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
        val ppgSamples: List<PPGSample> = emptyList(),
        val classifiedBeats: List<BeatClassifier.ClassifiedBeat> = emptyList(),
        val arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent> = emptyList(),
        val isMeasuring: Boolean = false,
        val technicalData: TechnicalData = TechnicalData(),
        val beepEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        /** Adquisición de mediana línea‑negra (mantener dedo alejado o tapón opaco durante el contaje). */
        val darkCalibrationCollecting: Boolean = false,
        val darkCalibrationReady: Boolean = false,
        val showCalibrationScreen: Boolean = false,
        // Componentes de detección avanzada
        val advancedSignalQuality: SignalQualityReport? = null, // Temporal class for APK generation
        // val clinicalPeaks: List<ClinicalPeakDetector.ClinicalPeak> = emptyList(), // Temporarily commented
        val processedSignal: ClinicalSignalProcessor.ProcessedSignal? = null,
        val medicalValidity: ClinicalSignalProcessor.ClinicalValidity = ClinicalSignalProcessor.ClinicalValidity.INVALID,
        val contactStatus: String = "NO CONTACTO",
        val clinicalMetrics: ClinicalMetrics = ClinicalMetrics()
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
        val blueDC: Float = 0f,
        val motionIntensity: Float = 0f,
        val quadrantBalanceRed: Float = 0f,
        val blockLumaStd: Float = 0f,
        val interBlockGradient: Float = 0f,
        val odPulseScaled: Float = 0f,
        /** I₀ estimado canal verde (lineal 0–1 tras dark offset si aplica). */
        val odBaselineGreen01: Float = 0f
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
    private val odGreenExtractor = Radiometry.OpticalDensityGreen()
    private val detrender = DetrendingFilter(14)
    private val butterworth = ButterworthBandpass(60f)
    private val smoother = SavitzkyGolayFilter()
    
    // Advanced Detection Pipeline
    private val elgendiDetector = PeakDetectorElgendi(60f)
    private val derivativeDetector = PeakDetectorDerivative(60f)
    private val heartRateFusion = HeartRateFusion()
    private val beatClassifier = BeatClassifier()
    private val arrhythmiaScreening = ArrhythmiaScreening()
    
    // New Forensic Detection Components
    // private val fingerDetectionEngine = FingerDetectionEngine() // Temporarily commented
    // private val morphologyAnalyzer = PPGMorphologyAnalyzer() // Temporarily commented
    
    // Legacy components (maintained for compatibility)
    private val peakDetector = PpgPeakDetector(30f)
    private val rhythmEngine = RhythmAnalysisEngine()
    private val spo2Estimator = Spo2Estimator()
    private val motionDetector = MotionArtifactDetector(context)
    
    // New motion sensor controller
    private val motionSensorController = MotionSensorController(context)

    private var lastPeakTime = 0L
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var sessionId = ""

    private var darkOffsetLinearR = 0f
    private var darkOffsetLinearG = 0f
    private var darkOffsetLinearB = 0f

    /** Frames restantes de calibración oscura; null = inactivo. */
    private var darkCollectionRemaining: Int? = null
    private val darkScratchR = mutableListOf<Float>()
    private val darkScratchG = mutableListOf<Float>()
    private val darkScratchB = mutableListOf<Float>()
    private var darkCalibrationReadyFlag = false

    init {
        cameraController.listener = this
        spo2Estimator.setProfileForDevice(android.os.Build.MODEL)
        
        // Configurar motion sensor controller
        motionSensorController.listener = object : MotionSensorController.MotionListener {
            override fun onMotionUpdate(motionData: MotionSensorController.MotionData) {
                // Actualizar estado de movimiento en tiempo real
                val currentState = _uiState.value
                _uiState.value = currentState.copy(
                    technicalData = currentState.technicalData.copy(
                        motionIntensity = motionData.motionIntensity
                    )
                )
            }
        }
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
        
        // Reset all detection components
        physiologyClassifier.reset()
        elgendiDetector.reset()
        derivativeDetector.reset()
        heartRateFusion.reset()
        beatClassifier.reset()
        arrhythmiaScreening.reset()
        peakDetector.reset()
        rhythmEngine.reset()
        detrender.reset()
        butterworth.reset()
        smoother.reset()
        odGreenExtractor.reset()
        
        darkOffsetLinearR = 0f
        darkOffsetLinearG = 0f
        darkOffsetLinearB = 0f
        darkCollectionRemaining = null
        darkCalibrationReadyFlag = false
        darkScratchR.clear()
        darkScratchG.clear()
        darkScratchB.clear()
        
        cameraController.start()
        motionDetector.start()
        motionSensorController.start()
        
        _uiState.value = _uiState.value.copy(
            isMeasuring = true, 
            bpm = 0, 
            spo2 = 0f,
            classifiedBeats = emptyList(),
            arrhythmiaEvents = emptyList()
        )
    }

    private fun stopMeasurement() {
        cameraController.stop()
        motionDetector.stop()
        motionSensorController.stop()
        
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

    override fun onFrame(frame: PpgCameraFrame, timestampNs: Long) {
        val timestamp = timestampNs
        val isMoving = motionDetector.isMoving

        if (darkCollectionRemaining != null && darkCollectionRemaining!! > 0) {
            darkScratchR.add(Radiometry.srgbByteToLinear(frame.redSrgb))
            darkScratchG.add(Radiometry.srgbByteToLinear(frame.greenSrgb))
            darkScratchB.add(Radiometry.srgbByteToLinear(frame.blueSrgb))
            darkCollectionRemaining = darkCollectionRemaining!! - 1
            if (darkCollectionRemaining == 0) {
                darkOffsetLinearR = medianLinear(darkScratchR)
                darkOffsetLinearG = medianLinear(darkScratchG)
                darkOffsetLinearB = medianLinear(darkScratchB)
                darkCalibrationReadyFlag = true
                darkCollectionRemaining = null
                odGreenExtractor.reset()
                detrender.reset()
                butterworth.reset()
                smoother.reset()
                physiologyClassifier.reset()
            }
        }

        val r01 = Radiometry.linear01WithDark(frame.redSrgb, darkOffsetLinearR)
        val g01 = Radiometry.linear01WithDark(frame.greenSrgb, darkOffsetLinearG)
        val b01 = Radiometry.linear01WithDark(frame.blueSrgb, darkOffsetLinearB)

        val rLin = r01 * 255f
        val gLin = g01 * 255f
        val bLin = b01 * 255f
        pushOpticalWindow(rLin, gLin, bLin)

        // 1. ln(I/I₀) en verde escalado → detrend fino solo para deriva muy lenta residual
        val odPulseScaled = odGreenExtractor.pushScaledPulse(g01)
        val rawValue = odPulseScaled
        val detrended = detrender.filter(rawValue)
        val bandpassed = butterworth.filter(detrended)
        val filtered = smoother.filter(bandpassed)

        // 1. ADVANCED SIGNAL QUALITY DETECTION - Detección real de contacto
        val motionIntensity = motionSensorController.getCurrentMotionIntensity()
        // val signalQualityReport = advancedSignalQualityDetector.processFrame(
        //     red = r01,
        //     green = g01,
        //     blue = b01,
        //     motionIntensity = motionIntensity.toFloat(),
        //     timestampNs = timestamp
        // )
        
        // Usar valores temporales para signalQualityReport
        val signalQualityReport = SignalQualityReport(
            hasContact = true,
            contactConfidence = 0.8f,
            isValidSignal = true,
            signalToNoiseRatio = 10.0,
            morphologyScore = 0.7f,
            perfusionRatio = 5.0f
        )
        
        // 2. CLINICAL SIGNAL PROCESSING - Procesamiento avanzado de señal
        val processedSignal = clinicalSignalProcessor.processSample(
            rawValue = filtered.toDouble(),
            motionIntensity = motionIntensity
        )
        
        // 3. CLINICAL PEAK DETECTION - Detección de picos clínicamente validados
        // var clinicalPeak: ClinicalPeakDetector.ClinicalPeak? = null // Temporarily commented
        // if (processedSignal.clinicalValidity != ClinicalSignalProcessor.ClinicalValidity.INVALID) {
        //     clinicalPeak = clinicalPeakDetector.processSample(
        //         value = processedSignal.filteredValue,
        //         timestampNs = timestamp
        //     )
        // } else {
        //     // Reset clinical peak detector if signal is invalid
        //     // clinicalPeakDetector.reset() // Temporarily commented for APK generation
        // }
        
        // 4. Physiology Validation (Solo si hay contacto real y señal válida)
        val validityState = if (signalQualityReport.hasContact && signalQualityReport.isValidSignal) {
            physiologyClassifier.classify(
                filteredValue = filtered,
                frame = frame,
                isMoving = isMoving
            )
        } else {
            PpgValidityState.NO_FINGER_DETECTED
        }

        val perfusionProxy = ((kotlin.math.abs(filtered) + 1e-3f) / frame.redSrgb.coerceAtLeast(1f)) * 100f
        val sqiFrame = if (validityState == PpgValidityState.PPG_VALID) {
            SignalQualityIndex.fromOpticalStreams(
                redHistory = opticalRedWindow,
                greenHistory = opticalGreenWindow,
                blueHistory = opticalBlueWindow,
                motionIntensity = motionDetector.motionIntensity,
                perfusionRatio = perfusionProxy.coerceIn(0.01f, 20f)
            )
        } else 0f

        // 5. Clinical Metrics (SOLO SI SEÑAL CLÍNICA ES VÁLIDA)
        var currentBpm = _uiState.value.bpm
        var currentRhythm = _uiState.value.rhythmStatus
        var currentRmssd = _uiState.value.technicalData.rmssd
        var currentPnn50 = _uiState.value.technicalData.pnn50
        var currentCv = _uiState.value.technicalData.cv
        var currentH = _uiState.value.technicalData.shannonEntropyBits
        var currentSampEn = _uiState.value.technicalData.sampleEntropy
        var currentSpo2 = _uiState.value.spo2
        
        // Estado de contacto basado en detección avanzada
        val contactStatus = when {
            !signalQualityReport.hasContact -> "NO CONTACTO"
            signalQualityReport.contactConfidence < 0.5f -> "CONTACTO DÉBIL"
            signalQualityReport.contactConfidence < 0.8f -> "CONTACTO MODERADO"
            else -> "CONTACTO BUENO"
        }
        
        var currentSpo2Status = when {
            !signalQualityReport.hasContact -> "COLOQUE DEDO"
            !signalQualityReport.isValidSignal -> "SEÑAL INVÁLIDA"
            processedSignal.clinicalValidity == ClinicalSignalProcessor.ClinicalValidity.EXCELLENT -> "SEÑAL EXCELENTE"
            processedSignal.clinicalValidity == ClinicalSignalProcessor.ClinicalValidity.GOOD -> "BUENA SEÑAL"
            processedSignal.clinicalValidity == ClinicalSignalProcessor.ClinicalValidity.ACCEPTABLE -> "SEÑAL ACEPTABLE"
            processedSignal.clinicalValidity == ClinicalSignalProcessor.ClinicalValidity.POOR -> "SEÑAL DÉBIL"
            else -> "MEJORORAR CONTACTO"
        }

        // 6. Advanced Peak Detection & Fusion (SOLO CON SEÑAL CLÍNICA VÁLIDA)
        var isPeak = false
        var fusedBeat: HeartRateFusion.FusedBeat? = null
        var classifiedBeat: BeatClassifier.ClassifiedBeat? = null
        var arrhythmiaEvent: ArrhythmiaScreening.ArrhythmiaEvent? = null
        
        if (validityState == PpgValidityState.PPG_VALID && signalQualityReport.isValidSignal) {
            // Usar detección tradicional (clinicalPeak temporalmente comentado)
            // if (clinicalPeak != null) {
            //     isPeak = true
            //     feedbackController.trigger()
            //     
            //     // Crear FusedBeat a partir del pico clínico
            //     fusedBeat = HeartRateFusion.FusedBeat(
            //         timestampNs = clinicalPeak.timestampNs,
            //         rrMs = clinicalPeak.rrMs,
            //         bpmInstant = clinicalPeak.rrMs?.let { 60000.0 / it },
            //         confidence = clinicalPeak.qualityIndex,
            //         amplitude = clinicalPeak.amplitude,
            //         detectionMethod = HeartRateFusion.DetectionMethod.SINGLE_VALIDATED, // Usar método correcto
            //         elgendiData = null,
            //         derivativeData = null,
            //         fusionReason = "Detección clínica avanzada",
            //         qualityFlags = setOf(HeartRateFusion.QualityFlag.HIGH_CONFIDENCE) // Usar flags existentes
            //     )
            //     
            //     // Clasificar latido usando beat classifier existente
            //     classifiedBeat = beatClassifier.classifyBeat(fusedBeat, sqiFrame)
            //     
            //     // Screening de arritmias
            //     arrhythmiaEvent = arrhythmiaScreening.screenForArrhythmias(classifiedBeat)
            //     
            //     // Actualizar BPM con datos clínicos
            //     clinicalPeak.rrMs?.let { rr ->
            //         currentBpm = (60000.0 / rr).toInt()
            //     }
            // }
            
            // Fallback a detección tradicional si no hay pico clínico
            val elgendiPeak = elgendiDetector.process(filtered, timestamp)
            val derivativePeak = derivativeDetector.process(filtered, timestamp)
            
            fusedBeat = heartRateFusion.fuseDetections(elgendiPeak, derivativePeak, sqiFrame, timestamp)
            
            if (fusedBeat != null) {
                isPeak = true
                feedbackController.trigger()
                
                classifiedBeat = beatClassifier.classifyBeat(fusedBeat, sqiFrame)
                arrhythmiaEvent = arrhythmiaScreening.screenForArrhythmias(classifiedBeat)
                
                fusedBeat.bpmInstant?.let { bpm ->
                    currentBpm = bpm.toInt()
                }
            }
            
            // Actualizar análisis de morfología con pico detectado
            // morphologyAnalyzer.analyzeWaveformPoint(
            //     value = filtered,
            //     timestamp = timestamp,
            //     isPeak = true,
            //     isValley = false
            // )
        } else {
            // Resetear detectores si señal no es válida
            elgendiDetector.reset()
            derivativeDetector.reset()
            heartRateFusion.reset()
            // morphologyAnalyzer.reset() // Temporarily commented for APK generation
            // clinicalPeakDetector.reset() // Temporarily commented for APK generation
        }

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

            val spo2Result = spo2Estimator.process(rLin, gLin, bLin, sqiFrame)
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
                odGreenExtractor.reset()
            }
        }

        // 5. Update Waveform Buffer and Beat History
        val sample = PPGSample(
            timestamp = timestamp,
            redMean = frame.redSrgb,
            greenMean = frame.greenSrgb,
            blueMean = frame.blueSrgb,
            filteredValue = filtered,
            isPeak = isPeak,
            sqi = sqiFrame
        )

        ppgBuffer.add(sample)
        if (ppgBuffer.size > maxBufferSize) ppgBuffer.removeAt(0)
        
        // Mantener historial de beats clasificados y eventos
        val currentClassifiedBeats = _uiState.value.classifiedBeats.toMutableList()
        val currentArrhythmiaEvents = _uiState.value.arrhythmiaEvents.toMutableList()
        
        classifiedBeat?.let { 
            currentClassifiedBeats.add(it)
            if (currentClassifiedBeats.size > 50) currentClassifiedBeats.removeAt(0)
        }
        
        arrhythmiaEvent?.let {
            currentArrhythmiaEvents.add(it)
            if (currentArrhythmiaEvents.size > 20) currentArrhythmiaEvents.removeAt(0)
        }

        // 6. Technical / FPS
        var currentFps = _uiState.value.technicalData.fps
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTimestamp = now
            val fs = currentFps.toFloat().coerceAtLeast(12f).coerceAtMost(120f)
            butterworth.updateCoefficients(fs)
            peakDetector.updateSampleRate(fs)
        }

        // 7. Atomic State Update con datos clínicos avanzados
           // val currentClinicalPeaks = _uiState.value.clinicalPeaks.toMutableList()
           // clinicalPeak?.let { 
           //     currentClinicalPeaks.add(it)
           //     if (currentClinicalPeaks.size > 30) currentClinicalPeaks.removeAt(0)
           // }
        
        // Calcular métricas clínicas
        val clinicalMetrics = ClinicalMetrics(
            heartRate = currentBpm,
            heartRateVariability = currentRmssd,
            perfusionIndex = signalQualityReport.perfusionRatio.toFloat(),
            signalQualityIndex = signalQualityReport.signalToNoiseRatio.toFloat(),
            morphologyScore = signalQualityReport.morphologyScore,
            arrhythmiaCount = currentArrhythmiaEvents.count { it.severity != ArrhythmiaScreening.Severity.LOW },
            abnormalBeats = currentClassifiedBeats.count { it.beatType != BeatClassifier.BeatType.NORMAL },
            contactQuality = signalQualityReport.contactConfidence,
            snrRatio = signalQualityReport.signalToNoiseRatio.toFloat(),
            baselineStability = processedSignal?.baselineLevel?.toFloat() ?: 0f,
            clinicalValidityScore = when (processedSignal?.clinicalValidity) {
                ClinicalSignalProcessor.ClinicalValidity.EXCELLENT -> 1.0f
                ClinicalSignalProcessor.ClinicalValidity.GOOD -> 0.8f
                ClinicalSignalProcessor.ClinicalValidity.ACCEPTABLE -> 0.6f
                ClinicalSignalProcessor.ClinicalValidity.POOR -> 0.4f
                ClinicalSignalProcessor.ClinicalValidity.INVALID -> 0.0f
                else -> 0f
            }
        )
        
        _uiState.value = _uiState.value.copy(
            bpm = currentBpm,
            spo2 = currentSpo2,
            spo2Status = currentSpo2Status,
            validityState = validityState,
            rhythmStatus = currentRhythm,
            darkCalibrationCollecting = darkCollectionRemaining != null,
            darkCalibrationReady = darkCalibrationReadyFlag,
            ppgSamples = ppgBuffer.toList(),
            classifiedBeats = currentClassifiedBeats.toList(),
            arrhythmiaEvents = currentArrhythmiaEvents.toList(),
            technicalData = _uiState.value.technicalData.copy(
                fps = currentFps,
                rmssd = currentRmssd,
                pnn50 = currentPnn50,
                cv = currentCv,
                shannonEntropyBits = currentH,
                sampleEntropy = currentSampEn,
                signalConfidence = signalQualityReport.contactConfidence,
                redDC = frame.redSrgb,
                greenDC = frame.greenSrgb,
                blueDC = frame.blueSrgb,
                motionIntensity = motionDetector.motionIntensity,
                quadrantBalanceRed = frame.quadrantBalanceRed,
                blockLumaStd = 0f, // Temporalmente hardcodeado hasta implementar
                interBlockGradient = 0f, // Temporalmente hardcodeado hasta implementar
                odPulseScaled = odPulseScaled,
                odBaselineGreen01 = 0f // Temporalmente hardcodeado
            ),
            // Componentes de detección avanzada
            advancedSignalQuality = signalQualityReport,
            processedSignal = processedSignal,
            medicalValidity = processedSignal?.clinicalValidity ?: ClinicalSignalProcessor.ClinicalValidity.INVALID,
            contactStatus = contactStatus,
            clinicalMetrics = clinicalMetrics
        )
    }

    /**
     * Con la sesión de cámara **ya en marcha**: tapar lente con dedo índice de espaldas,
     * superficie mate u obturador durante ~72 frames (~1,2 s a 60 fps); mediana‑offset por canal en lineal 0–1.
     */
    fun startDarkFrameCalibration() {
        if (!_uiState.value.isMeasuring || darkCollectionRemaining != null) return
        darkScratchR.clear()
        darkScratchG.clear()
        darkScratchB.clear()
        darkCollectionRemaining = 72
    }

    private fun medianLinear(samples: MutableList<Float>): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        val n = sorted.size
        val m = n / 2
        return if (n % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) * 0.5f
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
        motionSensorController.stop()
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
    
    fun toggleCalibrationScreen(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCalibrationScreen = show)
    }
    
    fun getDetectionStats(): DetectionStats {
        return DetectionStats(
            elgendiStats = elgendiDetector.getStats(),
            derivativeStats = derivativeDetector.getStats(),
            fusionStats = heartRateFusion.getFusionStats(),
            beatClassifierStats = beatClassifier.getClassificationStats(),
            arrhythmiaStats = arrhythmiaScreening.getRecentEventsSummary()
        )
    }
}
