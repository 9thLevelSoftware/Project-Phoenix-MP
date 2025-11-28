package com.devil.phoenixproject.util

/**
 * Protocol Tester - Diagnostic tool for testing different BLE initialization protocols
 *
 * This helps diagnose connection issues on specific device/firmware combinations by
 * cycling through different initialization sequences and delays to find what works.
 */
object ProtocolTester {

    /**
     * Different initialization protocol variants to test
     */
    enum class InitProtocol(val displayName: String, val description: String) {
        NO_INIT(
            "No Init (Default)",
            "Skip initialization - just connect and start workout directly"
        ),
        INIT_0x0A_NO_WAIT(
            "Init 0x0A (No Wait)",
            "Send INIT command (0x0A) but don't wait for response"
        ),
        INIT_0x0A_WAIT_0x0B(
            "Init 0x0A + Wait 0x0B",
            "Send INIT (0x0A) and wait up to 5 seconds for 0x0B response"
        ),
        INIT_0x0A_PLUS_PRESET(
            "Init 0x0A + Preset",
            "Legacy web app protocol: Send 0x0A then 0x11 preset frame"
        ),
        INIT_DOUBLE_0x0A(
            "Double Init 0x0A",
            "Send INIT command twice with delay between"
        )
    }

    /**
     * Different delay configurations to test after connection
     */
    enum class ConnectionDelay(val displayName: String, val delayMs: Long) {
        NONE("No Delay", 0L),
        DELAY_50MS("50ms", 50L),
        DELAY_100MS("100ms", 100L),
        DELAY_250MS("250ms", 250L),
        DELAY_500MS("500ms", 500L),
        DELAY_1000MS("1 second", 1000L),
        DELAY_2000MS("2 seconds", 2000L)
    }

    /**
     * Result of a single protocol test
     */
    data class TestResult(
        val protocol: InitProtocol,
        val delay: ConnectionDelay,
        val success: Boolean,
        val connectionTimeMs: Long,
        val initTimeMs: Long,
        val errorMessage: String? = null,
        val notes: String? = null,
        val diagnostics: TestDiagnostics? = null
    ) {
        val totalTimeMs: Long get() = connectionTimeMs + initTimeMs

        fun toFormattedString(): String = buildString {
            append("${protocol.displayName} + ${delay.displayName}: ")
            if (success) {
                append("SUCCESS (${totalTimeMs}ms)")
            } else {
                append("FAILED")
                errorMessage?.let { append(" - $it") }
            }
        }
    }

    /**
     * Enhanced diagnostic data collected during test
     */
    data class TestDiagnostics(
        val firmwareVersion: String? = null,
        val mtuSize: Int? = null,
        val monitorPollsReceived: Int = 0,
        val monitorPollsFailed: Int = 0,
        val disconnectTimeSeconds: Int? = null,
        val lastDisconnectReason: String? = null,
        val rssiAtDisconnect: Int? = null,
        val workoutSimulationDurationMs: Long = 0
    )

    /**
     * Protocol test configuration
     */
    data class TestConfig(
        val protocol: InitProtocol,
        val delay: ConnectionDelay,
        val timeout: Long = 10000L
    )

    /**
     * Generate all test configurations to try
     */
    fun generateAllTestConfigs(): List<TestConfig> {
        val configs = mutableListOf<TestConfig>()
        InitProtocol.entries.forEach { protocol ->
            ConnectionDelay.entries.forEach { delay ->
                configs.add(TestConfig(protocol, delay))
            }
        }
        return configs
    }

    /**
     * Generate a recommended subset of test configurations (faster testing)
     */
    fun generateRecommendedTestConfigs(): List<TestConfig> {
        return listOf(
            TestConfig(InitProtocol.NO_INIT, ConnectionDelay.DELAY_2000MS),
            TestConfig(InitProtocol.NO_INIT, ConnectionDelay.DELAY_500MS),
            TestConfig(InitProtocol.NO_INIT, ConnectionDelay.NONE),
            TestConfig(InitProtocol.INIT_0x0A_NO_WAIT, ConnectionDelay.DELAY_50MS),
            TestConfig(InitProtocol.INIT_0x0A_WAIT_0x0B, ConnectionDelay.DELAY_2000MS),
            TestConfig(InitProtocol.INIT_0x0A_PLUS_PRESET, ConnectionDelay.DELAY_100MS),
            TestConfig(InitProtocol.INIT_DOUBLE_0x0A, ConnectionDelay.DELAY_500MS)
        )
    }

    /**
     * Generate quick test configurations
     */
    fun generateQuickTestConfigs(): List<TestConfig> {
        return listOf(
            TestConfig(InitProtocol.NO_INIT, ConnectionDelay.DELAY_500MS),
            TestConfig(InitProtocol.INIT_0x0A_NO_WAIT, ConnectionDelay.DELAY_100MS),
            TestConfig(InitProtocol.INIT_0x0A_WAIT_0x0B, ConnectionDelay.DELAY_500MS)
        )
    }
}

/**
 * Exercise Cycle Test - Tests full workout start/wait/stop sequence
 */
enum class ExerciseCyclePhase(val displayName: String, val description: String) {
    SCAN("Scan", "Scanning for Vitruvian device"),
    CONNECT("Connect", "Establishing BLE connection"),
    INITIALIZE("Initialize", "Sending INIT command"),
    CONFIGURE("Configure", "Sending workout configuration"),
    START("Start", "Sending START command"),
    WAIT("Wait", "Holding active for 15 seconds"),
    STOP_PRIMARY("Stop (0x05)", "Sending primary STOP command"),
    STOP_OFFICIAL("Stop (0x50)", "Sending official stop packet"),
    CLEANUP("Cleanup", "Disconnecting and cleaning up")
}

/**
 * Result of an exercise cycle phase
 */
data class ExerciseCyclePhaseResult(
    val phase: ExerciseCyclePhase,
    val success: Boolean,
    val durationMs: Long,
    val commandSent: ByteArray? = null,
    val errorMessage: String? = null,
    val notes: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ExerciseCyclePhaseResult
        if (phase != other.phase) return false
        if (success != other.success) return false
        if (durationMs != other.durationMs) return false
        if (commandSent != null) {
            if (other.commandSent == null) return false
            if (!commandSent.contentEquals(other.commandSent)) return false
        } else if (other.commandSent != null) return false
        if (errorMessage != other.errorMessage) return false
        if (notes != other.notes) return false
        return true
    }

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + (commandSent?.contentHashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (notes?.hashCode() ?: 0)
        return result
    }
}
