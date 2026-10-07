package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wiring contract for the issue #1164 iOS routine-complete exit-capture seam.
 *
 * The seam must be DEBUG-ONLY (follow-up review finding: a launch-argument host in iosMain
 * that seeds coordinator state is production/release exposure unless it is guarded). This
 * test pins, at the source level, that:
 *
 *  1. the launch check routes through [com.devil.phoenixproject.util.DebugOnlyVerificationGate]
 *     and passes `kotlin.native.Platform.isDebugBinary` (so release binaries can never
 *     activate the seam — see DebugOnlyVerificationGateTest for the predicate truth table), and
 *  2. [IosAppHost] activates the verification host ONLY through
 *     `isRoutineCompleteExitVerificationLaunch()` — never unconditionally, and never via a
 *     second, unguarded launch-argument check.
 */
class IosVerificationHostDebugOnlyContractTest {

    @Test
    fun launchCheck_requiresTheDebugBinarySignalAndTheGate() {
        val src = stripComments(
            requireNotNull(
                readProjectFile("shared/src/iosMain/kotlin/com/devil/phoenixproject/IosRoutineCompleteExitVerification.kt")
                    ?: readProjectFile("../shared/src/iosMain/kotlin/com/devil/phoenixproject/IosRoutineCompleteExitVerification.kt"),
            ) { "IosRoutineCompleteExitVerification.kt must be readable from tests" },
        )

        assertTrue(
            src.contains("DebugOnlyVerificationGate.routineCompleteExitVerificationEnabled"),
            "the verification launch check must route through DebugOnlyVerificationGate " +
                "(single source of truth for the debug-only activation policy)",
        )
        assertTrue(
            src.contains("kotlin.native.Platform.isDebugBinary"),
            "the verification launch check must consult kotlin.native.Platform.isDebugBinary so " +
                "the seam is dead in release binaries (issue #1164 release-exposure follow-up)",
        )
        assertTrue(
            Regex("""routineCompleteExitVerificationEnabled\(\s*launchArguments = launchArguments,\s*isDebugBinary = isDebugBinary,""")
                .containsMatchIn(src),
            "both gate signals (launch arguments and debug-binary state) must be passed to the gate",
        )

        // No raw, unguarded argument check may bypass the gate.
        val rawArgumentChecks = Regex(
            """NSProcessInfo\.processInfo\.arguments\.(contains|any|first|indexOf)\(""",
        ).findAll(src).count()
        assertTrue(
            rawArgumentChecks == 0,
            "launch-argument handling must go through DebugOnlyVerificationGate, not a raw " +
                "NSProcessInfo.arguments check (found $rawArgumentChecks raw checks)",
        )
    }

    @Test
    fun appHost_activatesTheSeamOnlyThroughTheLaunchGate() {
        val src = stripComments(
            requireNotNull(
                readProjectFile("shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt")
                    ?: readProjectFile("../shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt"),
            ) { "IosAppHost.kt must be readable from tests" },
        )

        val gateCalls = Regex("""isRoutineCompleteExitVerificationLaunch\(\)""").findAll(src).count()
        assertTrue(
            gateCalls == 1,
            "IosAppHost must consult isRoutineCompleteExitVerificationLaunch() exactly once " +
                "(found $gateCalls call sites)",
        )
        assertTrue(
            src.contains("IosRoutineCompleteExitVerificationHost("),
            "IosAppHost must keep the issue #1164 verification host wiring (debug-gated)",
        )
        assertTrue(
            !src.contains("NSProcessInfo"),
            "IosAppHost must not duplicate launch-argument handling; the debug-only gate owns it",
        )

        // The host call must sit inside the gate's true-branch, not run unconditionally.
        val gateIndex = src.indexOf("isRoutineCompleteExitVerificationLaunch()")
        val hostIndex = src.indexOf("IosRoutineCompleteExitVerificationHost(")
        assertTrue(
            hostIndex > gateIndex,
            "the verification host must be activated after (inside) the launch gate check",
        )
    }

    private fun stripComments(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("""//[^\n]*"""), " ")
}
