package com.julylove.medical.signal

data class PPGSample(
    val timestamp: Long,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val filteredValue: Float = 0f,
    val isPeak: Boolean = false,
    val sqi: Float = 0f
)
