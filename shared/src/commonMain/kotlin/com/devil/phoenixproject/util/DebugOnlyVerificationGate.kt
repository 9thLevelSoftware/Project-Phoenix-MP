package com.devil.phoenixproject.util

/**
 * Debug-only gate for development verification seams (currently the issue #1164 iOS
 * routine-complete exit-capture host).
 *
 * A verification seam that reacts to a launch argument is otherwise reachable in RELEASE
 * builds by anyone who can set process arguments (TestFlight tooling, `simctl launch`,
 * MDM profiles, ...). The gate therefore requires BOTH conditions:
 *
 *  1. the explicit verification launch argument, and
 *  2. a debug binary — Kotlin/Native `Platform.isDebugBinary`, baked in at link time
 *     (`linkDebugFramework*` produces a debug binary, `linkReleaseFramework*` a release one).
 *
 * Store/TestFlight builds link the release framework (`iosApp/install-xcode-framework.sh
 * release`), so there the seam is dead no matter which launch arguments are passed. Local
 * verification builds link the debug framework and keep the documented exit-capture
 * workflow reproducible.
 *
 * The pure predicate lives in commonMain so the CI host-test suite can pin the full truth
 * table (see `DebugOnlyVerificationGateTest`), and a source contract test
 * (IosVerificationHostDebugOnlyContractTest) pins that the iOS wiring passes BOTH signals.
 */
object DebugOnlyVerificationGate {
    /** Launch argument requesting the issue #1164 routine-complete exit-capture host. */
    const val ROUTINE_COMPLETE_EXIT_VERIFICATION_ARGUMENT: String = "-PhoenixVerify1164RoutineComplete"

    fun routineCompleteExitVerificationEnabled(
        launchArguments: List<String>,
        isDebugBinary: Boolean,
    ): Boolean = isDebugBinary && ROUTINE_COMPLETE_EXIT_VERIFICATION_ARGUMENT in launchArguments
}
