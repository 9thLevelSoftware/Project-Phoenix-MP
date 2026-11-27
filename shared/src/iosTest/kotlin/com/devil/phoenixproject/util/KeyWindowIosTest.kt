@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertNull

class KeyWindowIosTest {

    @Test
    fun keyWindowRootViewControllerReturnsNullWhenKeyWindowUnavailable() {
        assertNull(keyWindowRootViewController(keyWindowProvider = { null }))
    }
}
