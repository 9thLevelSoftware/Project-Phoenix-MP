package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DiscoModeTest {

    @Test
    fun `shutdown cancels active disco job without restore`() = runTest {
        val discoMode = DiscoMode(scope = this, sendCommand = {})

        discoMode.start()
        runCurrent()

        assertTrue(discoMode.isActive.value)
        assertTrue(discoMode.isJobActiveForTest())

        discoMode.shutdown()
        runCurrent()

        assertFalse(discoMode.isActive.value)
        assertFalse(discoMode.isJobActiveForTest())
    }
}
