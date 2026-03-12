package com.devil.phoenixproject.conformance

import com.devil.phoenixproject.data.ble.MonitorPacket
import com.devil.phoenixproject.testutil.FakePeripheralFixtures

interface VendorConformanceTarget {
    val id: String

    fun encodeStartCommand(): ByteArray
    fun encodeStopCommand(): ByteArray
    fun encodeActivationCommand(): ByteArray
    fun decodeTelemetry(packet: ByteArray): MonitorPacket?
}

private class PhoenixVendorTarget : VendorConformanceTarget {
    override val id: String = "phoenix"

    override fun encodeStartCommand(): ByteArray = FakePeripheralFixtures.startCommandPacket()
    override fun encodeStopCommand(): ByteArray = FakePeripheralFixtures.stopCommandPacket()
    override fun encodeActivationCommand(): ByteArray = FakePeripheralFixtures.activationPacket()
    override fun decodeTelemetry(packet: ByteArray): MonitorPacket? = FakePeripheralFixtures.decodeMonitorPayload(packet)
}

/**
 * Demo vendor target currently mirrors Phoenix packet semantics and serves as
 * a template for new adapter plugins to satisfy the shared conformance suite.
 */
private class DemoVendorTarget : VendorConformanceTarget {
    override val id: String = "demo"

    override fun encodeStartCommand(): ByteArray = FakePeripheralFixtures.startCommandPacket()
    override fun encodeStopCommand(): ByteArray = FakePeripheralFixtures.stopCommandPacket()
    override fun encodeActivationCommand(): ByteArray = FakePeripheralFixtures.activationPacket()
    override fun decodeTelemetry(packet: ByteArray): MonitorPacket? = FakePeripheralFixtures.decodeMonitorPayload(packet)
}

object VendorConformanceTargets {
    val all: List<VendorConformanceTarget> = listOf(
        PhoenixVendorTarget(),
        DemoVendorTarget(),
    )

    fun selected(): List<VendorConformanceTarget> {
        val requested = System.getenv("VENDOR_ADAPTER")?.trim()?.lowercase().orEmpty()
        if (requested.isBlank()) return all
        return all.filter { it.id == requested }
    }
}
