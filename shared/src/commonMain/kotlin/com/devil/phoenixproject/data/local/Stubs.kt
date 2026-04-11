package com.devil.phoenixproject.data.local

/**
 * Connection log entity - for logging BLE events
 * Matches parent repo structure for parity
 */
data class ConnectionLogEntity(
    val id: Long = 0,
    val timestamp: Long,
    val eventType: String,
    val level: String, // "DEBUG", "INFO", "WARNING", "ERROR"
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val message: String,
    val details: String? = null,
    val metadata: String? = null, // Additional metadata as JSON string if needed
)
