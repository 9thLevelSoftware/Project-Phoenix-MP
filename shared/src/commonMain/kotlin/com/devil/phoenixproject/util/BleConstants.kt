package com.devil.phoenixproject.util

/**
 * BLE Constants - UUIDs and configuration values for Vitruvian device communication
 * Based on Phoenix Backend (deobfuscated official app)
 */
@Suppress("unused")  // Protocol reference constants - many are kept for documentation
object BleConstants {
    // Service UUIDs
    const val GATT_SERVICE_UUID_STRING = "00001801-0000-1000-8000-00805f9b34fb"
    const val NUS_SERVICE_UUID_STRING = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    // Primary Characteristic UUIDs (from Phoenix Backend)
    const val NUS_RX_CHAR_UUID_STRING = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val SAMPLE_CHAR_UUID_STRING = "90e991a6-c548-44ed-969b-eb541014eae3" // 28 bytes
    const val MONITOR_CHAR_UUID_STRING = SAMPLE_CHAR_UUID_STRING // Alias for backward compat
    const val CABLE_LEFT_CHAR_UUID_STRING = "bc4344e9-8d63-4c89-8263-951e2d74f744" // 6 bytes
    const val CABLE_RIGHT_CHAR_UUID_STRING = "92ef83d6-8916-4921-8172-a9919bc82566" // 6 bytes
    const val REPS_CHAR_UUID_STRING = "8308f2a6-0875-4a94-a86f-5c5c5e1b068a" // 24 bytes notifiable
    const val REP_NOTIFY_CHAR_UUID_STRING = REPS_CHAR_UUID_STRING // Alias
    const val MODE_CHAR_UUID_STRING = "67d0dae0-5bfc-4ea2-acc9-ac784dee7f29" // 4 bytes notifiable
    const val VERSION_CHAR_UUID_STRING = "74e994ac-0e80-4c02-9cd0-76cb31d3959b" // Variable
    const val WIFI_STATE_CHAR_UUID_STRING = "a7d06ce0-2e84-485f-9c25-3d4ba6fe7319" // 74 bytes
    const val UPDATE_STATE_CHAR_UUID_STRING = "383f7276-49af-4335-9072-f01b0f8acad6" // Variable
    const val BLE_UPDATE_REQUEST_CHAR_UUID_STRING = "ef0e485a-8749-4314-b1be-01e57cd1712e" // 5 bytes notifiable
    const val HEURISTIC_CHAR_UUID_STRING = "c7b73007-b245-4503-a1ed-9e4e97eb9802" // Variable
    const val DIAGNOSTIC_CHAR_UUID_STRING = "5fa538ec-d041-42f6-bbd6-c30d475387b7" // Variable
    const val PROPERTY_CHAR_UUID_STRING = DIAGNOSTIC_CHAR_UUID_STRING // Alias

    // Unknown/Auth characteristic - present in web apps notification list
    // Purpose unclear but may be needed for proper device communication
    const val UNKNOWN_AUTH_CHAR_UUID_STRING = "36e6c2ee-21c7-404e-aa9b-f74ca4728ad4"

    val NOTIFY_CHAR_UUID_STRINGS = listOf(
        UPDATE_STATE_CHAR_UUID_STRING,
        VERSION_CHAR_UUID_STRING,
        MODE_CHAR_UUID_STRING,
        REPS_CHAR_UUID_STRING,
        HEURISTIC_CHAR_UUID_STRING,
        BLE_UPDATE_REQUEST_CHAR_UUID_STRING,
        UNKNOWN_AUTH_CHAR_UUID_STRING  // Web apps subscribe to this
    )

    // Official app workout command characteristics (discovered from HCI logs)
    // These are writable characteristics (props: 4 = WRITE_NO_RESPONSE)
    val WORKOUT_CMD_CHAR_UUID_STRINGS = listOf(
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6a5",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6a6",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6a7",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6a8",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6a9",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6aa",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6ab",
        "6d094aa3-b60d-4916-8a55-8ed73fb9f6ac"
    )

    // Device name pattern for filtering - matches "Vitruvian*" devices
    const val DEVICE_NAME_PREFIX = "Vee"
    const val DEVICE_NAME_PATTERN = "^Vitruvian.*$"

    // Command IDs (Official Protocol from Phoenix Backend)
    object Commands {
        const val STOP_COMMAND: Byte = 0x50        // Stop/halt (official app)
        const val RESET_COMMAND: Byte = 0x0A       // Reset/init (web app stop) - recovery fallback
        const val REGULAR_COMMAND: Byte = 0x4F    // 25-byte packet (79 decimal)
        const val ECHO_COMMAND: Byte = 0x4E       // 29-byte packet (78 decimal)
        const val ACTIVATION_COMMAND: Byte = 0x04 // 97-byte packet
        const val DEFAULT_ROM_REP_COUNT: Byte = 3
    }

    // Legacy aliases for backward compatibility
    @Suppress("unused") const val CMD_REGULAR = 0x4F
    @Suppress("unused") const val CMD_ECHO = 0x4E
    @Suppress("unused") const val CMD_STOP = 0x50

    // Data Protocol Constants (from Phoenix Backend)
    @Suppress("unused")  // Protocol reference documentation
    object DataProtocol {
        // Scaling factors for cable data
        const val POSITION_SCALE = 10.0  // divide raw by 10 for mm
        const val VELOCITY_SCALE = 10.0  // divide raw by 10 for mm/s
        const val FORCE_SCALE = 100.0    // divide raw by 100 for percentage

        // Valid ranges
        const val POSITION_MIN = -1000.0
        const val POSITION_MAX = 1000.0
        const val VELOCITY_MIN = -1000.0
        const val VELOCITY_MAX = 1000.0
        const val FORCE_MIN = 0.0
        const val FORCE_MAX = 100.0

        // Data sizes
        const val CABLE_DATA_SIZE = 6     // 3 x Int16
        const val SAMPLE_DATA_SIZE = 28   // 2 cables + timestamp + status
        const val REPS_DATA_SIZE = 24
    }

    // Connection timeouts
    const val CONNECTION_TIMEOUT_MS = 15000L
    const val GATT_OPERATION_TIMEOUT_MS = 5000L
    const val SCAN_TIMEOUT_MS = 30000L

    // BLE operation delays
    const val BLE_QUEUE_DRAIN_DELAY_MS = 250L
}
