package com.devil.phoenixproject.fixture

import com.devil.phoenixproject.util.Constants
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SimulatorLaunchFixtureTest {
    @Test
    fun `resolver accepts only allowlisted fixture ids`() {
        assertEquals("clean-eula", SimulatorLaunchFixture.resolve("clean-eula")?.id)
        assertEquals("just-lift-connected", SimulatorLaunchFixture.resolve("just-lift-connected")?.id)
        assertNull(SimulatorLaunchFixture.resolve(null))
        assertFailsWith<IllegalArgumentException> {
            SimulatorLaunchFixture.resolve("")
        }
    }

    @Test
    fun `resolver rejects unknown fixture ids deterministically`() {
        assertFailsWith<IllegalArgumentException> {
            SimulatorLaunchFixture.resolve("not-a-fixture")
        }
    }

    @Test
    fun `clean eula fixture removes only eula state`() {
        val settings = MapSettings().apply {
            putInt("eula_accepted_version", Constants.EULA_VERSION)
            putLong("eula_accepted_timestamp", 123L)
            putString("unrelated_setting", "preserve")
        }

        SimulatorLaunchFixture.resolve("clean-eula")!!.seed(settings)

        assertNull(settings.getIntOrNull("eula_accepted_version"))
        assertNull(settings.getLongOrNull("eula_accepted_timestamp"))
        assertEquals("preserve", settings.getStringOrNull("unrelated_setting"))
    }

    @Test
    fun `just lift connected fixture seeds accepted eula with deterministic timestamp`() {
        val settings = MapSettings()

        SimulatorLaunchFixture.resolve("just-lift-connected")!!.seed(settings)

        assertEquals(Constants.EULA_VERSION, settings.getIntOrNull("eula_accepted_version"))
        assertEquals(1_704_067_200_000L, settings.getLongOrNull("eula_accepted_timestamp"))
    }
}
