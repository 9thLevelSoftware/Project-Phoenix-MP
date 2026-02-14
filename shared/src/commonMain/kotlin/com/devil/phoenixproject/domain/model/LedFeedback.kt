package com.devil.phoenixproject.domain.model

/**
 * Velocity zone mapping for LED biofeedback.
 *
 * Maps absolute velocity (mm/s) to one of 6 LED color zones using the
 * predefined ColorSchemes indices (0-5). Thresholds are calibrated for
 * the Vitruvian V-Form cable travel range (~0-600mm).
 *
 * @property schemeIndex Index into ColorSchemes.ALL for this zone's color
 */
enum class VelocityZone(val schemeIndex: Int) {
    REST(0),        // Blue - at rest, between reps
    CONTROLLED(1),  // Green - controlled movement
    MODERATE(2),    // Teal - moderate velocity
    FAST(3),        // Yellow - fast / explosive
    VERY_FAST(4),   // Pink - very fast, caution
    EXPLOSIVE(5);   // Red - maximum effort

    companion object {
        /**
         * Resolve velocity zone from absolute velocity in mm/s.
         *
         * Thresholds (from spec Section 2.4):
         * - < 20 mm/s -> REST
         * - < 150 mm/s -> CONTROLLED
         * - < 300 mm/s -> MODERATE
         * - < 500 mm/s -> FAST
         * - < 700 mm/s -> VERY_FAST
         * - >= 700 mm/s -> EXPLOSIVE
         */
        fun fromVelocity(absVelocity: Double): VelocityZone = when {
            absVelocity < 20   -> REST
            absVelocity < 150  -> CONTROLLED
            absVelocity < 300  -> MODERATE
            absVelocity < 500  -> FAST
            absVelocity < 700  -> VERY_FAST
            else               -> EXPLOSIVE
        }
    }
}

/**
 * LED feedback mode selection.
 *
 * - VELOCITY_ZONE: Standard velocity-based color mapping (all modes)
 * - TEMPO_GUIDE: Tempo compliance feedback (TUT/TUT Beast)
 * - AUTO: Mode-dependent selection (TUT/TUTBeast -> TEMPO_GUIDE, others -> VELOCITY_ZONE)
 */
enum class LedFeedbackMode {
    VELOCITY_ZONE,
    TEMPO_GUIDE,
    AUTO
}
