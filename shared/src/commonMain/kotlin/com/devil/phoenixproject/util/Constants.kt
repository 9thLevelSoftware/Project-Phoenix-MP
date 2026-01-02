package com.devil.phoenixproject.util

/**
 * Constants used throughout the application.
 */
object Constants {
    // App version
    const val APP_VERSION = "0.2.0"

    // Weight limits (in kg)
    const val MIN_WEIGHT_KG = 0f
    const val MAX_WEIGHT_KG = 220f
    const val WEIGHT_INCREMENT_KG = 0.5f
    const val MAX_PROGRESSION_KG = 3f

    // Reps limits
    const val MIN_REPS = 1
    const val MAX_REPS = 100
    const val DEFAULT_WARMUP_REPS = 3

    // BLE configuration
    const val BLE_SCAN_TIMEOUT_MS = 30000L  // Matches parent repo BleConstants.SCAN_TIMEOUT_MS
    const val BLE_CONNECTION_TIMEOUT_MS = 15000L

    // Workout detection thresholds
    const val REP_DETECTION_THRESHOLD = 0.1f
    const val POSITION_TOP_THRESHOLD = 0.95f
    const val POSITION_BOTTOM_THRESHOLD = 0.05f

    // Data sampling
    const val METRICS_SAMPLE_INTERVAL_MS = 50L
    const val MAX_HISTORY_POINTS = 72000 // 2 hours at 100ms

    // Position ranges (mm) - from official app documentation
    // Cable position is measured in millimeters, valid range is -1000 to +1000 mm
    const val MAX_POSITION = 1000.0f
    const val MIN_POSITION = -1000.0f
}

/**
 * Unit conversion utilities.
 */
object UnitConverter {
    private const val KG_TO_LB = 2.20462f
    private const val LB_TO_KG = 0.453592f
    
    /**
     * Convert kilograms to pounds.
     */
    fun kgToLb(kg: Float): Float = kg * KG_TO_LB
    
    /**
     * Convert pounds to kilograms.
     */
    fun lbToKg(lb: Float): Float = lb * LB_TO_KG
    
    /**
     * Format weight for display with appropriate unit.
     */
    fun formatWeight(kg: Float, useLb: Boolean): String {
        return if (useLb) {
            "${kgToLb(kg).toInt()} lbs"
        } else {
            "${kg.toInt()} kg"
        }
    }
}

/**
 * Estimated one-rep max calculators.
 */
object OneRepMaxCalculator {
    /**
     * Calculate estimated 1RM using Brzycki formula.
     * @param weight Weight lifted
     * @param reps Number of reps completed
     * @return Estimated one rep max
     */
    fun brzycki(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (36f / (37f - reps))
    }

    /**
     * Calculate estimated 1RM using Epley formula.
     */
    fun epley(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (1f + reps / 30f)
    }
}

/**
 * Protocol constants - aligned with Phoenix Backend (official app)
 * NOTE: Legacy web app used different sizes and commands
 */
@Suppress("unused")  // Protocol reference constants
object ProtocolConstants {
    // Command types are in BleConstants.Commands

    // Frame sizes (Phoenix Backend aligned)
    const val STOP_PACKET_SIZE = 2
    const val REGULAR_PACKET_SIZE = 25        // Was 96 in web app
    const val ECHO_PACKET_SIZE = 29           // Was 40 in web app
    const val ACTIVATION_PACKET_SIZE = 97
    const val COLOR_SCHEME_SIZE = 34

    // Mode values (used in ActivationPacket)
    const val MODE_OLD_SCHOOL = 0
    const val MODE_PUMP = 2
    const val MODE_TUT = 3
    const val MODE_TUT_BEAST = 4
    const val MODE_ECCENTRIC_ONLY = 6
    const val MODE_ECHO = 10
}
