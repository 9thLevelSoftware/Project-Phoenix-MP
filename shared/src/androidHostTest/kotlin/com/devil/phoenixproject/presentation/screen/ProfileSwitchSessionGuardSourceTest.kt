package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GitHub #854 (codex 4080108011) source guard. The profile switcher must be gated on
 * the SESSION-scoped `isInWorkoutSession`, not `workoutState`: the latter is Idle on
 * SetReady between routine sets and during the Just Lift rest countdown, which would
 * let a switch split one workout across two profiles.
 */
class ProfileSwitchSessionGuardSourceTest {
    private fun read(path: String): String {
        val relative = "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/$path"
        return requireNotNull(readProjectFile(relative)) { "Missing $relative" }
    }

    private val source: String by lazy { read("screen/EnhancedMainScreen.kt") }

    @Test
    fun bothSwitchEntryPointsPassTheSessionScopedSignal() {
        assertTrue(
            source.contains("switchProfile(profile.id, viewModel::isInWorkoutSessionNow)"),
            "switchProfile must be gated on isInWorkoutSession",
        )
        assertTrue(
            source.contains("createAndActivateProfile(name, colorIndex, viewModel::isInWorkoutSessionNow)"),
            "createAndActivateProfile must be gated on isInWorkoutSession",
        )
    }

    @Test
    fun neitherSwitchEntryPointIsGatedOnWorkoutStateAlone() {
        assertFalse(Regex("""switchProfile\([^)]*workoutState""").containsMatchIn(source))
        assertFalse(Regex("""createAndActivateProfile\([^)]*workoutState""").containsMatchIn(source))
    }

    /** GitHub #854 (codex 4082092571): active-profile deletion gets the same live guard. */
    @Test
    fun theActiveProfileDeleteIsGatedOnTheLiveSessionSignal() {
        assertTrue(read("navigation/NavGraph.kt").contains("isInWorkoutSession = viewModel::isInWorkoutSessionNow"))
        assertTrue(read("screen/ProfileScreen.kt").contains("viewModel.deleteActiveProfile(isInWorkoutSession)"))
    }
}
