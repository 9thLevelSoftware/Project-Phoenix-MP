package com.devil.phoenixproject.domain.model

/**
 * Velocity zone mapping for LED biofeedback.
 *
 * Simplified 4-state scheme for clear visual feedback during workouts:
 * - OFF = stationary/rest (lights off)
 * - GREEN = slow/controlled movement
 * - BLUE = normal tempo
 * - RED = fast/explosive
 *
 * @property schemeIndex Index into ColorSchemes.ALL for this zone's color
 */
enum class VelocityZone(val schemeIndex: Int) {
    REST(7),        // None/Off - at rest, stationary (lights off)
    CONTROLLED(1),  // Green - slow/controlled movement
    MODERATE(0),    // Blue - normal tempo
    FAST(5),        // Red - fast movement (consolidated from FAST/VERY_FAST/EXPLOSIVE)
    VERY_FAST(5),   // Red - same as FAST for simplified feedback
    EXPLOSIVE(5);   // Red - same as FAST for simplified feedback

    companion object {
        /**
         * Resolve velocity zone from absolute velocity in mm/s.
         *
         * Simplified 4-zone mapping (2026-02-14, v3):
         * - < 5 mm/s -> REST (OFF - truly stationary only)
         * - < 30 mm/s -> CONTROLLED (Green - slow/controlled)
         * - < 60 mm/s -> MODERATE (Blue - normal tempo)
         * - >= 60 mm/s -> FAST (Red - fast/explosive)
         *
         * Thresholds based on user feedback:
         * - Only go dark when actually stopped (< 5)
         * - Slow movement should be green (5-30)
         * - Normal tempo should be blue (30-60)
         * - Any quick movement should be red (60+)
         */
        fun fromVelocity(absVelocity: Double): VelocityZone = when {
            absVelocity < 5    -> REST
            absVelocity < 30   -> CONTROLLED
            absVelocity < 60   -> MODERATE
            else               -> FAST
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
