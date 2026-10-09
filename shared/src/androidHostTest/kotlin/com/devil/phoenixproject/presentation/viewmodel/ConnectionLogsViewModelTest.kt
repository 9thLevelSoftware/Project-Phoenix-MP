package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.data.local.ConnectionLogEntity
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.LogLevel
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConnectionLogsViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: ConnectionLogRepository
    private lateinit var viewModel: ConnectionLogsViewModel

    @Before
    fun setup() {
        repository = ConnectionLogRepository.instance
        repository.clearAll()
        repository.setEnabled(true)
        viewModel = ConnectionLogsViewModel()
    }

    @Test
    fun `filters logs by level`() = runTest {
        viewModel.logs.test {
            // Initial empty state
            assertEquals(emptyList(), awaitItem())

            // Add logs
            repository.debug(LogEventType.SCAN_START, "Debug log")
            repository.error(LogEventType.CONNECT_FAIL, "Error log")
            advanceUntilIdle()

            // Should have both logs initially
            val withBothLogs = awaitItem()
            assertEquals(2, withBothLogs.size)

            // Toggle off DEBUG level
            viewModel.toggleLevel(LogLevel.DEBUG)
            advanceUntilIdle()

            // Should only have ERROR log now
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals(LogLevel.ERROR.name, filtered.first().level)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filters logs by search query`() = runTest {
        viewModel.logs.test {
            // Initial empty state
            assertEquals(emptyList(), awaitItem())

            // Add logs
            repository.info(LogEventType.CONNECT_SUCCESS, "Connected to DeviceA")
            repository.info(LogEventType.CONNECT_SUCCESS, "Connected to DeviceB")
            advanceUntilIdle()

            // Should have both logs initially
            val withBothLogs = awaitItem()
            assertEquals(2, withBothLogs.size)

            // Filter by search query
            viewModel.setSearchQuery("DeviceA")
            advanceUntilIdle()

            // Should only have DeviceA log now
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertTrue(filtered.first().message.contains("DeviceA"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `all level chips off hides unknown levels and known levels follow their chips`() = runTest {
        viewModel.logs.test {
            assertEquals(emptyList(), awaitItem())

            repository.debug(LogEventType.SCAN_START, "Debug log")
            repository.info(LogEventType.CONNECT_SUCCESS, "Info log")
            repository.warning(LogEventType.DISCONNECT, "Warning log")
            repository.error(LogEventType.CONNECT_FAIL, "Error log")
            val seeded = repository.logs.value
            setRepositoryLogs(
                seeded + ConnectionLogEntity(
                    id = seeded.maxOf { it.id } + 1,
                    timestamp = 1L,
                    eventType = LogEventType.DIAGNOSTIC,
                    level = "TRACE",
                    message = "Unknown level log",
                ),
            )
            advanceUntilIdle()

            val withAllChipsOn = awaitItem()
            assertEquals(
                listOf(
                    LogLevel.ERROR.name,
                    LogLevel.WARNING.name,
                    LogLevel.INFO.name,
                    LogLevel.DEBUG.name,
                ),
                withAllChipsOn.map { it.level },
            )

            LogLevel.entries.forEach { viewModel.toggleLevel(it) }
            advanceUntilIdle()

            assertEquals(emptyList(), awaitItem())

            viewModel.toggleLevel(LogLevel.ERROR)
            advanceUntilIdle()

            val errorsOnly = awaitItem()
            assertEquals(listOf(LogLevel.ERROR.name), errorsOnly.map { it.level })

            cancelAndIgnoreRemainingEvents()
        }
    }

    // log() only accepts LogLevel, so an unrecognized level has to be written onto the store directly.
    @Suppress("UNCHECKED_CAST")
    private fun setRepositoryLogs(logs: List<ConnectionLogEntity>) {
        val flow = ConnectionLogRepository::class.java
            .getDeclaredField("_logs")
            .apply { isAccessible = true }
            .get(repository) as MutableStateFlow<List<ConnectionLogEntity>>
        flow.value = logs
    }
}
