package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.LogLevel
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
