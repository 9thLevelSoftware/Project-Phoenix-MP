package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.ble.getUInt16BE as parseUInt16BE
import com.devil.phoenixproject.data.ble.parseDiagnosticPacket as parseDiagnostic
import com.devil.phoenixproject.data.ble.parseMonitorPacket as parseMonitor
import com.devil.phoenixproject.data.ble.parseRepPacket as parseReps
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.RGBColor

/** Encodes outbound protocol commands for Vitruvian BLE devices. */
interface CommandEncoder {
    fun createInitCommand(): ByteArray
    fun createStartCommand(): ByteArray
    fun createStopCommand(): ByteArray
    fun createOfficialStopPacket(): ByteArray
    fun createResetCommand(): ByteArray
    fun createWorkoutCommand(programMode: ProgramMode, weightPerCableKg: Float, targetReps: Int): ByteArray
    fun createProgramParams(params: WorkoutParameters): ByteArray
    fun createEchoCommand(level: Int, eccentricLoad: Int): ByteArray

    fun createEchoControl(
        level: EchoLevel,
        warmupReps: Int = 3,
        targetReps: Int = 2,
        isJustLift: Boolean = false,
        isAMRAP: Boolean = false,
        eccentricPct: Int = 75
    ): ByteArray

    fun createColorScheme(brightness: Float, colors: List<RGBColor>): ByteArray
    fun createColorSchemeCommand(schemeIndex: Int): ByteArray
}

/** Decodes inbound protocol telemetry streams for Vitruvian BLE devices. */
interface TelemetryDecoder {
    fun getUInt16BE(data: ByteArray, offset: Int): Int
    fun parseRepPacket(data: ByteArray, hasOpcodePrefix: Boolean, timestamp: Long): RepNotification?
    fun parseMonitorPacket(data: ByteArray): MonitorPacket?
    fun parseDiagnosticPacket(data: ByteArray): DiagnosticPacket?
}

/** Describes supported command + telemetry capabilities for a protocol adapter pair. */
interface ProtocolCapabilityDescriptor {
    val supportsInitCommands: Boolean
    val supportsStartStopCommands: Boolean
    val supportsConfigurationCommands: Boolean
    val supportsMonitorStream: Boolean
    val supportsRepStream: Boolean
    val supportsDiagnosticStream: Boolean
}

class VitruvianCommandEncoderAdapter : CommandEncoder {
    override fun createInitCommand(): ByteArray = BlePacketFactory.createInitCommand()
    override fun createStartCommand(): ByteArray = BlePacketFactory.createStartCommand()
    override fun createStopCommand(): ByteArray = BlePacketFactory.createStopCommand()
    override fun createOfficialStopPacket(): ByteArray = BlePacketFactory.createOfficialStopPacket()
    override fun createResetCommand(): ByteArray = BlePacketFactory.createResetCommand()
    override fun createWorkoutCommand(programMode: ProgramMode, weightPerCableKg: Float, targetReps: Int): ByteArray =
        BlePacketFactory.createWorkoutCommand(programMode, weightPerCableKg, targetReps)

    override fun createProgramParams(params: WorkoutParameters): ByteArray = BlePacketFactory.createProgramParams(params)
    override fun createEchoCommand(level: Int, eccentricLoad: Int): ByteArray = BlePacketFactory.createEchoCommand(level, eccentricLoad)

    override fun createEchoControl(
        level: EchoLevel,
        warmupReps: Int,
        targetReps: Int,
        isJustLift: Boolean,
        isAMRAP: Boolean,
        eccentricPct: Int
    ): ByteArray = BlePacketFactory.createEchoControl(level, warmupReps, targetReps, isJustLift, isAMRAP, eccentricPct)

    override fun createColorScheme(brightness: Float, colors: List<RGBColor>): ByteArray =
        BlePacketFactory.createColorScheme(brightness, colors)

    override fun createColorSchemeCommand(schemeIndex: Int): ByteArray = BlePacketFactory.createColorSchemeCommand(schemeIndex)
}

class VitruvianTelemetryDecoderAdapter : TelemetryDecoder {
    override fun getUInt16BE(data: ByteArray, offset: Int): Int = parseUInt16BE(data, offset)

    override fun parseRepPacket(data: ByteArray, hasOpcodePrefix: Boolean, timestamp: Long): RepNotification? =
        parseReps(data, hasOpcodePrefix, timestamp)

    override fun parseMonitorPacket(data: ByteArray): MonitorPacket? = parseMonitor(data)

    override fun parseDiagnosticPacket(data: ByteArray): DiagnosticPacket? = parseDiagnostic(data)
}

object VitruvianProtocolCapabilityDescriptor : ProtocolCapabilityDescriptor {
    override val supportsInitCommands: Boolean = true
    override val supportsStartStopCommands: Boolean = true
    override val supportsConfigurationCommands: Boolean = true
    override val supportsMonitorStream: Boolean = true
    override val supportsRepStream: Boolean = true
    override val supportsDiagnosticStream: Boolean = true
}
