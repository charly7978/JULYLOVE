package com.julylove.medical.data

import com.julylove.medical.signal.PPGSample
import com.julylove.medical.signal.RhythmAnalysisEngine

data class MeasurementSession(
    val id: String,
    val timestamp: Long,
    val deviceModel: String,
    val averageBpm: Int,
    val averageSpo2: Float,
    val finalRhythmStatus: RhythmAnalysisEngine.RhythmStatus,
    val finalRmssd: Double,
    val finalShannonEntropyBits: Double,
    val finalSampleEntropy: Double?,
    val finalCvPercent: Double,
    val motionMeanIntensity: Float,
    val samples: List<PPGSample>
)
