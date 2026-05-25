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

private fun decodeVitruvianFault(code: Int): String {
    if (code == 0) return "None"

    val faults = mutableListOf<String>()
    if ((code and 1) != 0) faults.add("No comms")
    if ((code and 2) != 0) faults.add("Init failure")
    if ((code and 4) != 0) faults.add("TI restarted")
    if ((code and 8) != 0 || (code and 16) != 0) faults.add("Message failure")
    if ((code and 32) != 0) faults.add("Firmware update failure")
    if ((code and 64) != 0) faults.add("Overtemp failure")

    return faults.joinToString(", ").ifEmpty { "Unknown" }
}

private fun decodeOtherFault(code: Int): String = when (code) {
    0 -> "None"
    else -> "Other"
}

private fun decodeMotorFault(code: Int): String {
    if (code == 0) return "None"

    val faults = mutableListOf<String>()
    if ((code and 1) != 0) faults.add("HW Overcurrent")
    if ((code and 2) != 0) faults.add("SW Overcurrent")
    if ((code and 4) != 0) faults.add("Over voltage")
    if ((code and 8) != 0) faults.add("Under voltage")
    if ((code and 16) != 0) faults.add("PIM temp")
    if ((code and 32) != 0) faults.add("Gate driver")
    if ((code and 64) != 0) faults.add("Bord Temp")
    if ((code and 128) != 0) faults.add("Kill switch")
    if ((code and 256) != 0) faults.add("Alignment")
    if ((code and 512) != 0) faults.add("Encoder")
    if ((code and 1024) != 0) faults.add("HW/FW mismatch")
    if ((code and 2048) != 0) faults.add("EEPROM")
    if ((code and 4096) != 0) faults.add("Motor overtemp")

    return faults.joinToString(", ").ifEmpty { "Unknown" }
}
