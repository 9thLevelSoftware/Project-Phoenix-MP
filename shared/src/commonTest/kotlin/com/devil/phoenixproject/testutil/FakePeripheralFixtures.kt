package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.ble.MonitorPacket
import com.devil.phoenixproject.data.ble.parseMonitorPacket
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BlePacketFactory

/**
 * Shared fake-peripheral fixtures for vendor adapter tests.
 *
 * Built from the simulator's connection/workout behavior and existing BLE packet tests.
 */
object FakePeripheralFixtures {
    val defaultWorkoutParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 10,
        weightPerCableKg = 20f,
    )

    fun monitorPacket(
        ticks: Int = 42,
        posAmm: Float = 123.4f,
        posBmm: Float = 121.0f,
        loadAkg: Float = 17.5f,
        loadBkg: Float = 18.0f,
        status: Int = 0x0102,
    ): ByteArray {
        val payload = ByteArray(18)

        putInt32Le(payload, 0, ticks)
        putInt16Le(payload, 4, (posAmm * 10f).toInt())
        putInt16Le(payload, 8, (posBmm * 10f).toInt())
        putInt16Le(payload, 10, (loadAkg * 100f).toInt())
        putInt16Le(payload, 14, (loadBkg * 100f).toInt())
        payload[16] = (status and 0xFF).toByte()
        payload[17] = ((status shr 8) and 0xFF).toByte()

        return payload
    }

    fun decodeMonitorPayload(packet: ByteArray): MonitorPacket? = parseMonitorPacket(packet)

    fun startCommandPacket(): ByteArray = BlePacketFactory.createStartCommand()
    fun stopCommandPacket(): ByteArray = BlePacketFactory.createStopCommand()
    fun activationPacket(params: WorkoutParameters = defaultWorkoutParameters): ByteArray =
        BlePacketFactory.createProgramParams(params)

    private fun putInt16Le(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putInt32Le(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
