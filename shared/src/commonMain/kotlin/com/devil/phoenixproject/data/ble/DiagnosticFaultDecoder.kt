package com.devil.phoenixproject.data.ble

enum class DiagnosticFaultCategory(val displayName: String) {
    VITRUVIAN("Vee"),
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
        decodeDiagnosticFault(DiagnosticFaultCategory.VITRUVIAN, words.getOrElse(0) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, words.getOrElse(1) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_A, words.getOrElse(2) { 0 }),
        decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_B, words.getOrElse(3) { 0 }),
    )
}

fun decodeDiagnosticFault(category: DiagnosticFaultCategory, code: Int): DiagnosticFault {
    val normalizedCode = code and 0xFFFF
    val label = when (category) {
        DiagnosticFaultCategory.VITRUVIAN -> decodeVitruvianFault(normalizedCode)

        DiagnosticFaultCategory.OTHER -> decodeOtherFault(normalizedCode)

        DiagnosticFaultCategory.MOTOR_A,
        DiagnosticFaultCategory.MOTOR_B,
        -> decodeMotorFault(normalizedCode)
    }
    return DiagnosticFault(category = category, code = normalizedCode, label = label)
}

fun formatDiagnosticFaultCode(code: Int): String = "0x${(code and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"

fun formatDiagnosticUInt32(value: Long): String = "0x${(value and 0xFFFF_FFFFL).toString(16).uppercase().padStart(8, '0')}"

private fun decodeVitruvianFault(code: Int): String = decodeFlaggedFault(
    code = code,
    flags = listOf(
        1 to "No comms",
        2 to "Init failure",
        4 to "TI restarted",
        8 to "Message failure",
        16 to "Message failure",
        32 to "Firmware update failure",
        64 to "Overtemp failure",
    ),
)

private fun decodeOtherFault(code: Int): String = when (code) {
    0 -> "None"
    else -> "Other"
}

private fun decodeMotorFault(code: Int): String = decodeFlaggedFault(
    code = code,
    flags = listOf(
        1 to "HW Overcurrent",
        2 to "SW Overcurrent",
        4 to "Over voltage",
        8 to "Under voltage",
        16 to "PIM temp",
        32 to "Gate driver",
        64 to "Bord Temp",
        128 to "Kill switch",
        256 to "Alignment",
        512 to "Encoder",
        1024 to "HW/FW mismatch",
        2048 to "EEPROM",
        4096 to "Motor overtemp",
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
