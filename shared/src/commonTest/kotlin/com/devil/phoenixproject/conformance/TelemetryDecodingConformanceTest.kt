package com.devil.phoenixproject.conformance

import com.devil.phoenixproject.testutil.FakePeripheralFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelemetryDecodingConformanceTest {

    @Test
    fun `valid monitor telemetry decodes to expected values`() {
        val telemetry = FakePeripheralFixtures.monitorPacket(
            ticks = 123,
            posAmm = 210.5f,
            posBmm = 190.0f,
            loadAkg = 25.25f,
            loadBkg = 26.0f,
            status = 0x1234,
        )

        VendorConformanceTargets.selected().forEach { target ->
            val decoded = target.decodeTelemetry(telemetry)
            assertNotNull(decoded, "${target.id}: expected valid telemetry to decode")
            assertEquals(123, decoded.ticks)
            assertEquals(210.5f, decoded.posA)
            assertEquals(190.0f, decoded.posB)
            assertEquals(25.25f, decoded.loadA)
            assertEquals(26.0f, decoded.loadB)
            assertEquals(0x1234, decoded.status)
        }
    }

    @Test
    fun `invalid short telemetry is rejected`() {
        val invalidPacket = ByteArray(10)

        VendorConformanceTargets.selected().forEach { target ->
            assertNull(target.decodeTelemetry(invalidPacket), "${target.id}: short telemetry should be rejected")
        }
    }
}
