package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.local.ConnectionLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Log level for connection events.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Event types for connection logging.
 */
object LogEventType {
    const val SCAN_START = "SCAN_START"
    const val SCAN_STOP = "SCAN_STOP"
    const val DEVICE_FOUND = "DEVICE_FOUND"
    const val CONNECT_START = "CONNECT_START"
    const val CONNECT_SUCCESS = "CONNECT_SUCCESS"
    const val CONNECT_FAIL = "CONNECT_FAIL"
    const val DISCONNECT = "DISCONNECT"
    const val SERVICE_DISCOVERED = "SERVICE_DISCOVERED"
    const val CHARACTERISTIC_READ = "CHAR_READ"
    const val CHARACTERISTIC_WRITE = "CHAR_WRITE"
    const val NOTIFICATION = "NOTIFICATION"
    const val MTU_CHANGED = "MTU_CHANGED"
    const val ERROR = "ERROR"
    const val COMMAND_SENT = "COMMAND_SENT"
    const val COMMAND_RESPONSE = "COMMAND_RESPONSE"
    const val HEARTBEAT = "HEARTBEAT"
    const val REP_RECEIVED = "REP_RECEIVED"
}

/**
 * Repository for managing BLE connection logs.
 * Singleton instance used by BLE manager and ViewModel.
 */
class ConnectionLogRepository {

    companion object {
        /** Maximum number of logs to keep in memory */
        const val MAX_LOGS = 1000

        /** Thread-safe singleton instance using lazy initialization */
        val instance: ConnectionLogRepository by lazy {
            ConnectionLogRepository()
        }
    }

    private var nextId = 1L

    private val _logs = MutableStateFlow<List<ConnectionLogEntity>>(emptyList())
    val logs: StateFlow<List<ConnectionLogEntity>> = _logs.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /**
     * Add a new log entry.
     */
    fun log(
        level: LogLevel,
        eventType: String,
        message: String,
        deviceName: String? = null,
        deviceAddress: String? = null,
        details: String? = null
    ) {
        if (!_isEnabled.value) return

        val entry = ConnectionLogEntity(
            id = nextId++,
            timestamp = currentTimeMillis(),
            eventType = eventType,
            level = level.name,
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            message = message,
            details = details,
            metadata = null
        )

        _logs.update { currentLogs ->
            val newList = listOf(entry) + currentLogs
            if (newList.size > MAX_LOGS) {
                newList.take(MAX_LOGS)
            } else {
                newList
            }
        }
    }

    /**
     * Convenience methods for different log levels.
     */
    fun debug(eventType: String, message: String, deviceName: String? = null, deviceAddress: String? = null, details: String? = null) {
        log(LogLevel.DEBUG, eventType, message, deviceName, deviceAddress, details)
    }

    fun info(eventType: String, message: String, deviceName: String? = null, deviceAddress: String? = null, details: String? = null) {
        log(LogLevel.INFO, eventType, message, deviceName, deviceAddress, details)
    }

    fun warning(eventType: String, message: String, deviceName: String? = null, deviceAddress: String? = null, details: String? = null) {
        log(LogLevel.WARNING, eventType, message, deviceName, deviceAddress, details)
    }

    fun error(eventType: String, message: String, deviceName: String? = null, deviceAddress: String? = null, details: String? = null) {
        log(LogLevel.ERROR, eventType, message, deviceName, deviceAddress, details)
    }

    /**
     * Clear all logs.
     */
    fun clearAll() {
        _logs.value = emptyList()
    }

    /**
     * Clear logs older than the specified timestamp.
     */
    fun clearOlderThan(timestamp: Long) {
        _logs.update { currentLogs ->
            currentLogs.filter { it.timestamp >= timestamp }
        }
    }

    /**
     * Enable or disable logging.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    /**
     * Export logs as a formatted string for sharing.
     */
    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Vitruvian Connection Logs ===")
        sb.appendLine("Exported: ${formatTimestamp(currentTimeMillis())}")
        sb.appendLine("Total entries: ${_logs.value.size}")
        sb.appendLine()

        _logs.value.forEach { log ->
            sb.appendLine("[${formatTimestamp(log.timestamp)}] [${log.level}] ${log.eventType}")
            sb.appendLine("  ${log.message}")
            if (log.deviceName != null || log.deviceAddress != null) {
                sb.appendLine("  Device: ${log.deviceName ?: "Unknown"} (${log.deviceAddress ?: "N/A"})")
            }
            if (log.details != null) {
                sb.appendLine("  Details: ${log.details}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Export logs as CSV format.
     */
    fun exportAsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("timestamp,level,event_type,message,device_name,device_address,details")

        _logs.value.forEach { log ->
            sb.appendLine(
                "${log.timestamp},${log.level},${log.eventType}," +
                "\"${log.message.replace("\"", "\"\"")}\","+
                "${log.deviceName ?: ""},${log.deviceAddress ?: ""}," +
                "\"${log.details?.replace("\"", "\"\"") ?: ""}\""
            )
        }

        return sb.toString()
    }

    /**
     * Get logs filtered by level.
     */
    fun getLogsByLevel(level: LogLevel): List<ConnectionLogEntity> {
        return _logs.value.filter { it.level == level.name }
    }

    /**
     * Get logs filtered by event type.
     */
    fun getLogsByEventType(eventType: String): List<ConnectionLogEntity> {
        return _logs.value.filter { it.eventType == eventType }
    }

    /**
     * Get logs for a specific device.
     */
    fun getLogsForDevice(deviceAddress: String): List<ConnectionLogEntity> {
        return _logs.value.filter { it.deviceAddress == deviceAddress }
    }

    private fun formatTimestamp(timestamp: Long): String {
        // Use kotlinx-datetime for KMP-compatible formatting
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.date} ${localDateTime.hour.toString().padStart(2, '0')}:" +
               "${localDateTime.minute.toString().padStart(2, '0')}:" +
               "${localDateTime.second.toString().padStart(2, '0')}.${(timestamp % 1000).toString().padStart(3, '0')}"
    }

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
