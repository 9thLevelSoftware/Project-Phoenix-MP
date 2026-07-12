# Profile Tab UI, Navigation, and Insights Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the fifth Profile root tab, shared long-press profile switcher, profile management and preferences screen, profile-correct compact exercise insights, and a pruned global Settings screen while removing the legacy Home and Just Lift profile panels.

**Architecture:** `EnhancedMainScreen` remains the owner of root navigation and the shared profile-switcher overlay, while a route-scoped `ProfileViewModel` consumes the data-foundation plan's `ActiveProfileContext` and typed profile-preference APIs. A shared `ResolveCurrentOneRepMaxUseCase` supplies profile-scoped 1RM precedence to both Profile and Exercise Detail; focused presentation-only components hold identity dialogs, switcher rows, compact insights, and preference groups so `ProfileScreen` and `SettingsTab` do not become monoliths.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Material 3, Navigation Compose, AndroidX Lifecycle ViewModel, Koin, Kotlin coroutines/StateFlow, SQLDelight repository queries, Compose Resources XML, kotlin.test/JUnit4, Gradle 9.5.1.

## Global Constraints

- Complete `docs/superpowers/plans/2026-07-11-profile-preferences-data-foundation.md` first. This plan consumes its exact profile types and persistence behavior; it must not recreate a second preference store.
- The approved bottom-bar order is Analytics, Workout, Insights, Profile, Settings.
- The bar remains icon-only. Profile uses `Icons.Default.Person`.
- At 320dp width, every one of the five equal-width cells must retain a clickable area at least 48dp high and 48dp wide; outer horizontal padding is at most 8dp and the old inner `widthIn(min = 64.dp)` is removed.
- A normal Profile-item tap navigates with the existing root-tab save/restore behavior. A long press performs long-press haptic feedback and opens the switcher without navigating.
- Because `clearAndSetSemantics` replaces generated semantics, the Profile item must explicitly expose both semantic click and semantic long-click actions with localized labels.
- `EnhancedMainScreen` owns one `ProfileSwitcherSheet` instance accessible from every route where the root bottom bar is visible. `ProfileScreen`'s visible Switch Profile action opens that same instance.
- The switcher lists and selects existing profiles and opens Add Profile. It contains no rename or delete actions.
- Creating a profile uses the data-foundation plan's atomic create-with-defaults-and-activate operation. The creation UI closes only after success.
- Rename/color and guarded delete live in the active profile header. The Default profile cannot be deleted.
- Do not expose Profile delete until the data-foundation plan's overlapping PR/badge collision merge tests pass.
- Profile switching is unavailable after leaving a root tab for Just Lift or another workout flow. Remove both legacy side-panel call sites rather than adding a replacement inside workout routes.
- Use a route-scoped `ProfileViewModel`; do not add Profile selection/insight state to `MainViewModel`.
- Exercise selection is an in-memory map keyed by profile ID for the lifetime of the Profile root navigation entry. It is not persisted through backup, SQL, or process death.
- On profile change, clear rendered exercise data while the new `ActiveProfileContext` is `Switching`; restore a still-valid saved selection for that profile, otherwise resolve that profile's most recent completed exercise.
- The current-1RM precedence is latest passing valid velocity estimate, then the latest profile-scoped assessment's first valid override/estimate, then the newest valid estimate among at most five recent profile-scoped completed sessions through `OneRepMaxCalculator.estimate`.
- Velocity and session values are per-cable kilograms. Assessment result and override values are total kilograms and must be divided by two before the shared resolver returns them.
- Never use `Exercise.oneRepMaxKg` as the Profile/Exercise Detail resolver fallback.
- Assessment save APIs require an explicit Ready profile ID; remove silent `"default"` parameters from assessment persistence.
- Historical velocity estimates and assessment actions remain visible when `vbtEnabled` is false.
- PR highlights show the all-time max-weight, estimated-1RM, and max-volume values for the selected exercise and active profile.
- Recent history contains at most five completed, non-deleted sessions for the selected exercise and active profile.
- `ExercisePickerDialog` remains the searchable/filterable selector and is called with `enableCustomExercises = false`.
- Settings retains Portal/cloud sync, theme/dynamic color, language, video behavior, integrations, backup/restore/destination/destructive data management, BLE compatibility/logs/diagnostics/developer tools, donation, and app information.
- Settings removes weight unit/increment, body weight, Equipment Rack entry, workout behavior, audio controls, gamification, Achievements entry, LED, VBT, verbal feedback, voice-stop, safe-word, and adult-mode controls.
- The global Show Exercise Videos control currently nested inside Workout Preferences must be extracted and retained before that card is removed.
- `velocityOneRepMaxBackfillDone` remains an internal global/device migration flag and is not presented as a profile preference.
- `discoModeActive` remains transient BLE state. Persisted LED choices belong to the active profile; the sound/haptic preview can continue through MainViewModel's existing event stream.
- UI state never encodes or edits raw JSON. It consumes typed values and invokes typed data-foundation callbacks.
- A failed mutation leaves repository state authoritative, dismisses no destructive dialog, and emits one concise localized snackbar error.
- Add no new runtime dependency or Compose UI-test framework for this feature. Use ViewModel/use-case/repository tests plus narrow source-contract guards for gesture wiring and source removal.
- Add new user-visible strings to default, Dutch, German, Spanish, and French Compose resource files. Italian remains an unsupported partial fallback because it is not offered by the current language selector.
- Backend SQL/Edge implementation is outside this plan.

---





## Dependency Contract from the Data-Foundation Plan

Do not begin Task 5 until the data-foundation implementation provides these names and semantics:

```kotlin
sealed interface ActiveProfileContext {
    data class Switching(val targetProfileId: String?) : ActiveProfileContext

    data class Ready(
        val profile: UserProfile,
        val preferences: UserProfilePreferences,
        val localSafety: ProfileLocalSafetyPreferences,
    ) : ActiveProfileContext
}

class ProfileContextRecoveryException(cause: Throwable) :
    IllegalStateException("Could not reconcile the active profile context", cause)

interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>
    val activeProfileContext: StateFlow<ActiveProfileContext>

    fun observePreferences(profileId: String): Flow<UserProfilePreferences>

    suspend fun createAndActivateProfile(name: String, colorIndex: Int): UserProfile
    suspend fun updateProfile(id: String, name: String, colorIndex: Int)
    suspend fun deleteProfile(id: String): Boolean
    suspend fun setActiveProfile(id: String)

    suspend fun updateCore(profileId: String, value: CoreProfilePreferences)
    suspend fun updateRack(profileId: String, value: RackPreferences)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences)
    suspend fun updateLed(profileId: String, value: LedPreferences)
    suspend fun updateVbt(profileId: String, value: VbtPreferences)
    suspend fun updateLocalSafety(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun reconcileActiveProfileContext()
}
```

Every presentation mutation passes the `Ready.profile.id` captured with the edited value. The repository serializes switch/update operations and rejects that write if the ID is no longer active, preventing a stale A screen from writing into B. Presentation code catches that rejection, keeps the observed Ready value authoritative, and emits a localized failure event. Do not introduce adapters that write the legacy global preference store.

## File Structure

### Create

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt` — shared profile-scoped 1RM precedence and unit normalization.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt` — route-scoped identity, per-profile exercise selection, independent insights state, typed preference mutation, and snackbar events.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt` — one scrolling Profile surface and dialog/snackbar orchestration.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt` — color palette, avatar, and switcher row extracted from obsolete speed-dial code.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt` — callback-driven add, edit, delete, and blocking profile-context recovery dialogs.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt` — switch/create-only shared Material 3 bottom sheet.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt` — selector row, 1RM source, PR highlights, five-session list, and compact volume chart.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt` — measurements, rack, workout behavior, LED, VBT, and safety cards with typed state/callbacks.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt` — safe-word calibration, adult confirmation, Dominatrix unlock, and Disco unlock dialogs moved out of Settings.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCaseTest.kt` — resolver precedence, normalization, invalid-value, and profile-isolation tests.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailOneRepMaxLoadTest.kt` — latest-request token, real cancellation, ordinary-error, and legacy-read guards.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt` — profile-selection restoration and independent insight/mutation-state tests.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModelProfileScopeTest.kt` — explicit assessment profile propagation.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/AssessmentProfileOwnershipTest.kt` — immutable route ownership, Ready-gated callsites, and A→Switching→B invalidation.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentResourceContractTest.kt` — localized assessment-save failure copy across selectable locales.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt` — five-item order, tap/long-press wiring, and navigation/recovery resource consumption.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt` — Settings pruning, retained-video resources, and obsolete-selector source guard.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileResourceContractTest.kt` — exact 56-key inventory, reused-copy presence, placeholder/XML parity, selectable locales, and cross-target source-reader paths.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt` — picker/chart/history behavior plus complete Profile insight/preference resource consumption.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt` — palette wrapping, Default-delete policy, and switcher/delete-copy resource consumption.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt` — controllable assessment saves and latest-read failures for resolver/ViewModel tests.

### Modify

- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` — assessment 1RM compare-and-set compensation plus profile/exercise-limited completed-session queries.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt` — expose most-recent exercise and limited recent-session reads.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` — map the new SQLDelight reads.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt` — make profile ID required.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt` — preserve explicit profile ownership and serialize/compensate multi-write assessment saves.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngine.kt` — keep assessment regression and clamp values in total kilograms.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt` — use a safe profile-independent first load and forward an explicit Ready profile ID.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt` — bind Results acceptance to the explicit route profile and hide the unscoped global 1RM.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt` — pass explicit assessment ownership through its callback, then replace split session/velocity hero logic with the shared resolver.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt` — retain its callback-only assessment entry point while NavGraph supplies an owner-bearing route.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt` — immutable profile-bearing assessment routes, Profile route, and canonical five-item order.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt` — Profile destination/callbacks and explicit assessment profile wiring.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt` — five-cell bar, accessible long press, shared switcher owner, localized title.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt` — retain only global settings and compose the extracted global video card.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt` — remove profile selector state and overlay.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt` — consume the extracted palette symbol.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt` — switcher-focused tap semantics and disabled state.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt` — Profile navigation/screen/sheet/action tags.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt` — unchanged repository ownership but verified against new repository contracts.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt` — shared resolver binding.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt` — ProfileViewModel binding.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt` — bounded deterministic session reads, request capture, and read failures.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeVelocityOneRepMaxRepository.kt` — injectable latest-read failure.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt` — data-foundation identity, context, captured-ID update, and injectable-failure APIs.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePersonalRecordRepository.kt` — controllable insight-read failure.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutesTest.kt` — assessment route ownership/encoding and blank-ID rejection.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt` — completed-session query filters and limit.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt` — explicit IDs, unit contract, and isolation.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngineTest.kt` — total-kilogram hardware ceiling.
- `shared/src/commonMain/composeResources/values/strings.xml` — English Profile/navigation/insights/preferences/errors copy.
- `shared/src/commonMain/composeResources/values-nl/strings.xml` — Dutch copy.
- `shared/src/commonMain/composeResources/values-de/strings.xml` — German copy.
- `shared/src/commonMain/composeResources/values-es/strings.xml` — Spanish copy.
- `shared/src/commonMain/composeResources/values-fr/strings.xml` — French copy.

### Delete after extraction and call-site removal

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt`

---

### Task 1: Require Explicit Profile Ownership for Assessments

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModelProfileScopeTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/AssessmentProfileOwnershipTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentResourceContractTest.kt`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq:2004-2006`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt:38-95`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt:56-171`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngine.kt:46-52`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt:270-321`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt:66-129`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt:27-58,167-175`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt:48-57,97-101`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt:49-53`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt:685-760`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutesTest.kt:1-22`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt:26-58`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngineTest.kt:119-134`
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-nl/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-de/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-es/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-fr/strings.xml`

**Interfaces:**
- Consumes: the data-foundation plan's `ActiveProfileContext` stream. Every navigation call is gated on `Ready` and writes that profile ID into the route; the destination reads only this immutable route argument as its owner.
- Produces: `AssessmentViewModel.acceptResult(profileId: String, overrideKg: Float? = null)`, `AssessmentUiEvent.SaveFailed`, and assessment repository/entity APIs whose `profileId: String` arguments have no default value.
- Unit contract: wizard loads, assessment estimates, and overrides are total kilograms; `WorkoutSession.weightPerCableKg` and `Exercise.oneRepMaxKg` are per-cable kilograms. Persistence divides only the session average and exercise 1RM, never the assessment row.
- Lifecycle contract: `Ready(A)` may render only an A-owned route. `Switching` or `Ready(B)` invalidates and pops it before a B-owned wizard can be constructed; no asynchronous effect captures mutable active-profile state as route ownership.
- Failure contract: non-cancellation saves restore `Results` and emit exactly one localized `SaveFailed` event. Cancellation is rethrown. Repository saves are serialized per repository; compensation runs in `NonCancellable`, restores an attempted exercise 1RM only by SQL compare-and-set, and never overwrites a newer value.

- [ ] **Step 1: Create a recording fake assessment repository**

```kotlin
package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.AssessmentResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeAssessmentRepository : AssessmentRepository {
    data class SavedSession(
        val exerciseId: String,
        val estimatedOneRepMaxTotalKg: Float,
        val userOverrideTotalKg: Float?,
        val weightPerCableKg: Float,
        val profileId: String,
    )

    val attemptedSessions = mutableListOf<SavedSession>()
    val savedSessions = mutableListOf<SavedSession>()
    var saveSessionFailure: Throwable? = null
    private val assessments = mutableListOf<AssessmentResultEntity>()

    override suspend fun saveAssessment(
        exerciseId: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        sessionId: String?,
        userOverrideKg: Float?,
        profileId: String,
    ): Long {
        require(profileId.isNotBlank())
        val id = assessments.size.toLong() + 1L
        assessments += AssessmentResultEntity(
            id = id,
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg,
            loadVelocityData = loadVelocityDataJson,
            assessmentSessionId = sessionId,
            userOverrideKg = userOverrideKg,
            createdAt = id,
            profileId = profileId,
        )
        return id
    }

    override fun getAssessmentsByExercise(
        exerciseId: String,
        profileId: String,
    ): Flow<List<AssessmentResultEntity>> = flowOf(
        assessments.filter { it.exerciseId == exerciseId && it.profileId == profileId }
            .sortedByDescending { it.createdAt },
    )

    override suspend fun getLatestAssessment(
        exerciseId: String,
        profileId: String,
    ): AssessmentResultEntity? = assessments
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .maxByOrNull { it.createdAt }

    override suspend fun deleteAssessment(id: Long) {
        assessments.removeAll { it.id == id }
    }

    override suspend fun saveAssessmentSession(
        exerciseId: String,
        exerciseName: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        userOverrideKg: Float?,
        totalReps: Int,
        durationMs: Long,
        weightPerCableKg: Float,
        profileId: String,
    ): String {
        require(profileId.isNotBlank())
        val captured = SavedSession(
            exerciseId = exerciseId,
            estimatedOneRepMaxTotalKg = estimatedOneRepMaxKg,
            userOverrideTotalKg = userOverrideKg,
            weightPerCableKg = weightPerCableKg,
            profileId = profileId,
        )
        attemptedSessions += captured
        saveSessionFailure?.let { throw it }
        savedSessions += captured
        saveAssessment(
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg,
            loadVelocityDataJson = loadVelocityDataJson,
            sessionId = "assessment-session-${savedSessions.size}",
            userOverrideKg = userOverrideKg,
            profileId = profileId,
        )
        return "assessment-session-${savedSessions.size}"
    }
}
```

The fake records an attempted call before the failure seam and records a successful save only afterward. This distinction is required for the ordinary-error and cancellation tests; do not make a thrown save look committed.

- [ ] **Step 2: Write the failing ViewModel profile-propagation test**

```kotlin
package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AssessmentViewModelProfileScopeTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    @Test
    fun acceptResult_forwardsExplicitProfileAndPreservesUnitBoundary() = runTest {
        val assessmentRepository = FakeAssessmentRepository()
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.acceptResult(profileId = "athlete-a", overrideKg = 120f)
        advanceUntilIdle()

        val saved = assessmentRepository.savedSessions.single()
        assertEquals("athlete-a", saved.profileId)
        assertEquals(120f, saved.userOverrideTotalKg)
        assertEquals(30f, saved.weightPerCableKg)
        assertIs<AssessmentStep.Complete>(viewModel.currentStep.value)
        assertEquals(120f, (viewModel.currentStep.value as AssessmentStep.Complete).finalOneRepMaxKg)
    }

    @Test
    fun acceptResult_rejectsBlankProfileBeforeStartingSave() = runTest {
        val assessmentRepository = FakeAssessmentRepository()
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        assertFailsWith<IllegalArgumentException> {
            viewModel.acceptResult(profileId = " ")
        }

        assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
        assertEquals(emptyList(), assessmentRepository.attemptedSessions)
    }

    @Test
    fun ordinarySaveFailureRestoresResultsAndEmitsOneEvent() = runTest {
        val assessmentRepository = FakeAssessmentRepository().apply {
            saveSessionFailure = IllegalStateException("test failure")
        }
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.events.test {
            viewModel.acceptResult(profileId = "athlete-a")
            advanceUntilIdle()

            assertEquals(AssessmentUiEvent.SaveFailed, awaitItem())
            expectNoEvents()
            assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
            assertEquals("athlete-a", assessmentRepository.attemptedSessions.single().profileId)
            assertEquals(emptyList(), assessmentRepository.savedSessions)
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoRetryOrFailureEvent() = runTest {
        val assessmentRepository = FakeAssessmentRepository().apply {
            saveSessionFailure = CancellationException("test cancellation")
        }
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.events.test {
            viewModel.acceptResult(profileId = "athlete-a")
            advanceUntilIdle()

            expectNoEvents()
            assertIs<AssessmentStep.Saving>(viewModel.currentStep.value)
            assertEquals("athlete-a", assessmentRepository.attemptedSessions.single().profileId)
        }
    }

    @Test
    fun startingLoad_isProfileIndependentWhenGlobalExerciseOneRmBelongsToAnotherProfile() = runTest {
        val sharedExercises = exerciseRepository(oneRepMaxKg = 100f)
        val profileA = readyViewModel(FakeAssessmentRepository(), sharedExercises)
        val profileB = readyViewModel(FakeAssessmentRepository(), sharedExercises)
        advanceUntilIdle()

        profileA.selectExerciseById("bench")
        profileB.selectExerciseById("bench")
        advanceUntilIdle()

        assertEquals(
            20f,
            (profileA.currentStep.value as AssessmentStep.ProgressiveLoading).suggestedWeightKg,
        )
        assertEquals(
            20f,
            (profileB.currentStep.value as AssessmentStep.ProgressiveLoading).suggestedWeightKg,
        )
    }

    private fun readyViewModel(
        assessmentRepository: FakeAssessmentRepository,
        exerciseRepository: FakeExerciseRepository = exerciseRepository(),
    ): AssessmentViewModel {
        return AssessmentViewModel(
            exerciseRepository = exerciseRepository,
            assessmentRepository = assessmentRepository,
            assessmentEngine = AssessmentEngine(),
        )
    }

    private fun exerciseRepository(oneRepMaxKg: Float? = null) =
        FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                    oneRepMaxKg = oneRepMaxKg,
                ),
            )
        }

    private suspend fun TestScope.reachResults(viewModel: AssessmentViewModel) {
        advanceUntilIdle()
        viewModel.selectExerciseById("bench")
        advanceUntilIdle()
        viewModel.recordSet(40f, 3, 1.0f, 1.1f)
        viewModel.recordSet(80f, 3, 0.25f, 0.30f)
        assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
    }
}
```

This test deliberately gives the shared `Exercise` row a 100 kg per-cable global value, then opens independent A and B route ViewModels. Both must display exactly the same conservative 20 kg total first load because the global column has no profile owner.

- [ ] **Step 3: Write failing route-ownership, resource, and total-ceiling tests**

Create `AssessmentProfileOwnershipTest.kt`:

```kotlin
package com.devil.phoenixproject.presentation.navigation

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AssessmentProfileOwnershipTest {
    @Test
    fun `route owned by A invalidates through switching and Ready B without binding B`() {
        val states = listOf(
            ready("athlete-a"),
            ActiveProfileContext.Switching("athlete-b"),
            ready("athlete-b"),
        ).map { context ->
            resolveAssessmentProfileDestination(
                routeProfileId = "athlete-a",
                context = context,
            )
        }

        assertEquals(
            listOf(
                AssessmentProfileDestinationState.Bound("athlete-a"),
                AssessmentProfileDestinationState.Invalidated,
                AssessmentProfileDestinationState.Invalidated,
            ),
            states,
        )
        assertFalse(
            states.any { it == AssessmentProfileDestinationState.Bound("athlete-b") },
        )
    }

    @Test
    fun `all assessment entry points pass Ready profile IDs into route factories`() {
        val navGraph = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
        )
        val analytics = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt",
        )
        val detail = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt",
        )
        val wizard = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt",
        )
        val assessmentViewModel = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt",
        )
        assertNotNull(navGraph)
        assertNotNull(analytics)
        assertNotNull(detail)
        assertNotNull(wizard)
        assertNotNull(assessmentViewModel)

        assertContains(navGraph, "NavigationRoutes.StrengthAssessmentPicker.createRoute(profileId)")
        assertContains(navGraph, "NavigationRoutes.StrengthAssessment.createRoute(profileId, exerciseId)")
        assertFalse(navGraph.contains("navigate(NavigationRoutes.StrengthAssessmentPicker.route)"))
        assertContains(analytics, "assessmentProfileId: String?")
        assertContains(analytics, "onNavigateToStrengthAssessment: (String) -> Unit")
        assertContains(detail, "assessmentProfileId: String?")
        assertContains(detail, "onNavigateToStrengthAssessment: (String) -> Unit")
        assertFalse(detail.contains("NavigationRoutes.StrengthAssessment"))
        assertFalse(wizard.contains("exercise.oneRepMaxKg"))
        assertFalse(assessmentViewModel.contains("exercise.oneRepMaxKg"))
    }

    private fun ready(profileId: String): ActiveProfileContext.Ready {
        val repository = FakeUserProfileRepository()
        repository.setActiveProfileForTest(profileId)
        return repository.activeProfileContext.value as ActiveProfileContext.Ready
    }
}
```

Extend the existing `NavigationRoutesTest.kt` with route-factory ownership, encoding, and guard coverage:

```kotlin
@Test
fun strengthAssessmentRoutes_encodeImmutableProfileOwnership() {
    assertEquals(
        "strength_assessment_picker/athlete%2FA",
        NavigationRoutes.StrengthAssessmentPicker.createRoute("athlete/A"),
    )
    assertEquals(
        "strength_assessment/athlete%2FA/bench%20press",
        NavigationRoutes.StrengthAssessment.createRoute("athlete/A", "bench press"),
    )
}

@Test
fun strengthAssessmentRoutes_rejectBlankOwnership() {
    assertFailsWith<IllegalArgumentException> {
        NavigationRoutes.StrengthAssessmentPicker.createRoute(" ")
    }
    assertFailsWith<IllegalArgumentException> {
        NavigationRoutes.StrengthAssessment.createRoute(" ", "bench")
    }
    assertFailsWith<IllegalArgumentException> {
        NavigationRoutes.StrengthAssessment.createRoute("athlete-a", " ")
    }
}
```

Import `kotlin.test.assertFailsWith`. These factories are the only legal way to navigate to either assessment route; there is no profile-less overload or constant picker destination.

Create `AssessmentResourceContractTest.kt`:

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class AssessmentResourceContractTest {
    @Test
    fun `save failure copy exists in every selectable locale`() {
        listOf(
            "values",
            "values-nl",
            "values-de",
            "values-es",
            "values-fr",
        ).forEach { directory ->
            val path = "src/commonMain/composeResources/$directory/strings.xml"
            val source = readProjectFile(path)
            assertNotNull(source, "Could not read $path")
            assertContains(source, """name="assessment_save_failed"""", path)
        }
    }
}
```

In `AssessmentEngineTest.kt`, replace the old per-cable clamp test:

```kotlin
@Test
fun `estimateOneRepMax clamps total result at dual cable hardware maximum`() {
    val result = engine.estimateOneRepMax(
        listOf(
            LoadVelocityPoint(100f, 1.0f),
            LoadVelocityPoint(105f, 0.99f),
        ),
    )

    assertNotNull(result)
    assertEquals(220f, result.estimatedOneRepMaxKg, 0.1f)
}
```

- [ ] **Step 4: Run the focused tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*AssessmentViewModelProfileScopeTest*" --tests "*AssessmentProfileOwnershipTest*" --tests "*NavigationRoutesTest*" --tests "*AssessmentResourceContractTest*" --tests "*AssessmentEngineTest*" --console=plain
```

Expected: FAIL because the explicit-ID/event APIs, profile-bearing route factories/callsites, destination-state resolver, localized key, safe profile-independent starting load, and 220 kg total clamp do not exist yet.

- [ ] **Step 5: Remove assessment profile defaults and enforce total-kilogram engine semantics**

In `AssessmentRepository.kt`, change the existing entity property from `val profileId: String = "default"` to `val profileId: String`. In both `saveAssessment` and `saveAssessmentSession`, change the existing final parameter from `profileId: String = "default"` to `profileId: String`:

```kotlin
profileId: String,
```

Keep `userOverrideKg` optional, but document `estimatedOneRepMaxKg` and `userOverrideKg` as total kilograms and `weightPerCableKg` as per-cable kilograms. Every current caller is in this Task's inventory: the two `SqlDelightAssessmentRepositoryTest` calls and `AssessmentViewModel` for `saveAssessmentSession`; the new fake/repository isolation tests for `saveAssessment`; the two `NavGraph` calls for `AssessmentWizardScreen`; and `AssessmentWizardScreen` for `acceptResult`. Do not retain a compatibility overload.

In `AssessmentEngine.kt`, make its existing total-valued regression agree with the wizard and persistence contract:

```kotlin
val estimatedLoad = ((config.oneRmVelocityMs.toDouble() - intercept) / slope)
    .coerceAtLeast(1.0)
    .coerceAtMost((Constants.MAX_WEIGHT_PER_CABLE_KG * 2f).toDouble())
```

In `AssessmentViewModel.startAssessmentInternal`, remove the `exercise.oneRepMaxKg` branch entirely. That column is global, so using it while an A-owned value is visible to profile B leaks profile state and can prescribe the wrong starting load. Until Task 2's profile-scoped resolver exists, seed every assessment conservatively in total-kilogram units:

```kotlin
private fun startAssessmentInternal() {
    assessmentStartTimeMs = currentTimeMillis()
    if (selectedExercise == null) return

    _currentStep.value = AssessmentStep.ProgressiveLoading(
        currentSetNumber = 1,
        suggestedWeightKg = SAFE_STARTING_LOAD_TOTAL_KG,
        recordedSets = emptyList(),
        latestVelocity = null,
        shouldStop = false,
    )
}

private companion object {
    const val SAFE_STARTING_LOAD_TOTAL_KG = 20f
}
```

Also remove the `exercise.oneRepMaxKg` badge from `AssessmentWizardScreen`'s exercise-picker row. Do not substitute `Exercise.oneRepMaxKg` elsewhere in the assessment flow. Task 2 may replace the conservative seed/display only after its resolver can accept the immutable route `profileId` and return a profile-scoped value.

Add `AssessmentUiEvent` at top level beside `AssessmentStep`, then add the two flow properties inside `AssessmentViewModel`:

```kotlin
sealed interface AssessmentUiEvent {
    data object SaveFailed : AssessmentUiEvent
}

private val _events = MutableSharedFlow<AssessmentUiEvent>(extraBufferCapacity = 1)
val events: SharedFlow<AssessmentUiEvent> = _events.asSharedFlow()
```

Import `kotlin.coroutines.cancellation.CancellationException`, `kotlinx.coroutines.flow.MutableSharedFlow`, `SharedFlow`, and `asSharedFlow`. Then replace `acceptResult`:

```kotlin
fun acceptResult(profileId: String, overrideKg: Float? = null) {
    require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
    val exercise = selectedExercise ?: return
    val current = _currentStep.value
    if (current !is AssessmentStep.Results) return

    _currentStep.value = AssessmentStep.Saving
    viewModelScope.launch {
        try {
            val finalOneRm = overrideKg?.takeIf { it > 0f } ?: current.estimatedOneRepMaxKg
            val lvDataJson = Json.encodeToString(
                current.loadVelocityPoints.map {
                    mapOf(
                        "loadKg" to it.loadKg.toString(),
                        "velocityMs" to it.meanVelocityMs.toString(),
                    )
                },
            )
            val totalReps = current.loadVelocityPoints.size * 3
            val durationMs = currentTimeMillis() - assessmentStartTimeMs
            val avgWeight = current.loadVelocityPoints.map { it.loadKg }.average().toFloat()

            assessmentRepository.saveAssessmentSession(
                exerciseId = exercise.id ?: exercise.name,
                exerciseName = exercise.displayName,
                estimatedOneRepMaxKg = current.estimatedOneRepMaxKg,
                loadVelocityDataJson = lvDataJson,
                userOverrideKg = overrideKg?.takeIf { it > 0f },
                totalReps = totalReps,
                durationMs = durationMs,
                weightPerCableKg = avgWeight / 2f,
                profileId = profileId,
            )

            _currentStep.value = AssessmentStep.Complete(
                finalOneRepMaxKg = finalOneRm,
                exerciseName = exercise.displayName,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save assessment" }
            _currentStep.value = current
            _events.emit(AssessmentUiEvent.SaveFailed)
        }
    }
}
```

The profile ID is validated synchronously before `Saving`, so blank ownership never launches a write. Estimated/override values forwarded to the repository and shown in `Complete` remain total kilograms; only `weightPerCableKg = avgWeight / 2f` crosses to per-cable storage. The safe first load is exactly 20 kg total; do not feed it through `suggestNextWeight`, and do not read the global exercise 1RM.

- [ ] **Step 6: Put immutable Ready ownership in every assessment route and wire failure feedback**

Change the screen signature and Results binding:

```kotlin
@Composable
fun AssessmentWizardScreen(
    viewModel: AssessmentViewModel,
    profileId: String,
    exerciseId: String? = null,
    themeMode: ThemeMode,
    onNavigateBack: () -> Unit,
    metricsFlow: StateFlow<WorkoutMetric?>? = null,
) {
    require(profileId.isNotBlank())
    val currentStep by viewModel.currentStep.collectAsState()
    val exercises by viewModel.exercises.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailureMessage = stringResource(Res.string.assessment_save_failed)
    LaunchedEffect(viewModel, saveFailureMessage) {
        viewModel.events.collect { event ->
            when (event) {
                AssessmentUiEvent.SaveFailed ->
                    snackbarHostState.showSnackbar(saveFailureMessage)
            }
        }
    }
}
```

Inside the function's existing exhaustive `when (val step = currentStep)`, replace its Results branch with this compiling branch; no other branch changes:

```kotlin
is AssessmentStep.Results -> ResultsContent(
    step = step,
    onAccept = { overrideKg ->
        viewModel.acceptResult(
            profileId = profileId,
            overrideKg = overrideKg,
        )
    },
    onDiscard = viewModel::reset,
)
```

Import `AssessmentUiEvent` beside the existing `AssessmentStep`/`AssessmentViewModel` imports. Add `SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))` as the final child of the screen's existing outer `Box` so one event produces one visible message without replacing authoritative `Results` state.

In `NavigationRoutes.kt`, replace both profile-less assessment routes. Require and encode every path segment so no caller can create an unowned destination:

```kotlin
object StrengthAssessment : NavigationRoutes(
    "strength_assessment/{profileId}/{exerciseId}",
) {
    fun createRoute(profileId: String, exerciseId: String): String {
        require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
        require(exerciseId.isNotBlank()) { "Assessment exerciseId must not be blank" }
        return "strength_assessment/${profileId.encodeRouteSegment()}/${exerciseId.encodeRouteSegment()}"
    }
}

object StrengthAssessmentPicker : NavigationRoutes(
    "strength_assessment_picker/{profileId}",
) {
    fun createRoute(profileId: String): String {
        require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
        return "strength_assessment_picker/${profileId.encodeRouteSegment()}"
    }
}
```

In `AnalyticsScreen.kt`, change both the public screen and internal content signatures to accept `assessmentProfileId: String?` and `onNavigateToStrengthAssessment: (String) -> Unit`. Forward both values through the existing call. Change the assessment card to:

```kotlin
ExpressiveCard(
    onClick = {
        assessmentProfileId?.let(onNavigateToStrengthAssessment)
    },
    enabled = assessmentProfileId != null,
    modifier = Modifier.fillMaxWidth(),
) {
    // existing card content unchanged
}
```

In `ExerciseDetailScreen.kt`, replace its `navController: NavController` parameter with `assessmentProfileId: String?` and `onNavigateToStrengthAssessment: (String) -> Unit`; this is the file's only navigation use, so delete the `NavController` and `NavigationRoutes` imports. Replace the button callback/guard with:

```kotlin
onClick = {
    assessmentProfileId?.let(onNavigateToStrengthAssessment)
},
enabled = isConnected && assessmentProfileId != null,
```

In `NavGraph`, inject `UserProfileRepository` once at the start of `NavGraph`, collect `activeProfileContext`, and derive a nullable Ready ID:

```kotlin
val profileRepository: UserProfileRepository = koinInject()
val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
val assessmentProfileId =
    (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id
```

Pass `assessmentProfileId` to Analytics and Exercise Detail. Their callbacks create routes only from the non-null ID delivered by the screen:

```kotlin
// Analytics
onNavigateToStrengthAssessment = { profileId ->
    navController.navigate(
        NavigationRoutes.StrengthAssessmentPicker.createRoute(profileId),
    )
}

// Exercise Detail; exerciseId is the already validated immutable route argument
onNavigateToStrengthAssessment = { profileId ->
    navController.navigate(
        NavigationRoutes.StrengthAssessment.createRoute(profileId, exerciseId),
    )
}
```

Add this pure destination resolver outside `NavGraph`:

```kotlin
internal sealed interface AssessmentProfileDestinationState {
    data class Bound(val profileId: String) : AssessmentProfileDestinationState
    data object Invalidated : AssessmentProfileDestinationState
}

internal fun resolveAssessmentProfileDestination(
    routeProfileId: String,
    context: ActiveProfileContext,
): AssessmentProfileDestinationState =
    if (
        routeProfileId.isNotBlank() &&
        context is ActiveProfileContext.Ready &&
        context.profile.id == routeProfileId
    ) {
        AssessmentProfileDestinationState.Bound(routeProfileId)
    } else {
        AssessmentProfileDestinationState.Invalidated
    }
```

Add one helper used by both assessment destinations:

```kotlin
@Composable
private fun AssessmentDestination(
    profileId: String,
    exerciseId: String?,
    activeContext: ActiveProfileContext,
    themeMode: ThemeMode,
    metricsFlow: StateFlow<WorkoutMetric?>,
    onNavigateBack: () -> Unit,
) {
    val destinationState = resolveAssessmentProfileDestination(
        routeProfileId = profileId,
        context = activeContext,
    )

    LaunchedEffect(destinationState) {
        if (destinationState == AssessmentProfileDestinationState.Invalidated) {
            onNavigateBack()
        }
    }

    when (destinationState) {
        is AssessmentProfileDestinationState.Bound -> {
            val assessmentViewModel: AssessmentViewModel = koinViewModel()
            AssessmentWizardScreen(
                viewModel = assessmentViewModel,
                profileId = destinationState.profileId,
                exerciseId = exerciseId,
                themeMode = themeMode,
                onNavigateBack = onNavigateBack,
                metricsFlow = metricsFlow,
            )
        }
        AssessmentProfileDestinationState.Invalidated -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(LoadingIndicatorSize.Large)
        }
    }
}
```

Import `ActiveProfileContext`, `UserProfileRepository`, `WorkoutMetric`, `StateFlow`, and `org.koin.compose.viewmodel.koinViewModel`. Route-scoped `koinViewModel` is required: popping an invalidated entry clears the ViewModel and cancels its save job. The remaining `LaunchedEffect` only pops an already-invalid immutable route; it never captures ownership.

Delete both existing `val assessmentViewModel: AssessmentViewModel = koinInject()` lines. Give the picker destination a required `profileId` navigation argument, read it from `backStackEntry`, and pop/return on a null or blank value before calling:

```kotlin
AssessmentDestination(
    profileId = profileId,
    exerciseId = null,
    activeContext = activeProfileContext,
    themeMode = themeMode,
    metricsFlow = viewModel.currentMetric,
    onNavigateBack = { navController.popBackStack() },
)
```

Give the preselected destination required `profileId` and `exerciseId` arguments, validate both, and call the same helper with `profileId = profileId`, `exerciseId = exerciseId`, and `activeContext = activeProfileContext`. The only active-profile read allowed is the equality check that invalidates a stale route; ownership always comes from `backStackEntry.arguments`.

Add the localized failure key:

```xml
<!-- values -->
<string name="assessment_save_failed">Couldn’t save the assessment. Try again.</string>
<!-- values-nl -->
<string name="assessment_save_failed">De krachtmeting kon niet worden opgeslagen. Probeer het opnieuw.</string>
<!-- values-de -->
<string name="assessment_save_failed">Die Kraftmessung konnte nicht gespeichert werden. Versuche es erneut.</string>
<!-- values-es -->
<string name="assessment_save_failed">No se pudo guardar la evaluación. Inténtalo de nuevo.</string>
<!-- values-fr -->
<string name="assessment_save_failed">Impossible d’enregistrer l’évaluation. Réessayez.</string>
```

Each line goes only in its named locale file. The route behavior is:

- entry points are disabled unless context is `Ready`, and synchronously encode that Ready profile ID into the route;
- `Ready(A)` plus an A route renders only an A-owned wizard;
- `Switching`, `Ready(B)`, or a malformed/restored route removes the wizard immediately and pops; never render A state against B;
- cancellation from that pop reaches `acceptResult` and the repository unchanged.

- [ ] **Step 7: Make persistence atomic under error/cancellation and prove profile/unit isolation**

In `VitruvianDatabase.sq`, add a compare-and-set restore beside `updateOneRepMax`:

```sql
restoreOneRepMaxIfCurrent:
UPDATE Exercise
SET one_rep_max_kg = :previousOneRepMaxKg
WHERE id = :exerciseId
  AND one_rep_max_kg = :attemptedOneRepMaxKg;
```

An assessment may restore its snapshot only while the column still equals the exact per-cable value that assessment attempted. If any external writer has published a newer value, this statement affects zero rows and preserves it.

In `SqlDelightAssessmentRepository`, add a defaulted dispatcher seam and one repository-wide mutex. The same mutex must cover both `saveAssessment` and `saveAssessmentSession`; otherwise concurrent inserts can race `lastInsertRowId()` even if session compensation is serialized:

```kotlin
class SqlDelightAssessmentRepository(
    db: VitruvianDatabase,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssessmentRepository {
    private val queries = db.vitruvianDatabaseQueries
    private val assessmentWriteMutex = Mutex()
```

Import `CoroutineDispatcher`, `NonCancellable`, `Mutex`, `withLock`, and `kotlin.coroutines.cancellation.CancellationException`. Validate profile ownership before dispatch/locking, then replace `saveAssessment` so insert plus ID lookup is one serialized identity operation:

```kotlin
override suspend fun saveAssessment(
    exerciseId: String,
    estimatedOneRepMaxKg: Float,
    loadVelocityDataJson: String,
    sessionId: String?,
    userOverrideKg: Float?,
    profileId: String,
): Long {
    require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
    return withContext(ioDispatcher) {
        assessmentWriteMutex.withLock {
            queries.insertAssessmentResult(
                exerciseId = exerciseId,
                estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
                loadVelocityData = loadVelocityDataJson,
                assessmentSessionId = sessionId,
                userOverrideKg = userOverrideKg?.toDouble(),
                createdAt = currentTimeMillis(),
                profile_id = profileId,
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }
}
```

Replace `saveAssessmentSession` completely:

```kotlin
override suspend fun saveAssessmentSession(
    exerciseId: String,
    exerciseName: String,
    estimatedOneRepMaxKg: Float,
    loadVelocityDataJson: String,
    userOverrideKg: Float?,
    totalReps: Int,
    durationMs: Long,
    weightPerCableKg: Float,
    profileId: String,
): String {
    require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
    return withContext(ioDispatcher) {
        assessmentWriteMutex.withLock {
            val attemptedOneRepMaxPerCableKg =
                (userOverrideKg ?: estimatedOneRepMaxKg) / 2f
            val sessionId = generateUUID()
            val session = WorkoutSession(
                id = sessionId,
                timestamp = currentTimeMillis(),
                mode = "OldSchool",
                reps = totalReps,
                weightPerCableKg = weightPerCableKg,
                duration = durationMs,
                totalReps = totalReps,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                routineName = ASSESSMENT_ROUTINE_NAME,
                profileId = profileId,
            )

            var insertedResultId: Long? = null
            var previousOneRepMaxPerCableKg: Float? = null
            var exerciseWriteAttempted = false
            try {
                workoutRepository.saveSession(session)
                queries.insertAssessmentResult(
                    exerciseId = exerciseId,
                    estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
                    loadVelocityData = loadVelocityDataJson,
                    assessmentSessionId = sessionId,
                    userOverrideKg = userOverrideKg?.toDouble(),
                    createdAt = currentTimeMillis(),
                    profile_id = profileId,
                )
                insertedResultId = queries.lastInsertRowId().executeAsOne()

                previousOneRepMaxPerCableKg =
                    exerciseRepository.getExerciseById(exerciseId)?.oneRepMaxKg
                exerciseWriteAttempted = true
                exerciseRepository.updateOneRepMax(
                    exerciseId,
                    attemptedOneRepMaxPerCableKg,
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (exerciseWriteAttempted) {
                        runCatching {
                            queries.restoreOneRepMaxIfCurrent(
                                previousOneRepMaxKg =
                                    previousOneRepMaxPerCableKg?.toDouble(),
                                exerciseId = exerciseId,
                                attemptedOneRepMaxKg =
                                    attemptedOneRepMaxPerCableKg.toDouble(),
                            )
                        }
                    }
                    insertedResultId?.let { id ->
                        runCatching { queries.deleteAssessmentResult(id) }
                    }
                    runCatching { workoutRepository.deleteSession(sessionId) }
                }
                if (failure !is CancellationException) {
                    Logger.w(failure) {
                        "Assessment save failed; compensated owned writes"
                    }
                }
                throw failure
            }

            sessionId
        }
    }
}
```

The mutex covers the full logical save, but the prior 1RM snapshot is intentionally read immediately before the exercise write, after the session/result writes, so it cannot become stale merely because those earlier writes took time. The `try` begins before `saveSession`, so cancellation at any write boundary cleans up owned rows. `exerciseWriteAttempted` stays false for session/result/pre-snapshot failures; once true, restoration is still conditional on the SQL compare-and-set. Cleanup is non-cancellable, uses the local SQLDelight query rather than the injected/failing `ExerciseRepository`, and propagates the original failure type and stable message (coroutine stack-trace recovery may copy the exception object).

Replace the two existing save-session tests with the complete unit/profile contract:

```kotlin
@Test
fun `saveAssessmentSession keeps estimate total and stores session and exercise per cable`() = runTest {
    val sessionId = repository.saveAssessmentSession(
        exerciseId = "bench-press",
        exerciseName = "Bench Press",
        estimatedOneRepMaxKg = 100f,
        loadVelocityDataJson = "[]",
        userOverrideKg = null,
        totalReps = 9,
        durationMs = 60_000L,
        weightPerCableKg = 30f,
        profileId = "athlete-a",
    )

    val session = workoutRepository.getSession(sessionId)
    val assessment = repository.getLatestAssessment("bench-press", "athlete-a")
    assertEquals("athlete-a", session?.profileId)
    assertEquals(30f, session?.weightPerCableKg)
    assertEquals(100f, assessment?.estimatedOneRepMaxKg)
    assertNull(assessment?.userOverrideKg)
    assertEquals(sessionId, assessment?.assessmentSessionId)
    assertEquals(50f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}

@Test
fun `saveAssessmentSession keeps override total and updates exercise per cable`() = runTest {
    val sessionId = repository.saveAssessmentSession(
        exerciseId = "bench-press",
        exerciseName = "Bench Press",
        estimatedOneRepMaxKg = 100f,
        loadVelocityDataJson = "[]",
        userOverrideKg = 120f,
        totalReps = 9,
        durationMs = 60_000L,
        weightPerCableKg = 30f,
        profileId = "athlete-a",
    )

    val assessment = repository.getLatestAssessment("bench-press", "athlete-a")
    assertEquals(100f, assessment?.estimatedOneRepMaxKg)
    assertEquals(120f, assessment?.userOverrideKg)
    assertEquals(sessionId, assessment?.assessmentSessionId)
    assertEquals(60f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}
```

Then add explicit profile isolation:

```kotlin
@Test
fun `latest assessment is isolated by explicit profile`() = runTest {
    repository.saveAssessment(
        exerciseId = "bench-press",
        estimatedOneRepMaxKg = 100f,
        loadVelocityDataJson = "[]",
        sessionId = null,
        userOverrideKg = null,
        profileId = "athlete-a",
    )
    repository.saveAssessment(
        exerciseId = "bench-press",
        estimatedOneRepMaxKg = 140f,
        loadVelocityDataJson = "[]",
        sessionId = null,
        userOverrideKg = null,
        profileId = "athlete-b",
    )

    assertEquals(100f, repository.getLatestAssessment("bench-press", "athlete-a")?.estimatedOneRepMaxKg)
    assertEquals(140f, repository.getLatestAssessment("bench-press", "athlete-b")?.estimatedOneRepMaxKg)
}
```

Add an insert-identity regression proving every concurrent `saveAssessment` result belongs to the row/profile it inserted. Use unique profiles so each row can be read back unambiguously:

```kotlin
@Test
fun `direct and session inserts share one mutex and direct saves return their own row IDs`() = runTest {
    val directSaves = (0 until 16).map { index ->
        async(Dispatchers.Default) {
            val profileId = "direct-$index"
            val id = repository.saveAssessment(
                exerciseId = "bench-press",
                estimatedOneRepMaxKg = 100f + index,
                loadVelocityDataJson = "[]",
                sessionId = null,
                userOverrideKg = null,
                profileId = profileId,
            )
            profileId to id
        }
    }
    val sessionSaves = (0 until 16).map { index ->
        async(Dispatchers.Default) {
            saveSession(repository, profileId = "session-$index")
        }
    }

    sessionSaves.awaitAll()
    val saves = directSaves.awaitAll()

    assertEquals(16, saves.map { it.second }.distinct().size)
    saves.forEach { (profileId, returnedId) ->
        assertEquals(
            returnedId,
            repository.getLatestAssessment("bench-press", profileId)?.id,
        )
    }
}
```

Import `kotlinx.coroutines.Dispatchers`, `async`, and `awaitAll`. This test protects the shared mutex around `insertAssessmentResult` plus `lastInsertRowId`; a mutex only in `saveAssessmentSession` is insufficient.

Prove the repository rejects blank ownership before any write:

```kotlin
@Test
fun `blank profile IDs are rejected before assessment writes`() = runTest {
    assertFailsWith<IllegalArgumentException> {
        repository.saveAssessment(
            exerciseId = "bench-press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = null,
            profileId = " ",
        )
    }
    assertFailsWith<IllegalArgumentException> {
        repository.saveAssessmentSession(
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            userOverrideKg = null,
            totalReps = 9,
            durationMs = 60_000L,
            weightPerCableKg = 30f,
            profileId = " ",
        )
    }

    assertNull(repository.getLatestAssessment("bench-press", " "))
    assertEquals(emptyList(), workoutRepository.getAllSessions(" ").first())
}
```

Add deterministic compensation/race coverage. Use this local call helper to keep every save's fixtures identical:

```kotlin
private suspend fun saveSession(
    target: AssessmentRepository,
    profileId: String = "athlete-a",
    overrideTotalKg: Float? = null,
): String = target.saveAssessmentSession(
    exerciseId = "bench-press",
    exerciseName = "Bench Press",
    estimatedOneRepMaxKg = 100f,
    loadVelocityDataJson = "[]",
    userOverrideKg = overrideTotalKg,
    totalReps = 9,
    durationMs = 60_000L,
    weightPerCableKg = 30f,
    profileId = profileId,
)
```

First prove an ordinary post-write failure restores only its own attempted value:

```kotlin
@Test
fun `ordinary post-write failure removes rows and restores prior per-cable 1RM`() = runTest {
    exerciseRepository.updateOneRepMax("bench-press", 40f)
    val failure = IllegalStateException("test failure")
    val failingRepository = repositoryFailingAfterExerciseUpdate(failure)

    val thrown = assertFailsWith<IllegalStateException> {
        saveSession(failingRepository)
    }
    assertEquals(failure.message, thrown.message)

    assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
    assertNull(failingRepository.getLatestAssessment("bench-press", "athlete-a"))
    assertEquals(40f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}
```

Then prove both halves of the guarded compare-and-set policy. A failure before the exercise write must not restore the stale snapshot; a failure after the attempted write must not overwrite a newer external value:

```kotlin
@Test
fun `pre-write failure preserves a concurrent exercise 1RM update`() = runTest {
    exerciseRepository.updateOneRepMax("bench-press", 40f)
    val failure = IllegalStateException("session write failed")
    val failingWorkouts = object : WorkoutRepository by workoutRepository {
        override suspend fun saveSession(session: WorkoutSession) {
            exerciseRepository.updateOneRepMax("bench-press", 55f)
            throw failure
        }
    }
    val target = SqlDelightAssessmentRepository(
        database,
        failingWorkouts,
        exerciseRepository,
    )

    val thrown = assertFailsWith<IllegalStateException> {
        saveSession(target)
    }
    assertEquals(failure.message, thrown.message)

    assertNull(target.getLatestAssessment("bench-press", "athlete-a"))
    assertEquals(55f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}

@Test
fun `CAS compensation preserves newer 1RM after an attempted exercise write`() = runTest {
    exerciseRepository.updateOneRepMax("bench-press", 40f)
    val failure = IllegalStateException("post-write failure")
    val target = repositoryFailingAfterExerciseUpdate(failure) {
        exerciseRepository.updateOneRepMax("bench-press", 55f)
    }

    val thrown = assertFailsWith<IllegalStateException> {
        saveSession(target)
    }
    assertEquals(failure.message, thrown.message)

    assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
    assertNull(target.getLatestAssessment("bench-press", "athlete-a"))
    assertEquals(55f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}
```

Cancellation must be a real structured-concurrency cancellation at a paused post-write seam, not an injected function that merely throws a synthetic `CancellationException`:

```kotlin
@Test
fun `real child cancellation runs NonCancellable compensation and escapes unchanged`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    exerciseRepository.updateOneRepMax("bench-press", 40f)
    val exerciseWriteApplied = CompletableDeferred<Unit>()
    val pausingExercises = object : ExerciseRepository by exerciseRepository {
        override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
            exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
            exerciseWriteApplied.complete(Unit)
            awaitCancellation()
        }
    }
    val target = SqlDelightAssessmentRepository(
        database,
        workoutRepository,
        pausingExercises,
        ioDispatcher = dispatcher,
    )
    val child = async { saveSession(target, overrideTotalKg = 120f) }
    runCurrent()
    exerciseWriteApplied.await()

    val original = CancellationException("test cancellation")
    child.cancel(original)
    runCurrent()
    val thrown = assertFailsWith<CancellationException> { child.await() }
    assertEquals(original.message, thrown.message)

    assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
    assertNull(target.getLatestAssessment("bench-press", "athlete-a"))
    assertEquals(40f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}
```

Finally, deterministically pause a failing save after its exercise write, start a successful save for the same exercise, and prove the repository mutex prevents the second writer from entering until compensation completes:

```kotlin
@Test
fun `failing and successful saves for one exercise serialize snapshot write and compensation`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    exerciseRepository.updateOneRepMax("bench-press", 40f)
    val firstWriteApplied = CompletableDeferred<Unit>()
    val releaseFirstFailure = CompletableDeferred<Unit>()
    val firstFailure = IllegalStateException("first save failed")
    var updateCalls = 0
    val controlledExercises = object : ExerciseRepository by exerciseRepository {
        override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
            updateCalls += 1
            exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
            if (updateCalls == 1) {
                firstWriteApplied.complete(Unit)
                releaseFirstFailure.await()
                throw firstFailure
            }
        }
    }
    val target = SqlDelightAssessmentRepository(
        database,
        workoutRepository,
        controlledExercises,
        ioDispatcher = dispatcher,
    )

    val failing = async {
        runCatching { saveSession(target, profileId = "athlete-a") }
    }
    runCurrent()
    firstWriteApplied.await()
    val succeeding = async {
        saveSession(target, profileId = "athlete-b", overrideTotalKg = 120f)
    }
    runCurrent()
    assertEquals(1, updateCalls, "second save must still be waiting on the mutex")

    releaseFirstFailure.complete(Unit)
    runCurrent()
    val firstThrown = assertIs<IllegalStateException>(
        failing.await().exceptionOrNull(),
    )
    assertEquals(firstFailure.message, firstThrown.message)
    val successfulSessionId = succeeding.await()

    assertEquals(emptyList(), workoutRepository.getAllSessions("athlete-a").first())
    assertEquals(
        successfulSessionId,
        workoutRepository.getAllSessions("athlete-b").first().single().id,
    )
    assertNull(target.getLatestAssessment("bench-press", "athlete-a"))
    assertEquals(
        120f,
        target.getLatestAssessment("bench-press", "athlete-b")?.userOverrideKg,
    )
    assertEquals(60f, exerciseRepository.getExerciseById("bench-press")?.oneRepMaxKg)
}

private fun repositoryFailingAfterExerciseUpdate(
    failure: Throwable,
    afterAttempt: suspend () -> Unit = {},
): SqlDelightAssessmentRepository {
    val failingExercises = object : ExerciseRepository by exerciseRepository {
        override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
            exerciseRepository.updateOneRepMax(exerciseId, oneRepMaxKg)
            afterAttempt()
            throw failure
        }
    }
    return SqlDelightAssessmentRepository(
        database,
        workoutRepository,
        failingExercises,
    )
}
```

Import `CompletableDeferred`, `Dispatchers`, `async`, `awaitAll`, `awaitCancellation`, `StandardTestDispatcher`, `runCurrent`, `CancellationException`, `assertFailsWith`, `assertIs`, `assertNull`, and `kotlinx.coroutines.flow.first`. Assert propagated exception type plus stable message, not referential identity: coroutine stack-trace recovery may copy an exception. The pre-write test proves the guard; the newer-value test proves SQL CAS; the real child cancellation proves `NonCancellable` cleanup and cancellation propagation; the controlled dispatcher test proves snapshot/write/compensation serialization without timing sleeps, and its failing child returns `runCatching` so it cannot cancel the parent test.

- [ ] **Step 8: Run focused tests, compile every caller, and scan removed defaults**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*AssessmentViewModelProfileScopeTest*" --tests "*AssessmentProfileOwnershipTest*" --tests "*NavigationRoutesTest*" --tests "*AssessmentResourceContractTest*" --tests "*AssessmentEngineTest*" --tests "*SqlDelightAssessmentRepositoryTest*" --rerun --console=plain
```

Expected: BUILD SUCCESSFUL. Do not infer that every requested filter matched merely from the task exit code. Prove a positive JUnit XML count for each exact class:

```powershell
$resultRoot = 'shared/build/test-results/testAndroidHostTest'
$documents = @(
    Get-ChildItem $resultRoot -Filter 'TEST-*.xml' |
        ForEach-Object { [xml](Get-Content $_.FullName -Raw) }
)
$expectedClasses = @(
    'com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModelProfileScopeTest',
    'com.devil.phoenixproject.presentation.navigation.AssessmentProfileOwnershipTest',
    'com.devil.phoenixproject.presentation.navigation.NavigationRoutesTest',
    'com.devil.phoenixproject.presentation.screen.AssessmentResourceContractTest',
    'com.devil.phoenixproject.domain.assessment.AssessmentEngineTest',
    'com.devil.phoenixproject.data.repository.SqlDelightAssessmentRepositoryTest'
)
foreach ($className in $expectedClasses) {
    $count = 0
    foreach ($document in $documents) {
        $count += @(
            $document.SelectNodes("//testcase[@classname='$className']")
        ).Count
    }
    if ($count -lt 1) {
        throw "No executed test cases found for $className"
    }
    Write-Output "$className : $count"
}
```

Expected: every exact class prints a count greater than zero. The preceding Gradle success establishes zero failures/errors; this XML gate establishes that no filter silently matched nothing.

Compile common production/test call sites for both configured platform families:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileAndroidMain :shared:compileAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
```

Expected: BUILD SUCCESSFUL. The configured iOS tasks are `compileKotlinIosArm64` and `compileTestKotlinIosArm64`; there is no `compileKotlinIosSimulatorArm64` task in this repository.

Run the exact ownership/default scan:

```powershell
$assessmentApi = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt'
$defaults = rg -n 'profileId: String = "default"' $assessmentApi
if ($LASTEXITCODE -eq 0) {
    $defaults
    throw 'Assessment profile defaults remain'
}
rg -n 'AssessmentResultEntity\(' shared/src --glob '*.kt'
rg -n '\b(saveAssessment|saveAssessmentSession|getAssessmentsByExercise|getLatestAssessment|acceptResult|AssessmentWizardScreen)\s*\(' shared/src --glob '*.kt'
$unsafeStartingLoad = rg -n 'exercise\.oneRepMaxKg' shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt
if ($LASTEXITCODE -eq 0) {
    $unsafeStartingLoad
    throw 'Assessment still reads the unscoped global exercise 1RM'
}
$profilelessRoutes = rg -n --pcre2 '\bnavigate\s*\(\s*(?:route\s*=\s*)?NavigationRoutes\.StrengthAssessment(?:Picker)?\.route\s*[,)]' shared/src/commonMain/kotlin shared/src/androidMain/kotlin shared/src/iosMain/kotlin --glob '*.kt'
if ($LASTEXITCODE -eq 0) {
    $profilelessRoutes
    throw 'A profile-less assessment navigation call remains'
}
rg -n 'StrengthAssessment(Picker)?(\.createRoute)?' shared/src --glob '*.kt'
```

Expected: all guards exit normally. No assessment API/entity profile default, global-exercise 1RM read, or profile-less route call remains. The final route inventory includes the two route definitions, the Ready-gated `NavGraph` factories, and focused tests only; Analytics and Exercise Detail pass explicit Ready IDs through callbacks rather than navigating directly.

- [ ] **Step 9: Commit the assessment ownership slice**

```powershell
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModelProfileScopeTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/AssessmentProfileOwnershipTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentResourceContractTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngineTest.kt shared/src/commonMain/composeResources/values/strings.xml shared/src/commonMain/composeResources/values-nl/strings.xml shared/src/commonMain/composeResources/values-de/strings.xml shared/src/commonMain/composeResources/values-es/strings.xml shared/src/commonMain/composeResources/values-fr/strings.xml
git commit -m "fix: scope strength assessments to active profile"
```

---

### Task 2: Add Bounded Exercise History Reads and the Shared Current-1RM Resolver

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCaseTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailOneRepMaxLoadTest.kt`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeVelocityOneRepMaxRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt`

**Interfaces:**
- Consumes: Task 1's explicit profile-safe Assessment APIs and Ready-gated `ExerciseDetailScreen.assessmentProfileId`; `VelocityOneRepMaxRepository.getLatestPassing`; `OneRepMaxCalculator.estimate`.
- Produces: `MAX_RECENT_EXERCISE_SESSIONS = 5`, strict `WorkoutRepository.getMostRecentCompletedExerciseId`, bounded `WorkoutRepository.getRecentCompletedSessionsForExercise`, `WorkoutSession.estimatedOneRepMaxPerCableOrNull()`, and `ResolveCurrentOneRepMaxUseCase.invoke(exerciseId, profileId): CurrentOneRepMax?` for Tasks 5 and 6.
- Error contract: repository validation failures and source read failures propagate from the resolver, because swallowing a failed higher-priority read could incorrectly select a lower source. Exercise Detail catches only ordinary failures into its own 1RM-card state; it rethrows cancellation and leaves charts/history usable.

- [ ] **Step 1: Write failing SQLDelight repository tests for eligibility and limit**

Add three tests using the test class's existing database/repository setup. The first two deliberately create equal timestamps so the secondary ID ordering is observable:

```kotlin
@Test
fun `recent completed sessions are profile exercise scoped newest first and limited`() = runTest {
    repository.saveSession(workoutSession("old", "a", "bench", 10L, workingReps = 5))
    repository.saveSession(workoutSession("total-only", "a", "bench", 20L, workingReps = 0, totalReps = 4))
    repository.saveSession(workoutSession("a-tie", "a", "bench", 30L, workingReps = 3))
    repository.saveSession(workoutSession("z-tie", "a", "bench", 30L, workingReps = 3))
    repository.saveSession(workoutSession("wrong-exercise", "a", "squat", 50L, workingReps = 5))
    repository.saveSession(workoutSession("wrong-profile", "b", "bench", 60L, workingReps = 5))
    repository.saveSession(workoutSession("zero", "a", "bench", 70L, workingReps = 0, totalReps = 0))
    repository.saveSession(workoutSession("deleted", "a", "bench", 80L, workingReps = 5))
    database.vitruvianDatabaseQueries.softDeleteSession(
        id = "deleted",
        deletedAt = 81L,
        updatedAt = 81L,
    )

    val result = repository.getRecentCompletedSessionsForExercise(
        exerciseId = "bench",
        profileId = "a",
        limit = 3,
    )

    assertEquals(listOf("z-tie", "a-tie", "total-only"), result.map { it.id })
}

@Test
fun `most recent completed exercise uses deterministic live eligible row`() = runTest {
    repository.saveSession(workoutSession("a-tie", "a", "bench", 30L, workingReps = 5))
    repository.saveSession(workoutSession("z-tie", "a", "squat", 30L, workingReps = 0, totalReps = 2))
    repository.saveSession(workoutSession("ghost", "a", "deadlift", 50L, workingReps = 0, totalReps = 0))
    repository.saveSession(workoutSession("blank-exercise", "a", " ", 55L, workingReps = 5))
    repository.saveSession(workoutSession("other-profile", "b", "row", 60L, workingReps = 5))
    repository.saveSession(workoutSession("deleted", "a", "press", 70L, workingReps = 5))
    database.vitruvianDatabaseQueries.softDeleteSession(
        id = "deleted",
        deletedAt = 71L,
        updatedAt = 71L,
    )

    assertEquals("squat", repository.getMostRecentCompletedExerciseId("a"))
}

@Test
fun `bounded reads reject blank ownership and limits outside one through five`() = runTest {
    assertFailsWith<IllegalArgumentException> {
        repository.getRecentCompletedSessionsForExercise(" ", "a", 1)
    }
    assertFailsWith<IllegalArgumentException> {
        repository.getRecentCompletedSessionsForExercise("bench", " ", 1)
    }
    listOf(0, -1, MAX_RECENT_EXERCISE_SESSIONS + 1).forEach { invalidLimit ->
        assertFailsWith<IllegalArgumentException> {
            repository.getRecentCompletedSessionsForExercise("bench", "a", invalidLimit)
        }
    }
    assertFailsWith<IllegalArgumentException> {
        repository.getMostRecentCompletedExerciseId(" ")
    }
}
```

Import `MAX_RECENT_EXERCISE_SESSIONS` and `kotlin.test.assertFailsWith`. The deleted rows must be created normally and tombstoned through the existing `softDeleteSession(id, deletedAt, updatedAt)` query so the SQL `deletedAt IS NULL` predicate, rather than test fixture omission, is exercised.

Add this local helper using the current `WorkoutSession` constructor defaults:

```kotlin
private fun workoutSession(
    id: String,
    profileId: String,
    exerciseId: String,
    timestamp: Long,
    workingReps: Int,
    totalReps: Int = workingReps,
): WorkoutSession = WorkoutSession(
    id = id,
    timestamp = timestamp,
    mode = "OldSchool",
    reps = totalReps,
    weightPerCableKg = 40f,
    duration = 1_000L,
    totalReps = totalReps,
    workingReps = workingReps,
    exerciseId = exerciseId,
    exerciseName = exerciseId,
    profileId = profileId,
)
```

- [ ] **Step 2: Run the query tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SqlDelightWorkoutRepositoryTest*" --console=plain
```

Expected: FAIL to compile because both repository methods are absent.

- [ ] **Step 3: Add the bounded SQL queries and repository contract**

Add to `VitruvianDatabase.sq` next to other WorkoutSession reads:

```sql
selectRecentCompletedSessionsForExercise:
SELECT * FROM WorkoutSession
WHERE profile_id = :profileId
  AND exerciseId = :exerciseId
  AND deletedAt IS NULL
  AND (workingReps > 0 OR totalReps > 0)
ORDER BY timestamp DESC, id DESC
LIMIT :limit;

selectMostRecentCompletedExerciseId:
SELECT exerciseId FROM WorkoutSession
WHERE profile_id = :profileId
  AND exerciseId IS NOT NULL
  AND TRIM(exerciseId) != ''
  AND deletedAt IS NULL
  AND (workingReps > 0 OR totalReps > 0)
ORDER BY timestamp DESC, id DESC
LIMIT 1;
```

Add the shared cap and methods to `WorkoutRepository.kt`:

```kotlin
const val MAX_RECENT_EXERCISE_SESSIONS = 5

suspend fun getRecentCompletedSessionsForExercise(
    exerciseId: String,
    profileId: String,
    limit: Int = MAX_RECENT_EXERCISE_SESSIONS,
): List<WorkoutSession>

suspend fun getMostRecentCompletedExerciseId(profileId: String): String?
```

- [ ] **Step 4: Implement production and fake repository methods**

Production:

```kotlin
override suspend fun getRecentCompletedSessionsForExercise(
    exerciseId: String,
    profileId: String,
    limit: Int,
): List<WorkoutSession> {
    require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
    require(profileId.isNotBlank()) { "profileId must not be blank" }
    require(limit in 1..MAX_RECENT_EXERCISE_SESSIONS) {
        "limit must be in 1..$MAX_RECENT_EXERCISE_SESSIONS"
    }
    return withContext(Dispatchers.IO) {
        queries.selectRecentCompletedSessionsForExercise(
            profileId = profileId,
            exerciseId = exerciseId,
            limit = limit.toLong(),
            mapper = ::mapToSession,
        ).executeAsList()
    }
}

override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? {
    require(profileId.isNotBlank()) { "profileId must not be blank" }
    return withContext(Dispatchers.IO) {
        queries.selectMostRecentCompletedExerciseId(profileId).executeAsOneOrNull()
    }
}
```

Import `MAX_RECENT_EXERCISE_SESSIONS`. Validate before dispatching so the production and fake contracts fail identically without touching storage.

Fake: inside `FakeWorkoutRepository`, add the nested request type, read-failure controls, and request recording for resolver/error-bound tests, then mirror the same guards and deterministic ordering:

```kotlin
data class RecentCompletedRequest(
    val exerciseId: String,
    val profileId: String,
    val limit: Int,
)

val recentCompletedRequests = mutableListOf<RecentCompletedRequest>()
var recentCompletedFailure: Throwable? = null
var mostRecentCompletedExerciseFailure: Throwable? = null

override suspend fun getRecentCompletedSessionsForExercise(
    exerciseId: String,
    profileId: String,
    limit: Int,
): List<WorkoutSession> {
    require(exerciseId.isNotBlank())
    require(profileId.isNotBlank())
    require(limit in 1..MAX_RECENT_EXERCISE_SESSIONS)
    recentCompletedRequests += RecentCompletedRequest(exerciseId, profileId, limit)
    recentCompletedFailure?.let { throw it }
    return sessions.values
        .asSequence()
        .filter { it.profileId == profileId && it.exerciseId == exerciseId }
        .filter { it.workingReps > 0 || it.totalReps > 0 }
        .sortedWith(
            compareByDescending<WorkoutSession> { it.timestamp }
                .thenByDescending { it.id },
        )
        .take(limit)
        .toList()
}

override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? {
    require(profileId.isNotBlank())
    mostRecentCompletedExerciseFailure?.let { throw it }
    return sessions.values
        .asSequence()
        .filter { it.profileId == profileId }
        .filter { it.workingReps > 0 || it.totalReps > 0 }
        .filter { !it.exerciseId.isNullOrBlank() }
        .sortedWith(
            compareByDescending<WorkoutSession> { it.timestamp }
                .thenByDescending { it.id },
        )
        .firstOrNull()
        ?.exerciseId
}
```

Also clear `recentCompletedRequests` and reset both failures to null inside the fake's existing `reset()`. The fake has no `deletedAt` field on `WorkoutSession`; its delete helper removes rows, while the SQLDelight tests own tombstone filtering. Do not silently accept `take(0)` or `take(-1)` in the fake.

- [ ] **Step 5: Generate SQLDelight interfaces and make query tests green**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:testAndroidHostTest --tests "*SqlDelightWorkoutRepositoryTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; all three new repository tests pass, including deterministic ties, tombstones, total-reps-only eligibility, and validation parity.

- [ ] **Step 6: Write failing resolver precedence and normalization tests**

First extend the Task 1/read fakes with failures that are thrown before returning data:

```kotlin
// FakeVelocityOneRepMaxRepository
var latestPassingFailure: Throwable? = null

override suspend fun getLatestPassing(
    exerciseId: String,
    profileId: String,
): VelocityOneRepMaxEntity? {
    latestPassingFailure?.let { throw it }
    return latestPassing?.takeIf {
        it.exerciseId == exerciseId && it.profileId == profileId
    }
}

// FakeAssessmentRepository
var latestAssessmentFailure: Throwable? = null

override suspend fun getLatestAssessment(
    exerciseId: String,
    profileId: String,
): AssessmentResultEntity? {
    latestAssessmentFailure?.let { throw it }
    return assessments
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .maxByOrNull { it.createdAt }
}
```

Do not add fallback behavior to these fakes: failures must let resolver and Exercise Detail tests prove their explicit error contracts.

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest

class ResolveCurrentOneRepMaxUseCaseTest {
    private val velocity = FakeVelocityOneRepMaxRepository()
    private val assessments = FakeAssessmentRepository()
    private val workouts = FakeWorkoutRepository()
    private val resolver = ResolveCurrentOneRepMaxUseCase(velocity, assessments, workouts)

    @Test
    fun `velocity wins over assessment and session`() = runTest {
        seedAssessment(totalKg = 120f)
        seedSession(perCableKg = 50f, reps = 5)
        velocity.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench",
            estimatedPerCableKg = 70f,
            mvtUsedMs = 0.3f,
            r2 = 0.95f,
            distinctLoads = 3,
            passedQualityGate = true,
            computedAt = 30L,
            profileId = "athlete-a",
        )

        assertEquals(
            CurrentOneRepMax(70f, CurrentOneRepMaxSource.VELOCITY, 30L),
            resolver("bench", "athlete-a"),
        )
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `assessment override total is normalized to per cable`() = runTest {
        assessments.saveAssessment(
            exerciseId = "bench",
            estimatedOneRepMaxKg = 120f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = 140f,
            profileId = "athlete-a",
        )

        assertEquals(70f, resolver("bench", "athlete-a")?.perCableKg)
        assertEquals(CurrentOneRepMaxSource.ASSESSMENT, resolver("bench", "athlete-a")?.source)
    }

    @Test
    fun `invalid assessment override falls through to its valid estimate`() = runTest {
        seedAssessment(totalKg = 120f, overrideKg = Float.NaN)

        assertEquals(
            CurrentOneRepMax(60f, CurrentOneRepMaxSource.ASSESSMENT, 1L),
            resolver("bench", "athlete-a"),
        )
    }

    @Test
    fun `invalid velocity falls through to valid assessment`() = runTest {
        seedAssessment(totalKg = 120f)
        velocity.latestPassing = velocityEstimate(
            perCableKg = Float.POSITIVE_INFINITY,
            profileId = "athlete-a",
            exerciseId = "bench",
        )

        assertEquals(CurrentOneRepMaxSource.ASSESSMENT, resolver("bench", "athlete-a")?.source)
        assertEquals(60f, resolver("bench", "athlete-a")?.perCableKg)
    }

    @Test
    fun `session fallback uses canonical hybrid and never another profile`() = runTest {
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-b", timestamp = 40L)
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-a", timestamp = 20L)

        val result = resolver("bench", "athlete-a")

        assertEquals(112.5f, result?.perCableKg)
        assertEquals(CurrentOneRepMaxSource.SESSION, result?.source)
        assertEquals(20L, result?.measuredAt)
    }

    @Test
    fun `session fallback skips newest invalid estimate and uses newest valid bounded row`() = runTest {
        seedSession(perCableKg = 100f, reps = 5, timestamp = 20L)
        seedSession(perCableKg = Float.NaN, reps = 5, timestamp = 30L)

        val result = resolver("bench", "athlete-a")

        assertEquals(112.5f, result?.perCableKg)
        assertEquals(20L, result?.measuredAt)
        assertEquals(
            FakeWorkoutRepository.RecentCompletedRequest(
                exerciseId = "bench",
                profileId = "athlete-a",
                limit = MAX_RECENT_EXERCISE_SESSIONS,
            ),
            workouts.recentCompletedRequests.single(),
        )
    }

    @Test
    fun `session helper uses total reps fallback and rejects invalid load or reps`() {
        val base = session(perCableKg = 100f, workingReps = 0, totalReps = 5)

        assertEquals(112.5f, base.estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(weightPerCableKg = Float.NaN).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(weightPerCableKg = 0f).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(workingReps = 0, totalReps = 0).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(workingReps = -1, totalReps = -1).estimatedOneRepMaxPerCableOrNull())
    }

    @Test
    fun `wrong profile and exercise at higher sources cannot block current session`() = runTest {
        velocity.latestPassing = velocityEstimate(
            perCableKg = 200f,
            profileId = "athlete-b",
            exerciseId = "squat",
        )
        seedAssessment(
            totalKg = 400f,
            profileId = "athlete-b",
            exerciseId = "squat",
        )
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-a", timestamp = 20L)

        assertEquals(CurrentOneRepMaxSource.SESSION, resolver("bench", "athlete-a")?.source)
    }

    @Test
    fun `only five newest sessions are inspected and all invalid sources return null`() = runTest {
        velocity.latestPassing = velocityEstimate(perCableKg = Float.NaN)
        seedAssessment(totalKg = -1f, overrideKg = Float.POSITIVE_INFINITY)
        seedSession(perCableKg = 100f, reps = 5, timestamp = 1L)
        repeat(MAX_RECENT_EXERCISE_SESSIONS) { index ->
            seedSession(perCableKg = Float.NaN, reps = 5, timestamp = 10L + index)
        }

        assertNull(resolver("bench", "athlete-a"))
        assertEquals(MAX_RECENT_EXERCISE_SESSIONS, workouts.recentCompletedRequests.single().limit)
    }

    @Test
    fun `blank IDs fail before reading any source`() = runTest {
        assertFailsWith<IllegalArgumentException> { resolver(" ", "athlete-a") }
        assertFailsWith<IllegalArgumentException> { resolver("bench", " ") }
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `fake bounded read validation mirrors production`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            workouts.getRecentCompletedSessionsForExercise(" ", "athlete-a", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            workouts.getRecentCompletedSessionsForExercise("bench", " ", 1)
        }
        listOf(0, MAX_RECENT_EXERCISE_SESSIONS + 1).forEach { limit ->
            assertFailsWith<IllegalArgumentException> {
                workouts.getRecentCompletedSessionsForExercise("bench", "athlete-a", limit)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            workouts.getMostRecentCompletedExerciseId(" ")
        }
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `ordinary higher source failure propagates instead of selecting a lower source`() = runTest {
        seedAssessment(totalKg = 120f)
        velocity.latestPassingFailure = IllegalStateException("velocity unavailable")

        val thrown = assertFailsWith<IllegalStateException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("velocity unavailable", thrown.message)
    }

    @Test
    fun `cancellation from any source propagates`() = runTest {
        assessments.latestAssessmentFailure = CancellationException("profile changed")

        val thrown = assertFailsWith<CancellationException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("profile changed", thrown.message)
    }

    @Test
    fun `session read failure propagates when higher sources are absent`() = runTest {
        workouts.recentCompletedFailure = IllegalStateException("history unavailable")

        val thrown = assertFailsWith<IllegalStateException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("history unavailable", thrown.message)
    }

    private suspend fun seedAssessment(
        totalKg: Float,
        overrideKg: Float? = null,
        exerciseId: String = "bench",
        profileId: String = "athlete-a",
    ) {
        assessments.saveAssessment(
            exerciseId,
            totalKg,
            "[]",
            null,
            overrideKg,
            profileId,
        )
    }

    private fun seedSession(
        perCableKg: Float,
        reps: Int,
        profileId: String = "athlete-a",
        timestamp: Long = 10L,
    ) {
        workouts.addSession(
            session(
                perCableKg = perCableKg,
                workingReps = reps,
                totalReps = reps,
                profileId = profileId,
                timestamp = timestamp,
            ),
        )
    }

    private fun session(
        perCableKg: Float,
        workingReps: Int,
        totalReps: Int,
        profileId: String = "athlete-a",
        exerciseId: String = "bench",
        timestamp: Long = 10L,
    ) = WorkoutSession(
        id = "$profileId-$exerciseId-$timestamp-$perCableKg",
        timestamp = timestamp,
        mode = "OldSchool",
        reps = totalReps,
        weightPerCableKg = perCableKg,
        duration = 1_000L,
        totalReps = totalReps,
        workingReps = workingReps,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        profileId = profileId,
    )

    private fun velocityEstimate(
        perCableKg: Float,
        profileId: String = "athlete-a",
        exerciseId: String = "bench",
    ) = VelocityOneRepMaxEntity(
        id = 1L,
        exerciseId = exerciseId,
        estimatedPerCableKg = perCableKg,
        mvtUsedMs = 0.3f,
        r2 = 0.95f,
        distinctLoads = 3,
        passedQualityGate = true,
        computedAt = 30L,
        profileId = profileId,
    )
}
```

- [ ] **Step 7: Run resolver tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --console=plain
```

Expected: FAIL to compile because the result/source/use-case types do not exist.

- [ ] **Step 8: Implement the shared resolver**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.OneRepMaxCalculator

enum class CurrentOneRepMaxSource {
    VELOCITY,
    ASSESSMENT,
    SESSION,
}

data class CurrentOneRepMax(
    val perCableKg: Float,
    val source: CurrentOneRepMaxSource,
    val measuredAt: Long,
)

fun WorkoutSession.estimatedOneRepMaxPerCableOrNull(): Float? {
    val load = weightPerCableKg.takeIf { it.isFinite() && it > 0f } ?: return null
    val reps = workingReps.takeIf { it > 0 }
        ?: totalReps.takeIf { it > 0 }
        ?: return null
    return OneRepMaxCalculator.estimate(load, reps)
        .takeIf { it.isFinite() && it > 0f }
}

private fun Float.validPositiveOrNull(): Float? =
    takeIf { it.isFinite() && it > 0f }

class ResolveCurrentOneRepMaxUseCase(
    private val velocityRepository: VelocityOneRepMaxRepository,
    private val assessmentRepository: AssessmentRepository,
    private val workoutRepository: WorkoutRepository,
) {
    suspend operator fun invoke(exerciseId: String, profileId: String): CurrentOneRepMax? {
        require(exerciseId.isNotBlank())
        require(profileId.isNotBlank())

        velocityRepository.getLatestPassing(exerciseId, profileId)
            ?.takeIf {
                it.exerciseId == exerciseId &&
                    it.profileId == profileId &&
                    it.passedQualityGate
            }
            ?.let { estimate ->
                estimate.estimatedPerCableKg.validPositiveOrNull()
                    ?.let { estimate to it }
            }
            ?.let {
                return CurrentOneRepMax(
                    perCableKg = it.second,
                    source = CurrentOneRepMaxSource.VELOCITY,
                    measuredAt = it.first.computedAt,
                )
            }

        assessmentRepository.getLatestAssessment(exerciseId, profileId)?.let { assessment ->
            if (assessment.exerciseId == exerciseId && assessment.profileId == profileId) {
                val validTotalKg = assessment.userOverrideKg?.validPositiveOrNull()
                    ?: assessment.estimatedOneRepMaxKg.validPositiveOrNull()
                val perCableKg = validTotalKg?.div(2f)?.validPositiveOrNull()
                if (perCableKg != null) {
                    return CurrentOneRepMax(
                        perCableKg = perCableKg,
                        source = CurrentOneRepMaxSource.ASSESSMENT,
                        measuredAt = assessment.createdAt,
                    )
                }
            }
        }

        workoutRepository.getRecentCompletedSessionsForExercise(
            exerciseId = exerciseId,
            profileId = profileId,
            limit = MAX_RECENT_EXERCISE_SESSIONS,
        ).forEach { session ->
            if (session.exerciseId == exerciseId && session.profileId == profileId) {
                val estimate = session.estimatedOneRepMaxPerCableOrNull()
                if (estimate != null) {
                    return CurrentOneRepMax(
                        perCableKg = estimate,
                        source = CurrentOneRepMaxSource.SESSION,
                        measuredAt = session.timestamp,
                    )
                }
            }
        }
        return null
    }
}
```

There is intentionally no `try/catch` in the resolver. A failure at velocity or assessment means precedence is unknown; a session read failure means fallback is unavailable. All ordinary failures and `CancellationException` therefore propagate. Invalid values are data fallthrough, not exceptions. The shared session extension is the only session-estimate calculation used by both this resolver and Exercise Detail.

- [ ] **Step 9: Register the use case and make resolver tests green**

Add to `DomainModule.kt`:

```kotlin
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase

single { ResolveCurrentOneRepMaxUseCase(get(), get(), get()) }
```

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --tests "*KoinModuleVerifyTest*" --rerun --console=plain
```

Expected: BUILD SUCCESSFUL; the resolver suite and existing `KoinModuleVerifyTest.verifyAppModule` pass without adding the use case to `extraTypes`. It is an application-owned binding whose three repositories already resolve from `appModule`.

- [ ] **Step 10: Write failing Exercise Detail latest-request, cancellation, and error-isolation tests**

Create `ExerciseDetailOneRepMaxLoadTest.kt`:

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.usecase.CurrentOneRepMax
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMaxSource
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ExerciseDetailOneRepMaxLoadTest {
    private val requestA = ExerciseDetailOneRepMaxRequest("bench", "athlete-a", emptyList())
    private val requestB = ExerciseDetailOneRepMaxRequest("bench", "athlete-b", emptyList())
    private val resultA = CurrentOneRepMax(50f, CurrentOneRepMaxSource.SESSION, 10L)
    private val resultB = CurrentOneRepMax(60f, CurrentOneRepMaxSource.ASSESSMENT, 20L)

    @Test
    fun `late completion from A cannot overwrite Ready B`() = runTest {
        val gate = ExerciseDetailOneRepMaxLoadGate()
        val states = mutableListOf<ExerciseDetailOneRepMaxState>()
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val loadA = async {
            loadExerciseDetailOneRepMax(
                request = requestA,
                gate = gate,
                resolve = { _, _ ->
                    aStarted.complete(Unit)
                    releaseA.await()
                    resultA
                },
                publish = states::add,
            )
        }
        runCurrent()
        aStarted.await()

        loadExerciseDetailOneRepMax(
            request = requestB,
            gate = gate,
            resolve = { _, _ -> resultB },
            publish = states::add,
        )
        releaseA.complete(Unit)
        loadA.await()

        assertEquals(
            listOf(
                ExerciseDetailOneRepMaxState.Loading,
                ExerciseDetailOneRepMaxState.Loading,
                ExerciseDetailOneRepMaxState.Ready(resultB),
            ),
            states,
        )
    }

    @Test
    fun `late error from A cannot replace Ready B with Failed`() = runTest {
        val gate = ExerciseDetailOneRepMaxLoadGate()
        val states = mutableListOf<ExerciseDetailOneRepMaxState>()
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val loadA = async {
            loadExerciseDetailOneRepMax(
                request = requestA,
                gate = gate,
                resolve = { _, _ ->
                    aStarted.complete(Unit)
                    releaseA.await()
                    error("late A failure")
                },
                publish = states::add,
            )
        }
        runCurrent()
        aStarted.await()
        loadExerciseDetailOneRepMax(
            request = requestB,
            gate = gate,
            resolve = { _, _ -> resultB },
            publish = states::add,
        )
        releaseA.complete(Unit)
        loadA.await()

        assertEquals(ExerciseDetailOneRepMaxState.Ready(resultB), states.last())
        assertFalse(states.contains(ExerciseDetailOneRepMaxState.Failed))
    }

    @Test
    fun `cancellation escapes and is never rendered as failure`() = runTest {
        val states = mutableListOf<ExerciseDetailOneRepMaxState>()
        val started = CompletableDeferred<Unit>()
        val child = async {
            loadExerciseDetailOneRepMax(
                request = requestA,
                gate = ExerciseDetailOneRepMaxLoadGate(),
                resolve = { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                },
                publish = states::add,
            )
        }
        runCurrent()
        started.await()

        val cause = CancellationException("profile switched")
        child.cancel(cause)
        val thrown = assertFailsWith<CancellationException> { child.await() }

        assertEquals(cause.message, thrown.message)
        assertEquals(listOf(ExerciseDetailOneRepMaxState.Loading), states)
    }

    @Test
    fun `ordinary current-request error fails only the one rep max branch`() = runTest {
        val states = mutableListOf<ExerciseDetailOneRepMaxState>()

        loadExerciseDetailOneRepMax(
            request = requestA,
            gate = ExerciseDetailOneRepMaxLoadGate(),
            resolve = { _, _ -> error("resolver unavailable") },
            publish = states::add,
        )

        assertEquals(
            listOf(
                ExerciseDetailOneRepMaxState.Loading,
                ExerciseDetailOneRepMaxState.Failed,
            ),
            states,
        )
    }

    @Test
    fun `screen has one resolver path and no legacy profile or velocity read`() {
        val source = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt",
        )
        assertNotNull(source)
        assertContains(source, "assessmentProfileId")
        assertContains(source, "loadExerciseDetailOneRepMax(")
        assertContains(source, "estimatedOneRepMaxPerCableOrNull()")
        assertContains(source, "catch (cancellation: CancellationException)")
        assertContains(source, "catch (_: Exception)")
        assertFalse(source.contains("catch (_: Throwable)"))
        assertContains(source, "VolumeChartCard(")
        assertContains(source, "items(exerciseSessions")
        assertFalse(source.contains("viewModel.activeProfileId"))
        assertFalse(source.contains("velocityOneRepMaxRepository"))
        assertFalse(source.contains("VelocityOneRepMaxEntity"))
    }
}
```

This uses a real canceled child and compares cancellation type/message rather than object identity. The stale test intentionally leaves A running while B completes; only the current token may publish.

- [ ] **Step 11: Replace Exercise Detail's split hero with a token-gated shared-resolver load**

Add these internal presentation types/functions in `ExerciseDetailScreen.kt` above the composable:

```kotlin
internal data class ExerciseDetailOneRepMaxRequest(
    val exerciseId: String,
    val profileId: String,
    val completedSessions: List<WorkoutSession>,
)

internal data class ExerciseDetailOneRepMaxLoadToken(
    val generation: Long,
    val request: ExerciseDetailOneRepMaxRequest,
)

internal class ExerciseDetailOneRepMaxLoadGate {
    private var generation = 0L
    private var active: ExerciseDetailOneRepMaxLoadToken? = null

    fun begin(request: ExerciseDetailOneRepMaxRequest): ExerciseDetailOneRepMaxLoadToken =
        ExerciseDetailOneRepMaxLoadToken(++generation, request).also { active = it }

    fun isCurrent(token: ExerciseDetailOneRepMaxLoadToken): Boolean = active == token
}

internal sealed interface ExerciseDetailOneRepMaxState {
    data object Loading : ExerciseDetailOneRepMaxState
    data class Ready(val resolution: CurrentOneRepMax?) : ExerciseDetailOneRepMaxState
    data object Failed : ExerciseDetailOneRepMaxState
}

internal suspend fun loadExerciseDetailOneRepMax(
    request: ExerciseDetailOneRepMaxRequest,
    gate: ExerciseDetailOneRepMaxLoadGate,
    resolve: suspend (exerciseId: String, profileId: String) -> CurrentOneRepMax?,
    publish: (ExerciseDetailOneRepMaxState) -> Unit,
) {
    val token = gate.begin(request)
    publish(ExerciseDetailOneRepMaxState.Loading)
    try {
        val resolution = resolve(request.exerciseId, request.profileId)
        if (gate.isCurrent(token)) {
            publish(ExerciseDetailOneRepMaxState.Ready(resolution))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        if (gate.isCurrent(token)) {
            publish(ExerciseDetailOneRepMaxState.Failed)
        }
    }
}
```

The gate is confined to the Compose effect context; it is a latest-request token, not a cross-thread lock. The request includes the exact completed-session snapshot so a save/edit for the same exercise/profile creates a new generation. In `ExerciseDetailScreen`:

1. Inject `ResolveCurrentOneRepMaxUseCase`. Delete the `VelocityOneRepMaxEntity` import, direct `velocityOneRepMaxRepository.getLatestPassing` state/effect, and `viewModel.activeProfileId` collection.
2. Treat Task 1's nullable `assessmentProfileId` parameter as the only Ready profile source. While it is null, render no old resolution and make no resolver call.
3. Filter local sessions by exact profile, exercise, and completion, then order ties deterministically:

```kotlin
val profileId = assessmentProfileId
val exerciseSessions = remember(allWorkoutSessions, exerciseId, profileId) {
    if (profileId == null) {
        emptyList()
    } else {
        allWorkoutSessions
            .asSequence()
            .filter { it.profileId == profileId && it.exerciseId == exerciseId }
            .filter { it.workingReps > 0 || it.totalReps > 0 }
            .sortedWith(
                compareByDescending<WorkoutSession> { it.timestamp }
                    .thenByDescending { it.id },
            )
            .toList()
    }
}
```

4. Key the state holder itself by the full request, clearing stale UI synchronously before the new effect runs; use the load gate as a second defense against non-cooperative old reads:

```kotlin
val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase = koinInject()
val loadGate = remember { ExerciseDetailOneRepMaxLoadGate() }
val request = remember(exerciseId, profileId, exerciseSessions) {
    profileId?.let {
        ExerciseDetailOneRepMaxRequest(
            exerciseId = exerciseId,
            profileId = it,
            completedSessions = exerciseSessions,
        )
    }
}
val stateHolder = remember(request) {
    mutableStateOf<ExerciseDetailOneRepMaxState>(
        ExerciseDetailOneRepMaxState.Loading,
    )
}
val oneRepMaxState by stateHolder

LaunchedEffect(request, resolveCurrentOneRepMax) {
    val currentRequest = request ?: return@LaunchedEffect
    loadExerciseDetailOneRepMax(
        request = currentRequest,
        gate = loadGate,
        resolve = resolveCurrentOneRepMax::invoke,
        publish = { stateHolder.value = it },
    )
}
```

5. Use `WorkoutSession.estimatedOneRepMaxPerCableOrNull()` for chart and delta data. Never duplicate working-reps/load validation:

```kotlin
val validSessionEstimatesNewestFirst = remember(exerciseSessions) {
    exerciseSessions.mapNotNull { session ->
        session.estimatedOneRepMaxPerCableOrNull()
            ?.let { estimate -> session.timestamp to estimate }
    }
}
val oneRepMaxData = remember(validSessionEstimatesNewestFirst) {
    validSessionEstimatesNewestFirst.reversed()
}
val previousSessionOneRepMax =
    validSessionEstimatesNewestFirst.getOrNull(1)?.second
```

Change `OneRepMaxCard` to accept `state: ExerciseDetailOneRepMaxState` and `previousSessionOneRepMax`. Extract a resolution only from `Ready`. Show a delta only when its source is `SESSION`; `Loading` and `Failed` affect only this card and must not hide the history list/charts or assessment button. Keep the assessment action visible regardless of `vbtEnabled`. Task 3 replaces temporary source/empty/error labels with localized resources.

- [ ] **Step 12: Run exact focused suites, inspect XML counts, compile callers, and scan legacy paths**

Run generation plus focused tests:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:testAndroidHostTest --tests "*SqlDelightWorkoutRepositoryTest*" --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --tests "*ExerciseDetailOneRepMaxLoadTest*" --tests "*KoinModuleVerifyTest*" --tests "*VelocityOneRepMaxRepositoryTest*" --tests "*OneRepMaxCalculatorTest*" --rerun --console=plain
```

Expected: BUILD SUCCESSFUL. Verify every exact requested class executed at least one JUnit case:

```powershell
$resultRoot = 'shared/build/test-results/testAndroidHostTest'
$documents = @(
    Get-ChildItem $resultRoot -Filter 'TEST-*.xml' |
        ForEach-Object { [xml](Get-Content $_.FullName -Raw) }
)
$expectedClasses = @(
    'com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepositoryTest',
    'com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCaseTest',
    'com.devil.phoenixproject.presentation.screen.ExerciseDetailOneRepMaxLoadTest',
    'com.devil.phoenixproject.di.KoinModuleVerifyTest',
    'com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepositoryTest',
    'com.devil.phoenixproject.util.OneRepMaxCalculatorTest'
)
foreach ($className in $expectedClasses) {
    $count = 0
    foreach ($document in $documents) {
        $count += @(
            $document.SelectNodes("//testcase[@classname='$className']")
        ).Count
    }
    if ($count -lt 1) {
        throw "No executed test cases found for $className"
    }
    Write-Output "$className : $count"
}
```

Compile production and test callers for both configured platform families:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileAndroidMain :shared:compileAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
```

Expected: BUILD SUCCESSFUL. Then run exact implementation/caller scans:

```powershell
rg -n 'getRecentCompletedSessionsForExercise|getMostRecentCompletedExerciseId' shared/src --glob '*.kt'
rg -n 'ResolveCurrentOneRepMaxUseCase' shared/src --glob '*.kt'

$legacyDetailReads = rg -n 'viewModel\.activeProfileId|velocityOneRepMaxRepository|VelocityOneRepMaxEntity' shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt
if ($LASTEXITCODE -eq 0) {
    $legacyDetailReads
    throw 'Exercise Detail still has a legacy profile or velocity read'
}

$newestOnlyResolver = rg -n -U 'getRecentCompletedSessionsForExercise\([\s\S]{0,300}?limit\s*=\s*1' shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt
if ($LASTEXITCODE -eq 0) {
    $newestOnlyResolver
    throw 'Resolver still inspects only the newest session'
}

$implementationRoots = @(
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository',
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil'
)
$implementations = @(
    rg -l -U 'class\s+\w+[^\{]*:\s*WorkoutRepository' $implementationRoots --glob '*.kt' |
        ForEach-Object { $_ -replace '\\', '/' } |
        Sort-Object
)
$expectedImplementations = @(
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt',
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt'
) | Sort-Object
if (Compare-Object $expectedImplementations $implementations) {
    Compare-Object $expectedImplementations $implementations
    throw 'WorkoutRepository implementation inventory changed'
}

$resolverBindings = @(
    rg -n 'single\s*\{\s*ResolveCurrentOneRepMaxUseCase\(' shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt
)
if ($resolverBindings.Count -ne 1) {
    $resolverBindings
    throw "Expected one resolver binding, found $($resolverBindings.Count)"
}

$resolverProductionFiles = @(
    rg -l 'ResolveCurrentOneRepMaxUseCase' shared/src/commonMain --glob '*.kt' |
        ForEach-Object { $_ -replace '\\', '/' } |
        Sort-Object
)
$expectedResolverProductionFiles = @(
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt'
) | Sort-Object
if (Compare-Object $expectedResolverProductionFiles $resolverProductionFiles) {
    Compare-Object $expectedResolverProductionFiles $resolverProductionFiles
    throw 'Resolver production caller inventory changed'
}
```

Expected at Task 2 completion: bounded-method callers are the interface, production/fake repositories, resolver, and focused tests; Task 5 later adds `ProfileViewModel`. The resolver is bound exactly once in `DomainModule` and consumed by Exercise Detail plus focused tests; Task 5 later adds the Profile consumer. The implementation list is exactly `SqlDelightWorkoutRepository` and `FakeWorkoutRepository`.

- [ ] **Step 13: Commit the query/resolver slice**

```powershell
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCaseTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailOneRepMaxLoadTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeVelocityOneRepMaxRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt
git commit -m "feat: resolve profile scoped exercise one rep max"
```

---

### Task 3: Add Localized Profile, Navigation, Insight, and Error Copy

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileResourceContractTest.kt`
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-nl/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-de/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-es/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-fr/strings.xml`

**Interfaces:**
- Consumes: existing shared resource keys for actions, profile colors, Exercise Picker, Equipment Rack, voice stop, and adult-mode controls.
- Produces: 56 stable generated Profile/navigation/insight/error keys, each assigned to an exact Task 4–9 consumer contract.

The original draft listed 59 new keys. The audit intentionally removes three duplicate concepts and reuses existing resources in all five selectable locales:

| Profile concept | Reused resource | Exact downstream rendering |
|---|---|---|
| Weight unit | `settings_weight_unit` | Measurements control label in `ProfilePreferenceComponents.kt` |
| Equipment Rack | `equipment_rack_title` + `equipment_rack_manage` | Rack row headline + action in `ProfilePreferenceComponents.kt` |
| LED color scheme | `cd_led_scheme` | LED selector label in `ProfilePreferenceComponents.kt`; Task 3 also completes its German, Spanish, and French translations. |

Keep `profile_one_rep_max_source_assessment` even though its English value matches `strength_assessment_cta_title`: the latter exists only in the default bundle, so reusing it would leave the four other selectable locales without translated source copy.

Do not add `profile_weight_unit`, `profile_manage_equipment_rack`, or `profile_led_color_scheme`. `values-it` exists for system-locale coverage but is not selectable; missing Task 3 keys there use the default English fallback. `SettingsTab.languageOptions` exposes exactly `en`, `nl`, `de`, `es`, and `fr`, so this contract modifies and verifies those five selectable locales only. If Italian becomes selectable later, that change must extend this contract in the same commit.

The retained new-key ownership is exact:

| Consumer task/file | Required new keys |
|---|---|
| Task 4: `ProfileSwitcherSheet.kt`, `ProfileDialogs.kt` | `profiles_title`, `profile_delete_reassign_message` |
| Task 5: `ProfileViewModel.kt` | None; ViewModels emit typed state/events and never own localized copy. |
| Task 6: `ProfileScreen.kt`, `ProfileExerciseInsights.kt` | `switch_profile`, `profile_exercise_insights`, `profile_choose_exercise`, `profile_no_exercise_history`, `profile_current_one_rep_max`, `profile_one_rep_max_source_velocity`, `profile_one_rep_max_source_assessment`, `profile_one_rep_max_source_session`, `profile_one_rep_max_source_none`, `profile_pr_highlights`, `profile_pr_max_weight`, `profile_pr_estimated_one_rep_max`, `profile_pr_max_volume`, `profile_recent_history`, `profile_view_full_history`, `profile_switching`, `profile_missing_exercise`, `profile_insights_load_failed`, `profile_update_failed` |
| Task 7: `EnhancedMainScreen.kt`, `NavGraph.kt`, `ProfileDialogs.kt` | `nav_profile`, `cd_profile`, `cd_open_profile_switcher`, `profile_switch_failed`, `profile_create_failed`, `profile_recovery_title`, `profile_recovery_message`, `profile_recovery_retry_failed` |
| Task 8: `ProfilePreferenceComponents.kt`, `ProfileScreen.kt` | `profile_preferences_title`, `profile_measurements`, `profile_workout_behavior`, `profile_led`, `profile_vbt`, `profile_safety`, `profile_vbt_enabled`, `profile_weight_increment`, `profile_body_weight`, `profile_set_summary`, `profile_autostart_countdown`, `profile_auto_start_routine`, `profile_audio_rep_counter`, `profile_countdown_beeps`, `profile_rep_completion_sound`, `profile_motion_start`, `profile_gamification`, `profile_default_scaling_basis`, `profile_routine_starting_weights`, `profile_stop_at_top`, `profile_stall_detection`, `profile_velocity_loss_threshold`, `profile_auto_end_velocity_loss`, `profile_vbt_history_note` |
| Task 9: `SettingsTab.kt` | `settings_video_behavior`, `settings_show_exercise_videos`, `settings_show_exercise_videos_description` |
| Task 10: legacy selector removal | None; it deletes old consumers and introduces no copy. |

- [ ] **Step 1: Write the failing locale resource contract test**

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileResourceContractTest {
    private val selectableLocales = linkedMapOf(
        "en" to "values",
        "nl" to "values-nl",
        "de" to "values-de",
        "es" to "values-es",
        "fr" to "values-fr",
    )

    private val files = selectableLocales.mapValues { (_, directory) ->
        "src/commonMain/composeResources/$directory/strings.xml"
    }

    private val newKeys = listOf(
        "nav_profile",
        "cd_profile",
        "cd_open_profile_switcher",
        "profiles_title",
        "switch_profile",
        "profile_exercise_insights",
        "profile_choose_exercise",
        "profile_no_exercise_history",
        "profile_current_one_rep_max",
        "profile_one_rep_max_source_velocity",
        "profile_one_rep_max_source_assessment",
        "profile_one_rep_max_source_session",
        "profile_one_rep_max_source_none",
        "profile_pr_highlights",
        "profile_pr_max_weight",
        "profile_pr_estimated_one_rep_max",
        "profile_pr_max_volume",
        "profile_recent_history",
        "profile_view_full_history",
        "profile_preferences_title",
        "profile_measurements",
        "profile_workout_behavior",
        "profile_led",
        "profile_vbt",
        "profile_safety",
        "profile_vbt_enabled",
        "profile_switching",
        "profile_missing_exercise",
        "profile_insights_load_failed",
        "profile_update_failed",
        "profile_switch_failed",
        "profile_create_failed",
        "profile_recovery_title",
        "profile_recovery_message",
        "profile_recovery_retry_failed",
        "profile_delete_reassign_message",
        "settings_video_behavior",
        "settings_show_exercise_videos",
        "settings_show_exercise_videos_description",
        "profile_weight_increment",
        "profile_body_weight",
        "profile_set_summary",
        "profile_autostart_countdown",
        "profile_auto_start_routine",
        "profile_audio_rep_counter",
        "profile_countdown_beeps",
        "profile_rep_completion_sound",
        "profile_motion_start",
        "profile_gamification",
        "profile_default_scaling_basis",
        "profile_routine_starting_weights",
        "profile_stop_at_top",
        "profile_stall_detection",
        "profile_velocity_loss_threshold",
        "profile_auto_end_velocity_loss",
        "profile_vbt_history_note",
    )

    private val reusedKeys = listOf(
        "settings_weight_unit",
        "equipment_rack_title",
        "equipment_rack_manage",
        "cd_led_scheme",
        "color_blue",
        "color_green",
        "color_amber",
        "color_red",
        "color_purple",
        "color_pink",
        "color_cyan",
        "color_orange",
        "cd_select_profile_color",
    )

    private val expectedLedSchemeLabels = mapOf(
        "en" to "LED color scheme",
        "nl" to "LED-kleurenschema",
        "de" to "LED-Farbschema",
        "es" to "Esquema de color LED",
        "fr" to "Palette de couleurs LED",
    )

    private val expectedPlaceholders = mapOf(
        "profile_delete_reassign_message" to listOf("%1${'$'}s"),
        "cd_select_profile_color" to listOf("%1${'$'}s"),
    )

    private val expectedColorLabels = mapOf(
        "en" to listOf("Blue", "Green", "Amber", "Red", "Purple", "Pink", "Cyan", "Orange"),
        "nl" to listOf("Blauw", "Groen", "Amber", "Rood", "Paars", "Roze", "Cyaan", "Oranje"),
        "de" to listOf("Blau", "Grün", "Bernstein", "Rot", "Lila", "Rosa", "Cyan", "Orange"),
        "es" to listOf("Azul", "Verde", "Ámbar", "Rojo", "Morado", "Rosa", "Cian", "Naranja"),
        "fr" to listOf("Bleu", "Vert", "Ambre", "Rouge", "Violet", "Rose", "Cyan", "Orange"),
    )

    private val placeholderPattern = Regex("""%\d+\${'$'}[A-Za-z]""")
    private val invalidAmpersandPattern =
        Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9A-Fa-f]+;)""")

    @Test
    fun contractInventoryIsUniqueAndTracksExactlyFiveSelectableLocales() {
        assertEquals(56, newKeys.size)
        assertEquals(newKeys.size, newKeys.toSet().size)
        assertTrue(newKeys.intersect(reusedKeys.toSet()).isEmpty())

        val settings = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt",
            ),
        )
        selectableLocales.keys.forEach { languageCode ->
            assertContains(settings, "\"$languageCode\" to stringResource")
        }
        assertFalse(settings.contains("\"it\" to stringResource"))
    }

    @Test
    fun selectableLocalesContainOneWellFormedDeclarationPerContractKey() {
        files.forEach { (languageCode, path) ->
            val source = assertNotNull(readProjectFile(path), "Missing $path")

            val names = Regex("""<string\s+name="([^"]+)"""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
            val duplicateNames = names.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
            assertTrue(duplicateNames.isEmpty(), "$path duplicates $duplicateNames")
            assertFalse(
                invalidAmpersandPattern.containsMatchIn(source),
                "$path contains an unescaped XML ampersand",
            )

            (newKeys + reusedKeys).forEach { key ->
                val declarationCount = Regex("""<string\s+name="$key"(?:\s|>)""")
                    .findAll(source)
                    .count()
                assertEquals(1, declarationCount, "$path declaration count for $key")
            }
            assertEquals(
                expectedLedSchemeLabels.getValue(languageCode),
                resourceValue(source, "cd_led_scheme", path),
                "$path translated LED scheme label",
            )
            assertEquals(
                expectedColorLabels.getValue(languageCode),
                listOf(
                    "color_blue",
                    "color_green",
                    "color_amber",
                    "color_red",
                    "color_purple",
                    "color_pink",
                    "color_cyan",
                    "color_orange",
                ).map { resourceValue(source, it, path) },
                "$path translated profile color labels",
            )

            (newKeys + reusedKeys).forEach { key ->
                val value = resourceValue(source, key, path)
                val placeholders = placeholderPattern.findAll(value)
                    .map { it.value }
                    .toList()
                assertEquals(
                    expectedPlaceholders[key].orEmpty(),
                    placeholders,
                    "$path placeholder contract for $key",
                )
            }
        }
    }

    @Test
    fun sourceReaderActualsSupportSharedRelativeContractPaths() {
        val androidReader = assertNotNull(
            readProjectFile(
                "src/androidHostTest/kotlin/com/devil/phoenixproject/testutil/SourceFileReader.android.kt",
            ),
        )
        val iosReader = assertNotNull(
            readProjectFile(
                "src/iosTest/kotlin/com/devil/phoenixproject/testutil/SourceFileReader.ios.kt",
            ),
        )

        assertContains(androidReader, """File(dir, "shared/${'$'}relativePath")""")
        assertContains(iosReader, """candidates.add("${'$'}dir/shared/${'$'}relativePath")""")
    }

    private fun resourceValue(source: String, key: String, path: String): String =
        assertNotNull(
            Regex(
                """<string\s+name="$key"[^>]*>(.*?)</string>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(source)?.groupValues?.get(1),
            "$path is missing the value for $key",
        )
}
```

- [ ] **Step 2: Run the locale contract and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileResourceContractTest*" --console=plain
```

Expected: FAIL because `nav_profile` and the remaining new resource keys are absent.

- [ ] **Step 3: Add the complete English resource block**

```xml
<string name="nav_profile">Profile</string>
<string name="cd_profile">Profile</string>
<string name="cd_open_profile_switcher">Open profile switcher</string>
<string name="profiles_title">Profiles</string>
<string name="switch_profile">Switch Profile</string>
<string name="profile_exercise_insights">Exercise Insights</string>
<string name="profile_choose_exercise">Choose an exercise</string>
<string name="profile_no_exercise_history">No exercise history for this profile</string>
<string name="profile_current_one_rep_max">Current 1RM</string>
<string name="profile_one_rep_max_source_velocity">Velocity estimate</string>
<string name="profile_one_rep_max_source_assessment">Strength assessment</string>
<string name="profile_one_rep_max_source_session">Recent completed session</string>
<string name="profile_one_rep_max_source_none">No profile-scoped estimate</string>
<string name="profile_pr_highlights">PR Highlights</string>
<string name="profile_pr_max_weight">Max weight</string>
<string name="profile_pr_estimated_one_rep_max">Estimated 1RM</string>
<string name="profile_pr_max_volume">Max volume</string>
<string name="profile_recent_history">Recent History</string>
<string name="profile_view_full_history">View Full History</string>
<string name="profile_preferences_title">Preferences</string>
<string name="profile_measurements">Measurements</string>
<string name="profile_workout_behavior">Workout Behavior</string>
<string name="profile_led">LED</string>
<string name="profile_vbt">Velocity-Based Training</string>
<string name="profile_safety">Safety</string>
<string name="profile_vbt_enabled">Use VBT during live workouts</string>
<string name="profile_switching">Switching profile…</string>
<string name="profile_missing_exercise">This exercise is no longer available</string>
<string name="profile_insights_load_failed">Could not load exercise insights</string>
<string name="profile_update_failed">Could not save this profile preference</string>
<string name="profile_switch_failed">Could not switch profiles</string>
<string name="profile_create_failed">Could not create the profile</string>
<string name="profile_recovery_title">Profile recovery required</string>
<string name="profile_recovery_message">Phoenix could not confirm which profile is active. Retry before continuing.</string>
<string name="profile_recovery_retry_failed">Profile recovery is still unavailable</string>
<string name="profile_delete_reassign_message">Delete &quot;%1$s&quot;? Its workouts, routines, records, badges, assessments, and progression data will move to Default. This cannot be undone.</string>
<string name="settings_video_behavior">Video Behavior</string>
<string name="settings_show_exercise_videos">Show Exercise Videos</string>
<string name="settings_show_exercise_videos_description">Display exercise demonstrations; turn this off on slower devices</string>
<string name="profile_weight_increment">Weight increment</string>
<string name="profile_body_weight">Body weight</string>
<string name="profile_set_summary">Set summary duration</string>
<string name="profile_autostart_countdown">Autostart countdown</string>
<string name="profile_auto_start_routine">Auto-start routine</string>
<string name="profile_audio_rep_counter">Audio rep counter</string>
<string name="profile_countdown_beeps">Countdown beeps</string>
<string name="profile_rep_completion_sound">Rep completion sound</string>
<string name="profile_motion_start">Motion-triggered set start</string>
<string name="profile_gamification">Gamification</string>
<string name="profile_default_scaling_basis">Default weight scaling basis</string>
<string name="profile_routine_starting_weights">Routine starting weights</string>
<string name="profile_stop_at_top">Stop at top</string>
<string name="profile_stall_detection">Stall detection</string>
<string name="profile_velocity_loss_threshold">Velocity-loss threshold</string>
<string name="profile_auto_end_velocity_loss">Auto-end on velocity loss</string>
<string name="profile_vbt_history_note">Turning VBT off affects live workouts only; saved estimates and assessments remain available.</string>
```

- [ ] **Step 4: Add the complete Dutch resource block**

```xml
<string name="nav_profile">Profiel</string>
<string name="cd_profile">Profiel</string>
<string name="cd_open_profile_switcher">Profielwisselaar openen</string>
<string name="profiles_title">Profielen</string>
<string name="switch_profile">Profiel wisselen</string>
<string name="profile_exercise_insights">Oefeningsinzichten</string>
<string name="profile_choose_exercise">Kies een oefening</string>
<string name="profile_no_exercise_history">Geen oefeningsgeschiedenis voor dit profiel</string>
<string name="profile_current_one_rep_max">Huidige 1RM</string>
<string name="profile_one_rep_max_source_velocity">Snelheidsschatting</string>
<string name="profile_one_rep_max_source_assessment">Krachtmeting</string>
<string name="profile_one_rep_max_source_session">Recente voltooide sessie</string>
<string name="profile_one_rep_max_source_none">Geen profielgebonden schatting</string>
<string name="profile_pr_highlights">PR-hoogtepunten</string>
<string name="profile_pr_max_weight">Maximaal gewicht</string>
<string name="profile_pr_estimated_one_rep_max">Geschatte 1RM</string>
<string name="profile_pr_max_volume">Maximaal volume</string>
<string name="profile_recent_history">Recente geschiedenis</string>
<string name="profile_view_full_history">Volledige geschiedenis bekijken</string>
<string name="profile_preferences_title">Voorkeuren</string>
<string name="profile_measurements">Metingen</string>
<string name="profile_workout_behavior">Trainingsgedrag</string>
<string name="profile_led">LED</string>
<string name="profile_vbt">Snelheidsgebaseerde training</string>
<string name="profile_safety">Veiligheid</string>
<string name="profile_vbt_enabled">VBT gebruiken tijdens live trainingen</string>
<string name="profile_switching">Profiel wisselen…</string>
<string name="profile_missing_exercise">Deze oefening is niet meer beschikbaar</string>
<string name="profile_insights_load_failed">Oefeningsinzichten konden niet worden geladen</string>
<string name="profile_update_failed">Deze profielvoorkeur kon niet worden opgeslagen</string>
<string name="profile_switch_failed">Profiel wisselen is mislukt</string>
<string name="profile_create_failed">Profiel maken is mislukt</string>
<string name="profile_recovery_title">Profielherstel vereist</string>
<string name="profile_recovery_message">Phoenix kan niet bevestigen welk profiel actief is. Probeer opnieuw voordat u doorgaat.</string>
<string name="profile_recovery_retry_failed">Profielherstel is nog niet beschikbaar</string>
<string name="profile_delete_reassign_message">&quot;%1$s&quot; verwijderen? Trainingen, routines, records, badges, metingen en voortgang worden naar Default verplaatst. Dit kan niet ongedaan worden gemaakt.</string>
<string name="settings_video_behavior">Videogedrag</string>
<string name="settings_show_exercise_videos">Oefeningsvideo's tonen</string>
<string name="settings_show_exercise_videos_description">Toon oefendemonstraties; schakel dit uit op tragere apparaten</string>
<string name="profile_weight_increment">Gewichtsstap</string>
<string name="profile_body_weight">Lichaamsgewicht</string>
<string name="profile_set_summary">Duur setoverzicht</string>
<string name="profile_autostart_countdown">Aftellen voor automatisch starten</string>
<string name="profile_auto_start_routine">Routine automatisch starten</string>
<string name="profile_audio_rep_counter">Gesproken herhalingsteller</string>
<string name="profile_countdown_beeps">Aftelpiepjes</string>
<string name="profile_rep_completion_sound">Geluid na herhaling</string>
<string name="profile_motion_start">Set starten door beweging</string>
<string name="profile_gamification">Gamificatie</string>
<string name="profile_default_scaling_basis">Standaardbasis voor gewichtschaal</string>
<string name="profile_routine_starting_weights">Startgewichten van routines</string>
<string name="profile_stop_at_top">Stoppen bovenaan</string>
<string name="profile_stall_detection">Stildetectie</string>
<string name="profile_velocity_loss_threshold">Drempel voor snelheidsverlies</string>
<string name="profile_auto_end_velocity_loss">Automatisch stoppen bij snelheidsverlies</string>
<string name="profile_vbt_history_note">VBT uitschakelen beïnvloedt alleen live trainingen; opgeslagen schattingen en metingen blijven beschikbaar.</string>
```

- [ ] **Step 5: Add the complete German resource block**

In `values-de/strings.xml`, replace the existing fallback-English `cd_led_scheme` declaration in place with the following line, then append the new block. Do not add a second declaration:

```xml
<string name="cd_led_scheme">LED-Farbschema</string>
```

```xml
<string name="nav_profile">Profil</string>
<string name="cd_profile">Profil</string>
<string name="cd_open_profile_switcher">Profilwechsler öffnen</string>
<string name="profiles_title">Profile</string>
<string name="switch_profile">Profil wechseln</string>
<string name="profile_exercise_insights">Übungsanalysen</string>
<string name="profile_choose_exercise">Übung auswählen</string>
<string name="profile_no_exercise_history">Keine Übungshistorie für dieses Profil</string>
<string name="profile_current_one_rep_max">Aktuelles 1RM</string>
<string name="profile_one_rep_max_source_velocity">Geschwindigkeitsschätzung</string>
<string name="profile_one_rep_max_source_assessment">Krafttest</string>
<string name="profile_one_rep_max_source_session">Letzte abgeschlossene Einheit</string>
<string name="profile_one_rep_max_source_none">Keine profilspezifische Schätzung</string>
<string name="profile_pr_highlights">PR-Höhepunkte</string>
<string name="profile_pr_max_weight">Maximalgewicht</string>
<string name="profile_pr_estimated_one_rep_max">Geschätztes 1RM</string>
<string name="profile_pr_max_volume">Maximalvolumen</string>
<string name="profile_recent_history">Letzter Verlauf</string>
<string name="profile_view_full_history">Gesamten Verlauf anzeigen</string>
<string name="profile_preferences_title">Einstellungen</string>
<string name="profile_measurements">Messwerte</string>
<string name="profile_workout_behavior">Trainingsverhalten</string>
<string name="profile_led">LED</string>
<string name="profile_vbt">Geschwindigkeitsbasiertes Training</string>
<string name="profile_safety">Sicherheit</string>
<string name="profile_vbt_enabled">VBT im Live-Training verwenden</string>
<string name="profile_switching">Profil wird gewechselt…</string>
<string name="profile_missing_exercise">Diese Übung ist nicht mehr verfügbar</string>
<string name="profile_insights_load_failed">Übungsanalysen konnten nicht geladen werden</string>
<string name="profile_update_failed">Diese Profileinstellung konnte nicht gespeichert werden</string>
<string name="profile_switch_failed">Profilwechsel fehlgeschlagen</string>
<string name="profile_create_failed">Profil konnte nicht erstellt werden</string>
<string name="profile_recovery_title">Profilwiederherstellung erforderlich</string>
<string name="profile_recovery_message">Phoenix konnte nicht bestätigen, welches Profil aktiv ist. Versuche es erneut, bevor du fortfährst.</string>
<string name="profile_recovery_retry_failed">Profilwiederherstellung ist weiterhin nicht verfügbar</string>
<string name="profile_delete_reassign_message">„%1$s“ löschen? Trainings, Routinen, Rekorde, Abzeichen, Tests und Fortschrittsdaten werden zu Default verschoben. Dies kann nicht rückgängig gemacht werden.</string>
<string name="settings_video_behavior">Videoverhalten</string>
<string name="settings_show_exercise_videos">Übungsvideos anzeigen</string>
<string name="settings_show_exercise_videos_description">Übungsdemonstrationen anzeigen; auf langsameren Geräten deaktivieren</string>
<string name="profile_weight_increment">Gewichtsschritt</string>
<string name="profile_body_weight">Körpergewicht</string>
<string name="profile_set_summary">Dauer der Satzzusammenfassung</string>
<string name="profile_autostart_countdown">Autostart-Countdown</string>
<string name="profile_auto_start_routine">Routine automatisch starten</string>
<string name="profile_audio_rep_counter">Gesprochener Wiederholungszähler</string>
<string name="profile_countdown_beeps">Countdown-Töne</string>
<string name="profile_rep_completion_sound">Wiederholungston</string>
<string name="profile_motion_start">Satzstart durch Bewegung</string>
<string name="profile_gamification">Gamification</string>
<string name="profile_default_scaling_basis">Standardbasis für Gewichtsskalierung</string>
<string name="profile_routine_starting_weights">Startgewichte für Routinen</string>
<string name="profile_stop_at_top">Oben stoppen</string>
<string name="profile_stall_detection">Stillstandserkennung</string>
<string name="profile_velocity_loss_threshold">Schwelle für Geschwindigkeitsverlust</string>
<string name="profile_auto_end_velocity_loss">Bei Geschwindigkeitsverlust automatisch beenden</string>
<string name="profile_vbt_history_note">Das Ausschalten von VBT betrifft nur Live-Trainings; gespeicherte Schätzungen und Tests bleiben verfügbar.</string>
```

- [ ] **Step 6: Add the complete Spanish resource block**

In `values-es/strings.xml`, replace the existing fallback-English `cd_led_scheme` declaration in place with the following line, then append the new block. Do not add a second declaration:

```xml
<string name="cd_led_scheme">Esquema de color LED</string>
```

```xml
<string name="nav_profile">Perfil</string>
<string name="cd_profile">Perfil</string>
<string name="cd_open_profile_switcher">Abrir selector de perfiles</string>
<string name="profiles_title">Perfiles</string>
<string name="switch_profile">Cambiar perfil</string>
<string name="profile_exercise_insights">Análisis del ejercicio</string>
<string name="profile_choose_exercise">Elegir un ejercicio</string>
<string name="profile_no_exercise_history">No hay historial de ejercicios para este perfil</string>
<string name="profile_current_one_rep_max">1RM actual</string>
<string name="profile_one_rep_max_source_velocity">Estimación por velocidad</string>
<string name="profile_one_rep_max_source_assessment">Evaluación de fuerza</string>
<string name="profile_one_rep_max_source_session">Sesión completada reciente</string>
<string name="profile_one_rep_max_source_none">No hay estimación para este perfil</string>
<string name="profile_pr_highlights">Récords destacados</string>
<string name="profile_pr_max_weight">Peso máximo</string>
<string name="profile_pr_estimated_one_rep_max">1RM estimado</string>
<string name="profile_pr_max_volume">Volumen máximo</string>
<string name="profile_recent_history">Historial reciente</string>
<string name="profile_view_full_history">Ver historial completo</string>
<string name="profile_preferences_title">Preferencias</string>
<string name="profile_measurements">Mediciones</string>
<string name="profile_workout_behavior">Comportamiento del entrenamiento</string>
<string name="profile_led">LED</string>
<string name="profile_vbt">Entrenamiento basado en velocidad</string>
<string name="profile_safety">Seguridad</string>
<string name="profile_vbt_enabled">Usar VBT durante entrenamientos en vivo</string>
<string name="profile_switching">Cambiando perfil…</string>
<string name="profile_missing_exercise">Este ejercicio ya no está disponible</string>
<string name="profile_insights_load_failed">No se pudo cargar el análisis del ejercicio</string>
<string name="profile_update_failed">No se pudo guardar esta preferencia del perfil</string>
<string name="profile_switch_failed">No se pudo cambiar de perfil</string>
<string name="profile_create_failed">No se pudo crear el perfil</string>
<string name="profile_recovery_title">Se requiere recuperar el perfil</string>
<string name="profile_recovery_message">Phoenix no pudo confirmar qué perfil está activo. Vuelve a intentarlo antes de continuar.</string>
<string name="profile_recovery_retry_failed">La recuperación del perfil sigue sin estar disponible</string>
<string name="profile_delete_reassign_message">¿Eliminar &quot;%1$s&quot;? Sus entrenamientos, rutinas, récords, insignias, evaluaciones y datos de progreso pasarán a Default. Esta acción no se puede deshacer.</string>
<string name="settings_video_behavior">Comportamiento del vídeo</string>
<string name="settings_show_exercise_videos">Mostrar vídeos de ejercicios</string>
<string name="settings_show_exercise_videos_description">Mostrar demostraciones; desactívalo en dispositivos más lentos</string>
<string name="profile_weight_increment">Incremento de peso</string>
<string name="profile_body_weight">Peso corporal</string>
<string name="profile_set_summary">Duración del resumen de serie</string>
<string name="profile_autostart_countdown">Cuenta atrás de inicio automático</string>
<string name="profile_auto_start_routine">Iniciar rutina automáticamente</string>
<string name="profile_audio_rep_counter">Contador de repeticiones por voz</string>
<string name="profile_countdown_beeps">Pitidos de cuenta atrás</string>
<string name="profile_rep_completion_sound">Sonido al completar repetición</string>
<string name="profile_motion_start">Iniciar serie con movimiento</string>
<string name="profile_gamification">Gamificación</string>
<string name="profile_default_scaling_basis">Base predeterminada de escala de peso</string>
<string name="profile_routine_starting_weights">Pesos iniciales de las rutinas</string>
<string name="profile_stop_at_top">Detener arriba</string>
<string name="profile_stall_detection">Detección de bloqueo</string>
<string name="profile_velocity_loss_threshold">Umbral de pérdida de velocidad</string>
<string name="profile_auto_end_velocity_loss">Finalizar al perder velocidad</string>
<string name="profile_vbt_history_note">Desactivar VBT solo afecta a los entrenamientos en vivo; las estimaciones y evaluaciones guardadas siguen disponibles.</string>
```

- [ ] **Step 7: Add the complete French resource block**

In `values-fr/strings.xml`, replace the existing fallback-English `cd_led_scheme` declaration in place with the following line, then append the new block. Do not add a second declaration:

```xml
<string name="cd_led_scheme">Palette de couleurs LED</string>
```

```xml
<string name="nav_profile">Profil</string>
<string name="cd_profile">Profil</string>
<string name="cd_open_profile_switcher">Ouvrir le sélecteur de profil</string>
<string name="profiles_title">Profils</string>
<string name="switch_profile">Changer de profil</string>
<string name="profile_exercise_insights">Analyse de l'exercice</string>
<string name="profile_choose_exercise">Choisir un exercice</string>
<string name="profile_no_exercise_history">Aucun historique d'exercice pour ce profil</string>
<string name="profile_current_one_rep_max">1RM actuel</string>
<string name="profile_one_rep_max_source_velocity">Estimation par vélocité</string>
<string name="profile_one_rep_max_source_assessment">Évaluation de force</string>
<string name="profile_one_rep_max_source_session">Séance terminée récente</string>
<string name="profile_one_rep_max_source_none">Aucune estimation propre à ce profil</string>
<string name="profile_pr_highlights">Records marquants</string>
<string name="profile_pr_max_weight">Poids maximal</string>
<string name="profile_pr_estimated_one_rep_max">1RM estimé</string>
<string name="profile_pr_max_volume">Volume maximal</string>
<string name="profile_recent_history">Historique récent</string>
<string name="profile_view_full_history">Voir tout l'historique</string>
<string name="profile_preferences_title">Préférences</string>
<string name="profile_measurements">Mesures</string>
<string name="profile_workout_behavior">Comportement d'entraînement</string>
<string name="profile_led">LED</string>
<string name="profile_vbt">Entraînement basé sur la vélocité</string>
<string name="profile_safety">Sécurité</string>
<string name="profile_vbt_enabled">Utiliser le VBT pendant les entraînements en direct</string>
<string name="profile_switching">Changement de profil…</string>
<string name="profile_missing_exercise">Cet exercice n'est plus disponible</string>
<string name="profile_insights_load_failed">Impossible de charger l'analyse de l'exercice</string>
<string name="profile_update_failed">Impossible d'enregistrer cette préférence du profil</string>
<string name="profile_switch_failed">Impossible de changer de profil</string>
<string name="profile_create_failed">Impossible de créer le profil</string>
<string name="profile_recovery_title">Récupération du profil requise</string>
<string name="profile_recovery_message">Phoenix n’a pas pu confirmer quel profil est actif. Réessayez avant de continuer.</string>
<string name="profile_recovery_retry_failed">La récupération du profil est toujours indisponible</string>
<string name="profile_delete_reassign_message">Supprimer « %1$s » ? Ses entraînements, routines, records, badges, évaluations et données de progression seront transférés vers Default. Cette action est irréversible.</string>
<string name="settings_video_behavior">Comportement vidéo</string>
<string name="settings_show_exercise_videos">Afficher les vidéos d'exercice</string>
<string name="settings_show_exercise_videos_description">Afficher les démonstrations ; désactivez-les sur les appareils plus lents</string>
<string name="profile_weight_increment">Incrément de poids</string>
<string name="profile_body_weight">Poids corporel</string>
<string name="profile_set_summary">Durée du résumé de série</string>
<string name="profile_autostart_countdown">Compte à rebours automatique</string>
<string name="profile_auto_start_routine">Démarrer la routine automatiquement</string>
<string name="profile_audio_rep_counter">Compteur vocal de répétitions</string>
<string name="profile_countdown_beeps">Bips du compte à rebours</string>
<string name="profile_rep_completion_sound">Son de fin de répétition</string>
<string name="profile_motion_start">Démarrer la série par mouvement</string>
<string name="profile_gamification">Ludification</string>
<string name="profile_default_scaling_basis">Base de mise à l'échelle du poids</string>
<string name="profile_routine_starting_weights">Poids de départ des routines</string>
<string name="profile_stop_at_top">Arrêt en haut</string>
<string name="profile_stall_detection">Détection de blocage</string>
<string name="profile_velocity_loss_threshold">Seuil de perte de vélocité</string>
<string name="profile_auto_end_velocity_loss">Arrêt automatique sur perte de vélocité</string>
<string name="profile_vbt_history_note">Désactiver le VBT ne concerne que les entraînements en direct ; les estimations et évaluations enregistrées restent disponibles.</string>
```

- [ ] **Step 8: Run resource generation and the locale contract**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateResourceAccessorsForCommonMain :shared:testAndroidHostTest --tests "*ProfileResourceContractTest*" :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
```

Expected: BUILD SUCCESSFUL. Compose resource generation parses all five edited XML files and emits the common accessors; the Android-host contract executes all three tests, including shared-relative path discovery; Android main and iOS Arm64 main compile those accessors; iOS Arm64 test compilation verifies the common contract against `SourceFileReader.ios.kt`. This repository has no runnable iOS simulator test task, so do not claim an iOS runtime test from `compileTestKotlinIosArm64`.

- [ ] **Step 9: Commit the resource contract**

```powershell
$expected = @(
    'shared/src/commonMain/composeResources/values/strings.xml'
    'shared/src/commonMain/composeResources/values-nl/strings.xml'
    'shared/src/commonMain/composeResources/values-de/strings.xml'
    'shared/src/commonMain/composeResources/values-es/strings.xml'
    'shared/src/commonMain/composeResources/values-fr/strings.xml'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileResourceContractTest.kt'
) | Sort-Object
$actual = @(git diff --name-only | Sort-Object)
$scopeDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual
if ($scopeDiff) {
    $scopeDiff | Format-Table -AutoSize
    throw 'Task 3 worktree scope differs from the six-file resource contract'
}
git diff --check -- $expected
if ($LASTEXITCODE -ne 0) { throw 'Task 3 diff check failed' }
git add -- $expected
$staged = @(git diff --cached --name-only | Sort-Object)
$stagedDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $staged
if ($stagedDiff) {
    $stagedDiff | Format-Table -AutoSize
    throw 'Task 3 staged scope differs from the six-file resource contract'
}
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Task 3 cached diff check failed' }
git commit -m "feat: add localized profile screen copy"
```

---

### Task 4: Extract Reusable Profile Identity UI and Build the Switcher Sheet

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt`

**Interfaces:**
- Consumes: existing `UserProfile`; Task 3's localized profile/color resources; and common Material 3 sheet, dialog, selection, and semantics primitives.
- Produces: `ProfileColors`, `PROFILE_COLOR_COUNT`, `suggestedProfileColorIndex`, `normalizedProfileColorIndex`, contrast-safe `profileInitialsColor`, active-only `canDeleteProfile`, `ProfileAvatar`, callback-only add/edit/delete dialogs, `canDismissProfileSwitcher`, and a switch/create-only `ProfileSwitcherSheet` for Tasks 6–7.
- Interaction contract: every clickable avatar/swatch is at least 48dp, profile choices expose radio/selected semantics, dialog callbacks never mutate repositories or close optimistically, and a switch in flight cannot hide the sheet through drag, back, scrim, or callback dismissal.
- Delete-copy invariant: Task 6 deletes only the active non-default profile, whose repository reassignment target is Default. The shared policy and dialog must encode that active-only precondition so `profile_delete_reassign_message` can never lie about the target.

- [ ] **Step 1: Write the failing identity policy test**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileIdentityPolicyTest {
    @Test
    fun `palette selection wraps and invalid stored indexes normalize`() {
        assertEquals(PROFILE_COLOR_COUNT, ProfileColors.size)
        assertEquals(0, suggestedProfileColorIndex(-1))
        assertEquals(0, suggestedProfileColorIndex(0))
        assertEquals(7, suggestedProfileColorIndex(7))
        assertEquals(0, suggestedProfileColorIndex(8))

        assertEquals(0, normalizedProfileColorIndex(-1))
        assertEquals(0, normalizedProfileColorIndex(0))
        assertEquals(7, normalizedProfileColorIndex(7))
        assertEquals(0, normalizedProfileColorIndex(8))
    }

    @Test
    fun `avatar initials meet text contrast across the shared palette`() {
        ProfileColors.forEach { background ->
            val foreground = profileInitialsColor(background)
            assertTrue(
                contrastRatio(foreground, background) >= 4.5f,
                "Insufficient initials contrast for $background",
            )
        }
    }

    @Test
    fun `only the active non-default profile may be deleted`() {
        assertFalse(canDeleteProfile(profile("default", isActive = true)))
        assertFalse(canDeleteProfile(profile("athlete-a", isActive = false)))
        assertTrue(canDeleteProfile(profile("athlete-a", isActive = true)))
    }

    @Test
    fun `switcher may dismiss only while no switch is in flight`() {
        assertTrue(canDismissProfileSwitcher(null))
        assertFalse(canDismissProfileSwitcher("athlete-a"))
    }

    @Test
    fun `identity dialogs are callback only guarded and responsive`() {
        val dialogs = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
            ),
        )

        assertFalse(dialogs.contains("UserProfileRepository"))
        assertFalse(dialogs.contains("CoroutineScope"))
        assertFalse(dialogs.contains("DestructiveConfirmDialog("))
        assertContains(dialogs, "fun ProfileAddDialog(")
        assertContains(dialogs, "fun ProfileEditDialog(")
        assertContains(dialogs, "fun ProfileDeleteDialog(")
        assertContains(dialogs, "require(canDeleteProfile(profile))")
        assertContains(dialogs, "AlertDialog(")
        assertContains(dialogs, "name.trim()")
        assertContains(dialogs, "normalizedProfileColorIndex(selectedColorIndex)")
        assertContains(dialogs, "ProfileColors.indices.chunked(4)")
        assertContains(dialogs, ".size(48.dp)")
        assertContains(dialogs, ".selectable(")
        assertContains(dialogs, "Role.RadioButton")
        assertContains(dialogs, ".selectableGroup()")
        assertContains(dialogs, "enabled = !isSubmitting")
        assertContains(dialogs, "if (!isSubmitting)")
        val deleteCopyOffset = dialogs.indexOf("Res.string.profile_delete_reassign_message")
        assertTrue(deleteCopyOffset >= 0)
        val deleteCopyCall = dialogs.substring(
            deleteCopyOffset,
            minOf(dialogs.length, deleteCopyOffset + 240),
        )
        assertContains(deleteCopyCall, "profile.name")
    }

    @Test
    fun `switcher is switch create only and cannot hide while switching`() {
        val switcher = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt",
            ),
        )

        assertContains(switcher, "Res.string.profiles_title")
        assertContains(switcher, "TestTags.PROFILE_SWITCHER_SHEET")
        assertContains(switcher, "TestTags.ACTION_ADD_PROFILE")
        assertFalse(switcher.contains("onEditProfile"))
        assertFalse(switcher.contains("onDeleteProfile"))
        assertFalse(switcher.contains("combinedClickable"))
        assertContains(switcher, "rememberUpdatedState")
        assertContains(switcher, "confirmValueChange")
        assertContains(switcher, "targetValue != SheetValue.Hidden")
        assertContains(switcher, "sheetGesturesEnabled = canDismiss")
        assertContains(switcher, "if (canDismissProfileSwitcher(")
        assertContains(switcher, ".selectableGroup()")
    }

    @Test
    fun `identity surfaces declare accessible semantics and all downstream tags`() {
        val identity = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt",
            ),
        )
        val row = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt",
            ),
        )
        val tags = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt",
            ),
        )

        assertContains(identity, "minimumInteractiveComponentSize")
        assertContains(identity, "Role.Button")
        assertContains(identity, "contentDescription = accessibleName")
        assertContains(identity, "clearAndSetSemantics")
        assertContains(row, "heightIn(min = 56.dp)")
        assertContains(row, "Modifier.selectable(")
        assertContains(row, "Role.RadioButton")
        assertContains(row, "selected = isActive")
        assertContains(row, "enabled && !switching")
        listOf(
            "PROFILE_SWITCHER_SHEET",
            "ACTION_ADD_PROFILE",
            "ACTION_EDIT_PROFILE",
            "ACTION_DELETE_PROFILE",
        ).forEach { tag -> assertContains(tags, "const val $tag") }
    }

    private fun profile(id: String, isActive: Boolean) = UserProfile(
        id = id,
        name = id,
        colorIndex = 0,
        createdAt = 1L,
        isActive = isActive,
    )

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
```

- [ ] **Step 2: Run the policy test and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileIdentityPolicyTest*" --console=plain
```

Expected: FAIL to compile because the extracted policy functions/files and the four downstream test tags do not exist.

- [ ] **Step 3: Extract the palette, avatar, and identity policy before deleting the speed dial**

Create `ProfileIdentityComponents.kt` with the palette currently defined in `ProfileSpeedDial.kt`. Keep palette indexing total for imported/synced legacy rows, encode the active-only delete invariant, and choose whichever of black/white gives the stronger WCAG contrast against the avatar background:

```kotlin
val ProfileColors = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444),
    Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFF97316),
)

const val PROFILE_COLOR_COUNT = 8

internal fun suggestedProfileColorIndex(profileCount: Int): Int =
    profileCount.coerceAtLeast(0) % PROFILE_COLOR_COUNT

internal fun normalizedProfileColorIndex(colorIndex: Int): Int =
    colorIndex.takeIf(ProfileColors.indices::contains) ?: 0

internal fun canDeleteProfile(profile: UserProfile): Boolean =
    profile.id != "default" && profile.isActive

internal fun profileInitialsColor(background: Color): Color {
    val luminance = background.luminance()
    val blackContrast = (luminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (luminance + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

@Composable
fun ProfileAvatar(
    profile: UserProfile,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val accessibleName = profile.name.trim().ifEmpty { "?" }
    val interactionModifier = if (onClick == null) {
        // The surrounding row/header owns the name; do not announce a duplicate initial.
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = accessibleName
                role = Role.Button
                onClick(label = accessibleName) {
                    onClick()
                    true
                }
            }
    }
    val background = ProfileColors[normalizedProfileColorIndex(profile.colorIndex)]

    Box(
        modifier = modifier.then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(size)
                .shadow(if (isActive) 8.dp else 0.dp, CircleShape),
            shape = CircleShape,
            color = background,
        ) {
            Text(
                text = accessibleName.take(1).uppercase(),
                color = profileInitialsColor(background),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.wrapContentSize(Alignment.Center),
            )
        }
    }
}
```

Use `androidx.compose.ui.graphics.luminance`, `minimumInteractiveComponentSize`, `Role.Button`, and `clearAndSetSemantics` from common Compose APIs. The visual circle stays 40dp by default, while a clickable avatar's outer hit target is at least 48dp. The contrast test is the guard against future palette changes.

Remove only the duplicate palette/constants/avatar from `ProfileSpeedDial.kt`; its named `ProfileAvatar` call remains source-compatible. Do not edit `RoutinesTab`: it already imports the package-level `ProfileColors` symbol, and Kotlin imports do not point at source files.

- [ ] **Step 4: Make the list row switcher-specific**

Extend `ProfileListItem` with switcher state while keeping its legacy side-panel call source-compatible until Task 10:

```kotlin
@Composable
fun ProfileListItem(
    profile: UserProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    switching: Boolean = false,
    modifier: Modifier = Modifier,
)
```

Use a full-width 56dp-minimum row, a non-clickable/decorative `ProfileAvatar`, the profile name with one-line ellipsis, a check icon for `isActive`, and a 20dp `CircularProgressIndicator` for `switching`. Disable every pointer action while `switching` is true.

The legacy side-panel branch keeps `combinedClickable` because active rows still need their edit/delete context action. Add explicit radio/selected semantics there. The new switcher branch uses `selectable`, which supplies the correct mutually-exclusive control semantics and disables the already-active row:

```kotlin
val interactionEnabled = enabled && !switching
val interactionModifier = if (onLongClick != null) {
    Modifier
        .combinedClickable(
            enabled = interactionEnabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        .semantics {
            selected = isActive
            role = Role.RadioButton
        }
} else {
    Modifier.selectable(
        selected = isActive,
        enabled = interactionEnabled && !isActive,
        role = Role.RadioButton,
        onClick = onClick,
    )
}

Surface(
    modifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .then(interactionModifier),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileAvatar(profile = profile, isActive = isActive)
        Text(
            text = profile.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            switching -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            isActive -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_active),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
```

Task 10 removes the nullable legacy callback and `combinedClickable` after deleting the last side-panel call.

- [ ] **Step 5: Create callback-only dialogs that never mutate repositories**

Use these exact public signatures in `ProfileDialogs.kt`:

```kotlin
@Composable
fun ProfileAddDialog(
    existingProfileCount: Int,
    isSubmitting: Boolean,
    onConfirm: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
)

@Composable
fun ProfileEditDialog(
    profile: UserProfile,
    isSubmitting: Boolean,
    onConfirm: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
)

@Composable
fun ProfileDeleteDialog(
    profile: UserProfile,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)
```

Each add/edit dialog owns only transient text/color selection. Key edit state by `profile.id`, normalize the stored color before displaying or confirming it, trim the name passed to `onConfirm`, disable the text field, swatches, confirmation, cancellation, and `onDismissRequest` while submitting, and never call `onDismiss` from a confirmation callback. Keep these exact guards in the shared `AlertDialog` implementation:

```kotlin
val trimmedName = name.trim()
AlertDialog(
    onDismissRequest = { if (!isSubmitting) onDismiss() },
    confirmButton = {
        TextButton(
            onClick = onConfirm,
            enabled = trimmedName.isNotEmpty() && !isSubmitting,
        ) { Text(confirmLabel) }
    },
    dismissButton = {
        TextButton(
            onClick = onDismiss,
            enabled = !isSubmitting,
        ) { Text(stringResource(Res.string.action_cancel)) }
    },
)
```

Build `ProfileDeleteDialog` with its own guarded `AlertDialog`; do not reuse `DestructiveConfirmDialog`, whose current API cannot disable its buttons. Require the active non-default invariant before rendering, keep the dialog open until Task 6 observes a success event, and bind the one `%1$s` placeholder:

```kotlin
require(canDeleteProfile(profile)) {
    "Only the active non-default profile may be deleted from Profile"
}
AlertDialog(
    onDismissRequest = { if (!isSubmitting) onDismiss() },
    title = { Text(stringResource(Res.string.delete_profile)) },
    text = {
        Text(
            text = stringResource(
                Res.string.profile_delete_reassign_message,
                profile.name,
            ),
        )
    },
    confirmButton = {
        TextButton(onClick = onConfirm, enabled = !isSubmitting) {
            Text(stringResource(Res.string.action_delete))
        }
    },
    dismissButton = {
        TextButton(onClick = onDismiss, enabled = !isSubmitting) {
            Text(stringResource(Res.string.action_cancel))
        }
    },
)
```

Render the eight translated color choices as exactly two rows of four. A single eight-item row cannot preserve 48dp targets on compact dialog widths. The 48dp wrapper is the radio target; the 36dp inner circle is visual only. Use the same contrast-safe foreground for the selected border/check:

```kotlin
Column(
    modifier = Modifier.fillMaxWidth().selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    ProfileColors.indices.chunked(4).forEach { indices ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            indices.forEach { index ->
                val color = ProfileColors[index]
                val selected = normalizedProfileColorIndex(selectedColorIndex) == index
                val description = stringResource(
                    Res.string.cd_select_profile_color,
                    colorNames[index],
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .selectable(
                            selected = selected,
                            enabled = !isSubmitting,
                            role = Role.RadioButton,
                            onClick = { onColorSelected(index) },
                        )
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color, CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp,
                                        profileInitialsColor(color),
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = profileInitialsColor(color),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

The `Profile*Dialog` names intentionally avoid colliding with the repository-coupled legacy dialogs until Task 10 deletes them.

- [ ] **Step 6: Build the shared switch/create-only sheet**

```kotlin
internal fun canDismissProfileSwitcher(switchingTargetProfileId: String?): Boolean =
    switchingTargetProfileId == null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    activeProfileId: String?,
    switchingTargetProfileId: String?,
    onSelectProfile: (UserProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentSwitchingTargetProfileId by rememberUpdatedState(switchingTargetProfileId)
    val canDismiss = canDismissProfileSwitcher(switchingTargetProfileId)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            targetValue != SheetValue.Hidden ||
                canDismissProfileSwitcher(currentSwitchingTargetProfileId)
        },
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (canDismissProfileSwitcher(currentSwitchingTargetProfileId)) onDismiss()
        },
        sheetState = sheetState,
        sheetGesturesEnabled = canDismiss,
        modifier = Modifier.testTag(TestTags.PROFILE_SWITCHER_SHEET),
    ) {
        Text(
            text = stringResource(Res.string.profiles_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(profiles, key = UserProfile::id) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    enabled = canDismiss,
                    switching = profile.id == switchingTargetProfileId,
                    onClick = { onSelectProfile(profile) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.add_profile)) },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = canDismiss,
                            role = Role.Button,
                            onClick = onAddProfile,
                        )
                        .testTag(TestTags.ACTION_ADD_PROFILE),
                )
            }
        }
    }
}
```

The state veto is required because Material 3 hides a modal sheet before delivering `onDismissRequest`; a callback-only guard would otherwise leave a remembered but hidden sheet after a failed switch. The veto covers back/scrim dismissal, and `sheetGesturesEnabled` prevents a misleading drag while the switch is in flight.

Add all four stable tags to `TestTags`:

```kotlin
const val PROFILE_SWITCHER_SHEET = "profile-switcher-sheet"
const val ACTION_ADD_PROFILE = "action-add-profile"
const val ACTION_EDIT_PROFILE = "action-edit-profile"
const val ACTION_DELETE_PROFILE = "action-delete-profile"
```

`PROFILE_SWITCHER_SHEET` belongs to the sheet root and `ACTION_ADD_PROFILE` belongs to its add row. `ACTION_EDIT_PROFILE` and `ACTION_DELETE_PROFILE` are declared now for Task 6's header actions. Do not put the same action tag on dialog confirmation buttons while the underlying action remains composed, because duplicate visible tags make UI tests ambiguous. The sheet deliberately has no edit/delete callbacks.

- [ ] **Step 7: Run focused tests and compile both KMP targets**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileIdentityPolicyTest*" :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --rerun --console=plain
```

Expected: BUILD SUCCESSFUL; all seven identity policy/source-contract cases execute, production composables compile for Android and iOS, and the common test plus `SourceFileReader.ios.kt` compile for iOS. This is an iOS compile check, not an iOS runtime test.

Verify the exact Android-host test count and the callback-only boundary:

```powershell
$result = [xml](Get-Content 'shared/build/test-results/testAndroidHostTest/TEST-com.devil.phoenixproject.presentation.components.ProfileIdentityPolicyTest.xml' -Raw)
$cases = @($result.SelectNodes("//testcase[@classname='com.devil.phoenixproject.presentation.components.ProfileIdentityPolicyTest']"))
if ($cases.Count -ne 7) { throw "Expected 7 ProfileIdentityPolicyTest cases, found $($cases.Count)" }
if (@($result.SelectNodes('//failure | //error')).Count -ne 0) { throw 'ProfileIdentityPolicyTest failed' }

$repositoryCoupling = rg -n 'UserProfileRepository|CoroutineScope|profileRepository\.|scope\.launch' shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt
if ($LASTEXITCODE -eq 0) {
    $repositoryCoupling
    throw 'Callback-only profile dialogs still mutate repositories or launch work'
}

git diff --check -- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt
if ($LASTEXITCODE -ne 0) { throw 'Task 4 diff check failed' }
```

- [ ] **Step 8: Commit the reusable identity surface**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt
git commit -m "refactor: extract profile identity components"
```

---

### Task 5: Add the Route-Scoped Profile ViewModel and Profile-Correct Insights State

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePersonalRecordRepository.kt`

**Interfaces:**
- Consumes: `ActiveProfileContext`, `ExerciseRepository`, Task 2's bounded history methods and
  resolver, `PersonalRecordRepository.getAllPRsForExercise`, and the constructor-stable
  `ExternalMeasurementRepository` reserved for Task 8.
- Produces: one route-scoped `ProfileUiState`, per-profile in-memory selection restoration,
  recoverable `selectionFailure`, three independently loadable insight blocks, same-profile Ready
  refresh without data reset, and typed UI events used by Tasks 6 and 8.

- [ ] **Step 1: Add current-code-compatible deterministic controls to the existing fakes**

`FakeUserProfileRepository` currently owns `profiles`, `preferenceFlows`, `localSafety`,
`ensurePreferenceFlow`, `setActiveIdentityLocked`, and `publishReady`; it does **not** own
`preferencesByProfile` or `localSafetyByProfile`. Add the helpers below using those exact current
members. Replace the body of `createProfileLocked` with the delegating form so production-like fake
creation and UI-test seeding share the same preference construction and identity-flow updates:

```kotlin
fun seedReadyProfileForTest(
    profileId: String,
    name: String = profileId,
    colorIndex: Int = 0,
): UserProfile {
    seedProfileWithDefaultPreferences(profileId, name, colorIndex)
    emitReadyForTest(profileId)
    return profiles.getValue(profileId)
}

fun emitSwitchingForTest(targetProfileId: String?) {
    _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
}

fun emitReadyForTest(profileId: String) {
    require(profiles.containsKey(profileId)) { "Unknown profile: $profileId" }
    setActiveIdentityLocked(profileId)
    publishReady(profileId)
}

private fun seedProfileWithDefaultPreferences(
    profileId: String,
    name: String,
    colorIndex: Int,
): UserProfile {
    require(profileId.isNotBlank()) { "Profile ID must not be blank" }
    val trimmedName = name.trim()
    require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
    require(!profiles.containsKey(profileId)) { "Profile already exists: $profileId" }
    val profile = UserProfile(
        id = profileId,
        name = trimmedName,
        colorIndex = colorIndex,
        createdAt = currentTimeMillis(),
        isActive = false,
    )
    profiles[profileId] = profile
    ensurePreferenceFlow(profileId)
    localSafety.getOrPut(profileId) { ProfileLocalSafetyPreferences() }
    updateIdentityFlows()
    return profile
}

private fun createProfileLocked(
    name: String,
    colorIndex: Int,
    id: String = generateUUID(),
): UserProfile = seedProfileWithDefaultPreferences(
    profileId = id,
    name = name,
    colorIndex = colorIndex,
)
```

`emitReadyForTest` must update the identity map before publishing Ready. A test context whose
`Ready.profile.isActive` is false while `activeProfile` points elsewhere is not acceptable fake
parity.

Add a request record, an ordinary/cancellation failure seam, and a suspend hook to
`FakePersonalRecordRepository`. The hook runs after a profile-filtered snapshot is captured and
allows tests to hold or deliberately make one read non-cooperative without changing the production
interface:

```kotlin
data class GetAllForExerciseRequest(
    val exerciseId: String,
    val profileId: String,
)

val getAllForExerciseRequests = mutableListOf<GetAllForExerciseRequest>()
var getAllForExerciseFailure: Throwable? = null
var beforeGetAllForExerciseReturn: (suspend (String, String) -> Unit)? = null

fun reset() {
    records.clear()
    updateCalls.clear()
    getAllForExerciseRequests.clear()
    getAllForExerciseFailure = null
    beforeGetAllForExerciseReturn = null
    updateRecordsFlow()
}

override suspend fun getAllPRsForExercise(
    exerciseId: String,
    profileId: String,
): List<PersonalRecord> {
    getAllForExerciseRequests += GetAllForExerciseRequest(exerciseId, profileId)
    getAllForExerciseFailure?.let { throw it }
    val result = records.values
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .sortedByDescending { it.timestamp }
    beforeGetAllForExerciseReturn?.invoke(exerciseId, profileId)
    return result
}
```

Failure is checked before reading records. `reset()` must clear every new control so existing fake
users retain the documented clean-reset behavior.

- [ ] **Step 2: Write the complete failing lifecycle, isolation, and partial-failure suite**

Use `TestCoroutineRule`, `runTest`, and fresh fakes per test. Construct the real Task 2 resolver so
the ViewModel test covers its actual repository calls:

```kotlin
@get:Rule
val coroutineRule = TestCoroutineRule()

private lateinit var profiles: FakeUserProfileRepository
private lateinit var exercises: FakeExerciseRepository
private lateinit var workouts: FakeWorkoutRepository
private lateinit var personalRecords: FakePersonalRecordRepository
private lateinit var velocity: FakeVelocityOneRepMaxRepository
private lateinit var assessments: FakeAssessmentRepository
private lateinit var externalMeasurements: FakeExternalMeasurementRepository

@Before
fun setUp() {
    profiles = FakeUserProfileRepository()
    exercises = FakeExerciseRepository()
    workouts = FakeWorkoutRepository()
    personalRecords = FakePersonalRecordRepository()
    velocity = FakeVelocityOneRepMaxRepository()
    assessments = FakeAssessmentRepository()
    externalMeasurements = FakeExternalMeasurementRepository()
}

private fun createViewModel() = ProfileViewModel(
    profiles = profiles,
    exercises = exercises,
    workouts = workouts,
    personalRecords = personalRecords,
    resolveCurrentOneRepMax = ResolveCurrentOneRepMaxUseCase(
        velocityRepository = velocity,
        assessmentRepository = assessments,
        workoutRepository = workouts,
    ),
    externalMeasurements = externalMeasurements,
)

private fun session(
    id: String,
    profileId: String,
    exerciseId: String,
    timestamp: Long,
    weightPerCableKg: Float = 40f,
    workingReps: Int = 5,
) = WorkoutSession(
    id = id,
    profileId = profileId,
    exerciseId = exerciseId,
    exerciseName = exerciseId,
    timestamp = timestamp,
    weightPerCableKg = weightPerCableKg,
    workingReps = workingReps,
    totalReps = workingReps,
)

private fun record(
    id: Long,
    profileId: String,
    exerciseId: String,
    prType: PRType,
    weightPerCableKg: Float,
    oneRepMax: Float,
    volume: Float,
) = PersonalRecord(
    id = id,
    profileId = profileId,
    exerciseId = exerciseId,
    exerciseName = exerciseId,
    weightPerCableKg = weightPerCableKg,
    reps = 5,
    oneRepMax = oneRepMax,
    timestamp = id,
    workoutMode = "Old School",
    prType = prType,
    volume = volume,
)
```

The core test must drain the `StandardTestDispatcher` after **every** Switching emission. Emitting
Switching and Ready back-to-back can be conflated by `StateFlow` and does not prove stale-state
clearing:

```kotlin
@Test
fun `A to B to A restores selection and Switching clears all visible profile data`() = runTest {
    val bench = Exercise(name = "Bench", muscleGroup = "Chest", id = "bench")
    val squat = Exercise(name = "Squat", muscleGroup = "Legs", id = "squat")
    exercises.addExercise(bench)
    exercises.addExercise(squat)
    profiles.seedReadyProfileForTest("a", name = "A")
    profiles.seedReadyProfileForTest("b", name = "B")
    val viewModel = createViewModel()
    runCurrent()

    profiles.emitSwitchingForTest("a")
    runCurrent()
    profiles.emitReadyForTest("a")
    advanceUntilIdle()
    viewModel.selectExercise(bench)
    advanceUntilIdle()

    profiles.emitSwitchingForTest("b")
    runCurrent()
    val switching = viewModel.uiState.value
    assertIs<ActiveProfileContext.Switching>(switching.context)
    assertNull(switching.selectedExercise)
    assertNull(switching.missingExerciseId)
    assertNull(switching.selectionFailure)
    assertIs<ProfileLoadable.Empty>(switching.currentOneRepMax)
    assertIs<ProfileLoadable.Empty>(switching.prHighlights)
    assertIs<ProfileLoadable.Empty>(switching.recentSessions)

    profiles.emitReadyForTest("b")
    advanceUntilIdle()
    viewModel.selectExercise(squat)
    advanceUntilIdle()

    profiles.emitSwitchingForTest("a")
    runCurrent()
    profiles.emitReadyForTest("a")
    advanceUntilIdle()
    assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
}
```

Add exactly these thirteen additional tests, for fourteen Task 5 tests total:

| Test name | Exact setup and assertions |
|---|---|
| `no saved selection uses only the Ready profile recent exercise` | Add newer B/Squat and older A/Bench completed sessions, clear `recentCompletedRequests` immediately before entering A, and assert Bench is selected. Assert every resulting request has `profileId == "a"`, `exerciseId == "bench"`, and `limit == MAX_RECENT_EXERCISE_SESSIONS`. |
| `deleted saved exercise reports the unresolved profile fallback id` | Select Bench for A, switch to B with a dispatcher drain, call `exercises.reset()`, retain an A/Bench completed session, return to A, and assert `selectedExercise == null`, `missingExerciseId == "bench"`, and `selectionFailure == null`. |
| `selection fallback failure is distinct from missing and the collector recovers` | Set `mostRecentCompletedExerciseFailure = IllegalStateException("fallback")`, enter A, and assert `selectionFailure` is that failure while `missingExerciseId == null`. Clear the failure, emit Switching and drain, emit Ready, and assert fallback selection loads; this proves the long-lived collector survived. |
| `null resolver and empty repositories use explicit empty contracts` | Select a valid exercise with no estimates, PRs, or sessions. Assert 1RM is `Empty`, PRs are `Ready(ProfilePrHighlights(null, null, null))`, and sessions are `Ready(emptyList())`. |
| `PR failure does not blank one rep max or recent sessions` | Seed one valid A/Bench session, set `getAllForExerciseFailure = IllegalStateException("prs")`, select Bench, and assert PR is `Failed` while 1RM and sessions are `Ready`. |
| `resolver failure does not blank PRs or recent sessions` | Seed one valid A/Bench session, set `velocity.latestPassingFailure = IllegalStateException("1rm")`, select Bench, and assert only 1RM is `Failed`; PRs and sessions are `Ready`. |
| `recent session failure does not blank an earlier source one rep max or PRs` | Save a valid A/Bench assessment before selecting, set `recentCompletedFailure = IllegalStateException("recent")`, and assert assessment-backed 1RM and PRs are `Ready` while sessions are `Failed`. Seeding the assessment is required because the resolver otherwise uses the same failing history API. |
| `mixed A and B records sessions and selections never cross profiles` | Seed different A/B sessions and PR maxima for the same exercise, load A then B with a drained Switching state, and assert each state contains only its profile's timestamps and PR values. Assert `getAllForExerciseRequests` contains the captured profile ID for every publication. |
| `same profile Ready refresh updates context without restarting insights` | Load A fully, capture all three loadables plus `workouts.recentCompletedRequests.size` and `personalRecords.getAllForExerciseRequests.size`, call `profiles.updateCore("a", ready.preferences.core.value.copy(bodyWeightKg = 82f))`, and assert the context contains 82 kg while selection, loadables, and both counts are unchanged. |
| `nullable blank and switching time selections are ignored` | Pass exercises with null and blank IDs, then emit Switching without draining and call `selectExercise` again. Assert no new insight requests occur and B has no saved selection when Ready arrives. This proves the method checks repository context, not only lagging UI state. |
| `cooperative insight cancellation is never converted to Failed` | Suspend the A PR hook with `awaitCancellation()`, emit Switching and drain, and assert all three branches are `Empty` in the Switching state with no `Failed` value. |
| `non cooperative slow A result cannot overwrite B` | Use the suspend hook shown below, switch to B after A starts, and assert B's PR values remain after the canceled A call deliberately returns. |
| `non finite and non positive PR values are excluded per field` | Add NaN, infinity, zero, negative, and valid values across both PR types. Assert each highlight contains only the maximum finite positive value for its own mapping. |

Use this exact non-cooperative hook in the stale-result test. It swallows cancellation only inside
the test seam so the A repository call returns and exercises the publication guard:

```kotlin
val aStarted = CompletableDeferred<Unit>()
val staleAReturned = CompletableDeferred<Unit>()
personalRecords.beforeGetAllForExerciseReturn = { _, profileId ->
    if (profileId == "a") {
        aStarted.complete(Unit)
        try {
            awaitCancellation()
        } catch (_: CancellationException) {
            // Deliberately non-cooperative test double: return a stale A snapshot.
        }
        staleAReturned.complete(Unit)
    }
}

profiles.emitReadyForTest("a")
advanceUntilIdle()
viewModel.selectExercise(bench)
aStarted.await()
profiles.emitSwitchingForTest("b")
runCurrent()
profiles.emitReadyForTest("b")
advanceUntilIdle()
staleAReturned.await()
assertEquals(expectedBHighlights, assertIs<ProfileLoadable.Ready<ProfilePrHighlights>>(
    viewModel.uiState.value.prHighlights,
).value)
```

Every `PersonalRecord` and `WorkoutSession` fixture in these tests must set `profileId` explicitly;
their domain defaults are `"default"` and would make an A/B isolation test a false positive.

- [ ] **Step 3: Run the ViewModel tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --console=plain
```

Expected: FAIL to compile because `ProfileViewModel`, its state, and fake controls are absent.
Record the compile error for `ProfileViewModel` or `selectionFailure` as the RED evidence. A failure
caused by `JAVA_HOME`, dependency resolution, or another test does not count. At RED, only the two
fake files and `ProfileViewModelTest.kt` may be modified.

- [ ] **Step 4: Define stable UI state with independent insight loads**

```kotlin
sealed interface ProfileLoadable<out T> {
    data object Empty : ProfileLoadable<Nothing>
    data object Loading : ProfileLoadable<Nothing>
    data class Ready<T>(val value: T) : ProfileLoadable<T>
    data class Failed(val cause: Throwable) : ProfileLoadable<Nothing>
}

data class ProfilePrHighlights(
    val maxWeightPerCableKg: Float?,
    val estimatedOneRepMaxPerCableKg: Float?,
    val maxVolumeKg: Float?,
)

data class ProfileUiState(
    val context: ActiveProfileContext? = null,
    val selectedExercise: Exercise? = null,
    val missingExerciseId: String? = null,
    val selectionFailure: Throwable? = null,
    val currentOneRepMax: ProfileLoadable<CurrentOneRepMax> = ProfileLoadable.Empty,
    val prHighlights: ProfileLoadable<ProfilePrHighlights> = ProfileLoadable.Empty,
    val recentSessions: ProfileLoadable<List<WorkoutSession>> = ProfileLoadable.Empty,
)

sealed interface ProfileUiEvent {
    data object PreferenceUpdateFailed : ProfileUiEvent
    data object IdentityUpdateFailed : ProfileUiEvent
    data object IdentityUpdated : ProfileUiEvent
    data object ProfileDeleted : ProfileUiEvent
}
```

`missingExerciseId` means the repository successfully resolved an ID that the exercise catalog no
longer contains. `selectionFailure` means selection/fallback lookup failed; never translate a
repository exception into "missing exercise." This is the Task 6 boundary: that consumer must
render `selectionFailure` with `profile_insights_load_failed` before its ordinary null-selection
empty state. Do not put localized strings or raw JSON in the ViewModel.

- [ ] **Step 5: Implement profile-bound selection lifecycle**

Construct the ViewModel with the existing Koin-bound repository types:

```kotlin
class ProfileViewModel(
    private val profiles: UserProfileRepository,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val personalRecords: PersonalRecordRepository,
    private val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase,
    private val externalMeasurements: ExternalMeasurementRepository,
) : ViewModel()
```

`externalMeasurements` is intentionally constructor-stable but unused until Task 8 adds
profile-scoped body-weight attribution; retain it and use `FakeExternalMeasurementRepository` in
Task 5 tests.

Maintain per-profile selection only inside this route-scoped instance. A second Ready emission for
the **same** profile is normal: `updateProfile`, every preference mutation, and local-safety updates
all republish `ActiveProfileContext.Ready`. It must refresh `context` without reconstructing
`ProfileUiState`, clearing loadables, resetting later mutation-busy fields, or re-querying insights.

```kotlin
private val _uiState = MutableStateFlow(ProfileUiState())
val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

private val selectedExerciseIds = mutableMapOf<String, String>()
private var resolvedSelectionProfileId: String? = null
private var insightsJob: Job? = null

init {
    viewModelScope.launch {
        profiles.activeProfileContext.collectLatest { context ->
            when (context) {
                is ActiveProfileContext.Switching -> {
                    insightsJob?.cancel()
                    resolvedSelectionProfileId = null
                    _uiState.value = ProfileUiState(context = context)
                }

                is ActiveProfileContext.Ready -> applyReadyContext(context)
            }
        }
    }
}

private suspend fun applyReadyContext(context: ActiveProfileContext.Ready) {
    val profileId = context.profile.id
    val currentProfileId =
        (_uiState.value.context as? ActiveProfileContext.Ready)?.profile?.id

    if (currentProfileId == profileId && resolvedSelectionProfileId == profileId) {
        updateIfProfileCurrent(profileId) { state -> state.copy(context = context) }
        return
    }

    insightsJob?.cancel()
    _uiState.value = ProfileUiState(context = context)
    try {
        require(profileId.isNotBlank()) { "Ready profile ID must not be blank" }
        val savedId = selectedExerciseIds[profileId]
        val saved = savedId?.let { validExercise(it) }
        val fallbackId = if (saved == null) {
            workouts.getMostRecentCompletedExerciseId(profileId)
        } else {
            null
        }
        val fallback = fallbackId?.let { validExercise(it) }
        val selected = saved ?: fallback
        if (!isProfileCurrent(profileId)) return

        resolvedSelectionProfileId = profileId
        selected?.id?.let { selectedExerciseIds[profileId] = it }
        updateIfProfileCurrent(profileId) { state ->
            state.copy(
                context = context,
                selectedExercise = selected,
                missingExerciseId = (fallbackId ?: savedId)
                    ?.takeIf { it.isNotBlank() && selected == null },
                selectionFailure = null,
            )
        }
        selected?.let { loadInsights(profileId, it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        resolvedSelectionProfileId = null
        updateIfProfileCurrent(profileId) { state ->
            state.copy(
                context = context,
                selectedExercise = null,
                missingExerciseId = null,
                selectionFailure = error,
            )
        }
    }
}

private suspend fun validExercise(requestedId: String): Exercise? {
    if (requestedId.isBlank()) return null
    return exercises.getExerciseById(requestedId)
        ?.takeIf { it.id == requestedId && !it.id.isNullOrBlank() }
}
```

Guard both repository truth and the potentially lagging UI snapshot. This prevents a picker tap after
the repository entered Switching from being saved under the previous profile:

```kotlin
fun selectExercise(exercise: Exercise) {
    val exerciseId = exercise.id?.takeIf { it.isNotBlank() } ?: return
    val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return
    val repositoryReady =
        profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return
    val profileId = uiReady.profile.id
    if (repositoryReady.profile.id != profileId) return

    selectedExerciseIds[profileId] = exerciseId
    resolvedSelectionProfileId = profileId
    updateIfProfileCurrent(profileId) { state ->
        state.copy(
            selectedExercise = exercise,
            missingExerciseId = null,
            selectionFailure = null,
        )
    }
    if (isSelectionCurrent(profileId, exerciseId)) {
        loadInsights(profileId, exercise)
    }
}

private fun isProfileCurrent(profileId: String): Boolean {
    val repositoryReady =
        profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return false
    val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return false
    return repositoryReady.profile.id == profileId && uiReady.profile.id == profileId
}

private fun isSelectionCurrent(profileId: String, exerciseId: String): Boolean =
    isProfileCurrent(profileId) &&
        uiState.value.selectedExercise?.id == exerciseId &&
        selectedExerciseIds[profileId] == exerciseId

private inline fun updateIfProfileCurrent(
    profileId: String,
    transform: (ProfileUiState) -> ProfileUiState,
) {
    _uiState.update { state ->
        val repositoryReady = profiles.activeProfileContext.value as? ActiveProfileContext.Ready
        val uiReady = state.context as? ActiveProfileContext.Ready
        if (repositoryReady?.profile?.id == profileId && uiReady?.profile?.id == profileId) {
            transform(state)
        } else {
            state
        }
    }
}

private inline fun updateIfSelectionCurrent(
    profileId: String,
    exerciseId: String,
    transform: (ProfileUiState) -> ProfileUiState,
) {
    _uiState.update { state ->
        val repositoryReady = profiles.activeProfileContext.value as? ActiveProfileContext.Ready
        val uiReady = state.context as? ActiveProfileContext.Ready
        if (
            repositoryReady?.profile?.id == profileId &&
            uiReady?.profile?.id == profileId &&
            state.selectedExercise?.id == exerciseId &&
            selectedExerciseIds[profileId] == exerciseId
        ) {
            transform(state)
        } else {
            state
        }
    }
}
```

Do not guard publication with only `selectedExerciseIds`: that map intentionally retains A while B
is active. Do not catch selection lookup with `runCatching`, because it also catches cancellation.

- [ ] **Step 6: Load 1RM, PR highlights, and five sessions independently**

Set all three branches to Loading in one guarded update, then start them as children of one
`supervisorScope`. The helper rethrows cancellation before the ordinary `Exception` catch and
checks repository profile, UI profile, selected exercise, and saved selection again before every
publication:

```kotlin
private fun loadInsights(profileId: String, exercise: Exercise) {
    val exerciseId = exercise.id?.takeIf { it.isNotBlank() } ?: return
    insightsJob?.cancel()
    updateIfSelectionCurrent(profileId, exerciseId) { state ->
        state.copy(
            currentOneRepMax = ProfileLoadable.Loading,
            prHighlights = ProfileLoadable.Loading,
            recentSessions = ProfileLoadable.Loading,
        )
    }
    if (!isSelectionCurrent(profileId, exerciseId)) return

    insightsJob = viewModelScope.launch {
        supervisorScope {
            launch {
                loadBranch(
                    profileId = profileId,
                    exerciseId = exerciseId,
                    load = {
                        resolveCurrentOneRepMax(exerciseId, profileId)
                            ?.let { ProfileLoadable.Ready(it) }
                            ?: ProfileLoadable.Empty
                    },
                    publish = { state, value -> state.copy(currentOneRepMax = value) },
                )
            }
            launch {
                loadBranch(
                    profileId = profileId,
                    exerciseId = exerciseId,
                    load = {
                        ProfileLoadable.Ready(
                            personalRecords.getAllPRsForExercise(exerciseId, profileId)
                                .toHighlights(),
                        )
                    },
                    publish = { state, value -> state.copy(prHighlights = value) },
                )
            }
            launch {
                loadBranch(
                    profileId = profileId,
                    exerciseId = exerciseId,
                    load = {
                        ProfileLoadable.Ready(
                            workouts.getRecentCompletedSessionsForExercise(
                                exerciseId = exerciseId,
                                profileId = profileId,
                                limit = MAX_RECENT_EXERCISE_SESSIONS,
                            ),
                        )
                    },
                    publish = { state, value -> state.copy(recentSessions = value) },
                )
            }
        }
    }
}

private suspend fun <T> loadBranch(
    profileId: String,
    exerciseId: String,
    load: suspend () -> ProfileLoadable<T>,
    publish: (ProfileUiState, ProfileLoadable<T>) -> ProfileUiState,
) {
    val result: ProfileLoadable<T> = try {
        load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ProfileLoadable.Failed(error)
    }
    updateIfSelectionCurrent(profileId, exerciseId) { state ->
        publish(state, result)
    }
}
```

Use the canonical PR field mappings and reject non-finite/non-positive values independently:

```kotlin
private fun List<PersonalRecord>.toHighlights() = ProfilePrHighlights(
    maxWeightPerCableKg = asSequence()
        .filter { it.prType == PRType.MAX_WEIGHT }
        .map { it.weightPerCableKg }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
    estimatedOneRepMaxPerCableKg = asSequence()
        .map { it.oneRepMax }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
    maxVolumeKg = asSequence()
        .filter { it.prType == PRType.MAX_VOLUME }
        .map { it.volume }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
)
```

Import `MAX_RECENT_EXERCISE_SESSIONS`. Treat a null resolver result as `ProfileLoadable.Empty`, not failure. Treat no PR rows as `Ready(ProfilePrHighlights(null, null, null))` and no sessions as `Ready(emptyList())`.

When velocity and assessment are absent, Task 2's resolver reads the same bounded history API that
the recent-sessions branch reads, so one load may produce two identical
`recentCompletedRequests`. Tests must not assert a single call. To isolate a recent-history failure
from the 1RM branch, seed a valid velocity or assessment result first.

- [ ] **Step 7: Register the route-scoped factory and make tests green**

```kotlin
import com.devil.phoenixproject.presentation.viewmodel.ProfileViewModel

factory {
    ProfileViewModel(
        profiles = get(),
        exercises = get(),
        workouts = get(),
        personalRecords = get(),
        resolveCurrentOneRepMax = get(),
        externalMeasurements = get(),
    )
}
```

All six dependencies already exist in `dataModule`/`domainModule`; do not add duplicate bindings.
Run the focused tests, fake regressions, Koin verification, and both production/test Native
compilers in one forced gate:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' `
    :shared:testAndroidHostTest `
    --tests "*ProfileViewModelTest*" `
    --tests "*ResolveCurrentOneRepMaxUseCaseTest*" `
    --tests "*KoinModuleVerifyTest*" `
    --tests "*SqlDelightUserProfileRepositoryTest*" `
    --tests "*PersonalRecordRepositoryTest*" `
    :shared:compileAndroidMain `
    :shared:compileKotlinIosArm64 `
    :shared:compileTestKotlinIosArm64 `
    --rerun-tasks `
    --console=plain
```

Expected: BUILD SUCCESSFUL. `compileTestKotlinIosArm64` is required because both modified fakes live
in `commonTest`; an Android-host pass alone does not prove their Native compatibility.

Assert the focused suite actually executed all fourteen tests:

```powershell
$resultPath = 'shared/build/test-results/testAndroidHostTest/' +
    'TEST-com.devil.phoenixproject.presentation.viewmodel.ProfileViewModelTest.xml'
[xml]$result = Get-Content -Raw $resultPath
$suite = $result.testsuite
if (
    [int]$suite.tests -ne 14 -or
    [int]$suite.failures -ne 0 -or
    [int]$suite.errors -ne 0 -or
    [int]$suite.skipped -ne 0
) {
    throw "Unexpected ProfileViewModelTest result: tests=$($suite.tests) " +
        "failures=$($suite.failures) errors=$($suite.errors) skipped=$($suite.skipped)"
}
```

Run intent-specific static checks:

```powershell
$viewModel = Get-Content -Raw `
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt'
$presentation = Get-Content -Raw `
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt'

if ([regex]::Matches($presentation, 'factory\s*\{\s*ProfileViewModel\(').Count -ne 1) {
    throw 'Expected exactly one route-scoped ProfileViewModel factory'
}
if ($viewModel -match 'catch\s*\([^)]*Throwable') {
    throw 'ProfileViewModel must not catch Throwable'
}
$cancellationCatch = $viewModel.IndexOf('catch (error: CancellationException)')
$ordinaryCatch = $viewModel.IndexOf('catch (error: Exception)')
if ($cancellationCatch -lt 0 -or $ordinaryCatch -lt 0 -or $cancellationCatch -gt $ordinaryCatch) {
    throw 'CancellationException must be caught and rethrown before ordinary Exception'
}
foreach ($required in @(
    'catch (error: CancellationException)',
    'catch (error: Exception)',
    'profiles.activeProfileContext.value',
    'updateIfProfileCurrent',
    'updateIfSelectionCurrent',
    'resolvedSelectionProfileId',
    'selectionFailure',
    'MAX_RECENT_EXERCISE_SESSIONS'
)) {
    if (-not $viewModel.Contains($required)) {
        throw "ProfileViewModel is missing required lifecycle guard: $required"
    }
}
```

Expected: exactly one factory, no broad `Throwable` catch, and every captured-context guard present.

- [ ] **Step 8: Commit the profile state layer**

```powershell
$expected = @(
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePersonalRecordRepository.kt'
    'shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt'
) | Sort-Object
$actual = @(git status --porcelain=v1 | ForEach-Object { $_.Substring(3) } | Sort-Object)
$scopeDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual
if ($scopeDiff) {
    $scopeDiff | Format-Table -AutoSize
    throw 'Task 5 worktree scope differs from the five-file state contract'
}
git diff --check -- $expected
if ($LASTEXITCODE -ne 0) { throw 'Task 5 diff check failed' }
git add -- $expected
$staged = @(git diff --cached --name-only | Sort-Object)
$stagedDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $staged
if ($stagedDiff) {
    $stagedDiff | Format-Table -AutoSize
    throw 'Task 5 staged scope differs from the five-file state contract'
}
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Task 5 cached diff check failed' }
git commit -m "feat: add profile scoped insights state"
```

---

### Task 6: Build the Compact Profile Header and Exercise Insights Surface

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/KmpUtils.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/KmpUtilsTest.kt`

**Interfaces:**
- Consumes: Task 4's callback-only identity/dialog components and single-owner action tags; Task 5's stable `selectionFailure`, selected/missing exercise state, and three independent `ProfileLoadable` blocks; `ExercisePickerDialog`; `VolumeHistoryChart`; `WeightDisplayFormatter`; and `WorkoutSession.effectiveTotalVolumeKg`.
- Produces: atomic `UserProfileRepository.deleteActiveProfile(expectedProfileId)`, explicit `KmpUtils` support for `"MMM d"`, token/profile/kind-scoped identity mutation state and events, `buildProfileRecentHistory`, a compiling `ProfileScreen`, active-profile edit/delete actions, and compact independently resilient insight cards. Task 7 receives `onProfileRecoveryRequired` and can register the finished route without taking ownership of mutation state.
- Identity invariant: the UI may delete only the active non-default profile, and the repository validates that same identity while holding `profileContextMutex`; a concurrent switch can never make the delete copy's Default reassignment claim false.
- Overlay invariant: picker/edit/delete state is keyed by profile ID, terminal events are matched to that target, and a Switching/new-Ready context cannot retarget an already-open overlay.
- Accessibility/tag invariant: all selector/header actions have 48dp targets and localized names; `SCREEN_PROFILE` belongs to the screen root, `ACTION_EDIT_PROFILE`/`ACTION_DELETE_PROFILE` belong only to header triggers, and Task 4 dialogs do not reuse action tags on confirmation buttons.

#### Hardened Task 6 execution contract (authoritative)

This block supersedes any older Task 6 snippet below where the two disagree. Do not begin
production edits until the expanded red suite reaches the compiler/test runner.

**Atomic active deletion**

- Add `suspend fun deleteActiveProfile(expectedProfileId: String): Boolean` to
  `UserProfileRepository`; retain generic `deleteProfile` for the legacy side panel until Task 10.
- In `SqlDelightUserProfileRepository`, validate the current `ActiveProfileContext.Ready` and
  `expectedProfileId` while already holding `profileContextMutex`. A mismatch throws
  `StaleProfileContextException`; Switching throws `ProfileContextUnavailableException`.
- Refactor the existing deletion body into one lock-owned helper. The active-only path requires a
  non-default active row and always reassigns to Default. There must be no check-then-lock gap and
  no nested acquisition of `profileContextMutex`.
- Give `FakeUserProfileRepository` exact parity, plus update/delete call histories, ordinary
  failure seams, and suspend gates used by deterministic cancellation/race tests.
- Add two SQLDelight repository tests: a stale expected ID after a completed switch throws without
  mutating either profile, and an active deletion racing a queued switch can only (a) delete and
  reassign to Default before the switch or (b) fail stale before deletion. It may never reassign
  the deleted profile to the newly active non-default profile.

**Scoped identity mutations**

- Replace the bare Boolean operation model with token/profile/kind ownership:

```kotlin
enum class ProfileIdentityMutationKind { UPDATE, DELETE }

data class ProfileIdentityMutation(
    val token: Long,
    val profileId: String,
    val kind: ProfileIdentityMutationKind,
)
```

- `ProfileUiState` owns `identityMutation: ProfileIdentityMutation?`; expose
  `identityMutationInFlight` as `identityMutation != null`. Task 5's Switching/new-profile reset
  paths must preserve the current mutation record until its owning job clears it.
- Scope terminal events with IDs/kinds:

```kotlin
data class IdentityUpdated(val profileId: String) : ProfileUiEvent
data class IdentityUpdateFailed(
    val profileId: String,
    val kind: ProfileIdentityMutationKind,
) : ProfileUiEvent
data class ProfileDeleted(val profileId: String) : ProfileUiEvent
data class ProfileRecoveryRequired(
    val profileId: String,
    val cause: ProfileContextRecoveryException,
) : ProfileUiEvent
```

- Capture only when repository and UI contexts are both Ready for the same profile. Reject blank
  names, default-profile deletion, stale pairs, and overlaps before launching. Normalize submitted
  color indices with Task 4's helper.
- Allocate the token and publish busy state synchronously before returning from the public action,
  so two calls in the same frame cannot both launch. Recheck current ownership immediately before
  an update repository call. Switching cancels an UPDATE job but must not blindly cancel DELETE,
  because successful active deletion emits its own Switching(Default) transition.
- Rethrow `CancellationException` and emit no failure event. Ordinary exceptions emit a matching
  scoped failure only if the token still owns the operation. A
  `ProfileContextRecoveryException` emits only `ProfileRecoveryRequired` for Task 7's blocking
  recovery owner; it is never reduced to `profile_update_failed`.
- Clear busy state (only if the finishing token still owns it) before sending a terminal event.
  Suppress stale update success/failure after a profile switch. Call the new atomic
  `deleteActiveProfile(capturedProfileId)` for deletion.

Add exactly eleven identity-mutation tests to the existing fourteen ViewModel tests (25 total):

1. trimmed/normalized update targets the current Ready ID and success is observed only after the
   repository value is authoritative;
2. ordinary update failure leaves authoritative state, clears its token, and emits one scoped
   failure;
3. cancellation emits no failure/success and clears only its own token;
4. an overlapping update/delete call is rejected synchronously;
5. a same-profile Ready refresh preserves the in-flight token and does not restart insights;
6. a gated update followed by A→B cannot mutate B or publish a stale A event;
7. Default deletion never reaches the repository;
8. successful active deletion emits one scoped `ProfileDeleted` after busy clears;
9. repository `false` emits one scoped delete failure;
10. an ordinary delete exception emits one scoped delete failure without closing state; and
11. `ProfileContextRecoveryException` emits only the scoped recovery event.

**Compact insights and screen state**

- Add `selectionFailure: Throwable?` to `ProfileExerciseInsights`. With no selection, precedence is
  selection failure → missing exercise → ordinary no-history. Keep the exercise selector visible
  in every case so manual selection can recover.
- `CurrentOneRepMaxSource` has only VELOCITY/ASSESSMENT/SESSION. Map those three resources; use
  `profile_one_rep_max_source_none` only for `ProfileLoadable.Empty`.
- `ProfilePrHighlights.maxVolumeKg` is a legacy-named per-cable `kg × reps` value from
  `PersonalRecord.volume`, not a total. Convert its kg magnitude exactly once for LB display and
  never multiply it by cable count. Use `effectiveTotalVolumeKg()` only for recent sessions.
- Add a pure `buildProfileRecentHistory` helper. Sort defensively by timestamp DESC then ID DESC,
  take five for the newest-first accessible list, reverse that bounded list for oldest→newest chart
  order, and create chart points only for finite positive total volume. An empty valid chart does
  not hide the session list.
- Add explicit `"MMM d"` handling to `KmpUtils.formatTimestamp` and a focused common test proving it
  does not fall through to numeric `MM/dd/yyyy`.
- The exercise selector is a named `Role.Button` with a 48dp minimum. Header switch/edit/delete
  triggers are named 48dp controls and all are disabled during any identity mutation. Edit/delete
  tags appear only on these header triggers. Treat the chart as decorative because the accessible
  newest-first list conveys the same sessions.
- Add `TestTags.SCREEN_PROFILE` in this task. For a non-Ready context, use a full-size centered
  `Column` with `LoadingIndicator(LoadingIndicatorSize.Large)` and localized
  `profile_switching`; `LoadingIndicator` has no `message` parameter.
- Store `pickerProfileId`, `editTargetProfileId`, and `deleteTargetProfileId`, not free-floating
  Booleans. Render each overlay only when its target equals the current Ready ID. Clear mismatched
  targets on Switching/profile changes. Match scoped terminal events by ID and kind before closing;
  ordinary matching failure keeps the dialog open and shows one snackbar. Forward a matching
  recovery event through `onProfileRecoveryRequired` for Task 7.

Add exactly six `ProfileScreenContractTest` cases:

1. `SCREEN_PROFILE` exists and the non-Ready loader uses the real `LoadingIndicator` API;
2. selection failure has precedence over missing/empty copy while the picker remains available;
3. picker, all insight resources, source-empty handling, and no `Exercise.oneRepMaxKg` legacy read;
4. the pure recent-history helper proves deterministic newest-first list, oldest-first chart, five
   row cap, and nonfinite/nonpositive chart filtering;
5. PR max-volume display documents per-cable semantics and never applies cable multiplication; and
6. profile-keyed overlays, scoped event matching, 48dp named header controls, and single-owner tags.

**Required red/green and scope gates**

The red run includes all four focused families and records the missing atomic API/screen symbols:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests "*ProfileViewModelTest*" `
  --tests "*ProfileScreenContractTest*" `
  --tests "*SqlDelightUserProfileRepositoryTest*" `
  --tests "*KmpUtilsTest*" --console=plain
```

The final uncached gate is:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests "*ProfileViewModelTest*" `
  --tests "*ProfileScreenContractTest*" `
  --tests "*SqlDelightUserProfileRepositoryTest*" `
  --tests "*KmpUtilsTest*" `
  :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 `
  --rerun-tasks --console=plain
```

Require 25 ViewModel tests, 6 screen-contract tests, 2 new atomic-deletion tests within the existing
repository suite, and the compact-date test, all with zero failures/errors/skips. Run
`git diff --check` over exactly the eleven Task 6 files and verify both worktree and staged scope
before commit. No navigation, Settings, RoutinesTab, or resource bundle is in Task 6 scope.

- [ ] **Step 1: Write the expanded failing repository, mutation, formatter, and screen tests**

The authoritative matrix above replaces the smaller starter examples below: implement all
11 mutation, 6 screen, 2 atomic-delete, and 1 compact-date cases before production code.

Add `updateProfileFailure` and `deleteProfileFailure` test controls to `FakeUserProfileRepository`, throwing before mutating when set. Then add ViewModel tests proving:

- `updateIdentity` trims the name, passes the current Ready profile ID, and emits `IdentityUpdated` only after repository success.
- an update exception emits `IdentityUpdateFailed` and leaves the Ready repository value authoritative.
- `deleteActiveProfile` never calls the repository for ID `default`.
- successful non-default deletion emits `ProfileDeleted`; `false` or an exception emits `IdentityUpdateFailed`.

Create the source contract test:

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProfileScreenContractTest {
    @Test
    fun profileInsightsUseExistingPickerAndCompactHistory() {
        val screen = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
        )
        val insights = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt",
        )

        assertTrue(screen.contains("ExercisePickerDialog("))
        assertTrue(screen.contains("enableCustomExercises = false"))
        assertTrue(insights.contains("VolumeHistoryChart("))
        assertTrue(insights.contains("ProfileLoadable.Failed"))
        assertTrue(insights.contains("take(5)"))
    }

    @Test
    fun profileIdentityAndInsightsConsumeTheirCompleteResourceInventory() {
        val screen = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
        )
        val insights = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt",
        )

        assertUsesResources(
            screen,
            listOf("switch_profile", "profile_switching", "profile_update_failed"),
        )
        assertUsesResources(
            insights,
            listOf(
                "profile_exercise_insights",
                "profile_choose_exercise",
                "profile_no_exercise_history",
                "profile_current_one_rep_max",
                "profile_one_rep_max_source_velocity",
                "profile_one_rep_max_source_assessment",
                "profile_one_rep_max_source_session",
                "profile_one_rep_max_source_none",
                "profile_pr_highlights",
                "profile_pr_max_weight",
                "profile_pr_estimated_one_rep_max",
                "profile_pr_max_volume",
                "profile_recent_history",
                "profile_view_full_history",
                "profile_missing_exercise",
                "profile_insights_load_failed",
            ),
        )
    }

    private fun source(path: String): String = requireNotNull(readProjectFile(path), path)

    private fun assertUsesResources(source: String, keys: List<String>) {
        keys.forEach { key -> assertContains(source, "Res.string.$key", key) }
    }
}
```

- [ ] **Step 2: Run the four-family red gate and confirm the missing atomic/UI contracts**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests "*ProfileViewModelTest*" --tests "*ProfileScreenContractTest*" `
  --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*KmpUtilsTest*" --console=plain
```

Expected: FAIL for the missing atomic active-delete API, scoped identity state/events, compact date
formatter, history helper, and Profile UI files. Use the authoritative four-family command above.

- [ ] **Step 3: Add atomic deletion and scoped identity mutations**

Implement the lock-owned repository API, fake parity, token/profile/kind state, scoped events,
cancellation/recovery distinctions, and owner-only busy clearing exactly as specified above. Keep
events on a buffered one-shot channel; never replay them on recomposition.

- [ ] **Step 4: Build the compact insight component**

Use this boundary:

```kotlin
@Composable
fun ProfileExerciseInsights(
    selectedExercise: Exercise?,
    missingExerciseId: String?,
    selectionFailure: Throwable?,
    currentOneRepMax: ProfileLoadable<CurrentOneRepMax>,
    prHighlights: ProfileLoadable<ProfilePrHighlights>,
    recentSessions: ProfileLoadable<List<WorkoutSession>>,
    weightUnit: WeightUnit,
    onChooseExercise: () -> Unit,
    onViewFullHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

Implementation rules:

- The first row is a full-width, 48dp-minimum selector showing the exercise display name or `profile_choose_exercise` and a search icon.
- If selection is null, render selection failure first, then `profile_missing_exercise`, then ordinary `profile_no_exercise_history`. Render no metric shells in those states, but keep the selector available.
- Current 1RM uses `WeightDisplayFormatter.formatPerCableWeight`, the selected unit suffix, and maps the three enum sources. `ProfileLoadable.Empty` alone consumes `profile_one_rep_max_source_none`.
- PR highlights are three compact equal-width cells. Max weight and estimated 1RM are per-cable displays.
- PR max volume is stored per-cable kg×reps: convert once and never multiply by cable count.
- Recent history uses `buildProfileRecentHistory`: newest-first deterministic list, oldest-first finite-positive chart points, explicit `"MMM d"`, a 112dp decorative chart, and an accessible compact list.
- A `Failed` branch renders `profile_insights_load_failed` only inside that metric card. Other cards remain visible.
- A nonempty recent list ends with `profile_view_full_history`; pass the nonblank selected exercise ID to the callback.

- [ ] **Step 5: Compose ProfileScreen with header, dialogs, picker, and one snackbar host**

Start with this route-ready API; Task 8 adds the preference callbacks below the insight component without changing navigation ownership:

```kotlin
@Composable
fun ProfileScreen(
    onOpenProfileSwitcher: () -> Unit,
    onNavigateToExerciseDetail: (String) -> Unit,
    onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit,
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    exerciseRepository: ExerciseRepository = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val ready = state.context as? ActiveProfileContext.Ready
    var pickerProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.testTag(TestTags.SCREEN_PROFILE),
    ) { padding ->
        if (ready == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LoadingIndicator(LoadingIndicatorSize.Large)
                Text(stringResource(Res.string.profile_switching))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "profile-header") {
                    ProfileHeaderCard(
                        profile = ready.profile,
                        identityMutationInFlight = state.identityMutationInFlight,
                        onSwitchProfile = onOpenProfileSwitcher,
                        onEdit = { editTargetProfileId = ready.profile.id },
                        onDelete = { deleteTargetProfileId = ready.profile.id },
                    )
                }
                item(key = "exercise-insights") {
                    ProfileExerciseInsights(
                        selectedExercise = state.selectedExercise,
                        missingExerciseId = state.missingExerciseId,
                        selectionFailure = state.selectionFailure,
                        currentOneRepMax = state.currentOneRepMax,
                        prHighlights = state.prHighlights,
                        recentSessions = state.recentSessions,
                        weightUnit = ready.preferences.core.value.weightUnit,
                        onChooseExercise = { pickerProfileId = ready.profile.id },
                        onViewFullHistory = onNavigateToExerciseDetail,
                    )
                }
            }
        }
    }
}
```

The header is a compact Material 3 card with a 56dp `ProfileAvatar`, active name, visible `Switch Profile` button, edit icon, and—only when `canDeleteProfile(ready.profile)`—delete icon. Give every action a named 48dp target and attach edit/delete tags only to those header triggers. Call `ProfileEditDialog` and `ProfileDeleteDialog` outside the lazy list only when their target ID still equals the Ready profile; pass `state.identityMutationInFlight`. Match scoped ID/kind events before closing. On a matching ordinary failure, keep the dialog open and show the localized update error exactly once; forward only `ProfileRecoveryRequired` to `onProfileRecoveryRequired`.

Render `ExercisePickerDialog` outside the list with:

```kotlin
ExercisePickerDialog(
    showDialog = pickerProfileId == ready?.profile?.id,
    onDismiss = { pickerProfileId = null },
    onExerciseSelected = { exercise ->
        pickerProfileId = null
        viewModel.selectExercise(exercise)
    },
    exerciseRepository = exerciseRepository,
    enableVideoPlayback = enableVideoPlayback,
    themeMode = themeMode,
    enableCustomExercises = false,
)
```

Clear picker/edit/delete target IDs whenever the Ready profile ID changes or the context enters
Switching. Do not let a saved Boolean retarget an overlay to the next profile.

- [ ] **Step 6: Run the authoritative uncached four-family and cross-target gate**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests "*ProfileViewModelTest*" --tests "*ProfileScreenContractTest*" `
  --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*KmpUtilsTest*" `
  :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 `
  --rerun-tasks --console=plain
```

Expected: BUILD SUCCESSFUL with the authoritative counts, zero failures/errors/skips, and Android/iOS main plus iOS test compilation.

- [ ] **Step 7: Commit the Profile identity and insight UI**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/KmpUtils.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/util/KmpUtilsTest.kt
git commit -m "feat: build profile identity and exercise insights"
```

---

### Task 7: Add the Fifth Root Tab and Shared Long-Press Profile Switcher

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModel.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModelTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt`

**Interfaces:**
- Consumes: Tasks 3 and 4's localized recovery copy and callback-only modal surfaces; Task 6's exact `ProfileScreen(..., onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit, ...)` handoff; `UserProfileRepository.setActiveProfile`, `createAndActivateProfile`, and `reconcileActiveProfileContext`; and the full `ActiveProfileContext` sealed state, including `Switching(null)`.
- Produces: `NavigationRoutes.Profile`; the single canonical Analytics/Workout/Insights/Profile/Settings root order; a root-scoped `ProfileSwitcherViewModel`; pointer and semantic long press with haptic feedback; inline modal errors; one blocking recovery owner; and removal of every Home/Just Lift legacy profile selector and repository-coupled identity dialog.
- Concurrency invariant: switch, create, and recovery-retry operations acquire a synchronous token before launching, only the owning token may publish a terminal state, and Task 6 recovery invalidates any stale root operation before showing recovery.
- Modal invariant: `switchingInFlight` is explicit and never inferred from a nullable target ID; `Switching(null)` disables rows, add, gestures, back, scrim, and dismissal. Errors render inside their owning modal and never queue behind it in a root snackbar.
- Ownership invariant: `ProfileSwitcherViewModel` is the only switch/create/reconcile caller in presentation code after this task. `ProfileScreen` and the Profile tab long press only dispatch callbacks to that root owner.

#### Hardened Task 7 execution contract (authoritative)

This block supersedes every older Task 7 snippet below where the two disagree. Execute this block
in order. Do not start production edits until the complete fake-supported red suite reaches the
compiler/test runner.

- [ ] **Step A: Add deterministic fake seams and the complete failing test suites**

Extend `FakeUserProfileRepository` without changing the production interface. Task 6 owns its
separately named update/delete controls; add these switch/create/reconcile controls exactly:

```kotlin
data class CreateAndActivateRequest(val name: String, val colorIndex: Int)

val setActiveProfileRequests = mutableListOf<String>()
val createAndActivateRequests = mutableListOf<CreateAndActivateRequest>()
var reconcileActiveProfileContextRequests: Int = 0

var setActiveProfileFailure: Throwable? = null
var createAndActivateProfileFailure: Throwable? = null
var reconcileActiveProfileContextFailure: Throwable? = null

var beforeSetActiveProfile: (suspend (String) -> Unit)? = null
var beforeCreateAndActivateProfile: (suspend (String, Int) -> Unit)? = null
var beforeReconcileActiveProfileContext: (suspend () -> Unit)? = null

fun resetRootProfileOperationControls() {
    setActiveProfileRequests.clear()
    createAndActivateRequests.clear()
    reconcileActiveProfileContextRequests = 0
    setActiveProfileFailure = null
    createAndActivateProfileFailure = null
    reconcileActiveProfileContextFailure = null
    beforeSetActiveProfile = null
    beforeCreateAndActivateProfile = null
    beforeReconcileActiveProfileContext = null
}
```

Each fake method records its request, invokes its suspend hook, checks its failure seam, and only
then enters the existing mutex-backed success path. Failure therefore occurs before mutation.
Hooks allow a test to suspend cooperatively or swallow cancellation deliberately; `reset` clears
every control.

Create `ProfileSwitcherViewModelTest.kt` with `TestCoroutineRule`, fresh fakes per test, and exactly
these seventeen tests:

1. `sheet opens during Switching null but cannot dismiss or start an operation`;
2. `successful switch calls the repository once and closes the sheet`;
3. `ordinary switch failure keeps the sheet open with only switch error`;
4. `switch recovery failure closes ordinary overlays and opens blocking recovery`;
5. `switch cancellation clears only its token and emits no error`;
6. `two same-frame switch requests launch only the first`;
7. `noncooperative stale switch completion cannot close a newer recovery state`;
8. `add opens from the sheet and dismiss returns to that sheet`;
9. `successful create and activate calls once and closes both overlays`;
10. `ordinary create failure keeps both overlays with only create error`;
11. `create recovery failure closes both overlays and opens blocking recovery`;
12. `two same-frame create confirmations launch only the first`;
13. `Task 6 recovery handoff cancels ownership and opens the same recovery state`;
14. `successful recovery retry clears the blocking modal`;
15. `failed recovery retry keeps the modal with only retry error`;
16. `two same-frame recovery retries launch only the first`; and
17. `recovery cancellation clears its token but keeps recovery blocking without an error`.

Use `CompletableDeferred` gates in tests 6, 7, 12, and 16. The stale test must swallow
`CancellationException` inside the fake hook, return, and prove the old token cannot publish.
Construct recovery failures as
`ProfileContextRecoveryException(IllegalStateException("recovery"))`. Assert exact request
histories, token kind/target, overlay booleans, and the single matching error enum after every
transition.

Create `ProfileNavigationContractTest.kt` with exactly eight source-contract cases:

1. one `NavigationRoutes.Profile` and the exact five-value `BottomNavItem` order;
2. exactly five bottom-bar cells in canonical order with 8dp padding, 4dp spacing, equal weights,
   and 48dp minimum targets;
3. a selectable-group parent and merged `Role.Tab`, selected, click, labeled long-click, and all
   five stable navigation tags;
4. Profile pointer/semantic long press performs `HapticFeedbackType.LongPress`, opens only the
   switcher, and does not invoke normal navigation;
5. normal Profile tap uses Home `popUpTo { saveState = true }`, `launchSingleTop`, and
   `restoreState`, while bottom-bar visibility and selection include Profile;
6. `NavGraph` registers `ProfileScreen`, passes both root callbacks, and uses localized
   `nav_profile` for the destination and shell title paths;
7. explicit `switchingInFlight`, inline switch/create/retry errors, and the non-dismissible
   recovery dialog are present; and
8. Enhanced Main and Just Lift contain no legacy selector/add-dialog state and all four legacy
   source files are absent.

The tag contract must reject `Modifier.weight(1f).testTag(...)` as the only tag placement when the
item later calls `clearAndSetSemantics`; it must require the tag to be appended after the clear in
the item implementation. It must also assert `TestTags.SCREEN_PROFILE` is consumed but not declared
by Task 7, because Task 6 owns that constant.

- [ ] **Step B: Run the new suites and record a genuine red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
    --tests "*ProfileSwitcherViewModelTest*" `
    --tests "*ProfileNavigationContractTest*" `
    --console=plain
```

Expected: FAIL at `compileAndroidHostTest` because `ProfileSwitcherViewModel` and its typed state do
not exist, plus navigation/source assertions remain red. At this point only the two new test files
and `FakeUserProfileRepository.kt` may differ from the Task 6 commit. A Java/tooling failure is not
valid RED evidence.

- [ ] **Step C: Implement the root-scoped tokenized coordinator**

Create `ProfileSwitcherViewModel.kt` with this stable public state boundary:

```kotlin
enum class RootProfileOperationKind { SWITCH, CREATE, RECOVERY }

data class RootProfileOperation(
    val token: Long,
    val kind: RootProfileOperationKind,
    val targetProfileId: String? = null,
)

enum class ProfileOverlayError {
    SWITCH_FAILED,
    CREATE_FAILED,
    RECOVERY_RETRY_FAILED,
}

data class ProfileSwitcherUiState(
    val showSwitcher: Boolean = false,
    val showAddDialog: Boolean = false,
    val recoveryRequired: Boolean = false,
    val operation: RootProfileOperation? = null,
    val error: ProfileOverlayError? = null,
)

class ProfileSwitcherViewModel(
    private val profiles: UserProfileRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileSwitcherUiState())
    val uiState: StateFlow<ProfileSwitcherUiState> = _uiState.asStateFlow()

    private var nextToken = 0L
    private var operationJob: Job? = null

    fun openSwitcher()
    fun dismissSwitcher()
    fun openAddDialog()
    fun dismissAddDialog()
    fun switchProfile(profileId: String)
    fun createAndActivateProfile(name: String, colorIndex: Int)
    fun requireRecovery(error: ProfileContextRecoveryException)
    fun retryRecovery()
}
```

All public methods are called on the UI thread. `beginOperation(kind, target)` checks
`uiState.value.operation == null` and `!recoveryRequired`, allocates `++nextToken`, and writes the
operation into `_uiState` before returning or launching. Switch additionally requires a nonblank
target and repository `ActiveProfileContext.Ready`; it ignores the current Ready ID. Create requires
the add dialog, a nonblank trimmed name, and Ready. Retry requires `recoveryRequired`.

Use one ownership helper for every terminal transition:

```kotlin
private inline fun finishOwned(
    token: Long,
    transform: (ProfileSwitcherUiState) -> ProfileSwitcherUiState,
) {
    _uiState.update { state ->
        if (state.operation?.token == token) transform(state) else state
    }
}

private fun enterRecovery(error: ProfileContextRecoveryException) {
    Logger.e(error) { "PROFILE_CONTEXT: root recovery required" }
    operationJob?.cancel()
    _uiState.value = ProfileSwitcherUiState(recoveryRequired = true)
}
```

For switch/create: rethrow `CancellationException`; route
`ProfileContextRecoveryException` only to `enterRecovery`; route an ordinary `Exception` only to
the matching inline error; and close overlays only on success. Clear the owning operation in every
terminal state. For retry, any ordinary/recovery exception maps to `RECOVERY_RETRY_FAILED` while
`recoveryRequired` stays true. Cancellation clears only the owned operation and retains recovery.
`requireRecovery` is Task 6's handoff and must enter the same state synchronously, invalidating any
old token before its coroutine can return.

Register exactly one route-root factory in `PresentationModule.kt`:

```kotlin
factory { ProfileSwitcherViewModel(profiles = get()) }
```

`EnhancedMainScreen` obtains it with a default `koinViewModel()` parameter. The Android root
`ViewModelStoreOwner` preserves the blocking recovery/overlay state through configuration
recreation; process-death recovery remains backed by the repository's durable transition journal.

- [ ] **Step D: Make the Task 4 modals explicitly blocking and error-owning**

Change the sheet boundary to distinguish an unknown target from no operation:

```kotlin
@Composable
fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    activeProfileId: String?,
    switchingInFlight: Boolean,
    switchingTargetProfileId: String?,
    errorMessage: String?,
    onSelectProfile: (UserProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDismiss: () -> Unit,
)

internal fun canDismissProfileSwitcher(switchingInFlight: Boolean): Boolean =
    !switchingInFlight
```

Use `rememberUpdatedState(switchingInFlight)` in both `confirmValueChange` and
`onDismissRequest`; set `sheetGesturesEnabled = !switchingInFlight`; disable every row and Add
action while true. The optional target controls only which row shows the progress indicator. Render
`errorMessage` below the title with error color and
`Modifier.semantics { liveRegion = LiveRegionMode.Polite }`.

Extend `ProfileAddDialog` with `errorMessage: String?` and render it inside the dialog using the
same polite live-region semantics. Keep Task 4's submission guards and callback-only behavior.
Add:

```kotlin
@Composable
fun ProfileRecoveryDialog(
    isRetrying: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.profile_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.profile_recovery_message))
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onRetry, enabled = !isRetrying) {
                Text(stringResource(Res.string.action_retry))
            }
        },
    )
}
```

Do not add a dismiss button or action tags to confirmation buttons. Update the existing seven-case
`ProfileIdentityPolicyTest` in place for the Boolean dismissal API, inline live regions, callback
boundary, and the absence of duplicate action tags; the suite remains exactly seven tests.

- [ ] **Step E: Add the route, exact five-tab semantics, and localized title plumbing**

Add `NavigationRoutes.Profile("profile")`. Replace the unused labeled enum with the single
canonical root order:

```kotlin
enum class BottomNavItem(val route: String) {
    ANALYTICS(NavigationRoutes.Analytics.route),
    WORKOUT(NavigationRoutes.Home.route),
    INSIGHTS(NavigationRoutes.SmartInsights.route),
    PROFILE(NavigationRoutes.Profile.route),
    SETTINGS(NavigationRoutes.Settings.route),
}
```

Iterate `BottomNavItem.entries` in `PhoenixBottomNavigationBar`; map icon, localized description,
selected state, callback, and test tag with exhaustive `when` expressions. This prevents a second
manual order from drifting. The Row uses
`Modifier.fillMaxWidth().height(barHeight).padding(horizontal = 8.dp).selectableGroup()` and
`Arrangement.spacedBy(4.dp)`.

Use this item boundary; `testTag` is appended after `clearAndSetSemantics` so it survives the
semantic replacement:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoenixBottomNavigationItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Tab
                this.selected = selected
                this.onClick(label = contentDescription) { onClick(); true }
                if (onLongClick != null && longClickLabel != null) {
                    this.onLongClick(label = longClickLabel) { onLongClick(); true }
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
    }
}
```

Map all existing `NAV_ANALYTICS`, `NAV_WORKOUTS`, `NAV_INSIGHTS`, and `NAV_SETTINGS` tags and add
only `NAV_PROFILE`; Task 6 already adds `SCREEN_PROFILE`. The Profile long-click callback performs
`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` and then
`profileSwitcherViewModel.openSwitcher()`. Its ordinary click only navigates.

Include Profile in `shouldShowBottomBar`. Its ordinary click uses exactly:

```kotlin
navController.navigate(NavigationRoutes.Profile.route) {
    popUpTo(NavigationRoutes.Home.route) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```

Compute `val profileTitle = stringResource(Res.string.nav_profile)` in Enhanced Main, add a required
`profileTitle: String` parameter to `getScreenTitle`, return it for the Profile route, and let
`getCompactScreenTitle` return that already-localized title. Do not hardcode English.

- [ ] **Step F: Register ProfileScreen and wire the single recovery owner**

Add both required callbacks to `NavGraph` before its default `modifier` parameter:

```kotlin
onOpenProfileSwitcher: () -> Unit,
onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit,
```

Register Profile immediately after Smart Insights with tab fade transitions. Capture localized
`nav_profile`, update the shell title in `LaunchedEffect(profileTitle)`, collect video preferences,
and call Task 6's exact route API:

```kotlin
ProfileScreen(
    onOpenProfileSwitcher = onOpenProfileSwitcher,
    onNavigateToExerciseDetail = { exerciseId ->
        navController.navigate(NavigationRoutes.ExerciseDetail.createRoute(exerciseId))
    },
    onProfileRecoveryRequired = onProfileRecoveryRequired,
    enableVideoPlayback = userPreferences.enableVideoPlayback,
    themeMode = themeMode,
)
```

In Enhanced Main collect `activeProfileContext` in addition to `allProfiles`; remove the separate
`activeProfile` collection. Derive:

```kotlin
val readyProfileId =
    (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id
val repositorySwitching = activeProfileContext is ActiveProfileContext.Switching
val repositorySwitchTarget =
    (activeProfileContext as? ActiveProfileContext.Switching)?.targetProfileId
val localSwitchTarget = switcherState.operation
    ?.takeIf { it.kind == RootProfileOperationKind.SWITCH }
    ?.targetProfileId
val localOperationInFlight = switcherState.operation != null
val switchingInFlight = repositorySwitching || localOperationInFlight
val switchingTargetProfileId = repositorySwitchTarget ?: localSwitchTarget
```

Render the sheet, Add dialog, and recovery dialog after the root Scaffold. Their callbacks call only
the ViewModel methods. Map `ProfileOverlayError` to `profile_switch_failed`,
`profile_create_failed`, or `profile_recovery_retry_failed` only in its owning modal. There is no
root snackbar for these modal-bound errors.

Pass these callbacks into `NavGraph`:

```kotlin
onOpenProfileSwitcher = switcherViewModel::openSwitcher,
onProfileRecoveryRequired = switcherViewModel::requireRecovery,
```

The recovery dialog renders whenever `switcherState.recoveryRequired`; it remains present through
retry failure and clears only after successful reconciliation. Because `requireRecovery` resets
ordinary overlays and errors synchronously, a Task 6 identity recovery event can never fall through
to ProfileScreen's ordinary update snackbar or a root switch/create error.

- [ ] **Step G: Remove every legacy selector in the same atomic task**

Delete the Home-only `ProfileSidePanel` block and old Add dialog from Enhanced Main. Delete the
four legacy files listed in this task. Simplify `ProfileListItem` to a sheet-only API by removing
`onLongClick`, `combinedClickable`, and the legacy semantics branch; retain its `selectable`
radio/selected semantics and switching indicator.

In `JustLiftScreen`, remove only `allProfiles`, `activeProfile`, `showAddProfileDialog`,
`rememberCoroutineScope`, `ProfileSidePanel`, old `AddProfileDialog`, and their imports. Retain
`UserProfileRepository`, `activeProfileContext`, and `readyProfileId`: Just Lift still needs the
Ready profile ID to bind workout configuration and must not fall back across profiles.

Before deletion, run:

```powershell
rg -n "\b(ProfileSidePanel|ProfileSpeedDial|EditProfileDialog|DeleteProfileDialog)\b" `
    shared/src/commonMain/kotlin
```

Expected before deletion: definitions plus the two known selector call sites only. After deletion,
the same command must exit 1. Also verify `ProfileColors`, `PROFILE_COLOR_COUNT`, `ProfileAvatar`,
and `Profile(Add|Edit|Delete)Dialog` resolve only to Task 4's extracted identity files and current
callers.

- [ ] **Step H: Run the forced full gate and assert every suite actually executed**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' `
    :shared:testAndroidHostTest `
    --tests "*ProfileSwitcherViewModelTest*" `
    --tests "*ProfileNavigationContractTest*" `
    --tests "*ProfileIdentityPolicyTest*" `
    --tests "*ProfileScreenContractTest*" `
    --tests "*ProfileViewModelTest*" `
    --tests "*KoinModuleVerifyTest*" `
    :shared:compileAndroidMain `
    :shared:compileKotlinIosArm64 `
    :shared:compileTestKotlinIosArm64 `
    :androidApp:assembleDebug `
    --rerun-tasks `
    --console=plain
```

Expected: BUILD SUCCESSFUL. Assert XML counts and zero failures/errors/skips: exactly 17
`ProfileSwitcherViewModelTest`, 8 `ProfileNavigationContractTest`, 7
`ProfileIdentityPolicyTest`, 6 hardened `ProfileScreenContractTest`, 25 hardened
`ProfileViewModelTest`, and 1 `KoinModuleVerifyTest` test (64 total). The iOS test compiler is
mandatory because the fake and both common source-contract suites changed.

Run static intent checks: no legacy selector symbols/files; exactly one Profile route, one
`ProfileSwitcherViewModel` factory, and five enum values; no `catch (Throwable)` in the new
ViewModel; every `CancellationException` catch precedes ordinary `Exception`; explicit
`switchingInFlight` reaches every sheet gate; no root `SnackbarHostState` is used for modal errors;
and the Task 6 recovery callback appears from ProfileScreen through NavGraph to the root owner.

- [ ] **Step I: Audit exact scope and commit the atomic navigation replacement**

Use this exact expected path set, including deletions:

```powershell
$expected = @(
    'shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModelTest.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt'
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModel.kt'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt'
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt'
) | Sort-Object
$actual = @(git status --porcelain=v1 | ForEach-Object { $_.Substring(3) } | Sort-Object)
$scopeDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual
if ($scopeDiff) { $scopeDiff | Format-Table -AutoSize; throw 'Task 7 scope mismatch' }
git diff --check -- $expected
if ($LASTEXITCODE -ne 0) { throw 'Task 7 diff check failed' }
git add -A -- $expected
$staged = @(git diff --cached --name-only | Sort-Object)
$stagedDiff = Compare-Object -ReferenceObject $expected -DifferenceObject $staged
if ($stagedDiff) { $stagedDiff | Format-Table -AutoSize; throw 'Task 7 staged scope mismatch' }
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Task 7 cached diff check failed' }
git commit -m "feat: add profile tab and sole root switcher"
```

After commit, require a clean worktree and verify `git show --format= --name-only HEAD` matches the
same eighteen paths exactly.

- [ ] **Step 1: Write the failing navigation source contract**

```kotlin
package com.devil.phoenixproject.presentation.navigation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileNavigationContractTest {
    private val routes = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt",
    ))
    private val main = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
    ))
    private val graph = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
    ))
    private val dialogs = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
    ))

    @Test
    fun profileIsTheFourthOfFiveCanonicalRootItems() {
        assertTrue(routes.contains("object Profile : NavigationRoutes(\"profile\")"))
        val analytics = main.indexOf("Icons.Default.BarChart")
        val workout = main.indexOf("Icons.Default.Home", analytics)
        val insights = main.indexOf("Icons.Default.AutoAwesome", workout)
        val profile = main.indexOf("Icons.Default.Person", insights)
        val settings = main.indexOf("Icons.Default.Settings", profile)
        assertTrue(analytics >= 0 && analytics < workout && workout < insights && insights < profile && profile < settings)
    }

    @Test
    fun profileItemHasPointerAndSemanticLongPress() {
        assertTrue(main.contains("combinedClickable("))
        assertTrue(main.contains("HapticFeedbackType.LongPress"))
        assertTrue(main.contains("this.onLongClick("))
        assertTrue(main.contains("onProfileLongClick"))
        assertFalse(main.contains("widthIn(min = 64.dp"))
    }

    @Test
    fun graphUsesTheRootOwnedSwitcherCallback() {
        assertTrue(graph.contains("composable(\n                route = NavigationRoutes.Profile.route"))
        assertTrue(graph.contains("onOpenProfileSwitcher = onOpenProfileSwitcher"))
        assertTrue(main.contains("ProfileSwitcherSheet("))
        assertTrue(main.contains("createAndActivateProfile("))
    }

    @Test
    fun contextRecoveryFailureCannotFallThroughToAnOrdinarySnackbar() {
        assertTrue(main.contains("catch (error: ProfileContextRecoveryException)"))
        assertTrue(main.contains("ProfileRecoveryDialog("))
        assertTrue(main.contains("profileRepository.reconcileActiveProfileContext()"))
    }

    @Test
    fun navigationAndRecoveryConsumeTheirCompleteResourceInventory() {
        assertUsesResources(graph, listOf("nav_profile"))
        assertUsesResources(
            main,
            listOf(
                "cd_profile",
                "cd_open_profile_switcher",
                "profile_switch_failed",
                "profile_create_failed",
                "profile_recovery_retry_failed",
            ),
        )
        assertUsesResources(
            dialogs,
            listOf("profile_recovery_title", "profile_recovery_message"),
        )
    }

    private fun assertUsesResources(source: String, keys: List<String>) {
        keys.forEach { key -> assertTrue(source.contains("Res.string.$key"), key) }
    }
}
```

- [ ] **Step 2: Run the contract and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileNavigationContractTest*" --console=plain
```

Expected: FAIL because the Profile route/item/gesture/sheet wiring is absent.

- [ ] **Step 3: Add the route and repair the stale BottomNavItem declaration**

Add `object Profile : NavigationRoutes("profile")` between Smart Insights and Settings in the root-tab grouping. Replace the stale three-item enum with:

```kotlin
enum class BottomNavItem(val route: String) {
    ANALYTICS(NavigationRoutes.Analytics.route),
    WORKOUT(NavigationRoutes.Home.route),
    INSIGHTS(NavigationRoutes.SmartInsights.route),
    PROFILE(NavigationRoutes.Profile.route),
    SETTINGS(NavigationRoutes.Settings.route),
}
```

The enum holds no hardcoded labels; UI labels remain resources.

- [ ] **Step 4: Register ProfileScreen in NavGraph**

Add `onOpenProfileSwitcher: () -> Unit` to `NavGraph`. Insert the Profile destination immediately after Smart Insights:

```kotlin
composable(
    route = NavigationRoutes.Profile.route,
    enterTransition = { NavTransitions.tabFadeEnter() },
    exitTransition = { NavTransitions.tabFadeExit() },
    popEnterTransition = { NavTransitions.tabFadeEnter() },
    popExitTransition = { NavTransitions.tabFadeExit() },
) {
    val userPreferences by viewModel.userPreferences.collectAsState()
    ProfileScreen(
        onOpenProfileSwitcher = onOpenProfileSwitcher,
        onNavigateToExerciseDetail = { exerciseId ->
            navController.navigate(NavigationRoutes.ExerciseDetail.createRoute(exerciseId))
        },
        enableVideoPlayback = userPreferences.enableVideoPlayback,
        themeMode = themeMode,
    )
}
```

- [ ] **Step 5: Make EnhancedMainScreen the sole root switcher owner**

Collect `activeProfileContext`, then add root overlay state:

```kotlin
val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
val switchingTargetProfileId =
    (activeProfileContext as? ActiveProfileContext.Switching)?.targetProfileId
var showProfileSwitcher by rememberSaveable { mutableStateOf(false) }
var showAddProfileDialog by rememberSaveable { mutableStateOf(false) }
var createProfileInFlight by remember { mutableStateOf(false) }
var profileRecoveryFailure by remember { mutableStateOf<ProfileContextRecoveryException?>(null) }
var profileRecoveryInFlight by remember { mutableStateOf(false) }
val shellSnackbarHost = remember { SnackbarHostState() }
val switchFailed = stringResource(Res.string.profile_switch_failed)
val createFailed = stringResource(Res.string.profile_create_failed)
val recoveryRetryFailed = stringResource(Res.string.profile_recovery_retry_failed)
```

Pass `{ showProfileSwitcher = true }` into `NavGraph`. Add `snackbarHost = { SnackbarHost(shellSnackbarHost) }` to the existing root Scaffold. Compose exactly one sheet outside its content lambda:

```kotlin
if (showProfileSwitcher) {
    ProfileSwitcherSheet(
        profiles = profiles,
        activeProfileId = (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id,
        switchingTargetProfileId = switchingTargetProfileId,
        onSelectProfile = { profile ->
            if (profile.id != activeProfile?.id && switchingTargetProfileId == null) {
                scope.launch {
                    try {
                        profileRepository.setActiveProfile(profile.id)
                        showProfileSwitcher = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: ProfileContextRecoveryException) {
                        showProfileSwitcher = false
                        profileRecoveryFailure = error
                    } catch (error: Exception) {
                        shellSnackbarHost.showSnackbar(switchFailed)
                    }
                }
            }
        },
        onAddProfile = { showAddProfileDialog = true },
        onDismiss = { if (switchingTargetProfileId == null) showProfileSwitcher = false },
    )
}
```

Replace the old repository-coupled Add dialog with Task 4's callback dialog:

```kotlin
if (showAddProfileDialog) {
    ProfileAddDialog(
        existingProfileCount = profiles.size,
        isSubmitting = createProfileInFlight,
        onConfirm = { name, colorIndex ->
            scope.launch {
                createProfileInFlight = true
                try {
                    profileRepository.createAndActivateProfile(name, colorIndex)
                    showAddProfileDialog = false
                    showProfileSwitcher = false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ProfileContextRecoveryException) {
                    showAddProfileDialog = false
                    showProfileSwitcher = false
                    profileRecoveryFailure = error
                } catch (error: Exception) {
                    shellSnackbarHost.showSnackbar(createFailed)
                } finally {
                    createProfileInFlight = false
                }
            }
        },
        onDismiss = { if (!createProfileInFlight) showAddProfileDialog = false },
    )
}
```

The dialog and sheet remain open on ordinary operation failure. Creation calls no separate `setActiveProfile`. A `ProfileContextRecoveryException` is different: it means the repository could not prove that its database activation and published context agree, so close the ordinary overlays and show a blocking recovery dialog instead of a snackbar.

Add this callback-only component to `ProfileDialogs.kt`:

```kotlin
@Composable
fun ProfileRecoveryDialog(
    isRetrying: Boolean,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.profile_recovery_title)) },
        text = { Text(stringResource(Res.string.profile_recovery_message)) },
        confirmButton = {
            Button(onClick = onRetry, enabled = !isRetrying) {
                Text(stringResource(Res.string.action_retry))
            }
        },
    )
}
```

Compose it after the sheet/add-dialog blocks. It has no dismiss path and calls the facade's reconciliation method:

```kotlin
if (profileRecoveryFailure != null) {
    ProfileRecoveryDialog(
        isRetrying = profileRecoveryInFlight,
        onRetry = {
            if (!profileRecoveryInFlight) scope.launch {
                profileRecoveryInFlight = true
                try {
                    profileRepository.reconcileActiveProfileContext()
                    profileRecoveryFailure = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    shellSnackbarHost.showSnackbar(recoveryRetryFailed)
                } finally {
                    profileRecoveryInFlight = false
                }
            }
        },
    )
}
```

Use Task 3's localized `profile_recovery_title`, `profile_recovery_message`, and `profile_recovery_retry_failed` resources. The retry succeeds only when `reconcileActiveProfileContext()` drains any durable transition journal, re-reads SQLite's actual active row, removes a failed-created identity/preferences pair when recorded, and publishes a matching `Ready`; otherwise the modal remains. Extend the navigation test with a fake repository that throws `ProfileContextRecoveryException`, assert that no ordinary create/switch snackbar event is used, and assert that a successful reconciliation clears the modal.

- [ ] **Step 6: Fit five 48dp cells and wire pointer plus semantic long press**

Add Profile parameters to `PhoenixBottomNavigationBar` and place the Person item fourth. Set the Row to `.padding(horizontal = 8.dp)` and `Arrangement.spacedBy(4.dp)`. Set each weighted item's inner target to `.fillMaxWidth().heightIn(min = 48.dp)`; remove the 64dp `widthIn` constraint.

Update the item API and modifier:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoenixBottomNavigationItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Tab
                this.selected = selected
                this.onClick(label = contentDescription) { onClick(); true }
                if (onLongClick != null && longClickLabel != null) {
                    this.onLongClick(label = longClickLabel) { onLongClick(); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = iconColor,
        )
    }
}
```

At the Profile call site:

```kotlin
val haptic = LocalHapticFeedback.current

PhoenixBottomNavigationItem(
    icon = Icons.Default.Person,
    contentDescription = stringResource(Res.string.cd_profile),
    selected = currentRoute == NavigationRoutes.Profile.route,
    onClick = onProfileClick,
    onLongClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onProfileLongClick()
    },
    longClickLabel = stringResource(Res.string.cd_open_profile_switcher),
    modifier = Modifier.weight(1f).testTag(TestTags.NAV_PROFILE),
)
```

A long press invokes only `onProfileLongClick`; it must not invoke `onProfileClick` or navigate.

- [ ] **Step 7: Complete root-route visibility, selection, navigation, and titles**

- Include `NavigationRoutes.Profile.route` in `shouldShowBottomBar`.
- Navigate on normal Profile tap with the same `popUpTo(Home) { saveState = true }`, `launchSingleTop`, and `restoreState` options as Analytics/Insights/Settings.
- In the Profile destination, capture `val profileTitle = stringResource(Res.string.nav_profile)` and call `LaunchedEffect(profileTitle) { viewModel.updateTopBarTitle(profileTitle) }`, matching Smart Insights. Also add `NavigationRoutes.Profile.route -> profileTitle` to `getScreenTitle` by passing that localized value into the helper; `getCompactScreenTitle` may return the already-localized full title for Profile. Do not add a hardcoded English route title.
- Add `NAV_PROFILE` and `SCREEN_PROFILE` to `TestTags`.

- [ ] **Step 8: Run navigation contracts and compile the app shell**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileNavigationContractTest*" --tests "*ProfileScreenContractTest*" :shared:compileKotlinIosArm64 :androidApp:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL; the five-item route, pointer/semantic long press, sheet ownership, blocking context recovery, and Profile destination are compiled for Android and iOS.

- [ ] **Step 9: Commit navigation and switcher wiring**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt
git commit -m "feat: add profile tab and long press switcher"
```

---

### Task 8: Move Typed Profile Preferences and Safety Controls onto ProfileScreen — Authoritative Contract

> **Authoritative precedence:** This block replaces every earlier Task 8 draft. Execute only this contract. Task 9 removes the remaining Settings cards; it must not repeat the shared-dialog extraction completed here.

**Files:**
- Create: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferencePolicy.kt
- Create: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt
- Create: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt
- Move: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AdultModePresentation.kt to shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AdultModePresentation.kt
- Modify: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt
- Modify: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt
- Modify: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
- Modify: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
- Modify: shared/src/commonMain/composeResources/values/strings.xml
- Modify: shared/src/commonMain/composeResources/values-de/strings.xml
- Modify: shared/src/commonMain/composeResources/values-es/strings.xml
- Modify: shared/src/commonMain/composeResources/values-fr/strings.xml
- Modify: shared/src/commonMain/composeResources/values-nl/strings.xml
- Modify: shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt
- Modify: shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeExternalIntegrationRepositories.kt
- Modify: shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt
- Modify: shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt
- Create: shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferencePolicyTest.kt
- Move: shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/AdultModePresentationTest.kt to shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/AdultModePresentationTest.kt
- Verify unchanged: shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt
- Verify unchanged: shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManagerTest.kt
- Verify unchanged: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/VerbalEncouragementPreferenceCascadeTest.kt
- Verify unchanged: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodecTest.kt
- Verify unchanged: shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeExternalIntegrationRepositoriesTest.kt

**Interfaces:**
- Consumes: Task 6's exact ProfileUiState, token-bearing ProfileIdentityMutation, scoped identity/recovery events, selectionFailure, single Channel-backed event collector, and exact ProfileScreen recovery API; Task 7's root Profile route and onOpenProfileSwitcher; UserProfileRepository whole-section writes and stale-active rejection; profile-aware Equipment Rack navigation; MainViewModel transient connection/disco APIs only.
- Produces: token/profile/section-owned preference writes; authoritative same-profile refresh before controls re-enable; generation-guarded body-weight attribution; compact localized cards; shared safety dialogs; and scoped post-commit adult/unlock outcomes.
- Preserves exactly: ProfileUiState.selectionFailure, ProfileUiState.identityMutation, ProfileUiState.identityMutationInFlight, every Task 6 identity/recovery event, ProfileScreen.onOpenProfileSwitcher, ProfileScreen.onNavigateToExerciseDetail, and ProfileScreen.onProfileRecoveryRequired.
- Does not change: typed schemas, repository signatures, ProfilePreferencesValidator, SettingsManager, runtime safety policy, or sync DTOs. Future non-negative LED indices remain codec-compatible and are normalized for display only.

**Exact count contract:**
- ProfileViewModelTest begins at 25 Task 6 tests; add 20, final total 45.
- ProfileScreenContractTest begins at 6 Task 6 tests; add 8, final total 14.
- ProfilePreferencePolicyTest is new with 4 tests.
- Mandatory unchanged runtime set: VbtEnabledRuntimeTest 7, SafeWordDetectionManagerTest 2, VerbalEncouragementPreferenceCascadeTest 11, AdultModePresentationTest 3.
- Task 8 adds exactly 32 tests. The counted focused suite is 86 tests: 45 + 14 + 4 + 7 + 2 + 11 + 3. Codec and fake regressions run in addition.

- [ ] **Step 1: Enforce the clean Task 6/7 baseline**

Run only after Tasks 6 and 7 are committed in a clean isolated worktree:

~~~powershell
if (git status --porcelain) { throw "Task 8 requires a clean worktree" }
$vm = (Select-String -Path shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt -Pattern '^\s*@Test').Count
$screen = (Select-String -Path shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt -Pattern '^\s*@Test').Count
if ($vm -ne 25 -or $screen -ne 6) { throw "Expected Task 6 counts 25/6, found $vm/$screen" }
rg -n "selectionFailure|identityMutation: ProfileIdentityMutation\?|ProfileRecoveryRequired|onProfileRecoveryRequired|onOpenProfileSwitcher" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt
~~~

Expected: clean status, counts 25/6, and every preserved symbol present. Stop and reconcile earlier tasks on any mismatch.

- [ ] **Step 2: Add deterministic fake seams and all failing tests**

In FakeUserProfileRepository add:

~~~kotlin
sealed interface PreferenceUpdateRequest {
    val profileId: String

    data class Core(
        override val profileId: String,
        val value: CoreProfilePreferences,
    ) : PreferenceUpdateRequest

    data class Rack(
        override val profileId: String,
        val value: RackPreferences,
    ) : PreferenceUpdateRequest

    data class Workout(
        override val profileId: String,
        val value: WorkoutPreferences,
    ) : PreferenceUpdateRequest

    data class Led(
        override val profileId: String,
        val value: LedPreferences,
    ) : PreferenceUpdateRequest

    data class Vbt(
        override val profileId: String,
        val value: VbtPreferences,
    ) : PreferenceUpdateRequest

    data class LocalSafety(
        override val profileId: String,
        val value: ProfileLocalSafetyPreferences,
    ) : PreferenceUpdateRequest
}

val preferenceUpdateRequests = mutableListOf<PreferenceUpdateRequest>()
var beforePreferenceUpdate: (suspend (PreferenceUpdateRequest) -> Unit)? = null
var updateCoreFailure: Throwable? = null
var updateRackFailure: Throwable? = null
var updateWorkoutFailure: Throwable? = null
var updateLedFailure: Throwable? = null
var updateVbtFailure: Throwable? = null
var updateLocalSafetyFailure: Throwable? = null
~~~

Each fake update appends its typed request, invokes beforePreferenceUpdate, throws its section failure if present, then runs the existing validated mutation. This order supports deterministic same-frame blocking, switching between adult writes, partial failure, and cancellation.

In FakeExternalMeasurementRepository add:

~~~kotlin
data class MeasurementObservationRequest(
    val profileId: String,
    val measurementType: String,
)

val observationRequests = mutableListOf<MeasurementObservationRequest>()
var observeByTypeOverride:
    ((String, String) -> Flow<List<ExternalBodyMeasurement>>)? = null

override fun observeMeasurementsByType(
    profileId: String,
    measurementType: String,
): Flow<List<ExternalBodyMeasurement>> {
    observationRequests += MeasurementObservationRequest(profileId, measurementType)
    return observeByTypeOverride?.invoke(profileId, measurementType)
        ?: measurementsFlow.map { rows ->
            rows.filter {
                it.profileId == profileId && it.measurementType == measurementType
            }
        }
}
~~~

Add exactly these 20 ProfileViewModelTest cases:

1. all six typed updates capture Ready ID and refresh authoritative sections before release;
2. same-section update rejects synchronously while another section proceeds;
3. preference and identity claims reject cross-domain overlap both directions;
4. ordinary failure is token/profile/section scoped;
5. stale A completion cannot clear a later owner or publish an unowned outcome;
6. same-profile Ready preserves preference owner and exercise insights;
7. Switching preserves ownership while clearing visible preference data;
8. measurement attribution restarts on same-profile Core generation/body-weight change;
9. measurement attribution clears during Switching;
10. non-cooperative old measurement cannot overwrite a new generation/profile;
11. adult enable owns LOCAL_SAFETY and VBT and commits safety first;
12. adult first-write failure commits neither section;
13. adult second-write failure retains confirmed safety and leaves vulgar false;
14. switch between adult writes never mutates B;
15. adult cancellation emits no terminal outcome and clears only its owner;
16. adult operation overlaps neither safety nor VBT writes;
17. adult partial-commit retry writes only VBT;
18. disco unlock succeeds only after authoritative matching commit;
19. dominatrix failure/switch produces no stale success;
20. decline writes prompted/unconfirmed and explicit enable remains retryable.

Case 1 exercises every repository update. Case 5 uses a non-cooperative hook and compares tokens. Case 8 changes only Core and requires the old timestamp to clear without a new measurement emission. Case 13 asserts committedSections equals LOCAL_SAFETY.

Add exactly these 8 ProfileScreenContractTest cases:

1. complete preference resource inventory occurs once in all five locale files and visible copy is not hardcoded;
2. ProfileScreen retains one event collector and filters by current profile plus tracked token;
3. Profile route passes only transient MainViewModel actions and both navigation callbacks;
4. adult/unlock dialogs react only to matching post-commit outcomes;
5. Achievements precedes Preferences and is unconditional;
6. continuous sliders draft locally and commit only on onValueChangeFinished;
7. LED options use stable localized indices and radio semantics;
8. Settings delegates extracted dialogs and retains microphone disposal.

The route test isolates only the NavigationRoutes.Profile destination. Reject MainViewModel.unlockDiscoMode and every persisted preference setter there. Assert EquipmentRack, Badges, exercise-detail, recovery, and switcher callbacks.

Create ProfilePreferencePolicyTest with exactly four cases: newest matching imported weight; rejection of unset/wrong profile/provider/unit/type/tolerance; indices 0 through 7 remain stable; negative/future LED values display index 0 without storage writes.

Move AdultModePresentationTest with its production file, changing package/imports only; retain its three tests.

- [ ] **Step 3: Run the strict RED gate**

~~~powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.viewmodel.ProfileViewModelTest" --tests "com.devil.phoenixproject.presentation.screen.ProfileScreenContractTest" --tests "com.devil.phoenixproject.presentation.components.ProfilePreferencePolicyTest" --rerun-tasks --console=plain
~~~

Expected: FAIL for missing mutation types/events, policy, components, resources, and route wiring. A pass means tests are not binding the contract.

- [ ] **Step 4: Extend Task 6 state with token/profile/section ownership**

Add beside the existing identity types:

~~~kotlin
enum class ProfilePreferenceSection {
    CORE, RACK, WORKOUT, LED, VBT, LOCAL_SAFETY,
}

enum class ProfilePreferenceMutationKind {
    UPDATE, ADULT_ENABLE, ADULT_DECLINE, DISCO_UNLOCK, DOMINATRIX_UNLOCK,
}

data class ProfilePreferenceMutation(
    val token: Long,
    val profileId: String,
    val sections: Set<ProfilePreferenceSection>,
    val kind: ProfilePreferenceMutationKind,
)
~~~

Do not replace ProfileUiState. Add only:

~~~kotlin
val preferenceMutations:
    Map<ProfilePreferenceSection, ProfilePreferenceMutation> = emptyMap(),
val importedBodyWeightMeasuredAt: Long? = null,
~~~

and:

~~~kotlin
val busyPreferenceSections: Set<ProfilePreferenceSection>
    get() = preferenceMutations.keys
~~~

Keep selectionFailure and identityMutation unchanged. Preserve preferenceMutations beside identityMutation in Switching and different-Ready reconstruction. Same-profile Ready remains copy(context = context), preserving selection, insights, and both mutation domains.

Replace only the placeholder PreferenceUpdateFailed object; retain all Task 6 events:

~~~kotlin
data class PreferenceMutationSucceeded(
    val profileId: String,
    val token: Long,
    val kind: ProfilePreferenceMutationKind,
    val sections: Set<ProfilePreferenceSection>,
) : ProfileUiEvent

data class PreferenceUpdateFailed(
    val profileId: String,
    val token: Long,
    val kind: ProfilePreferenceMutationKind,
    val sections: Set<ProfilePreferenceSection>,
    val committedSections: Set<ProfilePreferenceSection>,
) : ProfileUiEvent
~~~

Claim synchronously before launch:

~~~kotlin
private fun claimPreferenceMutation(
    sections: Set<ProfilePreferenceSection>,
    kind: ProfilePreferenceMutationKind,
): ProfilePreferenceMutation? {
    require(sections.isNotEmpty())
    val profileId = currentMutationProfileId() ?: return null
    val mutation = ProfilePreferenceMutation(
        token = ++nextPreferenceToken,
        profileId = profileId,
        sections = sections,
        kind = kind,
    )
    while (true) {
        val state = _uiState.value
        val ready = state.context as? ActiveProfileContext.Ready ?: return null
        if (
            state.identityMutation != null ||
            sections.any(state.preferenceMutations::containsKey) ||
            ready.profile.id != profileId ||
            currentMutationProfileId() != profileId
        ) return null

        val claimed = state.copy(
            preferenceMutations = state.preferenceMutations +
                sections.associateWith { mutation },
        )
        if (_uiState.compareAndSet(state, claimed)) return mutation
    }
}
~~~

Clear only matching token records:

~~~kotlin
private fun clearPreferenceMutation(token: Long): Boolean {
    var owned = false
    _uiState.update { state ->
        val retained = state.preferenceMutations.filterValues {
            if (it.token == token) {
                owned = true
                false
            } else {
                true
            }
        }
        if (owned) state.copy(preferenceMutations = retained) else state
    }
    return owned
}
~~~

Use CoroutineStart.LAZY. Each job follows this exact order:

1. claim every required section with compareAndSet;
2. call repositories with mutation.profileId;
3. after each successful call add its section to committedSections;
4. read profiles.activeProfileContext.value and require Ready for the same ID;
5. apply that authoritative Ready before the next write and before releasing ownership;
6. rethrow CancellationException without an event;
7. map ordinary exceptions to the scoped failure;
8. in finally clear only the owning token, then send its terminal event only if it still owned the record;
9. return the token from accepted public calls, null from rejected calls.

Every accepted mutation produces exactly one terminal event after owner-only clearing. Ordinary UPDATE success emits PreferenceMutationSucceeded too, so ProfileScreen can discard its tracked token without showing UI; it must not leave completed tokens resident in screen state.

Rename currentIdentityProfileId to currentMutationProfileId and use it for both domains. Add state.preferenceMutations.isNotEmpty() to Task 6's identity claim; preference claims already reject identityMutation. This closes same-frame identity/delete versus preference races.

Expose exact public APIs:

~~~kotlin
fun updateCore(value: CoreProfilePreferences): Long?
fun updateRack(value: RackPreferences): Long?
fun updateWorkout(value: WorkoutPreferences): Long?
fun updateLed(value: LedPreferences): Long?
fun updateVbt(value: VbtPreferences): Long?
fun updateLocalSafety(value: ProfileLocalSafetyPreferences): Long?
fun confirmAdultsOnlyAndEnableVulgar(): Long?
fun declineAdultsOnly(): Long?
fun unlockDiscoMode(): Long?
fun unlockDominatrixMode(): Long?
~~~

Equipment Rack UI navigates rather than calling updateRack, but the complete typed facade remains tested.

- [ ] **Step 5: Implement adult ordering, partial failure, and post-commit effects**

confirmAdultsOnlyAndEnableVulgar takes no UI snapshots.

- If consent is false, claim LOCAL_SAFETY plus VBT under one ADULT_ENABLE token.
- Write adultsOnlyConfirmed = true and adultsOnlyPrompted = true first.
- Refresh authoritative Ready, derive VBT from it, then set vulgarModeEnabled = true.
- First-write failure: committedSections empty; do not attempt VBT.
- Second-write failure/stale switch: keep confirmed/prompted safety, leave vulgar false, report committedSections = LOCAL_SAFETY, never roll consent back.
- If consent is already true and vulgar is false, retry by claiming/writing only VBT.
- declineAdultsOnly owns LOCAL_SAFETY only and writes confirmed false, prompted true.
- adultsOnlyPrompted prevents automatic prompting only. There is no automatic dialog effect; explicit locked/enable actions may reopen it.

Disco and Dominatrix unlocks derive the latest authoritative section in ProfileViewModel. Dominatrix accepts only when verbal and vulgar intent are enabled, local consent is confirmed, and it is locked. Seven-tap counters reset when profile ID or eligibility changes.

Sounds and unlock dialogs occur only after matching DISCO_UNLOCK or DOMINATRIX_UNLOCK success events. Click handlers never fire effects before persistence. General failures use the Profile snackbar; adult modal failures remain inline.

- [ ] **Step 6: Add measurement generation and pure display policies**

In ProfilePreferencePolicy.kt add:

~~~kotlin
internal data class ProfileMeasurementKey(
    val profileId: String,
    val coreLocalGeneration: Long,
    val bodyWeightKg: Float,
)

internal fun normalizedLedSchemeIndex(
    storedIndex: Int,
    schemeCount: Int,
): Int = storedIndex.takeIf { it in 0 until schemeCount } ?: 0

internal fun latestImportedBodyWeightMeasuredAt(
    profileId: String,
    bodyWeightKg: Float,
    measurements: List<ExternalBodyMeasurement>,
): Long? {
    if (!bodyWeightKg.isFinite() || bodyWeightKg <= 0f) return null
    return measurements.asSequence()
        .filter { it.profileId == profileId }
        .filter { it.measurementType == HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT }
        .filter { it.unit == HealthBodyWeightSyncManager.UNIT_KG }
        .filter {
            it.provider == IntegrationProvider.APPLE_HEALTH ||
                it.provider == IntegrationProvider.GOOGLE_HEALTH
        }
        .filter { kotlin.math.abs(it.value - bodyWeightKg.toDouble()) < 0.05 }
        .maxOfOrNull(ExternalBodyMeasurement::measuredAt)
}
~~~

ProfileViewModel owns measurementJob, currentMeasurementKey, currentMeasurementToken, and nextMeasurementToken. The key is profile ID, Core metadata.localGeneration, and bodyWeightKg.

On every Ready, restart only when the key changes: increment token, cancel the old job, synchronously clear importedBodyWeightMeasuredAt, then observe the matching profile/type. Publication requires the current token/key, repository Ready ID, UI Ready ID, Core generation, and weight all still match. A profile-ID-only guard is forbidden.

Switching increments the token before cancellation and clears key/timestamp while preserving mutation ownership. Thus same-profile body-weight changes invalidate immediately, and non-cooperative old flows cannot overwrite.

Do not tighten the LED validator: codec tests intentionally preserve future non-negative values. Normalize only the displayed selection with normalizedLedSchemeIndex(stored, ColorSchemes.ALL.size); never auto-write index 0.

- [ ] **Step 7: Build compact typed cards and final-value slider commits**

Expose:

~~~kotlin
@Composable
fun ProfilePreferenceSections(
    profileId: String,
    preferences: UserProfilePreferences,
    localSafety: ProfileLocalSafetyPreferences,
    importedBodyWeightMeasuredAt: Long?,
    busySections: Set<ProfilePreferenceSection>,
    isConnected: Boolean,
    discoModeActive: Boolean,
    onCoreChange: (CoreProfilePreferences) -> Long?,
    onWorkoutChange: (WorkoutPreferences) -> Long?,
    onLedChange: (LedPreferences) -> Long?,
    onVbtChange: (VbtPreferences) -> Long?,
    onLocalSafetyChange: (ProfileLocalSafetyPreferences) -> Long?,
    onRequestAdultsOnlyConfirmation: () -> Unit,
    onUnlockDiscoMode: () -> Long?,
    onUnlockDominatrixMode: () -> Long?,
    onManageEquipmentRack: () -> Unit,
    onDiscoModeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
)
~~~

Render cards in fixed order: Measurements, Equipment Rack, Workout Behavior, LED, VBT, Safety.

| Card | Exact typed behavior |
|---|---|
| Measurements | Weight unit; increment with -1f as Automatic; body weight entered in selected unit, LB converted with UnitConverter.lbToKg, stored only as 0f or 20f..300f kg. Blank does not silently clamp: Clear explicitly writes 0f; invalid input shows profile_body_weight_invalid. Dialog draft is keyed by profile ID, unit, and authoritative value. |
| Equipment Rack | Enabled/total summary plus navigation only; the rack screen owns RackPreferences writes. |
| Workout | summary -1/0/5..30, autostart 2..10, default rest 0 or 5..300, rep timing, auto-start routine, motion start, stop-at-top, stall detection, master beeps, spoken reps, countdown beeps, rep sound, gamification, weight suggestions, percent-of-PR toggle/50..120, voice stop. |
| VBT-owned default | defaultScalingBasis writes VbtPreferences, not WorkoutPreferences. |
| LED | Stable indices 0 blue, 1 green, 2 teal, 3 yellow, 4 pink, 5 red, 6 purple, 7 none; unlock is persisted, active Disco state is transient and connection-gated. |
| VBT | Master enabled, threshold 10..50, auto-end, verbal, vulgar intent/tier, Dominatrix unlock/active. |
| Safety | Phrase/calibration is one local-only write; changing phrase clears calibrated. Voice-stop intent remains synced WorkoutPreferences. |

Only percent-of-PR and velocity threshold are continuous Sliders. Each uses rememberSaveable(profileId, authoritativeValue); onValueChange changes draft only; onValueChangeFinished sends exactly one final whole-section write; busy disables it; authoritative Ready resynchronizes it. Never write from Slider.onValueChange. Toggles/dropdowns rely on synchronous claims.

Effective gating never rewrites stored intent:

- VBT master off disables live subordinate controls while retaining/displaying values; insights remain visible.
- Auto-end additionally requires stall detection and visibly states that dependency.
- Turning verbal off clears vulgar and Dominatrix active in the same VBT copy.
- Turning vulgar off clears Dominatrix active in the same copy.
- Stored vulgar/Dominatrix intent without local consent remains visible but locked with an explicit Adults Only action.
- Explicit vulgar enable without consent opens confirmation regardless of adultsOnlyPrompted; no LaunchedEffect auto-prompt.
- Voice-stop may remain true while phrase is blank/uncalibrated; show settings_calibrate_first and calibration action.
- Achievements is outside this component and remains visible when gamification is off.

All targets are at least 48.dp. LED uses selectableGroup and selectable with Role.RadioButton, selected semantics, and localized cd_select_led_scheme. Never display or branch on ColorScheme.name/scheme.name; index 7 means off.

- [ ] **Step 8: Extract shared dialogs without duplicating or breaking Settings**

Move AdultModePresentation and its test to presentation.components. Move SafeWordCalibrationDialog, AdultModeDialogCard, AdultModeActions/private visuals, DominatrixUnlockDialog, AdultsOnlyConfirmDialog, and DiscoModeUnlockDialog from SettingsTab into ProfileSafetyDialogs. Remove the original private definitions immediately; Settings imports/calls the shared versions until Task 9 removes its cards.

At the temporary Settings call site pass isSubmitting = false and errorMessage = null while preserving its existing confirm, decline, and dismiss callbacks. This is the only compatibility adapter; there must be one implementation of each dialog.

Expose exact signatures:

~~~kotlin
@Composable
fun SafeWordCalibrationDialog(
    safeWord: String,
    onCalibrated: () -> Unit,
    onDismiss: () -> Unit,
)

@Composable
fun AdultsOnlyConfirmDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
)

@Composable
fun DominatrixUnlockDialog(onDismiss: () -> Unit)

@Composable
fun DiscoModeUnlockDialog(onDismiss: () -> Unit)
~~~

AdultsOnlyConfirmDialog disables confirm, decline, scrim/back dismiss while submitting and renders errorMessage inline. Close only on matching adult success. Preserve onDismiss when idle.

SafeWordCalibrationDialog retains DisposableEffect(safeWord), startListening, stopListening in onDispose, listener clearing, three detections, mic error, and open-settings action. Do not copy the implementation.

- [ ] **Step 9: Complete localization and LED accessibility inventory**

Add these 22 keys exactly once to values, values-de, values-es, values-fr, and values-nl, with idiomatic translations rather than English copies:

| Key | English source |
|---|---|
| profile_automatic | Automatic |
| profile_default_rest | Default rest |
| profile_master_beeps | Workout beeps |
| profile_rep_count_timing | Rep count timing |
| profile_body_weight_unset | Not set |
| profile_body_weight_invalid | Enter a body weight from 20 to 300 kg |
| profile_body_weight_imported | Matches an imported health measurement |
| profile_led_scheme_blue | Blue |
| profile_led_scheme_green | Green |
| profile_led_scheme_teal | Teal |
| profile_led_scheme_yellow | Yellow |
| profile_led_scheme_pink | Pink |
| profile_led_scheme_red | Red |
| profile_led_scheme_purple | Purple |
| profile_led_scheme_none | None |
| cd_select_led_scheme | Select LED scheme: %1$s |
| profile_disco_mode | Disco Mode |
| profile_disco_requires_connection | Connect to your trainer to use Disco Mode |
| profile_disco_unlocked_title | Disco Mode unlocked |
| profile_disco_unlocked_body | Turn on Disco Mode in LED preferences to make your trainer party. |
| profile_disco_unlocked_action | Let's party |
| profile_adult_enable_partial_failure | Age confirmation was saved, but Vulgar Mode could not be enabled. Try again. |

Reuse existing settings_weight_unit, settings_weight_suggestions_title, safety/calibration, verbal/vulgar/Dominatrix/adult, Equipment Rack, cd_led_scheme, action_clear, and Task 3 profile keys. Master beeps and countdown beeps must have distinct labels. Default rest and rep timing must be labeled. Replace every hardcoded Disco dialog string.

Map LED labels by the stable index list, never by ColorScheme.name. Contract tests parse all five XML files, require the identical 22-key set once per file, and reject old Disco literals plus scheme.name.

- [ ] **Step 10: Extend the exact ProfileScreen API and preserve one event collector**

Extend, do not replace, Task 6/7's signature:

~~~kotlin
@Composable
fun ProfileScreen(
    onOpenProfileSwitcher: () -> Unit,
    onNavigateToExerciseDetail: (String) -> Unit,
    onNavigateToEquipmentRack: () -> Unit,
    onNavigateToBadges: () -> Unit,
    onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit,
    isConnected: Boolean,
    discoModeActive: Boolean,
    onDiscoModeToggle: (Boolean) -> Unit,
    onPlayDiscoUnlockSound: () -> Unit,
    onPlayDominatrixUnlockSound: () -> Unit,
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    exerciseRepository: ExerciseRepository = koinInject(),
)
~~~

Keep the existing single LaunchedEffect collector; a second collector is forbidden because Channel.receiveAsFlow would load-balance events.

Track every accepted token by Ready profile ID. Reset pending tokens, adult target/error, tap counters, and unlock dialogs when profile changes. Act only when event.profileId is current and event.token remains tracked.

- Preserve Task 6 identity/recovery branches.
- Generic UPDATE failure shows profile_update_failed once.
- Adult failure remains inline; committed LOCAL_SAFETY uses profile_adult_enable_partial_failure.
- Adult success closes its dialog.
- Disco/Dominatrix success plays sound and opens celebration only after matching token/profile commit.
- Failed/cancelled/untracked/stale outcomes have no effect.

Adult isSubmitting reflects its tracked token; onDismiss is ignored while submitting.

Ready list order is exactly: profile-header, exercise-insights, achievements, preferences-heading, profile-preferences. Achievements uses existing copy/TestTags.ACTION_BADGES, navigates only, and is unconditional.

Wrap every ViewModel call to track its returned token. ProfileScreen never holds optimistic typed sections.

- [ ] **Step 11: Wire navigation and transient MainViewModel actions only**

Inside only NavigationRoutes.Profile:

~~~kotlin
onNavigateToEquipmentRack = {
    navController.navigate(NavigationRoutes.EquipmentRack.route)
},
onNavigateToBadges = {
    navController.navigate(NavigationRoutes.Badges.route)
},
isConnected = connectionState is ConnectionState.Connected,
discoModeActive = discoModeActive,
onDiscoModeToggle = viewModel::toggleDiscoMode,
onPlayDiscoUnlockSound = viewModel::emitDiscoSound,
onPlayDominatrixUnlockSound = viewModel::emitDominatrixUnlockSound,
~~~

Preserve Task 6 exercise/recovery and Task 7 switcher callbacks. Collect connection/disco flows once.

The Profile destination must not call MainViewModel.unlockDiscoMode or any persisted body-weight, rack, workout, LED, VBT, verbal, voice-stop, safe-word, or consent setter. Only toggleDiscoMode, emitDiscoSound, and emitDominatrixUnlockSound cross to MainViewModel.

- [ ] **Step 12: Run exact count, focused, runtime-safety, Android, and iOS gates**

Enforce counts:

~~~powershell
$counts = @{'shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt'=45;'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt'=14;'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferencePolicyTest.kt'=4;'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt'=7;'shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManagerTest.kt'=2;'shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/VerbalEncouragementPreferenceCascadeTest.kt'=11;'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/AdultModePresentationTest.kt'=3}
foreach($entry in $counts.GetEnumerator()){ $actual=(Select-String -Path $entry.Key -Pattern '^\s*@Test').Count; if($actual -ne $entry.Value){ throw "$($entry.Key): expected $($entry.Value), found $actual" } }
~~~

Run the counted 86 plus codec/fake regressions:

~~~powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.viewmodel.ProfileViewModelTest" --tests "com.devil.phoenixproject.presentation.screen.ProfileScreenContractTest" --tests "com.devil.phoenixproject.presentation.components.ProfilePreferencePolicyTest" --tests "com.devil.phoenixproject.presentation.manager.VbtEnabledRuntimeTest" --tests "com.devil.phoenixproject.domain.voice.SafeWordDetectionManagerTest" --tests "com.devil.phoenixproject.data.preferences.VerbalEncouragementPreferenceCascadeTest" --tests "com.devil.phoenixproject.presentation.components.AdultModePresentationTest" --tests "com.devil.phoenixproject.data.preferences.ProfilePreferencesCodecTest" --tests "com.devil.phoenixproject.testutil.FakeExternalIntegrationRepositoriesTest" --rerun-tasks --console=plain
~~~

Expected: BUILD SUCCESSFUL. These unchanged runtime tests prove master-off VBT gating, calibrated local safe-word gating, consent-aware verbal routing, and adult presentation.

Run targets separately:

~~~powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileAndroidMain --rerun-tasks --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --rerun-tasks --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:assembleDebug --rerun-tasks --console=plain
~~~

Expected: all BUILD SUCCESSFUL. Do not omit iOS test compilation.

Run:

~~~powershell
$forbidden = rg -n 'DISCO MODE UNLOCKED|Time to get funky|Toggle Disco Mode|Let.s Party|scheme\.name|ColorScheme\.name' shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt
if($LASTEXITCODE -eq 0){ throw "Hardcoded or unstable LED copy remains: $forbidden" }
if($LASTEXITCODE -gt 1){ throw "rg failed" }
~~~

Expected: no matches.

- [ ] **Step 13: Enforce exact implementation scope and commit**

Exact 21-path allowlist, counting both sides of two moves:

~~~powershell
$allowed=@('shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferencePolicy.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AdultModePresentation.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AdultModePresentation.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt','shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt','shared/src/commonMain/composeResources/values/strings.xml','shared/src/commonMain/composeResources/values-de/strings.xml','shared/src/commonMain/composeResources/values-es/strings.xml','shared/src/commonMain/composeResources/values-fr/strings.xml','shared/src/commonMain/composeResources/values-nl/strings.xml','shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt','shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeExternalIntegrationRepositories.kt','shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt','shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt','shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferencePolicyTest.kt','shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/AdultModePresentationTest.kt','shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/AdultModePresentationTest.kt')
$changed=@(git diff --no-renames --name-only)+@(git ls-files --others --exclude-standard)
$unexpected=@($changed|Where-Object{$_ -notin $allowed}); if($unexpected){ throw "Unexpected paths: $($unexpected -join ', ')" }
git diff --check
git add -A -- $allowed
$staged=@(git diff --cached --no-renames --name-only); $missing=@($allowed|Where-Object{$_ -notin $staged}); $extra=@($staged|Where-Object{$_ -notin $allowed}); if($missing -or $extra){ throw "Staged scope mismatch missing=[$($missing -join ', ')] extra=[$($extra -join ', ')]" }
git diff --cached --check
git commit -m "feat: move typed preferences to profile"
~~~

Expected: exact 21-path implementation commit. The five files marked Verify unchanged remain byte-for-byte unchanged; the moved AdultModePresentationTest changes only path/package.

Post-commit:

~~~powershell
$committed=@(git show --no-renames --name-only --format= HEAD|Where-Object{$_}); $missing=@($allowed|Where-Object{$_ -notin $committed}); $extra=@($committed|Where-Object{$_ -notin $allowed}); if($missing -or $extra){ throw "Committed scope mismatch" }
if(git status --porcelain){ throw "Task 8 left a dirty worktree" }
~~~

Expected: exact scope and clean worktree.

---

### Task 9: Prune Settings to Global App Configuration Only

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt:305-3900`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt:320-446`

**Interfaces:**
- Consumes: global `UserPreferences` fields retained by the data-foundation plan and Task 8's replacement Profile controls/dialogs.
- Produces: a Settings surface containing only app/device/global configuration, plus a source guard preventing profile controls from drifting back.

- [ ] **Step 1: Write the failing Settings/Profile separation contract**

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSettingsSeparationContractTest {
    private val settings = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt",
    ))
    private val profile = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
    ))
    private val graph = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
    ))

    @Test
    fun settingsHasOnlyGlobalCallbackSurface() {
        listOf(
            "onWeightUnitChange", "onWeightIncrementChange", "onBodyWeightKgChange",
            "onNavigateToEquipmentRack", "onAudioRepCountChange", "onSummaryCountdownChange",
            "onColorSchemeChange", "onGamificationEnabledChange", "onVoiceStopEnabledChange",
            "onSafeWordChange", "onVelocityLossThresholdChange", "onVulgarModeEnabledChange",
            "onNavigateToBadges", "SafeWordCalibrationDialog", "DominatrixUnlockDialog",
            "DiscoModeUnlockDialog", "UserProfileRepository", "ExternalMeasurementRepository",
        ).forEach { forbidden -> assertFalse(settings.contains(forbidden), forbidden) }

        listOf(
            "onEnableVideoPlaybackChange", "onThemeModeChange", "onLanguageChange",
            "onNavigateToIntegrations", "onBackupDestinationChange",
            "onBleCompatibilityModeChange", "onTestSounds", "onNavigateToDiagnostics",
        ).forEach { retained -> assertTrue(settings.contains(retained), retained) }
    }

    @Test
    fun profileOwnsAchievementsAndTypedPreferenceSections() {
        assertTrue(profile.contains("ProfilePreferenceSections("))
        assertTrue(profile.contains("onNavigateToBadges"))
        assertTrue(profile.contains("ACTION_BADGES"))
    }

    @Test
    fun settingsDestinationPassesNoProfileOwnedSetter() {
        val start = graph.indexOf("route = NavigationRoutes.Settings.route")
        val end = graph.indexOf("route = NavigationRoutes.EquipmentRack.route", start)
        val destination = graph.substring(start, end)
        assertFalse(destination.contains("setBodyWeightKg"))
        assertFalse(destination.contains("setColorScheme"))
        assertFalse(destination.contains("setVelocityLossThreshold"))
        assertFalse(destination.contains("setSafeWord"))
    }

    @Test
    fun retainedVideoCardConsumesItsCompleteResourceInventory() {
        listOf(
            "settings_video_behavior",
            "settings_show_exercise_videos",
            "settings_show_exercise_videos_description",
        ).forEach { key -> assertTrue(settings.contains("Res.string.$key"), key) }
    }
}
```

- [ ] **Step 2: Run the separation contract and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileSettingsSeparationContractTest*" --console=plain
```

Expected: FAIL with the current profile-owned callback surface and cards still present in Settings.

- [ ] **Step 3: Reduce SettingsTab to an explicit global-only API**

Replace the function signature with this bounded surface:

```kotlin
@Composable
fun SettingsTab(
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    dynamicColorAvailable: Boolean,
    dynamicColorEnabled: Boolean,
    onEnableVideoPlaybackChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    onDeleteAllWorkouts: () -> Unit,
    onNavigateToConnectionLogs: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToLinkAccount: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    connectionError: String?,
    onClearConnectionError: () -> Unit,
    onCancelAutoConnecting: () -> Unit,
    onSetTitle: (String) -> Unit,
    onTestSounds: () -> Unit,
    bleCompatibilityMode: BleCompatibilitySetting,
    onBleCompatibilityModeChange: (BleCompatibilitySetting) -> Unit,
    autoBackupEnabled: Boolean,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    backupStats: BackupStats?,
    onOpenBackupFolder: () -> Unit,
    backupDestination: BackupDestination,
    onBackupDestinationChange: (BackupDestination) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

Keep default arguments only where the current call sites genuinely omit an optional platform capability; do not retain dormant profile callbacks.

- [ ] **Step 4: Delete profile-owned cards, state, injections, and private dialog implementations**

Remove these source regions and any now-unused imports/state:

- Weight unit, increment, body weight, and Equipment Rack: current lines 713–1067.
- Workout Preferences: current lines 1264–1840, after extracting the global video row in Step 5.
- LED/disco persisted controls: current lines 1841–2032.
- VBT/verbal/adult controls: current lines 2033–2362.
- conditional Achievements navigation: current lines 2665–2744.
- modal dispatch for Disco/Dominatrix and all safety/adult/disco dialog helpers: current lines 3014–3053 and 3295–3900; Task 8 now owns them.
- `UserProfileRepository`, `ExternalMeasurementRepository`, active-profile/default fallback, health-measurement collection, weight dialog state, safe-word state, easter-egg counters, and every corresponding callback parameter.

Do not remove route implementations for Equipment Rack or Badges; Profile now links to them.

- [ ] **Step 5: Preserve the global video control as its own compact card**

Before deleting Workout Preferences, extract its video row into a standalone card placed after Language:

```kotlin
SettingsSectionCard(
    title = stringResource(Res.string.settings_video_behavior),
    icon = Icons.Default.VideoLibrary,
) {
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_show_exercise_videos),
        description = stringResource(Res.string.settings_show_exercise_videos_description),
        checked = enableVideoPlayback,
        onCheckedChange = onEnableVideoPlaybackChange,
    )
}
```

If the file has no reusable `SettingsSectionCard`/`SettingsSwitchRow`, extract those exact small primitives from an existing retained card in the same file rather than duplicating a second visual style.

- [ ] **Step 6: Preserve the exact global sections and simplify NavGraph**

Retain, in current visual order:

1. donation/support;
2. Portal/cloud sync and health/external integrations;
3. appearance/theme/dynamic color;
4. language;
5. video behavior;
6. backup/restore/destination/delete-history data management;
7. BLE compatibility, connection logs, diagnostics, developer tools, and sound test;
8. app version/info.

Update the Settings destination to collect/pass only the global fields in the new signature. Remove all Profile-owned MainViewModel setter calls from that destination. Keep `velocityOneRepMaxBackfillDone` internal and unrendered.

- [ ] **Step 7: Make the separation contract and retained-global regressions green**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileSettingsSeparationContractTest*" --tests "*SettingsManagerTest*" --tests "*SettingsPreferencesManagerTest*" :shared:compileKotlinIosArm64 :androidApp:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL; Settings has no profile-owned callbacks or repository reads, global preferences still pass, and the Profile route owns Achievements/preferences.

- [ ] **Step 8: Commit the Settings migration**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt
git commit -m "refactor: keep settings global only"
```

---

### Task 10: Verify Sole Profile-Switcher Ownership and Prune Dead References

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt`

**Interfaces:**
- Consumes: Task 7's already-deleted legacy selectors and sole root-owned switch/create/recovery coordinator, plus Task 9's global/profile settings separation contract.
- Produces: a final regression guard that later preference pruning did not restore a repository-coupled selector or dead compatibility branch. No production behavior changes are expected in this task.

#### Hardened Task 10 verification contract (authoritative)

This block supersedes the older removal draft below. Task 7 now performs the atomic removal; Task
10 verifies that Tasks 8–9 did not reintroduce it and commits only the final regression test.

- [ ] **Step A: Add the final sole-owner regression case**

Add `assertNull` if not already imported and append this fifth case to
`ProfileSettingsSeparationContractTest`:

```kotlin
@Test
fun legacyProfileSelectorsRemainAbsentAfterPreferenceMigration() {
    val mainPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt"
    val justLiftPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt"
    val listItemPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt"
    val switcherPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModel.kt"
    val main = requireNotNull(readProjectFile(mainPath), mainPath)
    val justLift = requireNotNull(readProjectFile(justLiftPath), justLiftPath)
    val listItem = requireNotNull(readProjectFile(listItemPath), listItemPath)
    val switcher = requireNotNull(readProjectFile(switcherPath), switcherPath)

    assertFalse(main.contains("ProfileSidePanel("))
    assertFalse(main.contains("AddProfileDialog("))
    assertFalse(justLift.contains("ProfileSidePanel("))
    assertFalse(justLift.contains("AddProfileDialog("))
    assertFalse(justLift.contains("showAddProfileDialog"))
    assertFalse(listItem.contains("onLongClick"))
    assertFalse(listItem.contains("combinedClickable"))
    assertTrue(switcher.contains("profiles.setActiveProfile("))
    assertTrue(switcher.contains("profiles.createAndActivateProfile("))
    assertTrue(switcher.contains("profiles.reconcileActiveProfileContext("))

    listOf(
        "ProfileSidePanel.kt",
        "ProfileSpeedDial.kt",
        "EditProfileDialog.kt",
        "DeleteProfileDialog.kt",
    ).forEach { fileName ->
        assertNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/$fileName",
            ),
            fileName,
        )
    }
}
```

- [ ] **Step B: Prove there is no second presentation-layer repository caller**

Run:

```powershell
$legacy = rg -n "\b(ProfileSidePanel|ProfileSpeedDial|EditProfileDialog|DeleteProfileDialog)\b" `
    shared/src/commonMain/kotlin
if ($LASTEXITCODE -eq 0) { $legacy; throw 'Legacy profile selector reference remains' }

$owners = @(rg -l `
    "setActiveProfile\(|createAndActivateProfile\(|reconcileActiveProfileContext\(" `
    shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation `
    -g '*.kt')
if ($owners.Count -ne 1 -or
    $owners[0] -notlike '*ProfileSwitcherViewModel.kt') {
    $owners
    throw 'Profile switch/create/recovery has more than one presentation owner'
}
```

Expected: both checks pass. Do not make opportunistic production edits here. A legacy match means
Task 7 is incomplete and must be corrected at its owning commit before continuing.

- [ ] **Step C: Run the verification-only cross-target gate**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' `
    :shared:testAndroidHostTest `
    --tests "*ProfileSettingsSeparationContractTest*" `
    --tests "*ProfileNavigationContractTest*" `
    --tests "*ProfileSwitcherViewModelTest*" `
    :shared:compileKotlinIosArm64 `
    :shared:compileTestKotlinIosArm64 `
    :androidApp:assembleDebug `
    --rerun-tasks `
    --console=plain
```

Expected: BUILD SUCCESSFUL; exactly 5 separation, 8 navigation, and 17 coordinator tests execute
(30 total), with zero failures/errors/skips, and both iOS main/test plus the Android app compile.

- [ ] **Step D: Commit the regression guard with exact one-file scope**

```powershell
$expected = @(
    'shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt'
)
$actual = @(git status --porcelain=v1 | ForEach-Object { $_.Substring(3) })
if (Compare-Object $expected $actual) { throw 'Task 10 must remain test-only' }
git diff --check -- $expected
if ($LASTEXITCODE -ne 0) { throw 'Task 10 diff check failed' }
git add -- $expected
if (Compare-Object $expected @(git diff --cached --name-only)) {
    throw 'Task 10 staged scope mismatch'
}
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Task 10 cached diff check failed' }
git commit -m "test: guard sole profile switcher ownership"
```

- [ ] **Step 1: Extend the source contract with failing legacy-removal assertions**

```kotlin
@Test
fun legacyProfileSelectorsAreGone() {
    val main = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
    ))
    val justLift = requireNotNull(readProjectFile(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt",
    ))
    assertFalse(main.contains("ProfileSidePanel("))
    assertFalse(justLift.contains("ProfileSidePanel("))
    assertFalse(justLift.contains("showAddProfileDialog"))
    assertNull(readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt"))
    assertNull(readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt"))
    assertNull(readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt"))
    assertNull(readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt"))
}
```

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileSettingsSeparationContractTest*" --console=plain
```

Expected: FAIL on both call sites and four existing files.

- [ ] **Step 2: Remove both overlays and their dead local state**

In `EnhancedMainScreen`, remove the Home-only `ProfileSidePanel` block and its import. Retain the profiles/context/scope state used by the new root sheet.

In `JustLiftScreen`, remove profile repository collection, `showAddProfileDialog`, `ProfileSidePanel`, old Add dialog, and imports. Remove its `rememberCoroutineScope` only if `rg -n "scope\." JustLiftScreen.kt` confirms no non-profile use remains.

- [ ] **Step 3: Delete obsolete files after verifying all extracted symbols**

Before deletion run:

```powershell
rg -n "ProfileColors|PROFILE_COLOR_COUNT|ProfileAvatar|Profile(Add|Edit|Delete)Dialog" shared/src/commonMain/kotlin/com/devil/phoenixproject
```

Expected: palette/avatar resolve to `ProfileIdentityComponents.kt`; new dialogs resolve to `ProfileDialogs.kt`; no production call resolves to the four legacy files. Delete the four files. Then simplify `ProfileListItem` to the Task 4 sheet-only API by removing `onLongClick` and `combinedClickable` entirely.

- [ ] **Step 4: Run removal guards and compile both targets**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileSettingsSeparationContractTest*" --tests "*ProfileNavigationContractTest*" :shared:compileKotlinIosArm64 :androidApp:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL; deleted sources have no references and Just Lift retains its normal workout setup behavior without profile UI.

- [ ] **Step 5: Commit legacy selector removal**

```powershell
git add -A shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt
git commit -m "refactor: remove legacy profile selectors"
```

---

### Task 11: Verify DI, Migrations, Cross-Target Compilation, and the Finished User Flows

**Files:**
- Verify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`, `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`, and `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt`
- Verify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt`
- Modify: `docs/superpowers/plans/2026-07-11-profile-tab-ui-navigation.md` — check completed boxes during execution; do not change approved behavior.

**Interfaces:**
- Consumes: all preceding UI tasks and the completed data-foundation plan.
- Produces: fresh proof that SQLDelight migrations, unit/source contracts, Koin, Android, iOS, lint, and the compact Profile flow all work together.

- [ ] **Step 1: Scan for unfinished markers, stale APIs, and duplicate ownership**

Run:

```powershell
rg -n "TODO|TBD|NotImplementedError|error\(\"placeholder|ProfileSidePanel|ProfileSpeedDial|createProfile\(" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation
rg -n "onBodyWeightKgChange|onColorSchemeChange|onVelocityLossThresholdChange|onSafeWordChange|onNavigateToBadges" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
rg -n "profiles\.update(Core|Rack|Workout|Led|Vbt|LocalSafety)\([^,\r\n]+\)" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation
```

Expected: no unfinished-marker, legacy-selector, or global-Settings hits; no one-argument profile-repository preference writes. Any `createProfile` hit must be a backward-compatibility repository implementation/test, never new UI.

- [ ] **Step 2: Run SQLDelight generation and migration verification**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration --console=plain
```

Expected: BUILD SUCCESSFUL from a clean schema generation path.

- [ ] **Step 3: Run the focused feature and graph suite**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*Profile*" --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --tests "*AssessmentViewModelProfileScopeTest*" --tests "*SqlDelightAssessmentRepositoryTest*" --tests "*SqlDelightWorkoutRepositoryTest*" --tests "*KoinModuleVerifyTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; profile A/B isolation, selection restoration, stale-write rejection, resolver precedence, Settings separation, removal guards, and dependency graph all pass.

- [ ] **Step 4: Run full shared and Android verification**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug --console=plain
```

Expected: BUILD SUCCESSFUL with no test, compiler, packaging, or lint regression. If a pre-existing unrelated failure is encountered, capture its exact task/test and rerun the affected Profile commands separately; do not label the feature verified without fresh passing feature evidence.

- [ ] **Step 5: Perform compact-width manual acceptance on an Android emulator**

Use a 320dp-wide emulator or resizable emulator profile and verify:

1. five icon-only tabs appear in Analytics → Workout → Insights → Profile → Settings order with no clipping;
2. Profile tap navigates and restores tab state; long press gives haptic feedback, opens the sheet, and does not navigate;
3. TalkBack exposes separate Profile click and long-click actions;
4. create activates only after success; A → B swaps every preference; A → B → A restores each exercise selection without flashing stale metrics;
5. 1RM source precedence, three PR highlights, five-session chart/list, empty state, and partial error state render compactly;
6. edit and guarded delete work; Default has no delete action; failed mutation leaves its dialog open and shows one snackbar;
7. Equipment Rack and Achievements open from Profile; Settings contains only the retained global groups;
8. Home edge swipe and Just Lift show no profile selector; historical VBT/assessment insights remain visible with VBT disabled.

Record the emulator/API level and pass/fail result in the implementation handoff.

- [ ] **Step 6: Review the final diff for scope and commit verification fixes**

Run:

```powershell
git status --short
git diff --check
git diff --stat
```

Expected: no whitespace errors, no generated/build artifacts, no backend SQL changes, and only files named by this plan plus the dependency data plan. If verification required a real DI/test fix, commit it:

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di
git commit -m "test: verify profile feature graph"
```

If no fix was needed, do not create an empty commit.

---

## Implementation Handoff

Execute `docs/superpowers/plans/2026-07-11-profile-preferences-data-foundation.md` first, including its migration, repository/context, runtime-consumer, deletion, and backup milestones. Then execute this plan in numeric task order even if work is parallelized inside a task. `docs/superpowers/plans/2026-07-11-profile-preferences-sync-backend.md` may proceed after the same data foundation without blocking this UI plan; it owns the Supabase SQL/RLS handoff, and this UI plan must not silently widen into remote schema mutation.
