package com.devil.phoenixproject.util

/**
 * Constants used throughout the application.
 */
object Constants {
    // App version
    const val APP_VERSION = "0.8.0"

    // EULA version - increment when EULA text changes materially
    // Users must re-accept when this version increases
    const val EULA_VERSION = 1

    // Weight limits (in kg) - per cable, not total
    // V-Form Trainer: 100kg max per cable (200kg total)
    // Trainer+: 110kg max per cable (220kg total) - use 100kg as safe default
    const val MIN_WEIGHT_KG = 0f
    const val MAX_WEIGHT_KG = 100f

    // Trainer+ hardware ceiling — used by UI sliders to enforce absolute maximum
    const val MAX_WEIGHT_PER_CABLE_KG = 110f
    // Configurable weight increment options per unit system (Issue #266)
    val WEIGHT_INCREMENT_OPTIONS_KG = listOf(0.5f, 1.0f, 2.5f, 5.0f)
    val WEIGHT_INCREMENT_OPTIONS_LB = listOf(0.1f, 0.5f, 1.0f, 2.5f, 5.0f)
    const val DEFAULT_WEIGHT_INCREMENT_KG = 0.5f
    const val DEFAULT_WEIGHT_INCREMENT_LB = 1.0f

    // Just Lift weight guard: threshold below which a weight write is considered
    // a race-condition artifact (e.g., the 0.453592f hardcoded initial in JustLiftScreen).
    // 1 kg per cable = 2 kg total, well below any practical training weight.
    const val JUST_LIFT_MIN_VALID_WEIGHT_KG = 1f

    // Reps
    const val DEFAULT_WARMUP_REPS = 3
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
    fun formatWeight(kg: Float, useLb: Boolean): String = if (useLb) {
        val lbs = kgToLb(kg)
        "${formatDecimal(lbs)} lbs"
    } else {
        "${formatDecimal(kg)} kg"
    }

    /**
     * Format a decimal value: shows as integer if whole, 1 decimal place otherwise.
     * Issue #266: Supports sub-1lb increments with proper decimal display.
     */
    fun formatDecimal(value: Float): String = if (value % 1.0f == 0f) {
        value.toInt().toString()
    } else {
        val rounded = (value * 10).toInt() / 10f
        if (rounded % 1.0f == 0f) {
            rounded.toInt().toString()
        } else {
            val intPart = rounded.toInt()
            val decPart = kotlin.math.abs(((rounded - intPart) * 10).toInt())
            "$intPart.$decPart"
        }
    }

    /**
     * Round a value to the nearest given increment.
     */
    fun roundToIncrement(value: Float, increment: Float): Float {
        if (increment <= 0f) return value
        return (kotlin.math.round(value / increment) * increment)
    }

    /**
     * Round to nearest 0.5kg — the machine's physical minimum step.
     */
    fun roundToMachineIncrement(kg: Float): Float = roundToIncrement(kg, 0.5f)
}

/**
 * Estimated one-rep max calculators.
 */
object OneRepMaxCalculator {
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
@Suppress("unused") // Protocol reference constants
object ProtocolConstants {
    // Command types are in BleConstants.Commands

    // Frame sizes (Phoenix Backend aligned)
    const val STOP_PACKET_SIZE = 2
    const val REGULAR_PACKET_SIZE = 25 // Was 96 in web app
    const val ECHO_PACKET_SIZE = 29 // Was 40 in web app
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
