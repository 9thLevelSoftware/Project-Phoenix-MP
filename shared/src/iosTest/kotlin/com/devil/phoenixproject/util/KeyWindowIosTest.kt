@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyWindowIosTest {

    @Test
    fun keyWindowRootViewControllerMatchesKeyWindowRoot() {
        val window = keyWindow()
        assertEquals(window?.rootViewController, keyWindowRootViewController())
    }
}
