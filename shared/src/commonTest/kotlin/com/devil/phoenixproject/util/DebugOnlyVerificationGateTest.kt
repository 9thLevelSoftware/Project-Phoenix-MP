package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the debug-only verification-seam gate.
 *
 * The release-binary rows are the release-exposure guard (issue #1164 follow-up): the
 * launch argument alone must never enable the seam in a release binary.
 */
class DebugOnlyVerificationGateTest {

    private val arg = DebugOnlyVerificationGate.ROUTINE_COMPLETE_EXIT_VERIFICATION_ARGUMENT

    private fun enabled(launchArguments: List<String>, isDebugBinary: Boolean): Boolean =
        DebugOnlyVerificationGate.routineCompleteExitVerificationEnabled(launchArguments, isDebugBinary)

    @Test
    fun debugBinaryWithVerificationArgument_enablesTheSeam() {
        assertTrue(enabled(listOf(arg), isDebugBinary = true))
        assertTrue(enabled(listOf("-AppleLanguages", "(en)", arg), isDebugBinary = true))
    }

    @Test
    fun releaseBinaryWithVerificationArgument_staysDisabled() {
        assertFalse(
            enabled(listOf(arg), isDebugBinary = false),
            "the seam must be dead in release binaries even when the launch argument is present",
        )
    }

    @Test
    fun argumentAbsent_staysDisabled() {
        assertFalse(enabled(emptyList(), isDebugBinary = true))
        assertFalse(enabled(listOf("-AppleLanguages", "(en)"), isDebugBinary = true))
    }

    @Test
    fun argumentLookalikes_stayDisabled() {
        assertFalse(enabled(listOf("-PhoenixVerify1164RoutineComplete=false"), isDebugBinary = true))
        assertFalse(enabled(listOf("PhoenixVerify1164RoutineComplete"), isDebugBinary = true))
        assertFalse(enabled(listOf(arg.lowercase()), isDebugBinary = true))
    }

    @Test
    fun releaseBinaryWithoutArgument_staysDisabled() {
        assertFalse(enabled(emptyList(), isDebugBinary = false))
    }
}
