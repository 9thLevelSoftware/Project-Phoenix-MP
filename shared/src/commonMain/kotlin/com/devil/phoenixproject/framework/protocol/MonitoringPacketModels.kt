package com.devil.phoenixproject.framework.protocol

/** Raw parsed monitor data before validation/processing. */
data class MonitorPacket(
    val ticks: Int,
    val posA: Float,
    val posB: Float,
    val loadA: Float,
    val loadB: Float,
    val status: Int,
    val firmwareVelA: Int = 0,
    val firmwareVelB: Int = 0,
    val extraBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is MonitorPacket && ticks == other.ticks && posA == other.posA &&
            posB == other.posB && loadA == other.loadA && loadB == other.loadB &&
            status == other.status && firmwareVelA == other.firmwareVelA &&
            firmwareVelB == other.firmwareVelB

    override fun hashCode(): Int = ticks.hashCode()
}

/** Raw parsed diagnostic packet. */
data class DiagnosticPacket(
    val seconds: Int,
    val faults: List<Short>,
    val temps: List<Byte>,
    val hasFaults: Boolean
)
