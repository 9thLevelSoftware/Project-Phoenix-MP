package com.devil.phoenixproject.data.ble

enum class DiagnosticFaultCategory(val displayName: String) {
    CONTROLLER("Controller"),
    OTHER("Other"),
    MOTOR_A("Motor A"),
    MOTOR_B("Motor B"),
}

data class DiagnosticFault(
    val category: DiagnosticFaultCategory,
    val code: Int,
    val label: String,
    val rawHex: String = formatDiagnosticFaultCode(code),
) {
    val hasFault: Boolean get() = code != 0
}

fun decodeDiagnosticFaults(packet: DiagnosticPacket): List<DiagnosticFault> {
    val words = packet.faultWords
    return listOf(
        decodeDiagnosticFault(DiagnosticFaultCategory.CONTROLLER, words.getOrElse(0) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, words.getOrElse(1) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_A, words.getOrElse(2) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_B, words.getOrElse(3) { 0 }),
    )
}

fun decodeDiagnosticFault(category: DiagnosticFaultCategory, code: Int): DiagnosticFault {
    val normalizedCode = code and 0xFFFF
    val label = when (category) {
        DiagnosticFaultCategory.CONTROLLER -> decodeControllerFault(normalizedCode)

        DiagnosticFaultCategory.OTHER -> decodeOtherFault(normalizedCode)

        DiagnosticFaultCategory.MOTOR_A,
        DiagnosticFaultCategory.MOTOR_B,
        -> decodeMotorFault(normalizedCode)
    }
    return DiagnosticFault(category = category, code = normalizedCode, label = label)
}

fun formatDiagnosticFaultCode(code: Int): String = "0x${(code and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"

fun formatDiagnosticUInt32(value: Long): String = "0x${(value and 0xFFFF_FFFFL).toString(16).uppercase().padStart(8, '0')}"

private fun decodeControllerFault(code: Int): String = decodeFlaggedFault(
    code = code,
    flags = listOf(
        1 to "Communication lost",
        2 to "Initialisation failure",
        4 to "Controller restarted",
        8 to "RX message failure",
        16 to "TX message failure",
        32 to "Firmware update failure",
        64 to "Over-temperature",
    ),
)

private fun decodeOtherFault(code: Int): String = when (code) {
    0 -> "None"
    else -> "Other"
}

private fun decodeMotorFault(code: Int): String = decodeFlaggedFault(
    code = code,
    flags = listOf(
        1 to "Hardware overcurrent",
        2 to "Software overcurrent",
        4 to "Overvoltage",
        8 to "Undervoltage",
        16 to "Power module temperature",
        32 to "Gate driver fault",
        64 to "Board temperature",
        128 to "Kill switch",
        256 to "Alignment fault",
        512 to "Encoder fault",
        1024 to "Hardware/firmware mismatch",
        2048 to "EEPROM fault",
        4096 to "Motor over-temperature",
    ),
)

private fun decodeFlaggedFault(code: Int, flags: List<Pair<Int, String>>): String {
    if (code == 0) return "None"

    val activeLabels = flags
        .filter { (mask, _) -> code and mask != 0 }
        .map { (_, label) -> label }
        .distinct()

    return activeLabels.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown"
}
