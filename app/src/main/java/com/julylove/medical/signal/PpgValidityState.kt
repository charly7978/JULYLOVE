package com.julylove.medical.signal

enum class PpgValidityState {
    MEASURING_RAW_OPTICAL,        // Raw optical acquisition, no assumptions
    NO_PPG_PHYSIOLOGICAL_SIGNAL,  // Optical signal present but lacks human pulsatility (e.g., red object)
    SEARCHING_PPG,                // Potential pulsatility detected, analyzing consistency
    PPG_CANDIDATE,                // Rhythm detected, waiting for stabilization
    PPG_VALID,                    // CLINICAL GRADE: Enable metrics and feedback
    SATURATED,                    // Excess light / Clipping
    MOTION_ARTIFACT,              // Motion noise detected
    LOW_PERFUSION,                // Signal too weak for reliable calculation
    ERROR,                        // Hardware or sensor error
    NO_FINGER_DETECTED            // No finger detected by forensic engine
}
