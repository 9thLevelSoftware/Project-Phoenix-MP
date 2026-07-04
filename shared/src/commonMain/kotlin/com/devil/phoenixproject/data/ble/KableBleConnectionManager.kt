package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.HardwareDetection
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the BLE connection lifecycle for Vitruvian machines.
 *
 * Extracted from KableBleRepository (Phase 12) — owns the Peripheral reference exclusively.
 * All connection lifecycle code (scan, connect with retry, disconnect, auto-reconnect,
 * notification subscriptions, device readiness, command sending) lives here.
 *
 * Communicates with the facade (KableBleRepository) exclusively via callbacks.
 * Does NOT own any Flow/StateFlow declarations — those stay in the facade.
 *
 * @param scope Coroutine scope for launching background work
 * @param logRepo Connection log repository for user-visible BLE logs
 * @param bleQueue BLE operation queue for serialized read/write
 * @param pollingEngine Metric polling engine for 4 polling loops
 * @param discoMode Disco mode easter egg manager
 * @param handleDetector Handle state detection for auto-start
 * @param onConnectionStateChanged Callback when connection state changes
 * @param onScannedDevicesChanged Callback when scanned device list changes
 * @param onReconnectionRequested Callback when auto-reconnect should be attempted
 * @param onCommandResponse Callback for command response opcode tracking
 * @param onRepEventFromCharacteristic Callback for rep events from REPS characteristic
 * @param onRepEventFromRx Callback for rep events from RX notifications (opcode 0x02)
 * @param onMetricFromRx Callback for metrics from RX notifications (opcode 0x01)
 * @param onDiagnosticData Callback for diagnostic snapshots from one-shot diagnostic reads
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleConnectionManager(
    private val scope: CoroutineScope,
    private val logRepo: ConnectionLogRepository,
    private val bleQueue: BleOperationQueue,
    private val pollingEngine: MetricPollingEngine,
    private val discoMode: DiscoMode,
    private val handleDetector: HandleStateDetector,
    // Callbacks for event routing to facade flows
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onScannedDevicesChanged: (List<ScannedDevice>) -> Unit,
    private val onReconnectionRequested: suspend (ReconnectionRequest) -> Unit,
    private val onCommandResponse: (UByte) -> Unit,
    // Callbacks for notification data routing
    private val onRepEventFromCharacteristic: (ByteArray) -> Unit,
    private val onRepEventFromRx: (ByteArray) -> Unit,
    private val onMetricFromRx: (ByteArray) -> Unit,
    private val onDiagnosticData: (DiagnosticPacket) -> Unit = {},
) {
    private val log = Logger.withTag("KableBleConnectionManager")

    companion object {
        /**
         * Issue #333: bounded retry for the workout CONFIG write on the small-MTU
         * compatibility path. Covers the transient "Write failed: Unknown"
         * (legacy writeCharacteristic() returned false — GATT momentarily busy)
         * seen on Pixel 6 Pro, which does NOT drop the connection.
         */
        internal const val CONFIG_WRITE_RETRY_COUNT = 2
        internal const val CONFIG_WRITE_RETRY_DELAY_MS = 250L
    }

    // -------------------------------------------------------------------------
    // Characteristic references from BleConstants
    // -------------------------------------------------------------------------
    private val txCharacteristic = BleConstants.txCharacteristic

    @Suppress("unused") // Vitruvian doesn't use standard NUS RX (6e400003)
    private val rxCharacteristic = BleConstants.rxCharacteristic
    private val monitorCharacteristic = BleConstants.monitorCharacteristic
    private val repsCharacteristic = BleConstants.repsCharacteristic
    private val diagnosticCharacteristic = BleConstants.diagnosticCharacteristic
    private val heuristicCharacteristic = BleConstants.heuristicCharacteristic
    private val versionCharacteristic = BleConstants.versionCharacteristic
    private val modeCharacteristic = BleConstants.modeCharacteristic
    private val firmwareRevisionCharacteristic = BleConstants.firmwareRevisionCharacteristic

    // -------------------------------------------------------------------------
    // State variables (9 mutable fields + 1 map) — owned exclusively by this manager
    // -------------------------------------------------------------------------

    /** THE central Peripheral reference — this manager owns it exclusively. */
    private var peripheral: Peripheral? = null

    /** Public read-only accessor for the current Peripheral reference. */
    val currentPeripheral: Peripheral? get() = peripheral

    /** Discovered advertisements keyed by identifier for connection lookup. */
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()

    /** Active scanning job reference. */
    private var scanJob: Job? = null

    /** Active state observer job — must be cancelled before launching a new one. */
    private var stateObserverJob: Job? = null

    /**
     * Issue #333 (v10 ready gate): job running onDeviceReady() for the current
     * connection attempt. App-level Connected is not published until it completes.
     * @Volatile for the same cross-dispatcher reassignment hazard as [readyGate].
     */
    @Volatile
    private var deviceReadyJob: Job? = null

    /**
     * Issue #333 (v10 ready gate): completed when ready-up finishes for the
     * current connection attempt. @Volatile because the connect retry loop
     * reassigns it on the caller's dispatcher while the state observer
     * coroutine reads it on [scope] — a stale read would complete the previous
     * attempt's gate and force a spurious initialization timeout.
     */
    @Volatile
    private var readyGate = CompletableDeferred<Unit>()

    /**
     * Issue #333: compatibility mode resolved ONCE per connection, at ready-up.
     * The persisted setting can change while connected, but MTU/heartbeat/
     * diagnostic choreography was already chosen for the live GATT connection —
     * mixing modes mid-connection would, e.g., take the compat CONFIG branch on
     * a connection that still has the large MTU. Setting changes apply on the
     * next connection, matching the Settings UI wording.
     */
    @Volatile
    private var compatibilityPathActive = false

    /** Connected device name (for logging). */
    private var connectedDeviceName: String = ""

    /** Connected device address (for logging). */
    private var connectedDeviceAddress: String = ""

    /** Flag to track explicit disconnect (to avoid auto-reconnect). */
    private var isExplicitDisconnect = false

    /**
     * Flag to track if we ever successfully connected (for auto-reconnect logic).
     * This prevents auto-reconnect from firing on the initial Disconnected state
     * when a Peripheral is first created (before connect() is even called).
     */
    private var wasEverConnected = false

    /** Detected firmware version (from DIS or proprietary characteristic). */
    private var detectedFirmwareVersion: String? = null

    /** Negotiated MTU (for diagnostic logging). */
    @Volatile
    private var negotiatedMtu: Int? = null

    // -------------------------------------------------------------------------
    // Local state tracking for stopScanning guard
    // -------------------------------------------------------------------------

    /**
     * Tracks the last connection state reported via callback.
     * Used by stopScanning() to guard against resetting state when not scanning.
     */
    private var lastReportedState: ConnectionState = ConnectionState.Disconnected

    // -------------------------------------------------------------------------
    // Command response flow (self-contained for awaitResponse)
    // -------------------------------------------------------------------------
    private val _commandResponses = MutableSharedFlow<UByte>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commandResponses: Flow<UByte> = _commandResponses.asSharedFlow()

    internal enum class LifecycleJob { SCAN, STATE_OBSERVER }

    internal fun isLifecycleJobActiveForTest(job: LifecycleJob): Boolean = when (job) {
        LifecycleJob.SCAN -> scanJob?.isActive == true
        LifecycleJob.STATE_OBSERVER -> stateObserverJob?.isActive == true
    }

    internal fun startFakeLifecycleJobsForTest() {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(Long.MAX_VALUE)
        }
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            delay(Long.MAX_VALUE)
        }
        onScannedDevicesChanged(
            listOf(
                ScannedDevice(
                    name = "Vee_Test",
                    address = "AA:BB:CC:DD:EE:FF",
                    rssi = -45,
                ),
            ),
        )
        discoveredAdvertisements.clear()
        reportConnectionState(ConnectionState.Scanning)
    }

    // -------------------------------------------------------------------------
    // Helper to update connection state via callback + local tracking
    // -------------------------------------------------------------------------
    private fun reportConnectionState(state: ConnectionState) {
        lastReportedState = state
        onConnectionStateChanged(state)
    }

    private fun clearConnectionState(clearScannedDevices: Boolean = false) {
        peripheral = null
        connectedDeviceName = ""
        connectedDeviceAddress = ""
        detectedFirmwareVersion = null
        negotiatedMtu = null
        wasEverConnected = false
        if (clearScannedDevices) {
            discoveredAdvertisements.clear()
            onScannedDevicesChanged(emptyList())
        }
    }

    // -------------------------------------------------------------------------
    // 1. startScanning()
    // -------------------------------------------------------------------------

    suspend fun startScanning(): Result<Unit> {
        log.i { "Starting BLE scan for Vitruvian devices" }
        logRepo.info(LogEventType.SCAN_START, "Starting BLE scan for Vitruvian devices")

        return try {
            // Cancel any existing scan job to prevent duplicates
            scanJob?.cancel()
            scanJob = null

            onScannedDevicesChanged(emptyList())
            discoveredAdvertisements.clear()
            reportConnectionState(ConnectionState.Scanning)

            // Track scanned devices locally for filtering logic
            var currentScannedDevices = emptyList<ScannedDevice>()

            scanJob = scope.launch {
                try {
                    withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS) {
                        Scanner {
                            // No specific filters - we'll filter manually
                        }
                            .advertisements
                            .onEach { advertisement ->
                                // Debug logging for all advertisements
                                log.d {
                                    "RAW ADV: name=${advertisement.name}, id=${advertisement.identifier}, uuids=${advertisement.uuids}, rssi=${advertisement.rssi}"
                                }
                            }
                            .filter { advertisement ->
                                // Filter by name if available
                                val name = advertisement.name
                                if (name != null) {
                                    val isVitruvian = name.startsWith("Vee_", ignoreCase = true) ||
                                        name.startsWith("VIT", ignoreCase = true) ||
                                        name.startsWith("Vitruvian", ignoreCase = true)
                                    if (isVitruvian) {
                                        log.i { "Found Vitruvian by name: $name" }
                                    } else {
                                        log.d { "Ignoring device: $name (not Vitruvian)" }
                                    }
                                    return@filter isVitruvian
                                }

                                // Check for Vitruvian service UUIDs (mServiceUuids)
                                val serviceUuids = advertisement.uuids
                                val hasVitruvianServiceUuid = serviceUuids.any { uuid ->
                                    val uuidStr = uuid.toString().lowercase()
                                    uuidStr.startsWith("0000fef3") ||
                                        uuidStr == BleConstants.NUS_SERVICE_UUID_STRING
                                }

                                if (hasVitruvianServiceUuid) {
                                    log.i { "Found Vitruvian by service UUID: ${advertisement.identifier}" }
                                    return@filter true
                                }

                                // CRITICAL: Check for FEF3 service data
                                // The Vitruvian device advertises FEF3 in serviceData, not serviceUuids!
                                // In Kable, serviceData is accessed differently - try to get FEF3 directly
                                val fef3Uuid = try {
                                    Uuid.parse("0000fef3-0000-1000-8000-00805f9b34fb")
                                } catch (_: Exception) {
                                    null
                                }

                                val hasVitruvianServiceData = if (fef3Uuid != null) {
                                    // Try to get data for FEF3 service UUID
                                    val fef3Data = advertisement.serviceData(fef3Uuid)
                                    if (fef3Data != null && fef3Data.isNotEmpty()) {
                                        log.i {
                                            "Found Vitruvian by FEF3 serviceData: ${advertisement.identifier}, data size: ${fef3Data.size}"
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }

                                hasVitruvianServiceData
                            }
                            .onEach { advertisement ->
                                @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // Needed for iOS where identifier is Uuid
                                val identifier = advertisement.identifier.toString()
                                val advertisedName = advertisement.name
                                val hasRealName = advertisedName != null &&
                                    (
                                        advertisedName.startsWith("Vee_", ignoreCase = true) ||
                                            advertisedName.startsWith("VIT", ignoreCase = true)
                                        )

                                // Use name if available, otherwise use identifier as placeholder
                                val name = advertisedName ?: "Vitruvian ($identifier)"

                                // Skip devices without a real Vitruvian name if we already have one
                                if (!hasRealName) {
                                    val alreadyHaveRealDevice = currentScannedDevices.any { existing ->
                                        existing.name.startsWith("Vee_", ignoreCase = true) ||
                                            existing.name.startsWith("VIT", ignoreCase = true)
                                    }
                                    if (alreadyHaveRealDevice) {
                                        log.d { "Skipping nameless device $identifier - already have named Vitruvian device" }
                                        return@onEach
                                    }
                                }

                                // Only log if this is a new device
                                if (!discoveredAdvertisements.containsKey(identifier)) {
                                    log.d { "Discovered device: $name ($identifier) RSSI: ${advertisement.rssi}" }
                                    logRepo.info(
                                        LogEventType.DEVICE_FOUND,
                                        "Found Vitruvian device",
                                        name,
                                        identifier,
                                        "RSSI: ${advertisement.rssi} dBm",
                                    )
                                }

                                // Store advertisement reference
                                discoveredAdvertisements[identifier] = advertisement

                                // Update scanned devices list
                                val device = ScannedDevice(
                                    name = name,
                                    address = identifier,
                                    rssi = advertisement.rssi,
                                )
                                var devices = currentScannedDevices.toMutableList()

                                // If this is a real-named device, remove any placeholder devices first
                                // (same physical device can advertise with different identifiers)
                                if (hasRealName) {
                                    devices = devices.filter { existing ->
                                        existing.name.startsWith("Vee_", ignoreCase = true) ||
                                            existing.name.startsWith("VIT", ignoreCase = true) ||
                                            existing.address == identifier // Keep if same address (will update below)
                                    }.toMutableList()
                                }

                                val existingIndex = devices.indexOfFirst { it.address == identifier }
                                if (existingIndex >= 0) {
                                    devices[existingIndex] = device
                                } else {
                                    devices.add(device)
                                }
                                val sorted = devices.sortedByDescending { it.rssi }
                                currentScannedDevices = sorted
                                onScannedDevicesChanged(sorted)
                            }
                            .catch { e ->
                                e.rethrowIfCancellation()
                                log.e { "Scan error: ${e.message}" }
                                logRepo.error(LogEventType.ERROR, "BLE scan failed", details = e.message)
                                // Return to Disconnected instead of Error for scan failures - user can retry
                                reportConnectionState(ConnectionState.Disconnected)
                            }
                            .collect {}
                    }
                    // withTimeoutOrNull returned null = timeout reached, auto-stop scan
                    if (lastReportedState == ConnectionState.Scanning) {
                        log.w { "Scan timeout reached (${BleConstants.SCAN_TIMEOUT_MS}ms)" }
                        logRepo.info(
                            LogEventType.SCAN_STOP,
                            "Scan auto-stopped after timeout",
                            details = "Found ${discoveredAdvertisements.size} device(s) in ${BleConstants.SCAN_TIMEOUT_MS}ms",
                        )
                        reportConnectionState(ConnectionState.Disconnected)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(e) { "Scan error: ${e.message}" }
                    logRepo.error(LogEventType.ERROR, "BLE scan failed", details = e.message)
                    reportConnectionState(ConnectionState.Disconnected)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "Failed to start scanning: ${e.message}" }
            reportConnectionState(ConnectionState.Disconnected)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 2. stopScanning()
    // -------------------------------------------------------------------------

    suspend fun stopScanning() {
        log.i { "Stopping BLE scan" }
        logRepo.info(
            LogEventType.SCAN_STOP,
            "BLE scan stopped",
            details = "Found ${discoveredAdvertisements.size} Vitruvian device(s)",
        )
        scanJob?.cancel()
        scanJob = null
        if (lastReportedState == ConnectionState.Scanning) {
            reportConnectionState(ConnectionState.Disconnected)
        }
    }

    /**
     * Terminal lifecycle cleanup for app/repository teardown.
     * Cancels background lifecycle jobs and clears connection-facing state.
     */
    suspend fun shutdown() {
        log.i { "Shutting down BLE connection manager" }
        isExplicitDisconnect = true

        scanJob?.cancel()
        scanJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null
        pollingEngine.stopAll()
        discoMode.shutdown()
        handleDetector.disable()
        handleDetector.reset()

        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Shutdown disconnect error (non-fatal): ${e.message}" }
        } finally {
            clearConnectionState(clearScannedDevices = true)
            reportConnectionState(ConnectionState.Disconnected)
        }
    }

    // -------------------------------------------------------------------------
    // 3. scanAndConnect()
    // -------------------------------------------------------------------------

    /**
     * Scan for first Vitruvian device and connect immediately.
     * This is the simple flow matching parent repo behavior.
     */
    suspend fun scanAndConnect(timeoutMs: Long = 30000L): Result<Unit> {
        log.i { "scanAndConnect: Starting scan and auto-connect (timeout: ${timeoutMs}ms)" }
        logRepo.info(LogEventType.SCAN_START, "Scan and connect started")

        // Connection cleanup is handled inside connect() to ensure consistent behavior.
        reportConnectionState(ConnectionState.Scanning)
        onScannedDevicesChanged(emptyList())
        discoveredAdvertisements.clear()

        return try {
            // Find first Vitruvian device with a real name
            val advertisement = withTimeoutOrNull(timeoutMs) {
                Scanner {}
                    .advertisements
                    .filter { adv ->
                        val name = adv.name
                        name != null && (
                            name.startsWith("Vee_", ignoreCase = true) ||
                                name.startsWith("VIT", ignoreCase = true)
                            )
                    }
                    .first()
            }

            if (advertisement == null) {
                log.w { "scanAndConnect: No Vitruvian device found within timeout" }
                logRepo.error(LogEventType.SCAN_STOP, "No device found", details = "Timeout after ${timeoutMs}ms")
                reportConnectionState(ConnectionState.Disconnected)
                return Result.failure(Exception("No Vitruvian device found"))
            }

            @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // Needed for iOS where identifier is Uuid
            val identifier = advertisement.identifier.toString()
            val name = advertisement.name ?: "Vitruvian"
            log.i { "scanAndConnect: Found device $name ($identifier), connecting..." }

            // Store for connection
            discoveredAdvertisements[identifier] = advertisement
            val device = ScannedDevice(name = name, address = identifier, rssi = advertisement.rssi)
            onScannedDevicesChanged(listOf(device))

            // Connect to it
            connect(device)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "scanAndConnect failed: ${e.message}" }
            reportConnectionState(ConnectionState.Disconnected)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 4. connect()
    // -------------------------------------------------------------------------

    suspend fun connect(device: ScannedDevice): Result<Unit> {
        log.i { "Connecting to device: ${device.name}" }
        logRepo.info(
            LogEventType.CONNECT_START,
            "Connecting to device",
            device.name,
            device.address,
        )

        // Clean up any existing connection first (matches parent repo)
        // Prevents "dangling GATT connections" on Android 16/Pixel 7
        cleanupExistingConnection()

        // F005: start every new connection lifecycle with a clean
        // explicit-disconnect flag. A prior disconnect/cancel sets this true, and
        // if that teardown did not flow through the State.Disconnected handler that
        // resets it, the flag could leak into this new connection and suppress
        // auto-reconnect on the next genuine unexpected drop.
        isExplicitDisconnect = false

        reportConnectionState(ConnectionState.Connecting)

        val advertisement = discoveredAdvertisements[device.address]
        if (advertisement == null) {
            log.e { "Advertisement not found for device: ${device.address}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Device not found in scanned list",
                device.name,
                device.address,
            )
            // Return to Disconnected - device may have gone out of range, user can retry
            reportConnectionState(ConnectionState.Disconnected)
            return Result.failure(IllegalStateException("Device not found in scanned list"))
        }

        // Store device info for logging
        connectedDeviceName = device.name
        connectedDeviceAddress = device.address

        return try {
            stopScanning()

            // Create peripheral
            // Note: MTU negotiation is handled in onDeviceReady() via expect/actual
            // pattern (requestMtuIfSupported) since Kable's requestMtu requires
            // platform-specific AndroidPeripheral cast
            peripheral = Peripheral(advertisement)

            // Cancel any stale state observer before launching a new one (H2 fix)
            stateObserverJob?.cancel()
            deviceReadyJob?.cancel()
            deviceReadyJob = null
            readyGate = CompletableDeferred()

            // Observe connection state
            stateObserverJob = peripheral?.state
                ?.onEach { state ->
                    when (state) {
                        is State.Connecting -> {
                            reportConnectionState(ConnectionState.Connecting)
                        }

                        is State.Connected -> {
                            // Mark that we successfully connected (for auto-reconnect logic)
                            wasEverConnected = true
                            log.i { "Connection established to ${device.name}" }
                            logRepo.info(
                                LogEventType.CONNECT_SUCCESS,
                                "Device connected successfully",
                                connectedDeviceName,
                                connectedDeviceAddress,
                            )
                            // Issue #333 (v10 ready gate): do not publish Connected until MTU,
                            // services, critical notifications, and baseline polling are
                            // initialized. The UI observes Connected and immediately restores
                            // the saved LED color scheme, so publishing early injects a
                            // 34-byte write directly into the ready-up GATT sequence.
                            if (deviceReadyJob == null && !readyGate.isCompleted) {
                                deviceReadyJob = scope.launch {
                                    try {
                                        withTimeout(BleConstants.CONNECTION_TIMEOUT_MS) {
                                            onDeviceReady()
                                        }
                                        reportConnectionState(
                                            ConnectionState.Connected(
                                                deviceName = device.name,
                                                deviceAddress = device.address,
                                                hardwareModel = HardwareDetection.detectModel(device.name),
                                            ),
                                        )
                                        readyGate.complete(Unit)
                                    } catch (e: TimeoutCancellationException) {
                                        val failure = BleDeviceInitializationException(
                                            "Device initialization timeout after ${BleConstants.CONNECTION_TIMEOUT_MS}ms",
                                            e,
                                        )
                                        log.e(e) { failure.message ?: "Device initialization timeout" }
                                        logRepo.error(
                                            LogEventType.ERROR,
                                            "Device initialization timeout",
                                            connectedDeviceName,
                                            connectedDeviceAddress,
                                            failure.message,
                                        )
                                        if (!readyGate.isCompleted) {
                                            readyGate.completeExceptionally(failure)
                                        }
                                    } catch (e: CancellationException) {
                                        readyGate.cancel(e)
                                        throw e
                                    } catch (e: Exception) {
                                        log.e(e) { "onDeviceReady failed: ${e.message}" }
                                        logRepo.error(
                                            LogEventType.ERROR,
                                            "Device initialization failed",
                                            connectedDeviceName,
                                            connectedDeviceAddress,
                                            e.message,
                                        )
                                        if (!readyGate.isCompleted) {
                                            readyGate.completeExceptionally(
                                                BleDeviceInitializationException(
                                                    "Device initialization failed: ${e.message}",
                                                    e,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is State.Disconnecting -> {
                            log.d { "Disconnecting from device" }
                            logRepo.info(
                                LogEventType.DISCONNECT,
                                "Device disconnecting",
                                connectedDeviceName,
                                connectedDeviceAddress,
                            )
                        }

                        is State.Disconnected -> {
                            // Capture device info and connection state BEFORE clearing
                            val deviceName = connectedDeviceName
                            val deviceAddress = connectedDeviceAddress
                            val hadConnection = wasEverConnected

                            // Only process disconnect if we were actually connected
                            if (hadConnection) {
                                // Issue #333 (v10 ready gate): fail the gate so a connect
                                // attempt waiting on ready-up surfaces the disconnect
                                // instead of timing out.
                                if (!readyGate.isCompleted) {
                                    readyGate.completeExceptionally(
                                        BleDeviceInitializationException("Device disconnected during initialization"),
                                    )
                                }
                                deviceReadyJob?.cancel()
                                deviceReadyJob = null

                                logRepo.info(
                                    LogEventType.DISCONNECT,
                                    "Device disconnected",
                                    deviceName,
                                    deviceAddress,
                                )

                                // Stop all polling jobs
                                pollingEngine.stopAll()
                                reportConnectionState(ConnectionState.Disconnected)
                                peripheral = null
                                connectedDeviceName = ""
                                connectedDeviceAddress = ""
                            } else {
                                // This is the initial Disconnected state when Peripheral is created
                                // Don't reset state or peripheral - we're about to call connect()
                                log.d { "Peripheral initial state: Disconnected (awaiting connect() call)" }
                                return@onEach // Skip the rest of this handler
                            }

                            // Request auto-reconnect ONLY if:
                            // 1. We were previously connected (wasEverConnected)
                            // 2. This was NOT an explicit disconnect
                            // 3. We have a valid device address
                            if (hadConnection && !isExplicitDisconnect && deviceAddress.isNotEmpty()) {
                                log.i { "Requesting auto-reconnect to $deviceName ($deviceAddress)" }
                                scope.launch {
                                    onReconnectionRequested(
                                        ReconnectionRequest(
                                            deviceName = deviceName,
                                            deviceAddress = deviceAddress,
                                            reason = "unexpected_disconnect",
                                            timestamp = currentTimeMillis(),
                                        ),
                                    )
                                }
                            }

                            // Reset flags for next connection cycle
                            isExplicitDisconnect = false
                            wasEverConnected = false
                        }
                    }
                }
                ?.launchIn(scope)

            // Connection with retry logic and timeout protection
            var lastException: Exception? = null
            for (attempt in 1..BleConstants.Timing.CONNECTION_RETRY_COUNT) {
                try {
                    log.d { "Connection attempt $attempt of ${BleConstants.Timing.CONNECTION_RETRY_COUNT}" }
                    readyGate = CompletableDeferred()
                    deviceReadyJob?.cancel()
                    deviceReadyJob = null

                    // Wrap connection in timeout to prevent zombie "Connecting" state
                    withTimeout(BleConstants.CONNECTION_TIMEOUT_MS) {
                        peripheral?.connect()
                        log.i { "Connection initiated to ${device.name}, waiting for established state..." }

                        // Wait for connection to actually establish (state becomes Connected)
                        // The state observer will emit Connected when ready
                        peripheral?.state?.first { it is State.Connected }
                        log.i { "Connection established to ${device.name}" }
                    }

                    // Issue #333 (v10 ready gate): connect() succeeding only means the
                    // physical link is up. Wait for ready-up (MTU path, services, critical
                    // notifications, polling) before reporting success to the caller.
                    awaitBleReadyGate(readyGate) {
                        deviceReadyJob?.cancel()
                        deviceReadyJob = null
                    }

                    return Result.success(Unit) // Success, exit retry loop
                } catch (_: TimeoutCancellationException) {
                    lastException = Exception("Connection timeout after ${BleConstants.CONNECTION_TIMEOUT_MS}ms")
                    log.w { "Connection attempt $attempt timed out after ${BleConstants.CONNECTION_TIMEOUT_MS}ms" }
                    if (attempt < BleConstants.Timing.CONNECTION_RETRY_COUNT) {
                        delay(BleConstants.Timing.CONNECTION_RETRY_DELAY_MS)
                    }
                } catch (e: BleDeviceInitializationException) {
                    // Ready-up failed with a bounded, descriptive error — retrying the
                    // physical connection would repeat the same initialization failure.
                    lastException = e
                    log.w { "Connection attempt $attempt failed during device initialization: ${e.message}" }
                    break
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    lastException = e
                    log.w { "Connection attempt $attempt failed: ${e.message}" }
                    if (attempt < BleConstants.Timing.CONNECTION_RETRY_COUNT) {
                        delay(BleConstants.Timing.CONNECTION_RETRY_DELAY_MS)
                    }
                }
            }

            // All retries failed - cleanup and return to disconnected state
            deviceReadyJob?.cancel()
            deviceReadyJob = null
            peripheral?.disconnect()
            peripheral = null
            reportConnectionState(ConnectionState.Disconnected)
            throw lastException ?: Exception("Connection failed after ${BleConstants.Timing.CONNECTION_RETRY_COUNT} attempts")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "Connection failed: ${e.message}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Failed to connect to device",
                device.name,
                device.address,
                e.message,
            )
            // Return to Disconnected instead of Error - connection failures are retryable
            reportConnectionState(ConnectionState.Disconnected)
            peripheral = null
            connectedDeviceName = ""
            connectedDeviceAddress = ""
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 5. onDeviceReady()
    // -------------------------------------------------------------------------

    /**
     * Called when the device is connected and ready.
     * Requests MTU, starts observing notifications, and starts heartbeat.
     */
    private suspend fun onDeviceReady() {
        val p = peripheral ?: return
        compatibilityPathActive = BleCompatibilityMode.isActive()
        val useCompatibilityPath = compatibilityPathActive

        if (useCompatibilityPath) {
            // Issue #333: official small-MTU path. Android 14+ coerces the FIRST
            // app-side requestMtu() to ATT MTU 517, and at 517 the 96-byte workout
            // CONFIG write goes out as a single large ATT PDU that wedges the
            // BCM4389 controller on Pixel 6/7 (write lane stuck busy → GATT 133 →
            // disconnect). Making NO MTU request keeps the default 23-byte MTU, so
            // large writes are chunked by the ATT long-write procedure instead —
            // the same path the official Vitruvian app uses.
            negotiatedMtu = null
            log.i { "[#333 compat] Small-MTU compatibility path active (${BleCompatibilityMode.summary()})" }
            logRepo.info(
                LogEventType.MTU_CHANGED,
                "[#333 compat] Skipping requestMtu() (small-MTU compatibility path)",
                connectedDeviceName,
                connectedDeviceAddress,
                BleCompatibilityMode.summary(),
            )
            try {
                val maxWriteLength = p.maximumWriteValueLengthForType(WriteType.WithResponse)
                log.i { "[#333 compat] WithResponse max write length: $maxWriteLength bytes" }
                logRepo.debug(
                    LogEventType.MTU_CHANGED,
                    "[#333 compat] WithResponse max write length",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "$maxWriteLength bytes",
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "[#333 compat] Could not read WithResponse max write length: ${e.message}" }
            }
        } else {
            // Request High Connection Priority (Android only - via expect/actual extension)
            // Critical for maintaining ~20Hz polling rate without lag
            p.requestHighPriority()

            // Request MTU negotiation (Android only - iOS handles automatically)
            // CRITICAL: Without MTU negotiation, BLE uses default 23-byte MTU (20 usable)
            // Vitruvian commands require up to 96 bytes for activation frames
            val mtu = p.requestMtuIfSupported(BleConstants.Timing.DESIRED_MTU)
            if (mtu != null) {
                negotiatedMtu = mtu
                log.i { "MTU negotiated: $mtu bytes (requested: ${BleConstants.Timing.DESIRED_MTU})" }
                logRepo.info(
                    LogEventType.MTU_CHANGED,
                    "MTU negotiated: $mtu bytes",
                    connectedDeviceName,
                    connectedDeviceAddress,
                )
            } else {
                // iOS returns null (handled by OS), or Android negotiation failed
                log.i { "MTU negotiation: using system default (iOS) or failed (Android)" }
                logRepo.debug(
                    LogEventType.MTU_CHANGED,
                    "MTU using system default",
                )
            }
        }

        // Verify services are discovered and log GATT structure
        try {
            // p.services is a StateFlow<List<DiscoveredService>?> - access .value
            val servicesList = p.services.value
            if (servicesList == null) {
                log.w { "No services discovered - device may not be fully ready" }
                logRepo.warning(
                    LogEventType.SERVICE_DISCOVERED,
                    "No services found after connection",
                    connectedDeviceName,
                    connectedDeviceAddress,
                )
            } else {
                // Log detailed GATT structure with characteristic properties
                log.i { "========== GATT SERVICE DISCOVERY ==========" }
                log.i { "Found ${servicesList.size} services" }
                servicesList.forEach { service ->
                    log.i { "  SERVICE: ${service.serviceUuid}" }
                    service.characteristics.forEach { char ->
                        // Properties.toString() shows the raw property flags value
                        log.i { "    CHAR: ${char.characteristicUuid} props=${char.properties}" }
                    }
                }
                log.i { "============================================" }

                // Check specifically for NUS TX characteristic (6e400002)
                val nusService = servicesList.find {
                    it.serviceUuid.toString().lowercase().contains("6e400001")
                }
                if (nusService != null) {
                    log.i { "NUS Service found: ${nusService.serviceUuid}" }
                    val txChar = nusService.characteristics.find {
                        it.characteristicUuid.toString().lowercase().contains("6e400002")
                    }
                    if (txChar != null) {
                        log.i { "NUS TX (6e400002) found, properties: ${txChar.properties}" }
                    } else {
                        log.e { "NUS TX characteristic (6e400002) NOT FOUND in NUS service!" }
                    }
                    val rxChar = nusService.characteristics.find {
                        it.characteristicUuid.toString().lowercase().contains("6e400003")
                    }
                    if (rxChar != null) {
                        log.i { "NUS RX (6e400003) found, properties: ${rxChar.properties}" }
                    } else {
                        log.e { "NUS RX characteristic (6e400003) NOT FOUND in NUS service!" }
                    }
                } else {
                    log.w { "NUS Service (6e400001) NOT FOUND - checking all services for NUS chars..." }
                    // Search all services for the TX/RX characteristics
                    servicesList.forEach { service ->
                        service.characteristics.forEach { char ->
                            val uuid = char.characteristicUuid.toString().lowercase()
                            if (uuid.contains("6e400002") || uuid.contains("6e400003")) {
                                log.i { "Found ${char.characteristicUuid} in service ${service.serviceUuid}, props=${char.properties}" }
                            }
                        }
                    }
                }

                logRepo.info(
                    LogEventType.SERVICE_DISCOVERED,
                    "GATT services discovered",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "Services: ${servicesList.size}",
                )
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "Failed to enumerate services: ${e.message}" }
            logRepo.warning(
                LogEventType.SERVICE_DISCOVERED,
                "Failed to access services",
                connectedDeviceName,
                connectedDeviceAddress,
                e.message,
            )
        }

        if (useCompatibilityPath) {
            // Issue #333: on the compatibility path, request high priority only after
            // service discovery — matching the tested v14 choreography, which keeps
            // the ready-up window quiet until discovery completes.
            p.requestHighPriority()
            log.i { "[#333 compat] High connection priority requested after service discovery" }
            logRepo.debug(
                LogEventType.SERVICE_DISCOVERED,
                "[#333 compat] High priority requested after service discovery",
                connectedDeviceName,
                connectedDeviceAddress,
            )
        }

        logRepo.info(
            LogEventType.SERVICE_DISCOVERED,
            if (useCompatibilityPath) {
                "[#333 compat] Device ready, starting notifications without heartbeat"
            } else {
                "Device ready, starting notifications and heartbeat"
            },
            connectedDeviceName,
            connectedDeviceAddress,
        )

        startObservingNotifications()
    }

    // -------------------------------------------------------------------------
    // 6. startObservingNotifications()
    // -------------------------------------------------------------------------

    private suspend fun startObservingNotifications() {
        val p = peripheral ?: return

        logRepo.info(
            LogEventType.NOTIFICATION,
            "Enabling BLE notifications and starting polling (matching parent repo)",
            connectedDeviceName,
            connectedDeviceAddress,
        )

        // NOTE: Standard NUS RX (6e400003) does NOT exist on Vitruvian devices.
        // The device uses custom characteristics for notifications instead.
        // Skipping observation of non-existent rxCharacteristic to avoid errors.
        // Command responses (if any) come through device-specific characteristics.

        // ===== CRITICAL NOTIFICATION: REPS (rep counting) =====
        // RCA (2026-06-28): Kable issues the reps CCCD (descriptor) write when this
        // flow is first collected, OUTSIDE BleOperationQueue. If it races another
        // in-flight GATT op during setup it fails with ERROR_GATT_WRITE_REQUEST_BUSY
        // and the old code tore the subscription down permanently — rep counting was
        // silently dead until a manual Bluetooth toggle. We now (1) subscribe reps
        // FIRST and alone, and (2) retry/resubscribe on transient busy so it
        // self-heals. [repsReady] is released once the reps subscription settles —
        // either the CCCD write lands OR it permanently gives up — so the gated work
        // below never waits longer than necessary.
        val repsReady = CompletableDeferred<Unit>()
        scope.launch {
            observeRepsWithRetry(p) { repsReady.complete(Unit) }
        }

        // ===== Everything else waits for the reps CCCD write to land (bounded) =====
        // Deferring the firmware reads, non-critical observes, and the polling read
        // flood until reps is subscribed gives the critical CCCD write a
        // contention-free window, so it normally succeeds on the first attempt.
        // Fail-open: if reps never subscribes we still start polling after a timeout.
        // Issue #333 (v10 ready gate): this runs inline (suspend) so the app-level
        // Connected state is not published until notifications + polling are up.
        withTimeoutOrNull(BleConstants.Timing.REPS_SUBSCRIBE_READY_TIMEOUT_MS) {
            repsReady.await()
        }
        if (peripheral !== p) return // disconnected/replaced while we waited

        if (!compatibilityPathActive) {
            // Firmware/version reads (best effort, diagnostic only) — own coroutine so
            // their up-to-2s timeouts never delay polling start.
            scope.launch {
                tryReadFirmwareVersion(p)
                tryReadVitruvianVersion(p)
            }
        } else {
            // Issue #333: the compatibility path stays quiet before ready-up —
            // best-effort reads must not compete with critical notification setup.
            log.i { "[#333 compat] Skipping pre-ready diagnostic version reads" }
            logRepo.debug(
                LogEventType.NOTIFICATION,
                "[#333 compat] Skipping pre-ready diagnostic version reads",
                connectedDeviceName,
                connectedDeviceAddress,
            )
        }

        // Non-critical notifications (logging only): VERSION + MODE.
        startNonCriticalObserve(p, versionCharacteristic, "VERSION")
        startNonCriticalObserve(p, modeCharacteristic, "MODE")

        // ===== POLLING (delegated to MetricPollingEngine) =====
        discoMode.stop()
        val includeHeartbeat = !compatibilityPathActive
        if (!includeHeartbeat) {
            logRepo.info(
                LogEventType.NOTIFICATION,
                "[#333 compat] BLE ready gate complete; starting polling without heartbeat",
                connectedDeviceName,
                connectedDeviceAddress,
            )
        }
        pollingEngine.startAll(p, includeHeartbeat = includeHeartbeat)
    }

    /**
     * Observe the REPS characteristic with bounded busy-retry and automatic
     * resubscribe (CRITICAL for rep counting).
     *
     * The reps notification CCCD (descriptor) write is performed by Kable's
     * [Peripheral.observe] flow on first collection, OUTSIDE [bleQueue], so it can
     * race other in-flight GATT operations during connection setup and fail with
     * Android `ERROR_GATT_WRITE_REQUEST_BUSY` ("WriteRequestBusy"). The previous
     * implementation caught that error once and let the flow complete, leaving reps
     * permanently unsubscribed for the whole session. This retries the subscription
     * (mirroring [BleOperationQueue.write]'s busy-retry) so a transient collision
     * self-heals instead of requiring the user to toggle Bluetooth.
     *
     * @param p the peripheral to observe; the loop exits if it is replaced/disconnected
     * @param onSettled invoked exactly once when the subscription settles — either the
     *   CCCD write succeeds OR retries are permanently exhausted. Lets the caller
     *   release work gated on the subscription without waiting for a timeout.
     */
    private suspend fun observeRepsWithRetry(p: Peripheral, onSettled: () -> Unit) {
        var attempt = 0
        while (peripheral === p) {
            try {
                log.i { "Starting REPS characteristic notifications (attempt ${attempt + 1})" }
                p.observe(repsCharacteristic) {
                    // onSubscription: CCCD write landed — reps notifications are live.
                    attempt = 0
                    onSettled()
                    log.i { "REPS notifications enabled" }
                }
                    .collect { data ->
                        log.d { "REPS notification received: ${data.size} bytes" }
                        onRepEventFromCharacteristic(data)
                    }
                // Flow completed without error — only happens on cancel/disconnect.
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection gone? Stop — a fresh connect() will resubscribe.
                if (peripheral !== p) {
                    log.d { "Reps observe stopped: peripheral replaced/disconnected" }
                    return
                }
                attempt++
                val transient = isTransientObservationError(e)
                log.e { "Reps observation error (attempt $attempt, transient=$transient): ${e.message}" }
                if (!transient || attempt >= BleConstants.Timing.REPS_SUBSCRIBE_MAX_ATTEMPTS) {
                    log.e { "Reps subscription failed permanently after $attempt attempt(s) — rep counting unavailable" }
                    logRepo.error(
                        LogEventType.ERROR,
                        "Rep counting unavailable — reconnect required",
                        connectedDeviceName,
                        connectedDeviceAddress,
                        "${e.message} (after $attempt attempt(s), transient=$transient)",
                    )
                    // Release the gate now so polling does not wait out the full
                    // REPS_SUBSCRIBE_READY_TIMEOUT_MS — there is nothing left to wait for.
                    onSettled()
                    return
                }
                logRepo.warning(
                    LogEventType.NOTIFICATION,
                    "Reps notification busy — retrying",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "${e.message} (attempt $attempt/${BleConstants.Timing.REPS_SUBSCRIBE_MAX_ATTEMPTS})",
                )
                delay(repsBackoffMs(attempt))
            }
        }
    }

    /**
     * Observe a non-critical (logging-only) notification characteristic. Failures
     * are logged at warn and never affect the connection — unlike reps, these are
     * not retried because no app behavior depends on them.
     */
    private fun startNonCriticalObserve(p: Peripheral, characteristic: Characteristic, label: String) {
        scope.launch {
            try {
                log.d { "Starting $label characteristic notifications" }
                p.observe(characteristic)
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.w { "$label observation error (non-fatal): ${e.message}" }
                    }
                    .collect { data ->
                        log.d { "$label notification: ${data.size} bytes" }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.d { "$label notifications not available (expected): ${e.message}" }
            }
        }
    }

    /**
     * Exponential backoff (ms) for reps resubscribe attempts: 100, 200, 400, 800,
     * capped at [BleConstants.Timing.REPS_SUBSCRIBE_BACKOFF_MAX_MS].
     */
    internal fun repsBackoffMs(attempt: Int): Long =
        // coerceIn(0, 30) guards the shift amount: a stray large attempt can never
        // wrap the Long shift (Kotlin shifts mod 64); 30 bits is far beyond the cap.
        (BleConstants.Timing.REPS_SUBSCRIBE_BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 30))
            .coerceAtMost(BleConstants.Timing.REPS_SUBSCRIBE_BACKOFF_MAX_MS)

    /**
     * Classify an observation/CCCD-write failure as transient (worth retrying).
     *
     * Defaults to transient so busy-variant messages we have not catalogued (the
     * exact failure mode behind the original bug) are still retried; only clear
     * disconnect/cancel signals are treated as permanent. Retries are bounded by
     * [BleConstants.Timing.REPS_SUBSCRIBE_MAX_ATTEMPTS], so an over-classification
     * costs at most a few short backoffs.
     */
    internal fun isTransientObservationError(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: return true
        val permanentMarkers = listOf("not connected", "disconnected", "cancel")
        return permanentMarkers.none { msg.contains(it) }
    }

    // -------------------------------------------------------------------------
    // 7. tryReadFirmwareVersion()
    // -------------------------------------------------------------------------

    /**
     * Try to read firmware version from Device Information Service (DIS).
     * This is purely diagnostic - failures are logged but don't affect connection.
     */
    private suspend fun tryReadFirmwareVersion(p: Peripheral) {
        try {
            val data = withTimeoutOrNull(2000L) {
                bleQueue.read { p.read(firmwareRevisionCharacteristic) }
            }
            if (data != null && data.isNotEmpty()) {
                detectedFirmwareVersion = data.decodeToString().trim()
                log.i { "FIRMWARE VERSION: $detectedFirmwareVersion" }
                logRepo.info(
                    LogEventType.CONNECT_SUCCESS,
                    "Firmware version detected: $detectedFirmwareVersion",
                    connectedDeviceName,
                    connectedDeviceAddress,
                )
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.d { "Device Information Service not available (expected): ${e.message}" }
        }
    }

    // -------------------------------------------------------------------------
    // 8. tryReadVitruvianVersion()
    // -------------------------------------------------------------------------

    /**
     * Try to read proprietary Vitruvian VERSION characteristic.
     * Contains hardware/firmware info in a proprietary format.
     */
    private suspend fun tryReadVitruvianVersion(p: Peripheral) {
        try {
            val data = withTimeoutOrNull(2000L) {
                bleQueue.read { p.read(versionCharacteristic) }
            }
            if (data != null && data.isNotEmpty()) {
                val hexString = data.joinToString(" ") { it.toHexString() }
                log.i { "Vitruvian VERSION characteristic: ${data.size} bytes - $hexString" }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.d { "Vitruvian VERSION characteristic not readable (expected): ${e.message}" }
        }
    }

    // -------------------------------------------------------------------------
    // 9. disconnect()
    // -------------------------------------------------------------------------

    suspend fun disconnect() {
        log.i { "Disconnecting (explicit)" }
        isExplicitDisconnect = true // Mark as explicit disconnect to prevent auto-reconnect

        // Cancel all polling jobs
        pollingEngine.stopAll()

        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "Disconnect error: ${e.message}" }
        }
        clearConnectionState()
        reportConnectionState(ConnectionState.Disconnected)
    }

    // -------------------------------------------------------------------------
    // 10. cancelConnection()
    // -------------------------------------------------------------------------

    suspend fun cancelConnection() {
        log.i { "Cancelling in-progress connection" }
        isExplicitDisconnect = true // Prevent auto-reconnect
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e { "Cancel connection error: ${e.message}" }
        }
        clearConnectionState()
        reportConnectionState(ConnectionState.Disconnected)
    }

    // -------------------------------------------------------------------------
    // 11. cleanupExistingConnection()
    // -------------------------------------------------------------------------

    /**
     * Clean up any existing connection before creating a new one.
     * Matches parent repo behavior to prevent "dangling GATT connections"
     * which cause issues on Android 16/Pixel 7.
     *
     * This is idempotent - safe to call even if no connection exists.
     */
    private suspend fun cleanupExistingConnection() {
        val existingPeripheral = peripheral ?: return

        log.d { "Cleaning up existing connection before new connection attempt" }
        logRepo.info(
            LogEventType.DISCONNECT,
            "Cleaning up existing connection (pre-connect)",
            connectedDeviceName,
            connectedDeviceAddress,
        )

        // Cancel stale state observer to prevent ghost callbacks (H2 fix)
        stateObserverJob?.cancel()
        stateObserverJob = null
        deviceReadyJob?.cancel()
        deviceReadyJob = null

        // Cancel all polling jobs (matches disconnect() behavior)
        pollingEngine.stopAll()

        // Disconnect and release the peripheral
        try {
            isExplicitDisconnect = true
            existingPeripheral.disconnect()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Cleanup disconnect error (non-fatal): ${e.message}" }
        }

        peripheral = null
        // Note: Don't update _connectionState here - we're about to connect
        // and the Connecting state will be set by the caller
    }

    // -------------------------------------------------------------------------
    // 12. sendWorkoutCommand()
    // -------------------------------------------------------------------------

    suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        val p = peripheral
        if (p == null) {
            log.w { "Not connected - cannot send command" }
            logRepo.warning(
                LogEventType.ERROR,
                "Cannot send command - not connected",
            )
            return Result.failure(IllegalStateException("Not connected"))
        }

        val commandDetails = commandSummary(command)
        val commandHex = command.joinToString(" ") { it.toHexString() }
        log.d { "Sending $commandDetails to NUS TX" }
        log.d { "Command hex: $commandHex" }

        // Issue #222: Log queue state before acquiring for debugging
        log.d { "BLE queue locked: ${bleQueue.isLocked}, acquiring..." }

        val isEchoConfig = command.size == 32 && command[0] == 0x4E.toByte()
        val isProgramConfig = command.size == 96 && command[0] == 0x04.toByte()
        // Per-connection snapshot, NOT the live setting — see [compatibilityPathActive].
        val useCompatibilityPath = compatibilityPathActive

        if (useCompatibilityPath && isProgramConfig) {
            val maxWriteLength = try {
                p.maximumWriteValueLengthForType(WriteType.WithResponse).toString()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                "unavailable (${e.message})"
            }
            log.i { "[#333 compat] CONFIG write starting: $commandDetails, maxWriteLength=$maxWriteLength" }
            logRepo.debug(
                LogEventType.CHARACTERISTIC_WRITE,
                "[#333 compat] CONFIG write starting",
                connectedDeviceName,
                connectedDeviceAddress,
                "$commandDetails; writeType=WithResponse; maxWriteLength=$maxWriteLength",
            )
        }

        val attemptStart = currentTimeMillis()
        var result = bleQueue.write(p, txCharacteristic, command, WriteType.WithResponse)

        // Issue #333: on the compatibility path the CONFIG long-write can fail
        // transiently at initiation ("Write failed: Unknown" = legacy
        // writeCharacteristic() returned false, GATT momentarily busy) WITHOUT
        // dropping the connection — observed on Pixel 6 Pro in v14 testing. A
        // short, bounded retry recovers it; the old wedged-lane failure mode
        // (GATT 133 + disconnect) is gone at small MTU, so retrying is safe.
        if (useCompatibilityPath && isProgramConfig) {
            var retriesLeft = CONFIG_WRITE_RETRY_COUNT
            while (result.isFailure && retriesLeft > 0 && peripheral === p) {
                val attempt = CONFIG_WRITE_RETRY_COUNT - retriesLeft + 1
                log.w {
                    "[#333 compat] CONFIG write failed (${result.exceptionOrNull()?.message}); " +
                        "retry $attempt of $CONFIG_WRITE_RETRY_COUNT in ${CONFIG_WRITE_RETRY_DELAY_MS}ms"
                }
                logRepo.warning(
                    LogEventType.CHARACTERISTIC_WRITE,
                    "[#333 compat] CONFIG write retry $attempt of $CONFIG_WRITE_RETRY_COUNT",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    result.exceptionOrNull()?.message,
                )
                delay(CONFIG_WRITE_RETRY_DELAY_MS)
                result = bleQueue.write(p, txCharacteristic, command, WriteType.WithResponse)
                retriesLeft--
            }
        }

        if (result.isSuccess) {
            val elapsedMs = currentTimeMillis() - attemptStart
            log.d { "TX write ok: $commandDetails, type=WithResponse, elapsed=${elapsedMs}ms" }
            log.i { "Command sent via NUS TX: $commandDetails" }
            if (useCompatibilityPath && isProgramConfig) {
                log.i { "[#333 compat] CONFIG write succeeded, elapsed=${elapsedMs}ms" }
                logRepo.info(
                    LogEventType.COMMAND_SENT,
                    "[#333 compat] CONFIG write succeeded",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "$commandDetails; writeType=WithResponse; elapsed=${elapsedMs}ms",
                )
            }

            // Issue #222 v16 (optional): One-shot diagnostic read after CONFIG to catch early faults.
            // Issue #333: skipped on the compatibility path — post-CONFIG traffic stays quiet
            // so nothing races the pending long-write completion on BCM4389.
            if ((isEchoConfig || isProgramConfig) && !useCompatibilityPath) {
                val delayMs = if (isProgramConfig) 350L else 200L
                scope.launch {
                    delay(delayMs)
                    try {
                        val data = withTimeoutOrNull(500L) {
                            bleQueue.read { p.read(diagnosticCharacteristic) }
                        }
                        if (data != null) {
                            log.d { "Post-CONFIG diagnostic read (${data.size} bytes)" }
                            parseDiagnosticData(data)
                        } else {
                            log.d { "Post-CONFIG diagnostic read timed out" }
                        }
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        log.w { "Post-CONFIG diagnostic read failed: ${e.message}" }
                    }
                }
            } else if (useCompatibilityPath && isProgramConfig) {
                log.i { "[#333 compat] Skipping post-CONFIG diagnostic read" }
                logRepo.debug(
                    LogEventType.CHARACTERISTIC_READ,
                    "[#333 compat] Skipped post-CONFIG diagnostic read",
                    connectedDeviceName,
                    connectedDeviceAddress,
                )
            }

            logRepo.debug(
                LogEventType.COMMAND_SENT,
                if (useCompatibilityPath && isProgramConfig) {
                    "Command sent (NUS TX, small-MTU compatibility path)"
                } else {
                    "Command sent (NUS TX)"
                },
                connectedDeviceName,
                connectedDeviceAddress,
                commandDetails,
            )
            return Result.success(Unit)
        } else {
            val ex = result.exceptionOrNull()
            log.e { "Failed to send $commandDetails after retries: ${ex?.message}" }
            if (useCompatibilityPath && isProgramConfig) {
                logRepo.error(
                    LogEventType.ERROR,
                    "[#333 compat] CONFIG write failed after retries",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "$commandDetails; error=${ex?.message}",
                )
            }
            logRepo.error(
                LogEventType.ERROR,
                "Failed to send command",
                connectedDeviceName,
                connectedDeviceAddress,
                "$commandDetails; error=${ex?.message}",
            )
            return Result.failure(ex ?: IllegalStateException("Unknown error"))
        }
    }

    /**
     * Issue #333: human-readable command summary for connection logs — size,
     * opcode, little-endian command id, first 8 bytes, and (for 96-byte CONFIG
     * packets) the force-profile floats that matter when debugging workout start.
     */
    private fun commandSummary(command: ByteArray): String {
        val opcode = command.firstOrNull()?.toInt()?.and(0xFF)
        val opcodeText = opcode?.let { "0x${it.toString(16).padStart(2, '0').uppercase()}" } ?: "n/a"
        val commandIdText = if (command.size >= 4) {
            val commandId = (command[0].toInt() and 0xFF) or
                ((command[1].toInt() and 0xFF) shl 8) or
                ((command[2].toInt() and 0xFF) shl 16) or
                ((command[3].toInt() and 0xFF) shl 24)
            ", commandIdLE=$commandId"
        } else {
            ""
        }
        val preview = command.take(8).joinToString(" ") { it.toHexString() }
        val configDetails = activationConfigSummary(command)
        return "Size: ${command.size} bytes, opcode=$opcodeText$commandIdText, first8=$preview$configDetails"
    }

    private fun activationConfigSummary(command: ByteArray): String {
        if (command.size < 96 || command[0] != 0x04.toByte()) {
            return ""
        }
        return ", profileTail[0x48]=${readFloatLE(command, 0x48)}" +
            ", profileTail[0x4C]=${readFloatLE(command, 0x4C)}" +
            ", forceMin[0x50]=${readFloatLE(command, 0x50)}" +
            ", forceMax[0x54]=${readFloatLE(command, 0x54)}" +
            ", officialSoftMax[0x58]=${readFloatLE(command, 0x58)}" +
            ", officialIncrement[0x5C]=${readFloatLE(command, 0x5C)}"
    }

    private fun readFloatLE(data: ByteArray, offset: Int): Float {
        val bits = (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    // -------------------------------------------------------------------------
    // 13. processIncomingData()
    // -------------------------------------------------------------------------

    /**
     * Route incoming RX data to appropriate callbacks based on opcode.
     *
     * Made internal for testability (consistent with MetricPollingEngine's
     * internal test helpers pattern).
     */
    internal fun processIncomingData(data: ByteArray) {
        if (data.isEmpty()) return

        // Extract opcode (first byte) for command response tracking
        val opcode = data[0].toUByte()
        log.d { "RX notification: opcode=0x${opcode.toString(16).padStart(2, '0')}, size=${data.size}" }

        // Emit to both internal flow (for awaitResponse) and external callback
        _commandResponses.tryEmit(opcode)
        onCommandResponse(opcode)

        // Route to specific callbacks
        when (opcode.toInt()) {
            0x01 -> if (data.size >= 16) onMetricFromRx(data)
            0x02 -> if (data.size >= 5) onRepEventFromRx(data)
            // Other opcodes can be handled here as needed
        }
    }

    // -------------------------------------------------------------------------
    // 14. parseDiagnosticData()
    // -------------------------------------------------------------------------

    /**
     * Parse diagnostic data from DIAGNOSTIC/PROPERTY characteristic.
     * Contains fault codes and temperature readings.
     *
     * Simplified version per [11-02] decision: no fault-change tracking.
     */
    internal fun parseDiagnosticData(bytes: ByteArray) {
        try {
            val packet = parseDiagnosticPacket(bytes) ?: return
            val timestampedPacket = packet.copy(receivedAtMillis = currentTimeMillis())
            onDiagnosticData(timestampedPacket)
            log.i { "DIAGNOSTIC: faults=${timestampedPacket.faultWords} temps=${timestampedPacket.temperatures}" }
            if (timestampedPacket.hasFaults) {
                log.w { "DIAGNOSTIC FAULTS DETECTED: ${timestampedPacket.faultWords}" }
            }
        } catch (e: Exception) {
            log.e { "Failed to parse diagnostic data: ${e.message}" }
        }
    }

    // -------------------------------------------------------------------------
    // 15. awaitResponse()
    // -------------------------------------------------------------------------

    /**
     * Wait for a specific response opcode with timeout.
     * Used for protocol handshakes that require acknowledgment.
     *
     * @param expectedOpcode The opcode to wait for
     * @param timeoutMs Timeout in milliseconds (default 5000ms)
     * @return true if the expected opcode was received, false on timeout
     */
    @Suppress("unused") // Reserved for future protocol handshake commands
    suspend fun awaitResponse(expectedOpcode: UByte, timeoutMs: Long = 5000L): Boolean = try {
        val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
        log.d { "Waiting for response opcode 0x$opcodeHex (timeout: ${timeoutMs}ms)" }

        val result = withTimeoutOrNull(timeoutMs) {
            commandResponses.filter { it == expectedOpcode }.first()
        }

        if (result != null) {
            log.d { "Received expected response opcode 0x$opcodeHex" }
            true
        } else {
            log.w { "Timeout waiting for response opcode 0x$opcodeHex" }
            false
        }
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
        log.e { "Error waiting for response opcode 0x$opcodeHex: ${e.message}" }
        false
    }

    // -------------------------------------------------------------------------
    // Private utility
    // -------------------------------------------------------------------------

    /**
     * Get current time in milliseconds (KMP-compatible).
     */
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
