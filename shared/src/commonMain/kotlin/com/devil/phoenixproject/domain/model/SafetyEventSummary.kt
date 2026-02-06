package com.devil.phoenixproject.domain.model

/**
 * Summary of safety events during a set
 */
data class SafetyEventSummary(
    val safetyFlags: Int = 0,
    val deloadWarnings: Int = 0,
    val romViolations: Int = 0,
    val spotterActivations: Int = 0
) {
    val hasSafetyEvents: Boolean
        get() = safetyFlags > 0 || deloadWarnings > 0 || romViolations > 0 || spotterActivations > 0
}
