package com.devil.phoenixproject.domain.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Issue #1392 — power-loss autostop (VBT-style): configuration and execution target plan.
 *
 * Opt-in per-exercise POWER set termination. A set auto-ends when per-rep Mean
 * Concentric Power (MCP) drops [PowerLossConfig.thresholdPercent] below the best
 * valid working rep, or on a trusted short-ROM observation. See
 * `implementation-spec.md` §2 for the binding signoff decisions (SD1–SD8).
 *
 * Pure domain types only: no BLE, SQL, UI, or audio references.
 */

/** Per-exercise set-target mode. REP_TARGET preserves existing behavior. */
enum class TargetMode {
    /** Fixed rep target (existing behavior, default). */
    REP_TARGET,

    /** Power-loss autostop with mandatory max-reps backstop (opt-in). */
    POWER,
    ;

    companion object {
        /**
         * Strict reader: unknown persisted values resolve to null so callers can
         * fail safe (never arm POWER from an unknown enum — spec §7).
         */
        fun fromPersisted(value: String?): TargetMode? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Per-exercise power-loss configuration (P2 domain type).
 *
 * @property targetMode REP_TARGET (default) or POWER (opt-in).
 * @property thresholdPercent Integer percent, valid range 5–40 (default 20).
 *   UI cautions 36–40; validation never silently clamps — [validated] returns null.
 * @property maxWorkingReps Mandatory backstop, min 2 (default 20). Not a product
 *   maximum; the protocol-specific byte bound is checked at plan derivation (SD4).
 * @property requireTwoConsecutive Optional 2-consecutive-qualifying-reps guard.
 * @property savedNominalReps The user's saved nominal rep target, preserved as-is.
 *   In POWER mode it is display-only and must never stop the set early (SD4).
 */
data class PowerLossConfig(
    val targetMode: TargetMode = TargetMode.REP_TARGET,
    val thresholdPercent: Int = 20,
    val maxWorkingReps: Int = 20,
    val requireTwoConsecutive: Boolean = false,
    val savedNominalReps: Int? = null,
) {
    /** Near-limit warning threshold: `threshold − 5`, floored at 0 (first positive loss at 5%). */
    val nearLimitLossPercent: Int
        get() = maxOf(0, thresholdPercent - NEAR_LIMIT_OFFSET_PERCENT)

    /** True when every field is in range. Strict: no silent clamping. */
    fun isValid(): Boolean =
        thresholdPercent in MIN_THRESHOLD_PERCENT..MAX_THRESHOLD_PERCENT &&
            maxWorkingReps >= MIN_MAX_WORKING_REPS &&
            (savedNominalReps == null || savedNominalReps >= 1)

    /** This config or null when any field is out of range. */
    fun validated(): PowerLossConfig? = takeIf { isValid() }

    companion object {
        const val MIN_THRESHOLD_PERCENT = 5
        const val MAX_THRESHOLD_PERCENT = 40
        const val MIN_MAX_WORKING_REPS = 2
        const val NEAR_LIMIT_OFFSET_PERCENT = 5

        /** Default is inert: REP mode with defaults. */
        val DEFAULT = PowerLossConfig()
    }
}

/**
 * Protocol family for power-loss capability gating. V1 effective modes are
 * Old School, Pump, Echo, and Just Lift execution style; TUT / TUT Beast /
 * Eccentric-Only / timed / bodyweight are retained-but-inert (spec §1).
 */
enum class PowerLossProtocolFamily {
    OLD_SCHOOL,
    PUMP,
    ECHO,
    JUST_LIFT,
    /** TUT, TUT Beast, Eccentric-Only, timed, bodyweight, unknown. */
    UNSUPPORTED,
    ;

    val supportsPowerLoss: Boolean
        get() = this != UNSUPPORTED

    companion object {
        /**
         * Map a [ProgramMode] plus the Just Lift execution-style flag to the
         * protocol family. Just Lift is an execution style, not a ProgramMode.
         */
        fun of(mode: ProgramMode, isJustLiftExecutionStyle: Boolean): PowerLossProtocolFamily {
            if (isJustLiftExecutionStyle) return JUST_LIFT
            return when (mode) {
                ProgramMode.OldSchool -> OLD_SCHOOL
                ProgramMode.Pump -> PUMP
                ProgramMode.Echo -> ECHO
                else -> UNSUPPORTED
            }
        }
    }
}

/**
 * Capability gate: POWER arms only when the protocol family is supported AND the
 * signal prerequisites hold (validated paired samples available). Default OFF —
 * an internal gate, never a user-facing "readiness" claim.
 */
object PowerLossCapability {
    fun isEligible(
        protocolFamily: PowerLossProtocolFamily,
        pairedSamplesValidated: Boolean,
    ): Boolean = protocolFamily.supportsPowerLoss && pairedSamplesValidated
}

/**
 * Protocol byte bounds for working-rep targets, supplied by the caller at plan
 * derivation. The Echo finite working-byte bound is taken from the accepted
 * target-plan validator's bound only — never hardcoded here (spec P2 stop rule).
 *
 * @property maxRepresentableWorkingReps Largest working-rep count the protocol
 *   frame can represent (e.g. PROGRAM: `254 − warmupSetCount`).
 * @property protocolIdentityFinite True when the protocol's rep target is finite;
 *   false for unlimited identities (Just Lift / AMRAP) which must stay unlimited
 *   under REP_TARGET (SD4).
 */
data class ProtocolRepBounds(
    val maxRepresentableWorkingReps: Int,
    val protocolIdentityFinite: Boolean,
)

/**
 * One immutable execution target plan derived before Start (SD4). The identical
 * effective target is propagated through packet/counter/freshness/validation/HUD
 * so firmware and app agree on the cap.
 *
 * @property targetMode Effective mode for this set.
 * @property nominalReps Saved nominal rep target; display-only in POWER mode.
 * @property maxWorkingReps Effective cap in POWER mode. Null under REP_TARGET
 *   with an unlimited protocol identity.
 * @property warmupSetCount Warmup bound preserved from the plan.
 * @property protocolIdentityFinite Finite/unlimited protocol identity preserved.
 * @property planValidatedAt Monotonic ms when the plan was derived (before Start).
 */
data class ExecutionTargetPlan(
    val targetMode: TargetMode,
    val nominalReps: Int?,
    val maxWorkingReps: Int?,
    val warmupSetCount: Int,
    val protocolIdentityFinite: Boolean,
    val planValidatedAt: Long,
) {
    /** Cap stops the set only in POWER mode and only with a finite cap. */
    fun capStopAtWorkingAttempt(workingAttemptOrdinal: Int): Boolean =
        targetMode == TargetMode.POWER &&
            maxWorkingReps != null &&
            workingAttemptOrdinal >= maxWorkingReps

    companion object {
        /**
         * Derive the plan before Start. Rejects impossible plans by returning null:
         * - POWER requires a valid config and a finite representable bound of at
         *   least [PowerLossConfig.MIN_MAX_WORKING_REPS].
         * - REP_TARGET keeps the protocol identity: unlimited stays unlimited and
         *   never derives a cap from the nominal target (nominal-below-cap must
         *   never stop POWER early; equally a nominal never caps REP identity).
         */
        fun derive(
            config: PowerLossConfig,
            bounds: ProtocolRepBounds,
            warmupSetCount: Int,
            now: Long,
        ): ExecutionTargetPlan? {
            if (!config.isValid() || warmupSetCount < 0) return null
            return when (config.targetMode) {
                TargetMode.POWER -> {
                    // Mandatory finite backstop, checked against the protocol bound.
                    if (bounds.maxRepresentableWorkingReps < PowerLossConfig.MIN_MAX_WORKING_REPS) return null
                    if (config.maxWorkingReps > bounds.maxRepresentableWorkingReps) return null
                    ExecutionTargetPlan(
                        targetMode = TargetMode.POWER,
                        nominalReps = config.savedNominalReps,
                        maxWorkingReps = config.maxWorkingReps,
                        warmupSetCount = warmupSetCount,
                        protocolIdentityFinite = true,
                        planValidatedAt = now,
                    )
                }
                TargetMode.REP_TARGET -> {
                    val nominal = config.savedNominalReps
                    if (bounds.protocolIdentityFinite) {
                        if (nominal == null || nominal > bounds.maxRepresentableWorkingReps) return null
                        ExecutionTargetPlan(
                            targetMode = TargetMode.REP_TARGET,
                            nominalReps = nominal,
                            maxWorkingReps = null,
                            warmupSetCount = warmupSetCount,
                            protocolIdentityFinite = true,
                            planValidatedAt = now,
                        )
                    } else {
                        // Unlimited identity (Just Lift / AMRAP): stays unlimited.
                        ExecutionTargetPlan(
                            targetMode = TargetMode.REP_TARGET,
                            nominalReps = nominal,
                            maxWorkingReps = null,
                            warmupSetCount = warmupSetCount,
                            protocolIdentityFinite = false,
                            planValidatedAt = now,
                        )
                    }
                }
            }
        }
    }
}
