package com.devil.phoenixproject.presentation.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.domain.voice.SafeWordListener
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordState
import com.devil.phoenixproject.presentation.navigation.JustLiftExitContext
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.navigation.exitToJustLift
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1366 regression: STOP -> Exit from a Just Lift session must settle on
 * JustLiftScreen with the selected mode retained and must never land Home.
 *
 * The failure mode is competing relative pops: the non-routine Exit dialog confirm
 * performs an unguarded `navigateUp()` while the Just-Lift Idle observer performs
 * another one after `stopWorkout(exitingWorkout = true)` synchronously publishes
 * `WorkoutState.Idle` — the documented Issue #204 double-navigateUp failure mode
 * over-popping [Home, JustLift, ActiveWorkout] down to Home.
 *
 * Coverage model (RCA acceptance criteria): real STOP -> Exit through the production
 * UI (HUD STOP button + Exit dialog) into the REAL engine (real execution lease and
 * synchronous Idle publication), over the PRODUCTION [NavGraph] route table with a
 * real NavController and Compose runtime. Back-stack entries and destinations are
 * captured through a settled observation window, so this is UI-to-engine coverage,
 * not a policy or source-string test. Deliberately separate from — and complementary
 * to — the source-level Issue660 family.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Issue1366JustLiftExitNavigationTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    /** Content is set through this tester so any test can emulate state restoration. */
    private val restorationTester = StateRestorationTester(rule)

    private var fixture: RoutineCompleteRuntimeFixture? = null
    private var navController: NavHostController? = null

    /** Every destination that became current, in order (bottom -> top evidence trail). */
    private val visitedRoutes = mutableListOf<String>()

    /** One-shot guard for the initial stack navigation across composition recreation. */
    private var initialNavigationDone = false

    @Before
    fun setUpKoin() {
        startKoin {
            modules(
                module {
                    // Same fakes the #1164 full-shell harness wires for the screens
                    // rendered along this flow (Home, JustLift, ActiveWorkout).
                    single<UserProfileRepository> { fixture!!.fakeUserProfileRepository }
                    single<TrainingCycleRepository> { fixture!!.fakeTrainingCycleRepository }
                    single<WorkoutRepository> { fixture!!.fakeWorkoutRepository }
                    single<ExerciseRepository> { fixture!!.fakeExerciseRepository }
                    single<PersonalRecordRepository> { fixture!!.fakePersonalRecordRepository }
                    single<ProfileExerciseBaselineRepository> { fixture!!.fakeBaselineRepository }
                    single<GamificationRepository> { fixture!!.fakeGamificationRepository }
                    single { com.devil.phoenixproject.data.sync.PortalTokenStorage(com.russhwolf.settings.MapSettings()) }
                    single { RoutineTimeEstimator(get()) }
                    single { TemplateConverter(get(), get()) }
                    viewModel { RoutineCsvViewModel(get(), get(), get()) }
                    // ActiveWorkoutScreen resolves these through Koin in production.
                    single<com.devil.phoenixproject.data.ble.PolarH10BleManager> {
                        com.devil.phoenixproject.data.ble.PolarH10BleManager()
                    }
                    single<com.devil.phoenixproject.domain.voice.SafeWordDetectionManager> {
                        com.devil.phoenixproject.domain.voice.SafeWordDetectionManager(
                            fixture!!.fakeUserProfileRepository,
                            UnavailableSafeWordListenerFactory,
                        )
                    }
                },
            )
        }
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        stopKoin()
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stop exit settles on JustLift with mode retained and never visits Home`() {
        val fx = driveJustLiftActiveSession(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                weightPerCableKg = 23.5f,
                warmupReps = 3,
                isJustLift = true,
            ),
        )

        // Precondition: production stack [Home, JustLift, ActiveWorkout] and a real lease.
        assertEquals(NavigationRoutes.ActiveWorkout.route, currentRoute())
        assertEquals("home -> just_lift -> active_workout", backStack())
        assertNotNull(
            "Precondition: the Just Lift session must hold a real execution lease",
            fx.viewModel.workoutSessionManager.activeSessionEngine.currentExecutionLeaseOrNull(),
        )

        val visitedAtExitClick = visitedRoutes.size
        rule.onNodeWithText("STOP").assertIsDisplayed().performClick()
        rule.onNodeWithText("Exit Workout?").assertIsDisplayed()
        rule.onNodeWithText("Exit").performClick()
        settle()

        val visitedAfterExit = visitedRoutes.drop(visitedAtExitClick)
        // Contract: the exit lands on JustLiftScreen with the selected mode retained,
        // and Home is never the exit destination.
        assertEquals(
            "STOP -> Exit must settle on JustLift, not Home (visited after Exit tap: $visitedAfterExit)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertEquals("home -> just_lift", backStack())
        assertTrue(
            "The exit must never navigate to Home (visited after Exit tap: $visitedAfterExit)",
            visitedAfterExit.none { it == NavigationRoutes.Home.route },
        )
        assertEquals(
            "Exactly one effective exit transition (visited after Exit tap: $visitedAfterExit)",
            listOf(NavigationRoutes.JustLift.route),
            visitedAfterExit,
        )

        // Mode/settings retention: state values AND the rendered JustLift selection
        // (JustLift reloads saved defaults on composition; the route alone proves nothing).
        val retained = fx.viewModel.workoutParameters.value
        assertEquals(ProgramMode.OldSchool, retained.programMode)
        assertEquals(23.5f, retained.weightPerCableKg)
        rule.onNodeWithText("Old School").assertIsSelected()

        RoutineCompleteRuntimeEvidence.record(
            "justLiftExit_stopExit",
            mapOf(
                "graph" to "production NavGraph",
                "destination" to currentRoute(),
                "backStackAfter" to backStack(),
                "visitedAfterExit" to visitedAfterExit,
                "modeRetained" to retained.programMode,
                "weightPerCableKgRetained" to retained.weightPerCableKg,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stop exit retains Echo level, eccentric load and per-cable weight across repeated cycles`() {
        val fx = driveJustLiftActiveSession(
            WorkoutParameters(
                programMode = ProgramMode.Echo,
                reps = 10,
                weightPerCableKg = 31.5f,
                warmupReps = 3,
                isJustLift = true,
                echoLevel = EchoLevel.EPIC,
                eccentricLoad = EccentricLoad.LOAD_120,
            ),
        )

        // Cycle 1: STOP -> Exit.
        val visitedAtExitClick1 = visitedRoutes.size
        exitViaStopDialog()
        settle()
        val visitedAfterExit1 = visitedRoutes.drop(visitedAtExitClick1)
        assertEquals(
            "Cycle 1 must land JustLift (visited: $visitedAfterExit1)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertEquals("home -> just_lift", backStack())
        assertTrue(
            "Cycle 1 must never navigate to Home (visited: $visitedAfterExit1)",
            visitedAfterExit1.none { it == NavigationRoutes.Home.route },
        )
        assertEchoSettingsRetained(fx)

        // Cycle 2: a complete new start/stop cycle. The production JustLift entry
        // producer pushes ActiveWorkout when the new session becomes Active.
        fx.viewModel.startWorkout(skipCountdown = true)
        rule.waitUntil(TIMEOUT_MS) {
            navController?.currentBackStackEntry?.destination?.route == NavigationRoutes.ActiveWorkout.route
        }
        rule.waitUntil(TIMEOUT_MS) {
            fx.viewModel.workoutState.value is WorkoutState.Active
        }
        assertEquals("home -> just_lift -> active_workout", backStack())

        val visitedAtExitClick2 = visitedRoutes.size
        exitViaStopDialog()
        settle()
        val visitedAfterExit2 = visitedRoutes.drop(visitedAtExitClick2)
        assertEquals(
            "Cycle 2 must land JustLift (visited: $visitedAfterExit2)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertEquals("home -> just_lift", backStack())
        assertTrue(
            "Cycle 2 must never navigate to Home (visited: $visitedAfterExit2)",
            visitedAfterExit2.none { it == NavigationRoutes.Home.route },
        )
        assertEquals(
            "Cycle 2 must perform exactly one effective transition (visited: $visitedAfterExit2)",
            listOf(NavigationRoutes.JustLift.route),
            visitedAfterExit2,
        )
        assertEchoSettingsRetained(fx)

        RoutineCompleteRuntimeEvidence.record(
            "justLiftExit_echoRepeatedCycles",
            mapOf(
                "destination" to currentRoute(),
                "backStackAfter" to backStack(),
                "echoLevel" to fx.viewModel.workoutParameters.value.echoLevel,
                "eccentricLoad" to fx.viewModel.workoutParameters.value.eccentricLoad,
                "weightPerCableKg" to fx.viewModel.workoutParameters.value.weightPerCableKg,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stop dialog cancel keeps the active lift screen and session`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())

        rule.onNodeWithText("STOP").assertIsDisplayed().performClick()
        rule.onNodeWithText("Exit Workout?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        settle()

        assertEquals(
            "Cancel must keep the active lift screen",
            NavigationRoutes.ActiveWorkout.route,
            currentRoute(),
        )
        assertTrue(
            "Cancel must not initiate exit teardown",
            fx.viewModel.workoutState.value is WorkoutState.Active,
        )
        assertNotNull(
            "Cancel must leave the session lease current",
            fx.viewModel.workoutSessionManager.activeSessionEngine.currentExecutionLeaseOrNull(),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stop exit tolerates duplicate ActiveWorkout entries`() {
        // Deliberate duplicate entries below the composed one: the exit must target the
        // JustLift destination and remove ALL ActiveWorkout entries above it in exactly
        // one transition (the RCA's duplicate-entry tolerance requirement).
        driveJustLiftActiveSession(justLiftParameters(), extraActiveWorkoutEntries = 3)
        assertEquals(
            "home -> just_lift -> active_workout -> active_workout -> active_workout",
            backStack(),
        )

        val visitedAtExitClick = visitedRoutes.size
        exitViaStopDialog()
        settle()
        val visitedAfterExit = visitedRoutes.drop(visitedAtExitClick)

        assertEquals(
            "Duplicate ActiveWorkout entries must all be removed; exactly one JustLift entry remains",
            "home -> just_lift",
            backStack(),
        )
        assertEquals(
            "Duplicate-entry exit must still be one effective transition to JustLift (visited: $visitedAfterExit)",
            listOf(NavigationRoutes.JustLift.route),
            visitedAfterExit,
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stop exit with absent JustLift entry falls back preserving Home without duplicates`() {
        // Unusual entry path: ActiveWorkout reached without a JustLift entry under it.
        // The guarded fallback must preserve Home as the bottom entry and must not
        // create duplicate JustLift entries.
        driveJustLiftActiveSession(justLiftParameters(), includeJustLiftEntry = false)
        assertEquals("home -> active_workout", backStack())

        val visitedAtExitClick = visitedRoutes.size
        exitViaStopDialog()
        settle()
        val visitedAfterExit = visitedRoutes.drop(visitedAtExitClick)

        assertEquals(
            "The absent-destination fallback must land JustLift (visited: $visitedAfterExit)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertEquals(
            "Fallback must preserve Home and never duplicate JustLift",
            "home -> just_lift",
            backStack(),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `just lift idle observer alone returns to JustLift`() {
        // Owner B alone: the engine publishes Idle (as after a stop) while no exit
        // dialog handler runs. The Just-Lift Idle observer must land JustLiftScreen.
        val fx = driveJustLiftActiveSession(justLiftParameters())
        val visitedAtStop = visitedRoutes.size

        fx.viewModel.stopWorkout(exitingWorkout = true)
        settle()

        val visitedAfterStop = visitedRoutes.drop(visitedAtStop)
        assertEquals(
            "The Just-Lift Idle observer must land JustLift, not Home (visited: $visitedAfterStop)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertTrue(
            "Idle-driven exit must never navigate to Home (visited: $visitedAfterStop)",
            visitedAfterStop.none { it == NavigationRoutes.Home.route },
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `repeated exit deliveries after settling perform no second transition`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())
        // The exact context the Exit dialog confirm captures (before stop).
        val exitContext = JustLiftExitContext.capture(
            navController!!,
            fx.viewModel.currentWorkoutSessionId(),
        )

        exitViaStopDialog()
        settle()
        val settledRoute = currentRoute()
        val settledStack = backStack()
        assertEquals(NavigationRoutes.JustLift.route, settledRoute)

        // Repeated confirmations / recreated-composition observer deliveries carrying
        // the same exit identity must be no-ops (one effective transition total).
        val second = navController!!.exitToJustLift(exitContext) { fx.viewModel.currentWorkoutSessionId() }
        val third = navController!!.exitToJustLift(exitContext) { fx.viewModel.currentWorkoutSessionId() }
        settle()
        assertEquals("A repeat delivery must not navigate", false, second)
        assertEquals("A repeat delivery must not navigate", false, third)
        assertEquals(settledRoute, currentRoute())
        assertEquals(settledStack, backStack())
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `stale exit callback from an ended session cannot navigate a later session`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())
        val staleSessionId = fx.viewModel.currentWorkoutSessionId()
        assertNotNull("Precondition: session identity exists before stop", staleSessionId)
        val staleContext = JustLiftExitContext.capture(
            navController!!,
            fx.viewModel.currentWorkoutSessionId(),
        )

        exitViaStopDialog()
        settle()
        assertEquals(NavigationRoutes.JustLift.route, currentRoute())

        // Later session: the production JustLift entry producer pushes a NEW
        // ActiveWorkout entry when the new session becomes Active.
        fx.viewModel.startWorkout(skipCountdown = true)
        rule.waitUntil(TIMEOUT_MS) {
            navController?.currentBackStackEntry?.destination?.route == NavigationRoutes.ActiveWorkout.route
        }
        rule.waitUntil(TIMEOUT_MS) {
            fx.viewModel.workoutState.value is WorkoutState.Active
        }
        val laterStack = backStack()
        assertEquals("home -> just_lift -> active_workout", laterStack)

        val effective = navController!!.exitToJustLift(staleContext) { fx.viewModel.currentWorkoutSessionId() }
        settle()
        assertEquals("A stale ended-session callback must not navigate", false, effective)
        assertEquals(laterStack, backStack())
        assertEquals(NavigationRoutes.ActiveWorkout.route, currentRoute())

        // The session-identity check alone (SAME entry, stale session token) must also
        // reject while the later session is current on that entry.
        val sameEntryStaleSession = JustLiftExitContext(
            sourceEntryId = navController!!.currentBackStackEntry?.id,
            sessionId = staleSessionId,
        )
        val effective2 = navController!!.exitToJustLift(sameEntryStaleSession) {
            fx.viewModel.currentWorkoutSessionId()
        }
        settle()
        assertEquals("A stale session token must not navigate the later session", false, effective2)
        assertEquals(laterStack, backStack())
    }

    // ===== Merge-gate validation extensions (entry-ID capture / delayed Idle /
    //      recreated-composition delivery / rendered Echo verification) =====

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `exit captures the real ActiveWorkout entry id and retains the existing JustLift entry`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())
        val homeId = entryIdOf(NavigationRoutes.Home.route)
        val justLiftId = entryIdOf(NavigationRoutes.JustLift.route)
        val activeId = entryIdOf(NavigationRoutes.ActiveWorkout.route)

        // Real back-stack entry-ID capture evidence: the authorisation context must
        // record the CURRENT ActiveWorkout entry's identity (the merge gate requires
        // actual entry-ID capture, not route-string evidence).
        val captured = JustLiftExitContext.capture(
            navController!!,
            fx.viewModel.currentWorkoutSessionId(),
        )
        assertEquals(
            "JustLiftExitContext.capture must record the current ActiveWorkout back-stack entry id",
            activeId,
            captured.sourceEntryId,
        )

        exitViaStopDialog()
        settle()

        val after = routedEntries()
        // Entry-ID level contract: [home, just_lift] with the EXISTING JustLift entry
        // retained non-inclusively, the captured ActiveWorkout entry gone, and no
        // duplicate JustLift entry.
        assertEquals(
            "After STOP -> Exit the stack must be exactly [home, just_lift] by ENTRY ID " +
                "(after: ${entryIdTrail()})",
            listOf(homeId, justLiftId),
            after.map { it.id },
        )
        assertEquals(
            "Exactly one JustLift entry must remain (no duplicate JustLift)",
            1,
            after.count { it.destination.route == NavigationRoutes.JustLift.route },
        )
        assertTrue(
            "The captured ActiveWorkout entry id must be gone from the stack",
            after.none { it.id == activeId },
        )
        assertFalse(
            "The captured source entry id must never re-enter the stack",
            after.any { it.id == captured.sourceEntryId },
        )

        RoutineCompleteRuntimeEvidence.record(
            "justLiftExit_entryIdCapture",
            mapOf(
                "destination" to currentRoute(),
                "backStackEntryIdsAfter" to entryIdTrail(),
                "capturedSourceEntryId" to captured.sourceEntryId,
                "retainedJustLiftEntryId" to justLiftId,
                "capturedEntryGoneFromStack" to after.none { it.id == captured.sourceEntryId },
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `delayed idle observer delivery after the exit confirm is a no-op`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())
        val homeId = entryIdOf(NavigationRoutes.Home.route)
        val justLiftId = entryIdOf(NavigationRoutes.JustLift.route)
        // The observer incarnation's composition-time capture (the production
        // `remember(navController, activeWorkoutEntryId) { JustLiftExitContext.capture(...) }`).
        val observerContext = JustLiftExitContext.capture(
            navController!!,
            fx.viewModel.currentWorkoutSessionId(),
        )

        rule.onNodeWithText("STOP").assertIsDisplayed().performClick()
        rule.onNodeWithText("Exit Workout?").assertIsDisplayed()

        // Deliberately control delivery timing: freeze the frame clock so the confirm
        // click's synchronous handler (capture -> real stop -> exitToJustLift) and the
        // Idle observer's collection resumption cannot share one natural interleaving.
        val visitedAtExitClick = visitedRoutes.size
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Exit").performClick()
        // Pump frames ONE AT A TIME and record the per-frame navigation trajectory:
        // delivery timing is under explicit test control, not ambient scheduling.
        val trajectory = mutableListOf<String>()
        repeat(DELAY_PUMP_FRAMES) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle {
                trajectory += "frame=$frame route=${currentRoute()} visited=${visitedRoutes.size} stack=${entryIdTrail()}"
            }
        }
        rule.mainClock.autoAdvance = true

        // The delayed Idle-observer delivery (its composition-time capture, delivered
        // after the confirm handler's exit has already landed) must be a no-op.
        val lateDelivery = navController!!.exitToJustLift(observerContext) {
            fx.viewModel.currentWorkoutSessionId()
        }
        settle()

        assertFalse("A delayed Idle-observer delivery must not navigate again", lateDelivery)
        val visitedAfterExit = visitedRoutes.drop(visitedAtExitClick)
        assertEquals(
            "Exactly one effective exit transition despite the delayed Idle delivery " +
                "(visited after Exit tap: $visitedAfterExit)",
            listOf(NavigationRoutes.JustLift.route),
            visitedAfterExit,
        )
        assertEquals(
            "Delayed delivery must not disturb the settled entry-id stack",
            listOf(homeId, justLiftId),
            routedEntries().map { it.id },
        )
        assertEquals(
            "No duplicate JustLift entry may appear after the delayed delivery",
            1,
            routedEntries().count { it.destination.route == NavigationRoutes.JustLift.route },
        )

        RoutineCompleteRuntimeEvidence.record(
            "justLiftExit_delayedIdleDelivery",
            mapOf(
                "destination" to currentRoute(),
                "frameTrajectory" to trajectory,
                "lateDeliveryNavigated" to lateDelivery,
                "visitedAfterExit" to visitedAfterExit,
                "backStackEntryIdsAfter" to entryIdTrail(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun `composition recreation mid-teardown delivers the pending idle exit exactly once`() {
        val fx = driveJustLiftActiveSession(justLiftParameters())
        val homeId = entryIdOf(NavigationRoutes.Home.route)
        val justLiftId = entryIdOf(NavigationRoutes.JustLift.route)

        // Real stop publishes Idle synchronously while the ActiveWorkout composition is
        // still outgoing. Emulate saved-instance-state restoration NOW: the outgoing
        // composition (and its pending observer delivery) is destroyed mid-teardown and
        // a RECREATED composition re-runs `remember { JustLiftExitContext.capture(...) }`
        // (a fresh capture) plus a fresh LaunchedEffect that collects the current Idle
        // state — the recreated-observer delivery path the merge gate requires.
        fx.viewModel.stopWorkout(exitingWorkout = true)
        val visitedAtStop = visitedRoutes.size
        val routeAtStop = currentRoute()
        restorationTester.emulateSavedInstanceStateRestore()
        settle()

        val visitedAfter = visitedRoutes.drop(visitedAtStop)
        // The recreation restarts the trail collector, which re-emits the current
        // entry; an observation re-emission of the same route with no intervening
        // destination change is NOT a navigation. Effective transitions = consecutive
        // distinct emissions.
        val effectiveTransitions = visitedAfter.fold(emptyList<String>()) { acc, route ->
            if (acc.lastOrNull() == route) acc else acc + route
        }
        assertEquals(
            "The recreated composition's pending Idle delivery must settle on JustLift " +
                "(visited after stop: $visitedAfter)",
            NavigationRoutes.JustLift.route,
            currentRoute(),
        )
        assertTrue(
            "Recreated-composition delivery must never navigate to Home (visited: $visitedAfter)",
            visitedAfter.none { it == NavigationRoutes.Home.route },
        )
        assertEquals(
            "Exactly one effective exit transition across composition recreation " +
                "(effective transitions: $effectiveTransitions)",
            listOf(NavigationRoutes.JustLift.route),
            effectiveTransitions,
        )
        assertEquals(
            "Nothing may navigate after the exit lands (visited after stop: $visitedAfter)",
            listOf(NavigationRoutes.JustLift.route),
            visitedAfter.dropWhile { it != NavigationRoutes.JustLift.route }.fold(emptyList<String>()) { acc, route ->
                if (acc.lastOrNull() == route) acc else acc + route
            },
        )
        assertEquals(
            "The settled stack must be exactly [home, just_lift] by ENTRY ID (after: ${entryIdTrail()})",
            listOf(homeId, justLiftId),
            routedEntries().map { it.id },
        )
        assertEquals(
            "No duplicate JustLift entry after composition recreation",
            1,
            routedEntries().count { it.destination.route == NavigationRoutes.JustLift.route },
        )

        RoutineCompleteRuntimeEvidence.record(
            "justLiftExit_recreatedCompositionDelivery",
            mapOf(
                "destination" to currentRoute(),
                "routeAtStop" to routeAtStop,
                "visitedAfterStop" to visitedAfter,
                "effectiveTransitions" to effectiveTransitions,
                "backStackEntryIdsAfter" to entryIdTrail(),
                "retainedJustLiftEntryIdMatchesPreRecreation" to
                    (routedEntries().singleOrNull { it.destination.route == NavigationRoutes.JustLift.route }?.id == justLiftId),
            ),
        )
    }

    // ===== Driving helpers =====

    /**
     * Builds the production shell over the production [NavGraph], starts a REAL Just
     * Lift session (real engine lease) and lets the production JustLiftScreen entry
     * producer push ActiveWorkout when the session becomes Active — the exact
     * production stack and entry path.
     */
    private fun driveJustLiftActiveSession(
        parameters: WorkoutParameters,
        includeJustLiftEntry: Boolean = true,
        extraActiveWorkoutEntries: Int = 0,
    ): RoutineCompleteRuntimeFixture {
        val fx = RoutineCompleteRuntimeFixture().also { fixture = it }
        fx.fakeBleRepository.simulateConnect("Vee_TestDevice")

        // Production order for the standard entry: JustLiftScreen composes while Idle
        // (its defaults load/param-sync seed the session configuration), THEN the user's
        // configuration applies, THEN the session starts and the production JustLift
        // entry producer pushes ActiveWorkout. Entry paths that land on ActiveWorkout
        // directly must instead compose with the session already Active: the Just-Lift
        // Idle observer legitimately pops a freshly composed Idle ActiveWorkout.
        val composeFirst = includeJustLiftEntry && extraActiveWorkoutEntries == 0
        if (!composeFirst) {
            fx.viewModel.updateWorkoutParameters(parameters)
            fx.viewModel.startWorkout(skipCountdown = true)
        } else {
            fx.viewModel.updateWorkoutParameters(parameters)
        }

        restorationTester.setContent {
            Box(Modifier.fillMaxSize()) {
                val nav = rememberNavController().also { navController = it }
                NavGraph(
                    navController = nav,
                    viewModel = fx.viewModel,
                    exerciseRepository = fx.fakeExerciseRepository,
                    themeMode = ThemeMode.SYSTEM,
                    onThemeModeChange = {},
                    dynamicColorAvailable = false,
                    dynamicColorEnabled = false,
                    onDynamicColorEnabledChange = {},
                    onOpenProfileSwitcher = {},
                    onProfileRecoveryRequired = {},
                )
                LaunchedEffect(Unit) {
                    nav.currentBackStackEntryFlow.collect { entry ->
                        synchronized(visitedRoutes) {
                            visitedRoutes += (entry.destination.route ?: "<graph>")
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    // Guarded so StateRestorationTester-driven composition recreation
                    // does not re-run the initial navigation and pollute the stack.
                    if (!initialNavigationDone) {
                        initialNavigationDone = true
                        if (includeJustLiftEntry) {
                            nav.navigate(NavigationRoutes.JustLift.route)
                        } else {
                            // Unusual entry path: ActiveWorkout without a JustLift entry below.
                            nav.navigate(NavigationRoutes.ActiveWorkout.route)
                        }
                        repeat(extraActiveWorkoutEntries) {
                            nav.navigate(NavigationRoutes.ActiveWorkout.route)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        if (composeFirst) {
            // The screen's defaults load + param-sync effect completes asynchronously and
            // re-seeds the engine parameters from the SAVED defaults. Wait for that seeding
            // phase to land (saved defaults are weight 20 kg / Old School in this harness)
            // before applying the user's session configuration, otherwise the sync lands
            // after the configure and clobbers the session's settings.
            rule.waitUntil(TIMEOUT_MS) {
                fx.viewModel.workoutParameters.value.weightPerCableKg == SAVED_DEFAULTS_WEIGHT_KG &&
                    fx.viewModel.workoutParameters.value.programMode == ProgramMode.OldSchool
            }
            fx.viewModel.updateWorkoutParameters(parameters)
            fx.viewModel.startWorkout(skipCountdown = true)
        }

        rule.waitUntil(TIMEOUT_MS) {
            fx.viewModel.workoutState.value is WorkoutState.Active
        }
        rule.waitUntil(TIMEOUT_MS) {
            navController?.currentBackStackEntry?.destination?.route == NavigationRoutes.ActiveWorkout.route
        }
        settle()
        return fx
    }

    /** Real STOP button -> exit confirmation dialog -> confirm, as the reporter does. */
    private fun exitViaStopDialog() {
        rule.onNodeWithText("STOP").assertIsDisplayed().performClick()
        rule.onNodeWithText("Exit Workout?").assertIsDisplayed()
        rule.onNodeWithText("Exit").performClick()
    }

    private fun assertEchoSettingsRetained(fx: RoutineCompleteRuntimeFixture) {
        val retained = fx.viewModel.workoutParameters.value
        assertEquals(ProgramMode.Echo, retained.programMode)
        assertEquals(EchoLevel.EPIC, retained.echoLevel)
        assertEquals(EccentricLoad.LOAD_120, retained.eccentricLoad)
        assertEquals(31.5f, retained.weightPerCableKg)
        // Rendered verification (merge gate): the returned JustLiftScreen must RENDER
        // the retained Echo selection and settings — engine parameters alone are not
        // evidence of what the user sees after the exit. The Echo mode chip and Echo
        // level chip carry Selected semantics; the eccentric-load picker is a dropdown
        // rendering the retained value as its visible text.
        rule.onNodeWithText("Echo").assertIsSelected()
        rule.onNodeWithText("Epic").assertIsSelected()
        rule.onNodeWithText("120%").assertIsDisplayed()
        rule.onNodeWithText("Adaptive resistance with echo feedback").assertIsDisplayed()
    }

    private fun justLiftParameters(): WorkoutParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 10,
        weightPerCableKg = 23.5f,
        warmupReps = 3,
        isJustLift = true,
    )

    /** Pumps frames/animations/effects and holds a settled observation window open. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_WINDOW_MS)
        rule.waitForIdle()
    }

    // ===== Navigation measurement primitives =====

    private fun currentRoute(): String =
        navController!!.currentBackStackEntry?.destination?.route ?: "<null>"

    /** Full back-stack route list (bottom -> top); the graph root has no route and is omitted. */
    private fun backStack(): String =
        navController!!.currentBackStack.value
            .mapNotNull { it.destination.route }
            .joinToString(" -> ")

    /** Back-stack entries that carry a route (the graph root has none), bottom -> top. */
    private fun routedEntries() =
        navController!!.currentBackStack.value.filter { it.destination.route != null }

    /** Real entry-ID evidence trail: route#entryId for every routed entry, bottom -> top. */
    private fun entryIdTrail(): String =
        routedEntries().joinToString(" -> ") { "${it.destination.route}#${it.id}" }

    private fun entryIdOf(route: String): String =
        routedEntries().first { it.destination.route == route }.id

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_WINDOW_MS = 5_000L

        /** Frames pumped one at a time while delivery timing is under explicit control. */
        const val DELAY_PUMP_FRAMES = 30

        /** Weight of the saved Just Lift defaults seeded in this harness (JustLiftDefaultsDocument default). */
        const val SAVED_DEFAULTS_WEIGHT_KG = 20f
    }
}

/** Safe-word listener that can never arm: exercises the production unavailable path. */
private object UnavailableSafeWordListenerFactory : SafeWordListenerFactory {
    override fun create(safeWord: String): SafeWordListener = UnavailableSafeWordListener
}

private object UnavailableSafeWordListener : SafeWordListener {
    override fun startListening() {}
    override fun stopListening() {}
    override val state: StateFlow<SafeWordState> = MutableStateFlow(
        SafeWordState.Unavailable(com.devil.phoenixproject.domain.voice.SafeWordUnavailableReason.RECOGNIZER_UNAVAILABLE),
    )
    override val detectedWord: SharedFlow<String> = MutableSharedFlow()
}
