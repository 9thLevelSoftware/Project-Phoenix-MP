package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.BlePacketFactory
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

import com.devil.phoenixproject.data.ble.DiscoMode
import com.devil.phoenixproject.data.ble.HandleStateDetector
import com.devil.phoenixproject.data.ble.BleOperationQueue
import com.devil.phoenixproject.data.ble.requestHighPriority
import com.devil.phoenixproject.data.ble.requestMtuIfSupported
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.data.ble.parseMonitorPacket
import com.devil.phoenixproject.data.ble.parseDiagnosticPacket
import com.devil.phoenixproject.data.ble.parseHeuristicPacket
import com.devil.phoenixproject.data.ble.MonitorPacket
import com.devil.phoenixproject.data.ble.getUInt16LE
import com.devil.phoenixproject.data.ble.getUInt16BE
import com.devil.phoenixproject.data.ble.toVitruvianHex

import com.devil.phoenixproject.util.HardwareDetection
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.HeuristicPhaseStatistics
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.concurrent.Volatile

/**
 * Kable-based BLE Repository implementation for Vitruvian machines.
 * Uses Kotlin Multiplatform Kable library for unified BLE across all platforms.
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleRepository : BleRepository {

    private val log = Logger.withTag("KableBleRepository")
    private val logRepo = ConnectionLogRepository.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // BLE protocol constants now centralized in BleConstants.kt

    // Kable characteristic references - using pre-built references from BleConstants
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

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // Handle detection delegated to HandleStateDetector (Phase 9 extraction)
    private val handleDetector = HandleStateDetector()
    override val handleDetection: StateFlow<HandleDetection> = handleDetector.handleDetection

    // Monitor data flow - CRITICAL: Need buffer for high-frequency emissions!
    // Matching parent repo: extraBufferCapacity=64 for ~640ms of data at 10ms/sample
    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    // Rep events flow - needs buffer to prevent dropped notifications
    private val _repEvents = MutableSharedFlow<RepNotification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()

    // Handle state (4-state machine for Just Lift mode) - delegated to HandleStateDetector
    override val handleState: StateFlow<HandleState> = handleDetector.handleState

    // Deload event flow (for Just Lift safety recovery)
    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()

    // ROM violation events (Task 5)
    enum class RomViolationType { OUTSIDE_HIGH, OUTSIDE_LOW }
    private val _romViolationEvents = MutableSharedFlow<RomViolationType>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val romViolationEvents: Flow<RomViolationType> = _romViolationEvents.asSharedFlow()

    // Reconnection request flow (for Android BLE bug workaround)
    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()

    // Heuristic/phase statistics from machine (for Echo mode force feedback)
    private val _heuristicData = MutableStateFlow<HeuristicStatistics?>(null)
    override val heuristicData: StateFlow<HeuristicStatistics?> = _heuristicData.asStateFlow()

    // Disco mode (Easter egg - rapidly cycles LED colors)
    // Delegated to DiscoMode module (Phase 8 extraction)
    private val discoMode = DiscoMode(
        scope = scope,
        sendCommand = { command -> sendWorkoutCommand(command) }
    )
    override val discoModeActive: StateFlow<Boolean> = discoMode.isActive

    // Command response flow (for awaitResponse() protocol handshake)
    private val _commandResponses = MutableSharedFlow<UByte>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandResponses: Flow<UByte> = _commandResponses.asSharedFlow()

    // Kable objects
    private var peripheral: Peripheral? = null
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()
    private var scanJob: kotlinx.coroutines.Job? = null

    // Handle detection delegated to HandleStateDetector (all 15 state variables removed)

    // Connected device info (for logging)
    private var connectedDeviceName: String = ""
    private var connectedDeviceAddress: String = ""

    // Data parsing state (for spike filtering) - Float for mm precision (Issue #197)
    @Volatile private var lastGoodPosA = 0.0f
    @Volatile private var lastGoodPosB = 0.0f

    // Velocity calculation state - Float for mm precision (Issue #197)
    @Volatile private var lastPositionA = 0.0f
    @Volatile private var lastPositionB = 0.0f
    @Volatile private var lastTimestamp = 0L

    // Smoothed velocity for stall detection (Issue #204, #214)
    // Uses EMA to prevent false stall resets from position jitter
    @Volatile private var smoothedVelocityA = 0.0
    @Volatile private var smoothedVelocityB = 0.0

    // EMA velocity initialization flag (Task 10)
    private var isFirstVelocitySample = true

    // Track if previous sample was filtered (velocity edge case fix)
    // When a sample is filtered due to position jump, the next valid sample should
    // reset velocity calculation to avoid using the filtered position as reference
    @Volatile private var lastSampleWasFiltered = false


    // Monitor polling job (for explicit control)
    private var monitorPollingJob: kotlinx.coroutines.Job? = null

    // Polling mutex to prevent race conditions (Task 4)
    private val monitorPollingMutex = Mutex()

    // Issue #222: All BLE operations serialized through single queue
    private val bleQueue = BleOperationQueue()

    // Diagnostic polling job (500ms keep-alive)
    private var diagnosticPollingJob: kotlinx.coroutines.Job? = null
    private var diagnosticPollCount: Long = 0
    private var lastDiagnosticFaults: List<Short>? = null

    // Heuristic polling job (250ms / 4Hz - force telemetry for Echo mode)
    private var heuristicPollingJob: kotlinx.coroutines.Job? = null

    // Deload event debouncing
    private var lastDeloadEventTime = 0L

    // Poll rate diagnostics
    private var pollIntervalSum = 0L
    private var pollIntervalCount = 0L
    private var maxPollInterval = 0L
    private var minPollInterval = Long.MAX_VALUE

    // Monitor notification counter (for diagnostic logging)
    @Volatile private var monitorNotificationCount = 0L

    // Heartbeat job
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    // Detected firmware version (from DIS or proprietary characteristic)
    private var detectedFirmwareVersion: String? = null

    // Negotiated MTU (for diagnostic logging)
    @Volatile private var negotiatedMtu: Int? = null

    // Strict validation flag (filters >20mm position jumps)
    private var strictValidationEnabled = true

    // Flag to track explicit disconnect (to avoid auto-reconnect)
    private var isExplicitDisconnect = false

    // Flag to track if we ever successfully connected (for auto-reconnect logic)
    // This prevents auto-reconnect from firing on the initial Disconnected state
    // when a Peripheral is first created (before connect() is even called)
    private var wasEverConnected = false

    override suspend fun startScanning(): Result<Unit> {
        log.i { "Starting BLE scan for Vitruvian devices" }
        logRepo.info(LogEventType.SCAN_START, "Starting BLE scan for Vitruvian devices")

        return try {
            // Cancel any existing scan job to prevent duplicates
            scanJob?.cancel()
            scanJob = null

            _scannedDevices.value = emptyList()
            discoveredAdvertisements.clear()
            _connectionState.value = ConnectionState.Scanning

            scanJob = Scanner {
                // No specific filters - we'll filter manually
            }
                .advertisements
                .onEach { advertisement ->
                    // Debug logging for all advertisements
                    log.d { "RAW ADV: name=${advertisement.name}, id=${advertisement.identifier}, uuids=${advertisement.uuids}, rssi=${advertisement.rssi}" }
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
                            log.i { "Found Vitruvian by FEF3 serviceData: ${advertisement.identifier}, data size: ${fef3Data.size}" }
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
                    val identifier = advertisement.identifier.toString()
                    val advertisedName = advertisement.name
                    val hasRealName = advertisedName != null &&
                        (advertisedName.startsWith("Vee_", ignoreCase = true) ||
                         advertisedName.startsWith("VIT", ignoreCase = true))

                    // Use name if available, otherwise use identifier as placeholder
                    val name = advertisedName ?: "Vitruvian ($identifier)"

                    // Skip devices without a real Vitruvian name if we already have one
                    if (!hasRealName) {
                        val alreadyHaveRealDevice = _scannedDevices.value.any { existing ->
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
                            "RSSI: ${advertisement.rssi} dBm"
                        )
                    }

                    // Store advertisement reference
                    discoveredAdvertisements[identifier] = advertisement

                    // Update scanned devices list
                    val device = ScannedDevice(
                        name = name,
                        address = identifier,
                        rssi = advertisement.rssi
                    )
                    var currentDevices = _scannedDevices.value.toMutableList()

                    // If this is a real-named device, remove any placeholder devices first
                    // (same physical device can advertise with different identifiers)
                    if (hasRealName) {
                        currentDevices = currentDevices.filter { existing ->
                            existing.name.startsWith("Vee_", ignoreCase = true) ||
                            existing.name.startsWith("VIT", ignoreCase = true) ||
                            existing.address == identifier  // Keep if same address (will update below)
                        }.toMutableList()
                    }

                    val existingIndex = currentDevices.indexOfFirst { it.address == identifier }
                    if (existingIndex >= 0) {
                        currentDevices[existingIndex] = device
                    } else {
                        currentDevices.add(device)
                    }
                    _scannedDevices.value = currentDevices.sortedByDescending { it.rssi }
                }
                .catch { e ->
                    log.e { "Scan error: ${e.message}" }
                    logRepo.error(LogEventType.ERROR, "BLE scan failed", details = e.message)
                    // Return to Disconnected instead of Error for scan failures - user can retry
                    _connectionState.value = ConnectionState.Disconnected
                }
                .launchIn(scope)

            Result.success(Unit)
        } catch (e: Exception) {
            log.e { "Failed to start scanning: ${e.message}" }
            _connectionState.value = ConnectionState.Disconnected
            Result.failure(e)
        }
    }

    override suspend fun stopScanning() {
        log.i { "Stopping BLE scan" }
        logRepo.info(
            LogEventType.SCAN_STOP,
            "BLE scan stopped",
            details = "Found ${discoveredAdvertisements.size} Vitruvian device(s)"
        )
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Scan for first Vitruvian device and connect immediately.
     * This is the simple flow matching parent repo behavior.
     */
    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        log.i { "scanAndConnect: Starting scan and auto-connect (timeout: ${timeoutMs}ms)" }
        logRepo.info(LogEventType.SCAN_START, "Scan and connect started")

        // Connection cleanup is handled inside connect() to ensure consistent behavior.
        _connectionState.value = ConnectionState.Scanning
        _scannedDevices.value = emptyList()
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
                _connectionState.value = ConnectionState.Disconnected
                return Result.failure(Exception("No Vitruvian device found"))
            }

            val identifier = advertisement.identifier.toString()
            val name = advertisement.name ?: "Vitruvian"
            log.i { "scanAndConnect: Found device $name ($identifier), connecting..." }

            // Store for connection
            discoveredAdvertisements[identifier] = advertisement
            val device = ScannedDevice(name = name, address = identifier, rssi = advertisement.rssi)
            _scannedDevices.value = listOf(device)

            // Connect to it
            connect(device)
        } catch (e: Exception) {
            log.e { "scanAndConnect failed: ${e.message}" }
            _connectionState.value = ConnectionState.Disconnected
            Result.failure(e)
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        log.i { "Connecting to device: ${device.name}" }
        logRepo.info(
            LogEventType.CONNECT_START,
            "Connecting to device",
            device.name,
            device.address
        )

        // Clean up any existing connection first (matches parent repo)
        // Prevents "dangling GATT connections" on Android 16/Pixel 7
        cleanupExistingConnection()

        _connectionState.value = ConnectionState.Connecting

        val advertisement = discoveredAdvertisements[device.address]
        if (advertisement == null) {
            log.e { "Advertisement not found for device: ${device.address}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Device not found in scanned list",
                device.name,
                device.address
            )
            // Return to Disconnected - device may have gone out of range, user can retry
            _connectionState.value = ConnectionState.Disconnected
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

            // Observe connection state
            peripheral?.state
                ?.onEach { state ->
                    when (state) {
                        is State.Connecting -> {
                            _connectionState.value = ConnectionState.Connecting
                        }
                        is State.Connected -> {
                            // Mark that we successfully connected (for auto-reconnect logic)
                            wasEverConnected = true
                            log.i { "✅ Connection established to ${device.name}" }
                            logRepo.info(
                                LogEventType.CONNECT_SUCCESS,
                                "Device connected successfully",
                                connectedDeviceName,
                                connectedDeviceAddress
                            )
                            _connectionState.value = ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address,
                                hardwareModel = HardwareDetection.detectModel(device.name)
                            )
                            // Launch onDeviceReady in a coroutine since we're in a non-suspend context
                            scope.launch { onDeviceReady() }
                        }
                        is State.Disconnecting -> {
                            log.d { "Disconnecting from device" }
                            logRepo.info(
                                LogEventType.DISCONNECT,
                                "Device disconnecting",
                                connectedDeviceName,
                                connectedDeviceAddress
                            )
                        }
                        is State.Disconnected -> {
                            // Capture device info and connection state BEFORE clearing
                            val deviceName = connectedDeviceName
                            val deviceAddress = connectedDeviceAddress
                            val hadConnection = wasEverConnected

                            // Only process disconnect if we were actually connected
                            if (hadConnection) {
                                logRepo.info(
                                    LogEventType.DISCONNECT,
                                    "Device disconnected",
                                    deviceName,
                                    deviceAddress
                                )

                                // Stop heartbeat and reset state
                                heartbeatJob?.cancel()
                                heartbeatJob = null
                                _connectionState.value = ConnectionState.Disconnected
                                peripheral = null
                                connectedDeviceName = ""
                                connectedDeviceAddress = ""
                            } else {
                                // This is the initial Disconnected state when Peripheral is created
                                // Don't reset state or peripheral - we're about to call connect()
                                log.d { "Peripheral initial state: Disconnected (awaiting connect() call)" }
                                return@onEach  // Skip the rest of this handler
                            }

                            // Request auto-reconnect ONLY if:
                            // 1. We were previously connected (wasEverConnected)
                            // 2. This was NOT an explicit disconnect
                            // 3. We have a valid device address
                            if (hadConnection && !isExplicitDisconnect && deviceAddress.isNotEmpty()) {
                                log.i { "🔄 Requesting auto-reconnect to $deviceName ($deviceAddress)" }
                                scope.launch {
                                    _reconnectionRequested.emit(
                                        ReconnectionRequest(
                                            deviceName = deviceName,
                                            deviceAddress = deviceAddress,
                                            reason = "unexpected_disconnect",
                                            timestamp = currentTimeMillis()
                                        )
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

                    // Wrap connection in timeout to prevent zombie "Connecting" state
                    withTimeout(BleConstants.CONNECTION_TIMEOUT_MS) {
                        peripheral?.connect()
                        log.i { "Connection initiated to ${device.name}, waiting for established state..." }

                        // Wait for connection to actually establish (state becomes Connected)
                        // The state observer (lines 552-641) will emit Connected when ready
                        peripheral?.state?.first { it is State.Connected }
                        log.i { "Connection established to ${device.name}" }
                    }

                    return Result.success(Unit) // Success, exit retry loop
                } catch (e: TimeoutCancellationException) {
                    lastException = Exception("Connection timeout after ${BleConstants.CONNECTION_TIMEOUT_MS}ms")
                    log.w { "Connection attempt $attempt timed out after ${BleConstants.CONNECTION_TIMEOUT_MS}ms" }
                    if (attempt < BleConstants.Timing.CONNECTION_RETRY_COUNT) {
                        delay(BleConstants.Timing.CONNECTION_RETRY_DELAY_MS)
                    }
                } catch (e: Exception) {
                    lastException = e
                    log.w { "Connection attempt $attempt failed: ${e.message}" }
                    if (attempt < BleConstants.Timing.CONNECTION_RETRY_COUNT) {
                        delay(BleConstants.Timing.CONNECTION_RETRY_DELAY_MS)
                    }
                }
            }

            // All retries failed - cleanup and return to disconnected state
            peripheral?.disconnect()
            peripheral = null
            _connectionState.value = ConnectionState.Disconnected
            throw lastException ?: Exception("Connection failed after ${BleConstants.Timing.CONNECTION_RETRY_COUNT} attempts")

        } catch (e: Exception) {
            log.e { "Connection failed: ${e.message}" }
            logRepo.error(
                LogEventType.CONNECT_FAIL,
                "Failed to connect to device",
                device.name,
                device.address,
                e.message
            )
            // Return to Disconnected instead of Error - connection failures are retryable
            _connectionState.value = ConnectionState.Disconnected
            peripheral = null
            connectedDeviceName = ""
            connectedDeviceAddress = ""
            Result.failure(e)
        }
    }

    /**
     * Called when the device is connected and ready.
     * Requests MTU, starts observing notifications, and starts heartbeat.
     */
    private suspend fun onDeviceReady() {
        val p = peripheral ?: return

        // Request High Connection Priority (Android only - via expect/actual extension)
        // Critical for maintaining ~20Hz polling rate without lag
        p.requestHighPriority()

        // Request MTU negotiation (Android only - iOS handles automatically)
        // CRITICAL: Without MTU negotiation, BLE uses default 23-byte MTU (20 usable)
        // Vitruvian commands require up to 96 bytes for activation frames
        val mtu = p.requestMtuIfSupported(BleConstants.Timing.DESIRED_MTU)
        if (mtu != null) {
            negotiatedMtu = mtu
            log.i { "✅ MTU negotiated: $mtu bytes (requested: ${BleConstants.Timing.DESIRED_MTU})" }
            logRepo.info(
                LogEventType.MTU_CHANGED,
                "MTU negotiated: $mtu bytes",
                connectedDeviceName,
                connectedDeviceAddress
            )
        } else {
            // iOS returns null (handled by OS), or Android negotiation failed
            log.i { "ℹ️ MTU negotiation: using system default (iOS) or failed (Android)" }
            logRepo.debug(
                LogEventType.MTU_CHANGED,
                "MTU using system default"
            )
        }

        // Verify services are discovered and log GATT structure
        try {
            // p.services is a StateFlow<List<DiscoveredService>?> - access .value
            val servicesList = p.services.value
            if (servicesList == null) {
                log.w { "⚠️ No services discovered - device may not be fully ready" }
                logRepo.warning(
                    LogEventType.SERVICE_DISCOVERED,
                    "No services found after connection",
                    connectedDeviceName,
                    connectedDeviceAddress
                )
            } else {
                // Log detailed GATT structure with characteristic properties
                log.i { "📋 ========== GATT SERVICE DISCOVERY ==========" }
                log.i { "📋 Found ${servicesList.size} services" }
                servicesList.forEach { service ->
                    log.i { "  SERVICE: ${service.serviceUuid}" }
                    service.characteristics.forEach { char ->
                        // Properties.toString() shows the raw property flags value
                        log.i { "    CHAR: ${char.characteristicUuid} props=${char.properties}" }
                    }
                }
                log.i { "📋 =========================================" }

                // Check specifically for NUS TX characteristic (6e400002)
                val nusService = servicesList.find {
                    it.serviceUuid.toString().lowercase().contains("6e400001")
                }
                if (nusService != null) {
                    log.i { "✅ NUS Service found: ${nusService.serviceUuid}" }
                    val txChar = nusService.characteristics.find {
                        it.characteristicUuid.toString().lowercase().contains("6e400002")
                    }
                    if (txChar != null) {
                        log.i { "✅ NUS TX (6e400002) found, properties: ${txChar.properties}" }
                    } else {
                        log.e { "❌ NUS TX characteristic (6e400002) NOT FOUND in NUS service!" }
                    }
                    val rxChar = nusService.characteristics.find {
                        it.characteristicUuid.toString().lowercase().contains("6e400003")
                    }
                    if (rxChar != null) {
                        log.i { "✅ NUS RX (6e400003) found, properties: ${rxChar.properties}" }
                    } else {
                        log.e { "❌ NUS RX characteristic (6e400003) NOT FOUND in NUS service!" }
                    }
                } else {
                    log.w { "⚠️ NUS Service (6e400001) NOT FOUND - checking all services for NUS chars..." }
                    // Search all services for the TX/RX characteristics
                    servicesList.forEach { service ->
                        service.characteristics.forEach { char ->
                            val uuid = char.characteristicUuid.toString().lowercase()
                            if (uuid.contains("6e400002") || uuid.contains("6e400003")) {
                                log.i { "🔍 Found ${char.characteristicUuid} in service ${service.serviceUuid}, props=${char.properties}" }
                            }
                        }
                    }
                }

                logRepo.info(
                    LogEventType.SERVICE_DISCOVERED,
                    "GATT services discovered",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    "Services: ${servicesList.size}"
                )
            }
        } catch (e: Exception) {
            log.e { "Failed to enumerate services: ${e.message}" }
            logRepo.warning(
                LogEventType.SERVICE_DISCOVERED,
                "Failed to access services",
                connectedDeviceName,
                connectedDeviceAddress,
                e.message
            )
        }

        logRepo.info(
            LogEventType.SERVICE_DISCOVERED,
            "Device ready, starting notifications and heartbeat",
            connectedDeviceName,
            connectedDeviceAddress
        )

        startObservingNotifications()
        startHeartbeat()
    }

    /**
     * Start the heartbeat to keep the BLE connection alive.
     * Uses read-then-write pattern: tries to read first, falls back to write if read fails.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            log.d { "Starting BLE heartbeat (interval=${BleConstants.Timing.HEARTBEAT_INTERVAL_MS}ms, read timeout=${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms)" }
            while (isActive) {
                delay(BleConstants.Timing.HEARTBEAT_INTERVAL_MS)

                val p = peripheral
                if (p == null) {
                    log.w { "Heartbeat: peripheral is null, stopping" }
                    break
                }

                // ATTEMPT READ FIRST with timeout
                val readSucceeded = try {
                    kotlinx.coroutines.withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        performHeartbeatRead(p)
                    } ?: false
                } catch (e: Exception) {
                    log.e { "Heartbeat read attempt crashed: ${e.message}" }
                    false
                }

                // FALLBACK TO WRITE if read failed
                if (!readSucceeded) {
                    sendHeartbeatNoOp(p)
                }
            }
        }
    }

    /**
     * Issue #222 v15: Perform heartbeat read matching parent repo behavior.
     * Parent reads TX characteristic (nusRxCharacteristic in parent naming).
     * This typically fails (TX is write-only) which triggers the no-op write.
     * Returns true if read succeeded, false otherwise.
     */
    private suspend fun performHeartbeatRead(p: Peripheral): Boolean {
        return try {
            bleQueue.read {
                // Issue #222 v15: Read TX char to match parent (will typically fail)
                p.read(txCharacteristic)
            }
            log.v { "Heartbeat read succeeded (TX char)" }
            true
        } catch (e: Exception) {
            log.d { "Heartbeat read failed (expected): ${e.message}" }
            false
        }
    }

    /**
     * Send heartbeat no-op write as fallback when read fails.
     * Uses 4-byte no-op command (MUST be exactly 4 bytes).
     * Issue #222 v15.1: V-Form requires WithResponse.
     */
    private suspend fun sendHeartbeatNoOp(p: Peripheral) {
        try {
            bleQueue.writeSimple(p, txCharacteristic, BleConstants.HEARTBEAT_NO_OP, WriteType.WithResponse)
            log.v { "Heartbeat no-op write sent" }
        } catch (e: Exception) {
            log.w { "Heartbeat no-op write failed: ${e.message}" }
        }
    }

    private fun startObservingNotifications() {
        val p = peripheral ?: return

        logRepo.info(
            LogEventType.NOTIFICATION,
            "Enabling BLE notifications and starting polling (matching parent repo)",
            connectedDeviceName,
            connectedDeviceAddress
        )

        // ===== FIRMWARE VERSION READ (best effort) =====
        // Try to read firmware version from Device Information Service
        scope.launch {
            tryReadFirmwareVersion(p)
            tryReadVitruvianVersion(p)
        }

        // ===== CORE NOTIFICATIONS =====

        // NOTE: Standard NUS RX (6e400003) does NOT exist on Vitruvian devices.
        // The device uses custom characteristics for notifications instead.
        // Skipping observation of non-existent rxCharacteristic to avoid errors.
        // Command responses (if any) come through device-specific characteristics.

        // Observe REPS characteristic for rep completion events (CRITICAL for rep counting!)
        scope.launch {
            try {
                log.i { "Starting REPS characteristic notifications (rep events)" }
                p.observe(repsCharacteristic)
                    .catch { e ->
                        log.e { "Reps observation error: ${e.message}" }
                        logRepo.error(
                            LogEventType.ERROR,
                            "Reps notification error",
                            connectedDeviceName,
                            connectedDeviceAddress,
                            e.message
                        )
                    }
                    .collect { data ->
                        log.d { "REPS notification received: ${data.size} bytes" }
                        parseRepsCharacteristicData(data)
                    }
            } catch (e: Exception) {
                log.e { "Failed to observe Reps: ${e.message}" }
                logRepo.error(
                    LogEventType.ERROR,
                    "Failed to enable Reps notifications",
                    connectedDeviceName,
                    connectedDeviceAddress,
                    e.message
                )
            }
        }

        // Observe VERSION characteristic (for firmware info logging)
        scope.launch {
            try {
                log.d { "Starting VERSION characteristic notifications" }
                p.observe(versionCharacteristic)
                    .catch { e -> log.w { "Version observation error (non-fatal): ${e.message}" } }
                    .collect { data ->
                        val hexString = data.joinToString(" ") { it.toHexString() }
                        log.i { "╔════════════════════════════════════════╗" }
                        log.i { "║  VERSION CHARACTERISTIC DATA RECEIVED   ║" }
                        log.i { "║  Size: ${data.size} bytes, Hex: $hexString" }
                        log.i { "╚════════════════════════════════════════╝" }
                    }
            } catch (e: Exception) {
                log.d { "VERSION notifications not available (expected): ${e.message}" }
            }
        }

        // Observe MODE characteristic (for mode change logging)
        scope.launch {
            try {
                log.d { "Starting MODE characteristic notifications" }
                p.observe(modeCharacteristic)
                    .catch { e -> log.w { "Mode observation error (non-fatal): ${e.message}" } }
                    .collect { data ->
                        log.d { "MODE notification: ${data.size} bytes" }
                    }
            } catch (e: Exception) {
                log.d { "MODE notifications not available (expected): ${e.message}" }
            }
        }

        // ===== POLLING (NOT notifications - these chars are ReadableCharacteristics) =====

        // MONITOR characteristic - use POLLING only (NOT notifications)
        // Per parent repo: "SAMPLE_CHAR is NOT a NotifiableCharacteristic!"
        log.i { "Starting MONITOR characteristic polling (real-time metrics)" }
        startMonitorPolling(p)

        // DIAGNOSTIC characteristic - 500ms keep-alive polling
        // Maintains connection and provides fault/temperature data
        log.i { "Starting DIAGNOSTIC characteristic polling (500ms keep-alive)" }
        startDiagnosticPolling(p)

        // HEURISTIC characteristic - 250ms/4Hz polling for force telemetry
        // Per parent repo: Uses polling (not notifications) to get phase statistics
        // Critical for Echo mode force feedback - provides kgMax for actual measured force
        log.i { "Starting HEURISTIC characteristic polling (250ms/4Hz - force telemetry)" }
        startHeuristicPolling(p)
    }

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
                log.i { "╔════════════════════════════════════════╗" }
                log.i { "║  🔧 FIRMWARE VERSION: $detectedFirmwareVersion" }
                log.i { "╚════════════════════════════════════════╝" }
                logRepo.info(
                    LogEventType.CONNECT_SUCCESS,
                    "Firmware version detected: $detectedFirmwareVersion",
                    connectedDeviceName,
                    connectedDeviceAddress
                )
            }
        } catch (e: Exception) {
            log.d { "Device Information Service not available (expected): ${e.message}" }
        }
    }

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
            log.d { "Vitruvian VERSION characteristic not readable (expected): ${e.message}" }
        }
    }

    /**
     * Poll DIAGNOSTIC characteristic every 500ms for keep-alive and health monitoring.
     * Matches official app interval. Uses suspend-based reads.
     */
    private fun startDiagnosticPolling(p: Peripheral) {
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = scope.launch {
            log.d { "🔄 Starting SEQUENTIAL diagnostic polling (${BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS}ms interval - matches official app)" }
            var successfulReads = 0L
            var failedReads = 0L
            diagnosticPollCount = 0
            lastDiagnosticFaults = null

            while (_connectionState.value is ConnectionState.Connected && isActive) {
                try {
                    val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        bleQueue.read { p.read(diagnosticCharacteristic) }
                    }

                    if (data != null) {
                        successfulReads++
                        diagnosticPollCount++
                        if (diagnosticPollCount == 1L || diagnosticPollCount % BleConstants.Timing.DIAGNOSTIC_LOG_EVERY == 0L) {
                            log.d { "📊 Diagnostic poll #$diagnosticPollCount (bytes=${data.size}, failed=$failedReads)" }
                        }
                        parseDiagnosticData(data)
                    } else {
                        failedReads++
                    }

                    // Fixed 500ms interval for keep-alive purposes
                    delay(BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    failedReads++
                    if (failedReads <= 5 || failedReads % 20 == 0L) {
                        log.w { "❌ Diagnostic poll failed #$failedReads: ${e.message}" }
                    }
                    delay(BleConstants.Timing.DIAGNOSTIC_POLL_INTERVAL_MS)
                }
            }
            log.d { "📊 Diagnostic polling ended (success: $successfulReads, failed: $failedReads)" }
        }
    }

    /**
     * Poll HEURISTIC characteristic every 250ms (4Hz) for force telemetry.
     * Matches parent repo and official app - provides phase statistics for
     * concentric/eccentric analysis and Echo mode force feedback.
     *
     * Returns 48 bytes: 6 floats for concentric stats, 6 floats for eccentric stats
     * Each phase has: kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax
     *
     * This is critical for accurate force display - heuristicData.kgMax represents
     * actual measured force from the machine, not the user's configured weight.
     */
    private fun startHeuristicPolling(p: Peripheral) {
        heuristicPollingJob?.cancel()
        heuristicPollingJob = scope.launch {
            log.d { "🔄 Starting SEQUENTIAL heuristic polling (${BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS}ms interval / 4Hz - matching parent repo)" }
            var successfulReads = 0L
            var failedReads = 0L

            while (_connectionState.value is ConnectionState.Connected && isActive) {
                try {
                    val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                        bleQueue.read { p.read(heuristicCharacteristic) }
                    }

                    if (data != null && data.isNotEmpty()) {
                        successfulReads++
                        if (successfulReads % 100 == 0L) {
                            log.v { "📊 Heuristic poll #$successfulReads (failed: $failedReads)" }
                        }
                        parseHeuristicData(data)
                    } else {
                        failedReads++
                        if (failedReads <= 3) {
                            log.v { "Heuristic read returned null/empty" }
                        }
                    }

                    // Fixed 250ms interval (4Hz) matching parent repo
                    delay(BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    failedReads++
                    if (failedReads <= 5 || failedReads % 50 == 0L) {
                        log.w { "❌ Heuristic poll failed #$failedReads: ${e.message}" }
                    }
                    delay(BleConstants.Timing.HEURISTIC_POLL_INTERVAL_MS)
                }
            }
            log.d { "📊 Heuristic polling ended (success: $successfulReads, failed: $failedReads)" }
        }
    }

    /**
     * Parse diagnostic data from DIAGNOSTIC/PROPERTY characteristic.
     * Contains fault codes and temperature readings.
     */
    private fun parseDiagnosticData(bytes: ByteArray) {
        try {
            val packet = parseDiagnosticPacket(bytes) ?: return

            val faultSnapshot = packet.faults
            val faultsChanged = lastDiagnosticFaults == null || lastDiagnosticFaults != faultSnapshot
            if (faultsChanged) {
                log.i { "DIAGNOSTIC update: faults=$faultSnapshot temps=${packet.temps.map { it.toInt() }}" }
                lastDiagnosticFaults = faultSnapshot
            }

            if (packet.hasFaults) {
                log.w { "DIAGNOSTIC FAULTS DETECTED: ${packet.faults}" }
            }
        } catch (e: Exception) {
            log.e { "Failed to parse diagnostic data: ${e.message}" }
        }
    }

    /**
     * Parse heuristic data from HEURISTIC characteristic.
     * Contains concentric/eccentric phase statistics (48 bytes, Little Endian).
     * Format: 6 floats for concentric stats, 6 floats for eccentric stats
     */
    private fun parseHeuristicData(bytes: ByteArray) {
        try {
            val stats = parseHeuristicPacket(bytes, timestamp = currentTimeMillis()) ?: return
            _heuristicData.value = stats
        } catch (e: Exception) {
            log.v { "Failed to parse heuristic data: ${e.message}" }
        }
    }

    /**
     * Poll MONITOR characteristic for real-time position/load data.
     * Per parent repo: "SAMPLE_CHAR is NOT a NotifiableCharacteristic!
     * Per official Vitruvian app analysis, Sample data MUST be polled via readCharacteristic()"
     *
     * CRITICAL: Uses withTimeout to prevent hangs if BLE stack doesn't respond.
     * Parent repo uses withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) for the same reason.
     * NO fixed delay between successful reads - natural rate-limiting by BLE response time.
     *
     * @param forAutoStart If true, enables handle detection with WaitingForRest state (for Just Lift auto-start).
     *                     If false, sets handle state to Active (for active workout monitoring).
     */
    private fun startMonitorPolling(p: Peripheral, forAutoStart: Boolean = false) {
        // Stop disco mode if running (safety - don't interfere with workout polling)
        stopDiscoMode()

        // Issue #222 v16: Reset poll/velocity stats on restart to avoid stale gaps
        lastTimestamp = 0L
        pollIntervalSum = 0L
        pollIntervalCount = 0L
        minPollInterval = Long.MAX_VALUE
        maxPollInterval = 0L
        lastPositionA = 0.0f
        lastPositionB = 0.0f
        smoothedVelocityA = 0.0
        smoothedVelocityB = 0.0

        // Reset notification counter for this session
        val previousCount = monitorNotificationCount
        monitorNotificationCount = 0L
        log.i { "📊 Monitor notifications reset (previous session: $previousCount notifications)" }

        // Reset velocity initialization flag (Task 10)
        isFirstVelocitySample = true
        lastSampleWasFiltered = false  // Clear filter tracking for fresh session

        if (forAutoStart) {
            // AUTO-START MODE: Delegate to HandleStateDetector
            handleDetector.enable(autoStart = true)
            log.i { "Monitor polling for AUTO-START - waiting for handles at rest (pos < ${BleConstants.Thresholds.HANDLE_REST_THRESHOLD}mm), vel threshold=${BleConstants.Thresholds.AUTO_START_VELOCITY_THRESHOLD}mm/s" }
        }

        // Cancel any existing polling job before starting new one
        monitorPollingJob?.cancel()

        // Start sequential polling using suspend-based reads (official app approach)
        monitorPollingJob = scope.launch {
            // Use mutex to ensure only one polling loop runs at a time.
            // NOTE: We removed the premature `isLocked` check which caused a race condition
            // where the second call to startMonitorPolling (with forAutoStart=true) would skip
            // because the first call's mutex was still locked. Now we properly wait for the
            // cancelled job to release the mutex before starting the new polling loop.
            monitorPollingMutex.withLock {
                var failCount = 0
                var successCount = 0L
                var consecutiveTimeouts = 0
                log.i { "🔄 Starting SEQUENTIAL monitor polling (with timeout=${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms, forAutoStart=$forAutoStart)" }

                try {
                    while (_connectionState.value is ConnectionState.Connected && isActive) {
                    try {
                        // CRITICAL: Wrap read in timeout to prevent indefinite hangs
                        // BLE stack can sometimes fail to return success/failure callback
                        // This matches parent repo's withTimeoutOrNull pattern
                        val data = withTimeoutOrNull(BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS) {
                            bleQueue.read { p.read(monitorCharacteristic) }
                        }

                        if (data != null) {
                            // Success - parse data and continue immediately
                            successCount++
                            consecutiveTimeouts = 0
                            if (successCount == 1L || successCount % 500 == 0L) {
                                log.i { "📊 Monitor poll SUCCESS #$successCount, data size: ${data.size}" }
                            }
                            parseMonitorData(data)
                            failCount = 0
                            // NO DELAY on success - BLE response time naturally rate-limits (~10-20ms)
                        } else {
                            // Timeout - BLE stack hung, continue polling
                            consecutiveTimeouts++
                            if (consecutiveTimeouts <= 3 || consecutiveTimeouts % 10 == 0) {
                                log.w { "⏱️ Monitor read timed out (${BleConstants.Timing.HEARTBEAT_READ_TIMEOUT_MS}ms) - consecutive: $consecutiveTimeouts" }
                            }
                            // Task 13: Disconnect after too many consecutive timeouts
                            if (consecutiveTimeouts >= BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS) {
                                log.e { "Too many consecutive timeouts ($consecutiveTimeouts), triggering disconnect" }
                                _connectionState.value = ConnectionState.Disconnected
                                scope.launch { disconnect() }
                                return@withLock
                            }
                            // Small delay after timeout to avoid tight loop
                            delay(50)
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        // Explicit timeout exception (shouldn't happen with withTimeoutOrNull, but safety)
                        consecutiveTimeouts++
                        log.w { "⏱️ Monitor read timeout exception - consecutive: $consecutiveTimeouts" }
                        delay(50)
                    } catch (e: Exception) {
                        // Kable exceptions (IOException, GattException, etc.)
                        failCount++
                        consecutiveTimeouts = 0
                        if (failCount <= 5 || failCount % 50 == 0) {
                            log.w { "❌ Monitor poll FAILED #$failCount: ${e.message}" }
                        }
                        // Delay on failure to prevent tight error loops
                        delay(50)
                    }
                    }
                } catch (e: Exception) {
                    log.e { "Monitor polling stopped: ${e.message}" }
                }
                log.i { "📊 Monitor polling ended (reads: $successCount, failures: $failCount, timeouts: $consecutiveTimeouts)" }
            }
        }
    }

    override suspend fun disconnect() {
        log.i { "Disconnecting (explicit)" }
        isExplicitDisconnect = true  // Mark as explicit disconnect to prevent auto-reconnect

        // Cancel all polling jobs
        heartbeatJob?.cancel()
        heartbeatJob = null
        monitorPollingJob?.cancel()
        monitorPollingJob = null
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = null
        heuristicPollingJob?.cancel()
        heuristicPollingJob = null

        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            log.e { "Disconnect error: ${e.message}" }
        }
        peripheral = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun cancelConnection() {
        log.i { "Cancelling in-progress connection" }
        isExplicitDisconnect = true  // Prevent auto-reconnect
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            log.e { "Cancel connection error: ${e.message}" }
        }
        peripheral = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        log.d { "Setting color scheme: $schemeIndex" }
        return try {
            // Color scheme command - use proper 34-byte frame format
            val command = com.devil.phoenixproject.util.BlePacketFactory.createColorSchemeCommand(schemeIndex)
            sendWorkoutCommand(command)
        } catch (e: Exception) {
            log.e { "Failed to set color scheme: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        val p = peripheral
        if (p == null) {
            log.w { "Not connected - cannot send command" }
            logRepo.warning(
                LogEventType.ERROR,
                "Cannot send command - not connected"
            )
            return Result.failure(IllegalStateException("Not connected"))
        }

        val commandHex = command.joinToString(" ") { it.toHexString() }
        log.d { "Sending ${command.size}-byte command to NUS TX" }
        log.d { "Command hex: $commandHex" }

        // Issue #222: Log queue state before acquiring for debugging
        log.d { "BLE queue locked: ${bleQueue.isLocked}, acquiring..." }

        val attemptStart = currentTimeMillis()
        val result = bleQueue.write(p, txCharacteristic, command, WriteType.WithResponse)

        if (result.isSuccess) {
            val elapsedMs = currentTimeMillis() - attemptStart
            log.d { "TX write ok: size=${command.size}, type=WithResponse, elapsed=${elapsedMs}ms" }
            log.i { "Command sent via NUS TX: ${command.size} bytes" }

            // Issue #222 v16 (optional): One-shot diagnostic read after CONFIG to catch early faults.
            val isEchoConfig = command.size == 32 && command[0] == 0x4E.toByte()
            val isProgramConfig = command.size == 96 && command[0] == 0x04.toByte()
            if (isEchoConfig || isProgramConfig) {
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
                        log.w { "Post-CONFIG diagnostic read failed: ${e.message}" }
                    }
                }
            }

            logRepo.debug(
                LogEventType.COMMAND_SENT,
                "Command sent (NUS TX)",
                connectedDeviceName,
                connectedDeviceAddress,
                "Size: ${command.size} bytes"
            )
            return Result.success(Unit)
        } else {
            val ex = result.exceptionOrNull()
            log.e { "Failed to send command after retries: ${ex?.message}" }
            logRepo.error(
                LogEventType.ERROR,
                "Failed to send command",
                connectedDeviceName,
                connectedDeviceAddress,
                ex?.message
            )
            return Result.failure(ex ?: IllegalStateException("Unknown error"))
        }
    }

    // ===== HIGH-LEVEL WORKOUT CONTROL (parity with parent repo) =====

    override suspend fun sendInitSequence(): Result<Unit> {
        log.i { "Sending initialization sequence" }
        return try {
            // Send initialization commands to prepare machine for workout
            // Based on parent repo protocol analysis
            val initCmd = byteArrayOf(0x01, 0x00, 0x00, 0x00)  // Init command
            sendWorkoutCommand(initCmd)
        } catch (e: Exception) {
            log.e { "Failed to send init sequence: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        // Stop disco mode if running (safety - don't interfere with workout)
        stopDiscoMode()

        log.i { "Starting workout with params: type=${params.programMode}, weight=${params.weightPerCableKg}kg" }
        return try {
            // Build workout start command based on parameters
            // Format matches parent repo protocol
            val modeCode = params.programMode.modeValue.toByte()
            val weightBytes = (params.weightPerCableKg * 100).toInt()  // Weight in hectograms
            val weightLow = (weightBytes and 0xFF).toByte()
            val weightHigh = ((weightBytes shr 8) and 0xFF).toByte()

            val startCmd = byteArrayOf(0x02, modeCode, weightLow, weightHigh)
            val result = sendWorkoutCommand(startCmd)

            if (result.isSuccess) {
                // Start active workout polling (not auto-start)
                startActiveWorkoutPolling()
            }

            result
        } catch (e: Exception) {
            log.e { "Failed to start workout: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun stopWorkout(): Result<Unit> {
        log.i { "Stopping workout" }
        return try {
            // Send RESET command (0x0A) - matches web app and parent repo behavior
            // This fully stops the workout on the machine
            val resetCmd = BlePacketFactory.createResetCommand()
            log.d { "Sending RESET command (0x0A)..." }
            println("Issue222 TRACE: stopWorkout -> sending RESET (0x0A)")
            sendWorkoutCommand(resetCmd)
            kotlinx.coroutines.delay(50)  // Short delay for machine to process

            // Stop polling AFTER reset (parent behavior)
            log.d { "Stopping polling after RESET..." }
            println("Issue222 TRACE: stopWorkout -> stopping polling after RESET")
            stopPolling()

            Result.success(Unit)
        } catch (e: Exception) {
            log.e { "Failed to stop workout: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        log.i { "Sending stop command (polling continues)" }
        return try {
            // Send StopPacket (0x50) - official app stop command
            // This is a "soft stop" that releases tension but allows polling to continue
            // Used for Just Lift mode where we need continuous polling for auto-start detection
            val stopPacket = BlePacketFactory.createOfficialStopPacket()
            log.d { "Sending StopPacket (0x50)..." }
            sendWorkoutCommand(stopPacket)
        } catch (e: Exception) {
            log.e { "Failed to send stop command: ${e.message}" }
            Result.failure(e)
        }
    }

    override fun enableHandleDetection(enabled: Boolean) {
        log.i { "Handle detection ${if (enabled) "ENABLED" else "DISABLED"}" }
        if (enabled) {
            handleDetector.enable(autoStart = true)
            val p = peripheral
            if (p != null) {
                startMonitorPolling(p, forAutoStart = true)
            }
        } else {
            handleDetector.disable()
        }
    }

    override fun resetHandleState() = handleDetector.reset()

    override fun enableJustLiftWaitingMode() = handleDetector.enableJustLiftWaiting()

    override fun restartMonitorPolling() {
        log.i { "🔄 Restarting monitor polling to clear machine fault state" }
        val p = peripheral
        if (p != null) {
            // Restart polling WITHOUT arming auto-start (forAutoStart=false)
            // This clears the machine's danger zone alarm but doesn't enable grab detection
            startMonitorPolling(p, forAutoStart = false)
        } else {
            log.w { "Cannot restart monitor polling - peripheral is null" }
        }
    }

    override fun startActiveWorkoutPolling() {
        log.i { "🏋️ Starting active workout polling (no auto-start)" }
        val p = peripheral
        if (p != null) {
            log.d {
                "Issue #222 v16: Polling job states before restart - " +
                    "monitor=${monitorPollingJob?.isActive}, " +
                    "diagnostic=${diagnosticPollingJob?.isActive}, " +
                    "heuristic=${heuristicPollingJob?.isActive}, " +
                    "heartbeat=${heartbeatJob?.isActive}"
            }

            // Start polling for active workout (forAutoStart=false)
            // Handle state is set to Active, no grab detection
            startMonitorPolling(p, forAutoStart = false)

            // Issue #222 v16: Restart keep-alive + telemetry polling if stopWorkout() canceled them
            if (diagnosticPollingJob?.isActive != true) {
                log.d { "Issue #222 v16: Restarting diagnostic polling (was stopped by previous stopWorkout)" }
                startDiagnosticPolling(p)
            }
            if (heartbeatJob?.isActive != true) {
                log.d { "Issue #222 v16: Restarting heartbeat (was stopped by previous stopWorkout)" }
                startHeartbeat()
            }
            if (heuristicPollingJob?.isActive != true) {
                log.d { "Issue #222 v16: Restarting heuristic polling (was stopped by previous stopWorkout)" }
                startHeuristicPolling(p)
            }
        } else {
            log.w { "Cannot start active workout polling - peripheral is null" }
        }
    }

    override fun stopPolling() {
        val timestamp = currentTimeMillis()
        log.d { "STOP_DEBUG: [$timestamp] stopPolling() called" }
        log.d {
            "STOP_DEBUG: Job states before cancel - monitor=${monitorPollingJob?.isActive}, " +
                "diagnostic=${diagnosticPollingJob?.isActive}, heuristic=${heuristicPollingJob?.isActive}, " +
                "heartbeat=${heartbeatJob?.isActive}"
        }

        // Log analysis from workout (position range from HandleStateDetector for diagnostics)
        if (handleDetector.minPositionSeen != Double.MAX_VALUE && handleDetector.maxPositionSeen != Double.MIN_VALUE) {
            log.i { "========== WORKOUT ANALYSIS ==========" }
            log.i { "Position range: min=${handleDetector.minPositionSeen}, max=${handleDetector.maxPositionSeen}" }
            log.i { "Detection thresholds (auto-start mode uses lower velocity):" }
            log.i { "  Handle grab: pos > ${BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD} + velocity > ${if (handleDetector.isAutoStartMode) BleConstants.Thresholds.AUTO_START_VELOCITY_THRESHOLD else BleConstants.Thresholds.VELOCITY_THRESHOLD}${if (handleDetector.isAutoStartMode) " (auto-start)" else ""}" }
            log.i { "  Handle release: pos < ${BleConstants.Thresholds.HANDLE_REST_THRESHOLD}" }
            log.i { "======================================" }
        }

        monitorPollingJob?.cancel()
        diagnosticPollingJob?.cancel()
        heuristicPollingJob?.cancel()
        heartbeatJob?.cancel()

        monitorPollingJob = null
        diagnosticPollingJob = null
        heuristicPollingJob = null
        heartbeatJob = null
        diagnosticPollCount = 0
        lastDiagnosticFaults = null

        val afterCancel = currentTimeMillis()
        log.d { "STOP_DEBUG: [$afterCancel] Jobs cancelled (took ${afterCancel - timestamp}ms)" }
    }

    /**
     * Stop monitor polling only, keeping diagnostic polling and heartbeat active.
     * Issue #222: Used during bodyweight exercises to prevent BLE link degradation
     * while not emitting workout metrics that would confuse the UI.
     */
    override fun stopMonitorPollingOnly() {
        log.d { "Stopping monitor polling only - diagnostic polling + heartbeat continue" }
        monitorPollingJob?.cancel()
        monitorPollingJob = null
        log.d {
            "Monitor-only stop: diagnostic=${diagnosticPollingJob?.isActive}, " +
                "heuristic=${heuristicPollingJob?.isActive}, heartbeat=${heartbeatJob?.isActive}"
        }
        // Keep diagnostic, heuristic, and heartbeat running
    }

    /**
     * Restart diagnostic polling and heartbeat only (not monitor polling).
     * Issue #222 v10: Use after bodyweight set completion to maintain BLE link during rest.
     */
    override fun restartDiagnosticPolling() {
        val p = peripheral
        if (p == null) {
            log.w { "Cannot restart diagnostic polling - peripheral is null" }
            return
        }

        log.d { "Restarting diagnostic polling + heartbeat (Issue #222 v10)" }

        // Restart diagnostic polling if not already running
        if (diagnosticPollingJob?.isActive != true) {
            startDiagnosticPolling(p)
        } else {
            log.d { "Diagnostic polling already active - skip restart" }
        }

        // Restart heartbeat if not already running
        if (heartbeatJob?.isActive != true) {
            startHeartbeat()
        } else {
            log.d { "Heartbeat already active - skip restart" }
        }
    }

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
            connectedDeviceAddress
        )

        // Cancel all polling jobs (matches disconnect() behavior)
        heartbeatJob?.cancel()
        heartbeatJob = null
        monitorPollingJob?.cancel()
        monitorPollingJob = null
        diagnosticPollingJob?.cancel()
        diagnosticPollingJob = null
        heuristicPollingJob?.cancel()
        heuristicPollingJob = null

        // Disconnect and release the peripheral
        try {
            isExplicitDisconnect = true
            existingPeripheral.disconnect()
        } catch (e: Exception) {
            log.w { "Cleanup disconnect error (non-fatal): ${e.message}" }
        }

        peripheral = null
        // Note: Don't update _connectionState here - we're about to connect
        // and the Connecting state will be set by the caller
    }

    private fun processIncomingData(data: ByteArray) {
        if (data.isEmpty()) return

        // Extract opcode (first byte) for command response tracking
        val opcode = data[0].toUByte()
        log.d { "RX notification: opcode=0x${opcode.toString(16).padStart(2, '0')}, size=${data.size}" }
        _commandResponses.tryEmit(opcode)

        // Route to specific parsers
        when (opcode.toInt()) {
            0x01 -> if (data.size >= 16) parseMetricsPacket(data)
            0x02 -> if (data.size >= 5) parseRepNotification(data)
            // Other opcodes can be handled here as needed
        }
    }

    /**
     * Wait for a specific response opcode with timeout.
     * Used for protocol handshakes that require acknowledgment.
     *
     * @param expectedOpcode The opcode to wait for
     * @param timeoutMs Timeout in milliseconds (default 5000ms)
     * @return true if the expected opcode was received, false on timeout
     */
    @Suppress("unused") // Reserved for future protocol handshake commands
    suspend fun awaitResponse(expectedOpcode: UByte, timeoutMs: Long = 5000L): Boolean {
        return try {
            val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
            log.d { "⏳ Waiting for response opcode 0x$opcodeHex (timeout: ${timeoutMs}ms)" }

            val result = withTimeoutOrNull(timeoutMs) {
                commandResponses.filter { it == expectedOpcode }.first()
            }

            if (result != null) {
                log.d { "✅ Received expected response opcode 0x$opcodeHex" }
                true
            } else {
                log.w { "⏱️ Timeout waiting for response opcode 0x$opcodeHex" }
                false
            }
        } catch (e: Exception) {
            val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
            log.e { "Error waiting for response opcode 0x$opcodeHex: ${e.message}" }
            false
        }
    }

    /**
     * Parse monitor characteristic data with full velocity calculation and status flag detection.
     * This is the CRITICAL function for Just Lift mode handle detection.
     */
    private fun parseMonitorData(data: ByteArray) {
        // Use extracted parser for byte-level parsing
        val packet = parseMonitorPacket(data)
        if (packet == null) {
            log.w { "Monitor data too short: ${data.size} bytes" }
            return
        }

        try {
            // Increment counter for diagnostic logging
            monitorNotificationCount++
            if (monitorNotificationCount % 100 == 0L) {
                log.i { "MONITOR NOTIFICATION #$monitorNotificationCount" }
            }

            // Extract values from parsed packet
            var posA = packet.posA
            var posB = packet.posB
            val loadA = packet.loadA
            val loadB = packet.loadB
            val ticks = packet.ticks
            val status = packet.status

            // ===== POSITION VALIDATION (matching parent repo) =====
            // Validate position range and use last good value if invalid
            // Per official app documentation, valid range is -1000 to +1000 mm
            if (posA !in BleConstants.Thresholds.MIN_POSITION.toFloat()..BleConstants.Thresholds.MAX_POSITION.toFloat()) {
                log.w { "Position A out of range: $posA, using last good: $lastGoodPosA" }
                posA = lastGoodPosA
            } else {
                lastGoodPosA = posA
            }
            if (posB !in BleConstants.Thresholds.MIN_POSITION.toFloat()..BleConstants.Thresholds.MAX_POSITION.toFloat()) {
                log.w { "Position B out of range: $posB, using last good: $lastGoodPosB" }
                posB = lastGoodPosB
            } else {
                lastGoodPosB = posB
            }

            // ===== STATUS FLAG PROCESSING =====
            if (packet.status != 0) {
                processStatusFlags(packet.status)
            }

            // ===== SAMPLE VALIDATION =====
            // Issue #210 Fix: Store previous positions and update tracking BEFORE validation
            // This prevents cascading filter failures where every sample after one jump is filtered
            // because lastPositionA/B were never updated (early return skipped the update)
            val previousPosA = lastPositionA
            val previousPosB = lastPositionB
            lastPositionA = posA
            lastPositionB = posB

            if (!validateSample(posA, loadA, posB, loadB, previousPosA, previousPosB)) {
                lastSampleWasFiltered = true  // Mark for velocity reset on next valid sample
                return  // Skip invalid sample, but position tracking is updated for next sample
            }

            // ===== VELOCITY CALCULATION =====
            val currentTime = currentTimeMillis()
            val pollIntervalMs = if (lastTimestamp > 0L) currentTime - lastTimestamp else 0L

            // ===== POLL RATE DIAGNOSTICS =====
            // Parent repo achieves ~10-20ms poll intervals with Nordic BLE
            // If we're seeing >30ms consistently, connection priority may not be set
            if (pollIntervalMs > 0) {
                pollIntervalSum += pollIntervalMs
                pollIntervalCount++
                if (pollIntervalMs > maxPollInterval) maxPollInterval = pollIntervalMs
                if (pollIntervalMs < minPollInterval) minPollInterval = pollIntervalMs

                // Log every 100 samples with statistics
                if (pollIntervalCount % 100 == 0L) {
                    val avgInterval = pollIntervalSum / pollIntervalCount
                    log.i { "📊 POLL RATE: avg=${avgInterval}ms, min=${minPollInterval}ms, max=${maxPollInterval}ms, count=$pollIntervalCount" }
                    if (avgInterval > 30) {
                        log.w { "⚠️ SLOW POLL RATE: ${avgInterval}ms avg (expected <20ms). Check connection priority!" }
                    }
                }

                // Warn on individual slow polls (but not spam)
                if (pollIntervalMs > 50 && pollIntervalCount % 20 == 0L) {
                    log.w { "⚠️ Slow poll: ${pollIntervalMs}ms (sample #$pollIntervalCount)" }
                }
            }

            // Calculate raw velocity (SIGNED for proper EMA smoothing - Issue #204, #214)
            // Using signed velocity allows jitter oscillations (+2, -3, +1mm) to average toward 0
            // Issue #210: Use previousPosA/B since lastPositionA/B was updated before validateSample
            val rawVelocityA = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posA - previousPosA
                if (deltaTime > 0) deltaPos / deltaTime else 0.0
            } else 0.0

            val rawVelocityB = if (lastTimestamp > 0L) {
                val deltaTime = (currentTime - lastTimestamp) / 1000.0
                val deltaPos = posB - previousPosB
                if (deltaTime > 0) deltaPos / deltaTime else 0.0
            } else 0.0

            // Apply Exponential Moving Average (EMA) smoothing (Issue #204, #214)
            // This prevents false stall detection during controlled tempo movements
            // and reduces sensitivity to BLE position jitter
            // Task 10: Initialize EMA with first raw sample to prevent cold start lag
            // Velocity edge case fix: If previous sample was filtered due to position jump,
            // the raw velocity calculation used a bad reference position. Skip this sample's
            // velocity update to avoid propagating the error through the EMA.
            if (lastSampleWasFiltered) {
                // Don't update smoothed velocity - keep previous value
                // The raw velocity is calculated against the filtered position which is wrong
                // Next sample will have correct reference since lastPositionA/B were updated
                lastSampleWasFiltered = false
                log.d { "Velocity update skipped - previous sample was filtered" }
            } else if (isFirstVelocitySample) {
                smoothedVelocityA = rawVelocityA
                smoothedVelocityB = rawVelocityB
                isFirstVelocitySample = false
            } else {
                smoothedVelocityA = BleConstants.Thresholds.VELOCITY_SMOOTHING_ALPHA * rawVelocityA +
                        (1 - BleConstants.Thresholds.VELOCITY_SMOOTHING_ALPHA) * smoothedVelocityA
                smoothedVelocityB = BleConstants.Thresholds.VELOCITY_SMOOTHING_ALPHA * rawVelocityB +
                        (1 - BleConstants.Thresholds.VELOCITY_SMOOTHING_ALPHA) * smoothedVelocityB
            }

            // Update timestamp for next velocity calculation
            // Issue #210: lastPositionA/B are now updated before validateSample (see above)
            lastTimestamp = currentTime

            // Create metric with SMOOTHED velocity (absolute value for backwards compatibility)
            // Track both cables independently - matches official app behavior
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA,
                loadB = loadB,
                positionA = posA,
                positionB = posB,
                ticks = ticks,
                velocityA = smoothedVelocityA,  // Smoothed, signed velocity
                velocityB = smoothedVelocityB,  // Smoothed, signed velocity
                status = status
            )

            // Use tryEmit for non-blocking emission (matching parent repo)
            val emitted = _metricsFlow.tryEmit(metric)
            if (!emitted && monitorNotificationCount % 100 == 0L) {
                log.w { "Failed to emit metric - buffer full? Count: $monitorNotificationCount" }
            }

            // Handle detection + 4-state machine delegated to HandleStateDetector
            handleDetector.processMetric(metric)

        } catch (e: Exception) {
            log.e { "Error parsing monitor data: ${e.message}" }
        }
    }

    /**
     * Process status flags from bytes 16-17 of monitor data.
     * Handles deload detection and safety events.
     */
    private fun processStatusFlags(status: Int) {
        if (status == 0) return

        val sampleStatus = SampleStatus(status)

        // Task 5: ROM violation safety handling
        if (sampleStatus.isRomOutsideHigh()) {
            log.w { "SAFETY: ROM_OUTSIDE_HIGH detected - Status: 0x${status.toString(16)}" }
            scope.launch { _romViolationEvents.emit(RomViolationType.OUTSIDE_HIGH) }
        }
        if (sampleStatus.isRomOutsideLow()) {
            log.w { "SAFETY: ROM_OUTSIDE_LOW detected - Status: 0x${status.toString(16)}" }
            scope.launch { _romViolationEvents.emit(RomViolationType.OUTSIDE_LOW) }
        }

        if (sampleStatus.isDeloadOccurred()) {
            log.w { "MACHINE STATUS: DELOAD_OCCURRED flag set - Status: 0x${status.toString(16)}" }

            // Emit deload event (debounced) for repository/ViewModel to handle
            val now = currentTimeMillis()
            if (now - lastDeloadEventTime > BleConstants.Timing.DELOAD_EVENT_DEBOUNCE_MS) {
                lastDeloadEventTime = now
                scope.launch {
                    log.d { "DELOAD_OCCURRED: Emitting event" }
                    _deloadOccurredEvents.emit(Unit)
                }
            }
        }

        if (sampleStatus.isDeloadWarn()) {
            log.w { "MACHINE STATUS: DELOAD_WARN - Status: 0x${status.toString(16)}" }
        }

        if (sampleStatus.isSpotterActive()) {
            log.d { "MACHINE STATUS: SPOTTER_ACTIVE - Status: 0x${status.toString(16)}" }
        }
    }

    /**
     * Validate sample data is within acceptable ranges.
     * Position values are in millimeters (Issue #197).
     *
     * Issue #210: previousPosA/B are passed in explicitly for jump detection since
     * lastPositionA/B are now updated BEFORE calling this function to prevent
     * cascading filter failures.
     */
    private fun validateSample(
        posA: Float, loadA: Float, posB: Float, loadB: Float,
        previousPosA: Float, previousPosB: Float
    ): Boolean {
        // Official app range: -1000 to +1000 mm (Float for mm precision - Issue #197)
        if (posA !in BleConstants.Thresholds.MIN_POSITION.toFloat()..BleConstants.Thresholds.MAX_POSITION.toFloat() ||
            posB !in BleConstants.Thresholds.MIN_POSITION.toFloat()..BleConstants.Thresholds.MAX_POSITION.toFloat()) {
            log.w { "Position out of range: posA=$posA, posB=$posB (valid: ${BleConstants.Thresholds.MIN_POSITION} to ${BleConstants.Thresholds.MAX_POSITION} mm)" }
            return false
        }

        // Task 8: Load validation - check against hardware max weight
        if (loadA < 0f || loadA > BleConstants.Thresholds.MAX_WEIGHT_KG || loadB < 0f || loadB > BleConstants.Thresholds.MAX_WEIGHT_KG) {
            log.w { "Load out of range: loadA=$loadA, loadB=$loadB (max=${BleConstants.Thresholds.MAX_WEIGHT_KG})" }
            return false
        }

        // STRICT VALIDATION: Filter >20mm jumps between samples (matching parent repo)
        // This catches BLE glitches that produce sudden position changes
        // Issue #210: Use passed-in previous positions, not class fields
        if (strictValidationEnabled && lastTimestamp > 0L) {
            val jumpA = kotlin.math.abs(posA - previousPosA)
            val jumpB = kotlin.math.abs(posB - previousPosB)
            if (jumpA > BleConstants.Thresholds.POSITION_JUMP_THRESHOLD || jumpB > BleConstants.Thresholds.POSITION_JUMP_THRESHOLD) {
                log.w { "⚠️ Position jump filtered: jumpA=${jumpA}mm, jumpB=${jumpB}mm (threshold: ${BleConstants.Thresholds.POSITION_JUMP_THRESHOLD}mm)" }
                return false
            }
        }

        return true
    }

    /**
     * Parse metrics packet from RX notifications (0x01 command).
     * Uses big-endian byte order for this packet type.
     * Position values scaled to mm (Issue #197).
     */
    private fun parseMetricsPacket(data: ByteArray) {
        if (data.size < 16) return

        try {
            // RX notification metrics use big-endian byte order
            val positionARaw = getUInt16BE(data, 2)
            val positionBRaw = getUInt16BE(data, 4)
            val loadA = getUInt16BE(data, 6)
            val loadB = getUInt16BE(data, 8)
            val velocityA = getUInt16BE(data, 10)
            val velocityB = getUInt16BE(data, 12)

            // Scale position to mm (Issue #197)
            val positionA = positionARaw / 10.0f
            val positionB = positionBRaw / 10.0f

            val currentTime = currentTimeMillis()
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA / 10f,
                loadB = loadB / 10f,
                positionA = positionA,
                positionB = positionB,
                velocityA = (velocityA - 32768).toDouble(),
                velocityB = (velocityB - 32768).toDouble()
            )

            // Use tryEmit for non-blocking emission (matching parent repo)
            _metricsFlow.tryEmit(metric)

            // Handle detection + 4-state machine delegated to HandleStateDetector
            handleDetector.processMetric(metric)
        } catch (e: Exception) {
            log.e { "Error parsing metrics: ${e.message}" }
        }
    }

    /**
     * Parse rep notification from RX characteristic (with opcode prefix).
     * Note: data[0] is the opcode (0x02), so rep data starts at index 1.
     * See parseRepsCharacteristicData() for direct REPS characteristic parsing.
     */
    private fun parseRepNotification(data: ByteArray) {
        try {
            val currentTime = currentTimeMillis()
            val notification = parseRepPacket(data, hasOpcodePrefix = true, timestamp = currentTime)

            if (notification == null) {
                log.w { "Rep notification too short: ${data.size} bytes (minimum 7)" }
                return
            }

            // Log the parsed notification
            if (notification.isLegacyFormat) {
                log.w { "Rep notification (LEGACY 6-byte format - Issue #187 fallback):" }
                log.w { "  top=${notification.topCounter}, complete=${notification.completeCounter}" }
                log.w { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }
            } else {
                log.d { "Rep notification (24-byte format, RX):" }
                log.d { "  up=${notification.topCounter}, down=${notification.completeCounter}" }
                log.d { "  repsRomCount=${notification.repsRomCount} (warmup done), repsRomTotal=${notification.repsRomTotal} (warmup target)" }
                log.d { "  repsSetCount=${notification.repsSetCount} (working done), repsSetTotal=${notification.repsSetTotal} (working target)" }
                log.d { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }
            }

            val emitted = _repEvents.tryEmit(notification)
            log.d { "Emitted rep event (RX): success=$emitted, legacy=${notification.isLegacyFormat}" }

            // Log to user-visible connection logs for Issue #123 diagnosis
            logRepo.debug(
                LogEventType.REP_RECEIVED,
                if (notification.isLegacyFormat) "Legacy rep (6-byte)" else "Modern rep (24-byte)",
                connectedDeviceName.ifEmpty { null },
                connectedDeviceAddress.ifEmpty { null },
                "up=${notification.topCounter}, setCount=${notification.repsSetCount}, legacy=${notification.isLegacyFormat}"
            )
        } catch (e: Exception) {
            log.e { "Error parsing rep notification: ${e.message}" }
        }
    }

    /**
     * Parse rep data from REPS characteristic notifications (NO opcode prefix).
     * Called when the dedicated REPS_UUID characteristic sends notifications.
     */
    private fun parseRepsCharacteristicData(data: ByteArray) {
        try {
            val currentTime = currentTimeMillis()
            val notification = parseRepPacket(data, hasOpcodePrefix = false, timestamp = currentTime)

            if (notification == null) {
                log.w { "REPS characteristic data too short: ${data.size} bytes (minimum 6)" }
                return
            }

            // Log raw data for debugging
            log.i { "REPS CHAR notification: ${data.size} bytes" }
            log.d { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }

            // Log the parsed notification
            if (notification.isLegacyFormat) {
                log.w { "REPS (LEGACY 6-byte format):" }
                log.w { "  top=${notification.topCounter}, complete=${notification.completeCounter}" }
            } else {
                log.i { "REPS (24-byte official format):" }
                log.i { "  up=${notification.topCounter}, down=${notification.completeCounter}" }
                log.i { "  repsRomCount=${notification.repsRomCount} (warmup done), repsRomTotal=${notification.repsRomTotal} (warmup target)" }
                log.i { "  repsSetCount=${notification.repsSetCount} (working done), repsSetTotal=${notification.repsSetTotal} (working target)" }
                log.i { "  rangeTop=${notification.rangeTop}, rangeBottom=${notification.rangeBottom}" }
            }

            val emitted = _repEvents.tryEmit(notification)
            log.i { "Emitted rep event (REPS char): success=$emitted, legacy=${notification.isLegacyFormat}, repsSetCount=${notification.repsSetCount}" }

            // Log to user-visible connection logs for Issue #123 diagnosis
            logRepo.debug(
                LogEventType.REP_RECEIVED,
                if (notification.isLegacyFormat) "Legacy rep (6-byte)" else "Modern rep (24-byte)",
                connectedDeviceName.ifEmpty { null },
                connectedDeviceAddress.ifEmpty { null },
                "up=${notification.topCounter}, setCount=${notification.repsSetCount}, legacy=${notification.isLegacyFormat}"
            )
        } catch (e: Exception) {
            log.e { "Error parsing REPS characteristic data: ${e.message}" }
        }
    }

    /**
     * Get current time in milliseconds (KMP-compatible).
     */
    private fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }

    // ========== Disco Mode (Easter Egg) ==========

    override fun startDiscoMode() {
        if (peripheral == null) {
            log.w { "Cannot start disco mode - not connected" }
            return
        }
        discoMode.start()
    }

    override fun stopDiscoMode() = discoMode.stop()

    override fun setLastColorSchemeIndex(index: Int) = discoMode.setLastColorSchemeIndex(index)
}
