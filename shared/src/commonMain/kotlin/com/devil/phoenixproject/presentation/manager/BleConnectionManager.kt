package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Narrow interface for checking workout state from BleConnectionManager.
 * Breaks circular dependency: BleConnectionManager needs to know if a workout
 * is active for connection-loss alerting, but doesn't need the full workout API.
 */
interface WorkoutStateProvider {
    val isWorkoutActiveForConnectionAlert: Boolean

    /**
     * True when the machine is actively executing a set (WorkoutState.Active).
     * CRITICAL: Reconnecting mid-set will fault the machine because it cannot
     * receive a new exercise packet until the active one fully ends.
     * Use this to guard auto-reconnect attempts.
     */
    val isWorkoutMidSet: Boolean

    fun onWorkoutConnectionLost()
}

/**
 * Manages BLE device scanning, connection lifecycle, and connection-loss detection.
 * Extracted from MainViewModel during monolith decomposition.
 */
class BleConnectionManager(
    private val bleRepository: BleRepository,
    private val settingsManager: SettingsManager,
    private val workoutStateProvider: WorkoutStateProvider,
    private val bleErrorEvents: SharedFlow<String>,
    private val scope: CoroutineScope,
) {
    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    // Delegate directly to the repository's scanned devices (populated by KableBleConnectionManager)
    val scannedDevices: StateFlow<List<ScannedDevice>> = bleRepository.scannedDevices

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var _pendingConnectionCallback: (() -> Unit)? = null

    private val _connectionLostDuringWorkout = MutableStateFlow(false)
    val connectionLostDuringWorkout: StateFlow<Boolean> = _connectionLostDuringWorkout.asStateFlow()

    private var connectionJob: Job? = null

    init {
        // CRITICAL: All scope.launch coroutines in init must have try-catch.
        // On Kotlin/Native (iOS), unhandled exceptions in scope.launch call abort(),
        // causing SIGABRT crash on launch.

        // Collect BLE error events from WorkoutCoordinator (replaces circular lateinit dependency)
        scope.launch {
            try {
                bleErrorEvents.collect { message ->
                    _connectionError.value = message
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error collecting BLE error events" }
            }
        }

        scope.launch {
            try {
                combine(
                    bleRepository.connectionState,
                    settingsManager.userPreferences.map { it.colorScheme },
                ) { connection, color ->
                    (connection is ConnectionState.Connected) to color
                }
                    .distinctUntilChanged()
                    .collect { (connected, color) ->
                        bleRepository.setLastColorSchemeIndex(color)
                        if (connected) {
                            bleRepository.setColorScheme(color).onFailure { error ->
                                Logger.e(error) { "Failed to apply active profile LED color" }
                            }
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.e(error) { "Error collecting active profile LED color" }
            }
        }

        // Connection state observer for detecting connection loss during workout (Issue #42)
        // When connection is lost during an active workout, show the ConnectionLostDialog
        // Moved from MainViewModel init block
        scope.launch {
            try {
                var wasConnected = false
                bleRepository.connectionState.collect { state ->
                    when (state) {
                        is ConnectionState.Connected -> {
                            wasConnected = true
                            // Clear any previous connection lost alert when reconnected
                            _connectionLostDuringWorkout.value = false
                        }

                        is ConnectionState.Disconnected, is ConnectionState.Error -> {
                            // Only trigger alert if we were previously connected
                            // and a workout is actively in progress (not in summary)
                            // SetSummary is excluded since the summary screen doesn't need connection
                            // and users need to interact with it to save workout history
                            if (wasConnected && workoutStateProvider.isWorkoutActiveForConnectionAlert) {
                                Logger.w { "Connection lost during active workout! Showing reconnection dialog." }
                                workoutStateProvider.onWorkoutConnectionLost()
                                _connectionLostDuringWorkout.value = true
                            }
                            wasConnected = false
                        }

                        else -> {
                            // Scanning, Connecting - don't change wasConnected or alert state
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error in connection state observer" }
            }
        }

        // B2 fix: Collect reconnection requests from the BLE layer.
        // KableBleConnectionManager emits these when a peripheral disconnects unexpectedly.
        // We only attempt auto-reconnect during active workouts to avoid spurious reconnects
        // on intentional disconnections or idle screens.
        scope.launch {
            try {
                bleRepository.reconnectionRequested.collect { request ->
                    Logger.w { "Auto-reconnect requested: ${request.deviceName} (reason: ${request.reason})" }
                    // H1: CRITICAL — never reconnect mid-set. The machine cannot receive a new
                    // exercise packet until the active one fully ends; doing so causes a fault
                    // (blinking red light). Only reconnect between sets (Resting, Countdown, etc).
                    if (workoutStateProvider.isWorkoutMidSet) {
                        Logger.w { "Skipping reconnect — workout is mid-set (machine would fault)" }
                        return@collect
                    }
                    if (workoutStateProvider.isWorkoutActiveForConnectionAlert) {
                        delay(1500L) // BLE stack cooldown — Android needs time to release GATT resources
                        ensureConnection(
                            onConnected = { Logger.i { "Auto-reconnect succeeded for ${request.deviceName}" } },
                            onFailed = { Logger.w { "Auto-reconnect failed for ${request.deviceName}" } },
                        )
                    } else {
                        Logger.d { "Ignoring reconnection request — no active workout" }
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error collecting reconnection requests" }
            }
        }
    }

    fun startScanning() {
        scope.launch {
            bleRepository.startScanning().onFailure { e ->
                _connectionError.value = e.message ?: "Failed to start scan"
            }
        }
    }

    fun stopScanning() {
        scope.launch { bleRepository.stopScanning() }
    }

    fun cancelScanOrConnection() {
        scope.launch {
            bleRepository.stopScanning()
            // Only cancel connection if we're actually connecting
            val state = connectionState.value
            if (state is ConnectionState.Connecting) {
                bleRepository.cancelConnection()
            }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        scope.launch {
            val device = scannedDevices.value.find { it.address == deviceAddress }
            if (device != null) {
                bleRepository.connect(device).onFailure { e ->
                    _connectionError.value = e.message ?: "Failed to connect"
                }
            } else {
                Logger.e { "Device not found in scanned devices: $deviceAddress" }
            }
        }
    }

    fun disconnect() {
        scope.launch { bleRepository.disconnect() }
    }

    fun clearConnectionError() {
        _connectionError.value = null
    }

    /**
     * Set connection error from external code (e.g., workout BLE command failures).
     * Used by workout code that catches BLE send exceptions.
     */
    fun setConnectionError(message: String) {
        _connectionError.value = message
    }

    fun dismissConnectionLostAlert() {
        _connectionLostDuringWorkout.value = false
    }

    fun cancelAutoConnecting() {
        _isAutoConnecting.value = false
        _connectionError.value = null
        connectionJob?.cancel()
        connectionJob = null
        scope.launch { bleRepository.stopScanning() }
    }

    /**
     * Ensures connection to a Vitruvian device.
     * If already connected, immediately calls onConnected.
     * If not connected, starts scan and auto-connects to first device found.
     * Matches parent repo behavior with proper timeouts and cleanup.
     */
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) {
        // If already connected, just call the callback
        if (connectionState.value is ConnectionState.Connected) {
            Logger.d { "ensureConnection: Already connected, calling onConnected()" }
            onConnected()
            return
        }

        // If already connecting/scanning, restart with THIS caller's callbacks. Previously
        // this path called cancelConnection() and returned, dropping both onConnected and
        // onFailed — a silent "nothing happens" dead end (issue #620 secondary path).
        //
        // Single cancellation path: the new connection coroutine cancelAndJoin()s the
        // previous job — waiting for it (and any cleanup it performs) to fully unwind —
        // BEFORE tearing down the radio state and starting the fresh scan. Nothing can
        // interleave with the new scan.
        val staleAttemptInProgress = connectionState.value is ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Scanning
        if (staleAttemptInProgress) {
            Logger.d { "ensureConnection: Restarting in-progress connection with new callbacks" }
            _pendingConnectionCallback = null
            _isAutoConnecting.value = false
        }

        // Start new connection
        val staleJob = connectionJob
        connectionJob = null

        connectionJob = scope.launch {
            try {
                // Deterministic sequencing: previous attempt fully unwound first.
                staleJob?.cancelAndJoin()
                if (staleAttemptInProgress) {
                    bleRepository.stopScanning()
                    bleRepository.cancelConnection()
                }
                _isAutoConnecting.value = true
                _connectionError.value = null
                _pendingConnectionCallback = onConnected

                // Simple scan-and-connect matching parent repo behavior
                Logger.d { "ensureConnection: Starting scanAndConnect..." }
                val result = bleRepository.scanAndConnect(timeoutMs = 30000L)

                _isAutoConnecting.value = false

                if (result.isSuccess) {
                    // Wait briefly for connection state to propagate
                    delay(500)

                    // Check if we're actually connected
                    if (connectionState.value is ConnectionState.Connected) {
                        Logger.d { "ensureConnection: Connected successfully" }
                        onConnected()
                    } else {
                        // Wait a bit more for Connected state
                        val connected = withTimeoutOrNull(15000) {
                            connectionState
                                .filter { it is ConnectionState.Connected }
                                .first()
                        }
                        if (connected != null) {
                            Logger.d { "ensureConnection: Connected after waiting" }
                            onConnected()
                        } else {
                            Logger.w { "ensureConnection: Connection didn't complete" }
                            _connectionError.value = "Connection timeout"
                            _pendingConnectionCallback = null
                            onFailed()
                        }
                    }
                } else {
                    Logger.w { "ensureConnection: scanAndConnect failed: ${result.exceptionOrNull()?.message}" }
                    _connectionError.value = result.exceptionOrNull()?.message ?: "Connection failed"
                    _pendingConnectionCallback = null
                    onFailed()
                }
            } catch (e: CancellationException) {
                // H2: Don't swallow coroutine cancellation — propagate to caller
                throw e
            } catch (e: Exception) {
                Logger.e { "ensureConnection error: ${e.message}" }
                bleRepository.cancelConnection()
                _pendingConnectionCallback = null
                _isAutoConnecting.value = false
                _connectionError.value = "Error: ${e.message}"
                onFailed()
            }
        }
    }

    /**
     * Cancel any in-progress connection attempt and return to disconnected state.
     */
    fun cancelConnection() {
        Logger.d { "cancelConnection: Cancelling connection attempt" }
        connectionJob?.cancel()
        connectionJob = null
        _pendingConnectionCallback = null
        _isAutoConnecting.value = false
        scope.launch {
            bleRepository.stopScanning()
            bleRepository.cancelConnection()
        }
    }

    /**
     * Cancel the connection job (called from ViewModel cleanup).
     */
    fun cancelConnectionJob() {
        connectionJob?.cancel()
    }
}
