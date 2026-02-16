package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

import com.devil.phoenixproject.data.ble.DiscoMode
import com.devil.phoenixproject.data.ble.HandleStateDetector
import com.devil.phoenixproject.data.ble.MonitorDataProcessor
import com.devil.phoenixproject.data.ble.BleOperationQueue
import com.devil.phoenixproject.data.ble.MetricPollingEngine
import com.devil.phoenixproject.data.ble.requestHighPriority
import com.devil.phoenixproject.data.ble.requestMtuIfSupported
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.data.ble.parseDiagnosticPacket
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

    // Monitor data processing delegated to MonitorDataProcessor (Phase 10 extraction)
    private val monitorProcessor = MonitorDataProcessor(
        onDeloadOccurred = { scope.launch { _deloadOccurredEvents.emit(Unit) } },
        onRomViolation = { type ->
            scope.launch {
                when (type) {
                    MonitorDataProcessor.RomViolationType.OUTSIDE_HIGH ->
                        _romViolationEvents.emit(RomViolationType.OUTSIDE_HIGH)
                    MonitorDataProcessor.RomViolationType.OUTSIDE_LOW ->
                        _romViolationEvents.emit(RomViolationType.OUTSIDE_LOW)
                }
            }
        }
    )

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

    // Monitor data processing state (position, velocity, EMA, status flags)
    // All 15 state variables moved to MonitorDataProcessor (Phase 10 extraction)


    // Issue #222: All BLE operations serialized through single queue
    private val bleQueue = BleOperationQueue()

    // Polling state moved to MetricPollingEngine (Phase 11 extraction)
    private val pollingEngine = MetricPollingEngine(
        scope = scope,
        bleQueue = bleQueue,
        monitorProcessor = monitorProcessor,
        handleDetector = handleDetector,
        onMetricEmit = { metric ->
            val emitted = _metricsFlow.tryEmit(metric)
            if (!emitted && monitorProcessor.notificationCount % 100 == 0L) {
                log.w { "Failed to emit metric - buffer full? Count: ${monitorProcessor.notificationCount}" }
            }
            emitted
        },
        onHeuristicData = { stats -> _heuristicData.value = stats },
        onConnectionLost = { disconnect() }
    )

    // Detected firmware version (from DIS or proprietary characteristic)
    private var detectedFirmwareVersion: String? = null

    // Negotiated MTU (for diagnostic logging)
    @Volatile private var negotiatedMtu: Int? = null

    // strictValidationEnabled moved to MonitorDataProcessor (Phase 10 extraction)

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

                                // Stop all polling jobs
                                pollingEngine.stopAll()
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

        // ===== POLLING (delegated to MetricPollingEngine) =====
        stopDiscoMode()
        pollingEngine.startAll(p)
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
     * Parse diagnostic data from DIAGNOSTIC/PROPERTY characteristic.
     * Contains fault codes and temperature readings.
     */
    private fun parseDiagnosticData(bytes: ByteArray) {
        try {
            val packet = parseDiagnosticPacket(bytes) ?: return
            log.i { "DIAGNOSTIC: faults=${packet.faults} temps=${packet.temps.map { it.toInt() }}" }
            if (packet.hasFaults) {
                log.w { "DIAGNOSTIC FAULTS DETECTED: ${packet.faults}" }
            }
        } catch (e: Exception) {
            log.e { "Failed to parse diagnostic data: ${e.message}" }
        }
    }

    override suspend fun disconnect() {
        log.i { "Disconnecting (explicit)" }
        isExplicitDisconnect = true  // Mark as explicit disconnect to prevent auto-reconnect

        // Cancel all polling jobs
        pollingEngine.stopAll()

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
            val p = peripheral
            if (p != null) {
                stopDiscoMode()
                pollingEngine.startMonitorPolling(p, forAutoStart = true)
            }
        } else {
            handleDetector.disable()
        }
    }

    override fun resetHandleState() = handleDetector.reset()

    override fun enableJustLiftWaitingMode() = handleDetector.enableJustLiftWaiting()

    override fun restartMonitorPolling() {
        log.i { "Restarting monitor polling to clear machine fault state" }
        val p = peripheral ?: run {
            log.w { "Cannot restart monitor polling - peripheral is null" }
            return
        }
        stopDiscoMode()
        pollingEngine.startMonitorPolling(p, forAutoStart = false)
    }

    override fun startActiveWorkoutPolling() {
        log.i { "Starting active workout polling (no auto-start)" }
        val p = peripheral ?: run {
            log.w { "Cannot start active workout polling - peripheral is null" }
            return
        }
        stopDiscoMode()
        pollingEngine.restartAll(p)
    }

    override fun stopPolling() = pollingEngine.stopAll()

    override fun stopMonitorPollingOnly() = pollingEngine.stopMonitorOnly()

    override fun restartDiagnosticPolling() {
        val p = peripheral ?: run {
            log.w { "Cannot restart diagnostic polling - peripheral is null" }
            return
        }
        pollingEngine.restartDiagnosticAndHeartbeat(p)
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
        pollingEngine.stopAll()

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

    // parseMonitorData() moved to MetricPollingEngine (Phase 11 extraction)

    // processStatusFlags() moved to MonitorDataProcessor (Phase 10 extraction)

    // validateSample() moved to MonitorDataProcessor (Phase 10 extraction)

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
