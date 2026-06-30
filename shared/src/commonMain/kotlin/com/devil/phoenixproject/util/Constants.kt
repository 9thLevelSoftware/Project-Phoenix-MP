package com.devil.phoenixproject.util

/**
 * Constants used throughout the application.
 */
object Constants {
    // App version
    const val APP_VERSION = "0.9.3"

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
        // F420: round (not truncate) to nearest 0.1 and preserve the sign for
        // values in (-1, 0). The old code truncated (1.99 → 1.9) and dropped the
        // sign for -1 < value < 0 (-0.5 → "0.5") because intPart was 0.
        val scaled = kotlin.math.round(value * 10).toInt()
        if (scaled % 10 == 0) {
            (scaled / 10).toString()
        } else {
            val sign = if (scaled < 0) "-" else ""
            val absScaled = kotlin.math.abs(scaled)
            "$sign${absScaled / 10}.${absScaled % 10}"
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
 *
 * PARITY-CRITICAL: `estimate()` is the canonical cross-stack 1RM formula.
 * Mobile computes it and ships it to the portal (per-cable kg). The portal
 * MUST NOT use a different formula — see the monorepo parity doctrine.
 */
object OneRepMaxCalculator {
    /** Epley: weight * (1 + reps/30). Linear estimate; tends to overestimate at very high rep counts (>20). */
    fun epley(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (1f + reps / 30f)
    }

    /** Brzycki: weight * 36 / (37 - reps). Accurate for low reps; invalid for reps >= 37. */
    fun brzycki(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 0) return 0f
        if (reps == 1) return weight
        if (reps >= 37) return 0f
        return weight * (36f / (37f - reps))
    }

    /**
     * Canonical hybrid estimate: Brzycki for reps <= 10, Epley for reps > 10.
     * Continuous at reps == 10 (both yield weight * 1.3333).
     */
    fun estimate(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 0) return 0f
        if (reps == 1) return weight
        return if (reps <= 10) brzycki(weight, reps) else epley(weight, reps)
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
    const val ECHO_PACKET_SIZE = 32 // F308: matches BlePacketFactory.createEchoControl()
    const val ACTIVATION_PACKET_SIZE = 96 // F308: matches BlePacketFactory.createProgramParams()/ActivationPacket.SIZE
    const val COLOR_SCHEME_SIZE = 34

    // Mode values (used in ActivationPacket)
    const val MODE_OLD_SCHOOL = 0
    const val MODE_PUMP = 2
    const val MODE_TUT = 3
    const val MODE_TUT_BEAST = 4
    const val MODE_ECCENTRIC_ONLY = 6
    const val MODE_ECHO = 10
}
