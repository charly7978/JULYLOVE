package com.julylove.medical.data

/**
 * Clinical Metrics for Medical Forensic PPG Monitoring
 * Contains comprehensive clinical measurements and quality indicators
 */
data class ClinicalMetrics(
    val heartRate: Int = 0,
    val heartRateVariability: Double = 0.0,
    val perfusionIndex: Float = 0f,
    val signalQualityIndex: Float = 0f,
    val morphologyScore: Float = 0f,
    val arrhythmiaCount: Int = 0,
    val abnormalBeats: Int = 0,
    val contactQuality: Float = 0f,
    val snrRatio: Float = 0f,
    val baselineStability: Float = 0f,
    val clinicalValidityScore: Float = 0f
)
