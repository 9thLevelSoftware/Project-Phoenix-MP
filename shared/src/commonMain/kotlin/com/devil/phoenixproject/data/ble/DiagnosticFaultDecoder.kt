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

fun formatDiagnosticFaultCode(code: Int): String =
    "0x${(code and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"

fun formatDiagnosticUInt32(value: Long): String =
    "0x${(value and 0xFFFF_FFFFL).toString(16).uppercase().padStart(8, '0')}"

private fun decodeVitruvianFault(code: Int): String = when (code) {
    0 -> "None"
    1 -> "No comms"
    2 -> "Init failure"
    4 -> "TI restarted"
    8 -> "Message failure"
    16 -> "Message failure"
    32 -> "Firmware update failure"
    64 -> "Overtemp failure"
    else -> "Unknown"
}

private fun decodeOtherFault(code: Int): String = when (code) {
    0 -> "None"
    else -> "Other"
}

private fun decodeMotorFault(code: Int): String = when (code) {
    0 -> "None"
    1 -> "HW Overcurrent"
    2 -> "SW Overcurrent"
    4 -> "Over voltage"
    8 -> "Under voltage"
    16 -> "PIM temp"
    32 -> "Gate driver"
    64 -> "Bord Temp"
    128 -> "Kill switch"
    256 -> "Alignment"
    512 -> "Encoder"
    1024 -> "HW/FW mismatch"
    2048 -> "EEPROM"
    4096 -> "Motor overtemp"
    else -> "Unknown"
}
