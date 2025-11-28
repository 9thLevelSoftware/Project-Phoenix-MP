package com.devil.phoenixproject.data.local

/**
 * Stub data classes for types that need platform-specific Room/database implementations
 * TODO: Replace with proper expect/actual or interface-based implementations
 */

/**
 * Weekly program with associated days - stub for Room entity
 */
data class WeeklyProgramWithDays(
    val program: WeeklyProgramEntity,
    val days: List<ProgramDayEntity>
)

/**
 * Weekly program entity - stub for Room entity
 */
data class WeeklyProgramEntity(
    val id: String,
    val name: String,
    val description: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * Program day entity - stub for Room entity
 */
data class ProgramDayEntity(
    val id: String,
    val programId: String,
    val dayOfWeek: Int, // 1=Monday, 7=Sunday (ISO-8601)
    val routineId: String?,
    val orderIndex: Int = 0
)

/**
 * Connection log entity - stub for logging BLE events
 */
data class ConnectionLogEntity(
    val id: Long = 0,
    val timestamp: Long,
    val level: String, // "DEBUG", "INFO", "WARNING", "ERROR"
    val eventType: String,
    val message: String,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val details: String? = null
)
