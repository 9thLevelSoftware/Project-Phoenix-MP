package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.HardwareDetection
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
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
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
) {
    private val log = Logger.withTag("KableBleConnectionManager")

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
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandResponses: Flow<UByte> = _commandResponses.asSharedFlow()

    // -------------------------------------------------------------------------
    // Helper to update connection state via callback + local tracking
    // -------------------------------------------------------------------------
    private fun reportConnectionState(state: ConnectionState) {
        lastReportedState = state
        onConnectionStateChanged(state)
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
                    var devices = currentScannedDevices.toMutableList()

                    // If this is a real-named device, remove any placeholder devices first
                    // (same physical device can advertise with different identifiers)
                    if (hasRealName) {
                        devices = devices.filter { existing ->
                            existing.name.startsWith("Vee_", ignoreCase = true) ||
                            existing.name.startsWith("VIT", ignoreCase = true) ||
                            existing.address == identifier  // Keep if same address (will update below)
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
                    log.e { "Scan error: ${e.message}" }
                    logRepo.error(LogEventType.ERROR, "BLE scan failed", details = e.message)
                    // Return to Disconnected instead of Error for scan failures - user can retry
                    reportConnectionState(ConnectionState.Disconnected)
                }
                .launchIn(scope)

            Result.success(Unit)
        } catch (e: Exception) {
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
            details = "Found ${discoveredAdvertisements.size} Vitruvian device(s)"
        )
        scanJob?.cancel()
        scanJob = null
        if (lastReportedState == ConnectionState.Scanning) {
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
            device.address
        )

        // Clean up any existing connection first (matches parent repo)
        // Prevents "dangling GATT connections" on Android 16/Pixel 7
        cleanupExistingConnection()

        reportConnectionState(ConnectionState.Connecting)

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

            // Observe connection state
            peripheral?.state
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
                                connectedDeviceAddress
                            )
                            reportConnectionState(ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address,
                                hardwareModel = HardwareDetection.detectModel(device.name)
                            ))
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
                                reportConnectionState(ConnectionState.Disconnected)
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
                                log.i { "Requesting auto-reconnect to $deviceName ($deviceAddress)" }
                                scope.launch {
                                    onReconnectionRequested(
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
                        // The state observer will emit Connected when ready
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
            reportConnectionState(ConnectionState.Disconnected)
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
                connectedDeviceAddress
            )
        } else {
            // iOS returns null (handled by OS), or Android negotiation failed
            log.i { "MTU negotiation: using system default (iOS) or failed (Android)" }
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
                log.w { "No services discovered - device may not be fully ready" }
                logRepo.warning(
                    LogEventType.SERVICE_DISCOVERED,
                    "No services found after connection",
                    connectedDeviceName,
                    connectedDeviceAddress
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

    // -------------------------------------------------------------------------
    // 6. startObservingNotifications()
    // -------------------------------------------------------------------------

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
                        onRepEventFromCharacteristic(data)
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
                        log.i { "VERSION CHARACTERISTIC DATA RECEIVED" }
                        log.i { "  Size: ${data.size} bytes, Hex: $hexString" }
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
        discoMode.stop()
        pollingEngine.startAll(p)
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
                    connectedDeviceAddress
                )
            }
        } catch (e: Exception) {
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
            log.d { "Vitruvian VERSION characteristic not readable (expected): ${e.message}" }
        }
    }

    // -------------------------------------------------------------------------
    // 9. disconnect()
    // -------------------------------------------------------------------------

    suspend fun disconnect() {
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
        reportConnectionState(ConnectionState.Disconnected)
    }

    // -------------------------------------------------------------------------
    // 10. cancelConnection()
    // -------------------------------------------------------------------------

    suspend fun cancelConnection() {
        log.i { "Cancelling in-progress connection" }
        isExplicitDisconnect = true  // Prevent auto-reconnect
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            log.e { "Cancel connection error: ${e.message}" }
        }
        peripheral = null
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

    // -------------------------------------------------------------------------
    // 12. sendWorkoutCommand()
    // -------------------------------------------------------------------------

    suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
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
            log.i { "DIAGNOSTIC: faults=${packet.faults} temps=${packet.temps.map { it.toInt() }}" }
            if (packet.hasFaults) {
                log.w { "DIAGNOSTIC FAULTS DETECTED: ${packet.faults}" }
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
    suspend fun awaitResponse(expectedOpcode: UByte, timeoutMs: Long = 5000L): Boolean {
        return try {
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
            val opcodeHex = expectedOpcode.toString(16).uppercase().padStart(2, '0')
            log.e { "Error waiting for response opcode 0x$opcodeHex: ${e.message}" }
            false
        }
    }

    // -------------------------------------------------------------------------
    // Private utility
    // -------------------------------------------------------------------------

    /**
     * Get current time in milliseconds (KMP-compatible).
     */
    private fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }
}
