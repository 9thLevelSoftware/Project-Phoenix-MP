@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

class SecurityScopedAccessIosTest {

    @Test
    fun withSecurityScopedAccessReturnsBlockValueAndPropagatesExceptions() {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory())

        val result = url.withSecurityScopedAccess { "scoped-ok" }
        assertEquals("scoped-ok", result)

        val thrown = assertFailsWith<IllegalStateException> {
            url.withSecurityScopedAccess {
                throw IllegalStateException("block error")
            }
        }
        assertEquals("block error", thrown.message)
    }
}
