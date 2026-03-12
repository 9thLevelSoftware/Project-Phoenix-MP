package com.devil.phoenixproject.domain.model

enum class SafetyAuditEventType {
    COMMAND_DISPATCH,
    SAFETY_INTERVENTION
}

data class SafetyAuditEvent(
    val timestampMs: Long,
    val type: SafetyAuditEventType,
    val operation: String,
    val outcome: String,
    val details: String? = null
)
