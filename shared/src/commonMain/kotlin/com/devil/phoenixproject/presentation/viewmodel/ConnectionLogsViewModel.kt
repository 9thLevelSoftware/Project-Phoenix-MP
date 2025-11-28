package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.local.ConnectionLogEntity
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogLevel
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Filter options for connection logs.
 */
data class LogFilter(
    val showDebug: Boolean = true,
    val showInfo: Boolean = true,
    val showWarning: Boolean = true,
    val showError: Boolean = true,
    val searchQuery: String = ""
)

/**
 * ViewModel for connection logs screen.
 * Uses the global ConnectionLogRepository singleton.
 */
class ConnectionLogsViewModel : ViewModel() {

    private val repository = ConnectionLogRepository.instance

    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    private val _isAutoScrollEnabled = MutableStateFlow(true)
    val isAutoScrollEnabled: StateFlow<Boolean> = _isAutoScrollEnabled.asStateFlow()

    /**
     * Filtered logs based on current filter settings.
     */
    val logs: StateFlow<List<ConnectionLogEntity>> = combine(
        repository.logs,
        _filter
    ) { allLogs, filter ->
        allLogs.filter { log ->
            val levelMatch = when (log.level) {
                LogLevel.DEBUG.name -> filter.showDebug
                LogLevel.INFO.name -> filter.showInfo
                LogLevel.WARNING.name -> filter.showWarning
                LogLevel.ERROR.name -> filter.showError
                else -> true
            }

            val searchMatch = if (filter.searchQuery.isBlank()) {
                true
            } else {
                log.message.contains(filter.searchQuery, ignoreCase = true) ||
                log.eventType.contains(filter.searchQuery, ignoreCase = true) ||
                (log.deviceName?.contains(filter.searchQuery, ignoreCase = true) == true) ||
                (log.details?.contains(filter.searchQuery, ignoreCase = true) == true)
            }

            levelMatch && searchMatch
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val isLoggingEnabled: StateFlow<Boolean> = repository.isEnabled

    /**
     * Update the filter settings.
     */
    fun updateFilter(newFilter: LogFilter) {
        _filter.value = newFilter
    }

    /**
     * Toggle visibility of a specific log level.
     */
    fun toggleLevel(level: LogLevel) {
        _filter.value = when (level) {
            LogLevel.DEBUG -> _filter.value.copy(showDebug = !_filter.value.showDebug)
            LogLevel.INFO -> _filter.value.copy(showInfo = !_filter.value.showInfo)
            LogLevel.WARNING -> _filter.value.copy(showWarning = !_filter.value.showWarning)
            LogLevel.ERROR -> _filter.value.copy(showError = !_filter.value.showError)
        }
    }

    /**
     * Set the search query.
     */
    fun setSearchQuery(query: String) {
        _filter.value = _filter.value.copy(searchQuery = query)
    }

    /**
     * Toggle auto-scroll behavior.
     */
    fun toggleAutoScroll() {
        _isAutoScrollEnabled.value = !_isAutoScrollEnabled.value
    }

    /**
     * Enable or disable logging.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        repository.setEnabled(enabled)
    }

    /**
     * Clear all logs.
     */
    fun clearAllLogs() {
        repository.clearAll()
    }

    /**
     * Clear logs older than specified hours.
     */
    fun clearOldLogs(hoursOld: Int = 24) {
        val cutoffTime = Clock.System.now().toEpochMilliseconds() - (hoursOld * 60 * 60 * 1000L)
        repository.clearOlderThan(cutoffTime)
    }

    /**
     * Export logs as plain text.
     */
    fun exportLogsAsText(): String {
        return repository.exportAsText()
    }

    /**
     * Export logs as CSV.
     */
    fun exportLogsAsCsv(): String {
        return repository.exportAsCsv()
    }

    /**
     * Get count of logs by level.
     */
    fun getLogCounts(): Map<LogLevel, Int> {
        val allLogs = repository.logs.value
        return mapOf(
            LogLevel.DEBUG to allLogs.count { it.level == LogLevel.DEBUG.name },
            LogLevel.INFO to allLogs.count { it.level == LogLevel.INFO.name },
            LogLevel.WARNING to allLogs.count { it.level == LogLevel.WARNING.name },
            LogLevel.ERROR to allLogs.count { it.level == LogLevel.ERROR.name }
        )
    }
}
