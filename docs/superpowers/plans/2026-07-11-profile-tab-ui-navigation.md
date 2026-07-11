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
- The current-1RM precedence is latest passing velocity estimate, then latest profile-scoped assessment, then the most recent profile-scoped completed-session estimate through `OneRepMaxCalculator.estimate`.
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
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt` — profile-selection restoration and independent insight/mutation-state tests.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModelProfileScopeTest.kt` — explicit assessment profile propagation.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt` — five-item order and tap/long-press source wiring guard.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt` — Settings pruning and obsolete-selector source guard.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileResourceContractTest.kt` — required Profile keys across selectable locale files.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt` — picker, chart, compact-history, and partial-error source guard.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt` — palette wrapping and Default-delete policy.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt` — controllable assessment repository for resolver/ViewModel tests.

### Modify

- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` — profile/exercise-limited completed-session queries.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt` — expose most-recent exercise and limited recent-session reads.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` — map the new SQLDelight reads.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt` — make profile ID required.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt` — preserve explicit profile in all assessment/session rows.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt` — accept and forward an explicit Ready profile ID.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt` — bind Results acceptance to the explicit route profile.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt` — replace split session/velocity hero logic with the shared resolver.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt` — Profile route and canonical five-item order.
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
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt` — new limited-session methods.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt` — data-foundation identity, context, captured-ID update, and injectable-failure APIs.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePersonalRecordRepository.kt` — controllable insight-read failure.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt` — completed-session query filters and limit.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt` — explicit IDs, unit contract, and isolation.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt` — existing graph verification test; no new extra type should be needed.
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
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt:38-95`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt:56-171`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt:270-321`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt:66-129`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt:685-760`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt:26-58`

**Interfaces:**
- Consumes: a Ready `ActiveProfileContext.profile.id` from the data-foundation plan.
- Produces: `AssessmentViewModel.acceptResult(profileId: String, overrideKg: Float? = null)` and assessment repository methods whose `profileId: String` arguments have no default value.

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
        val estimatedOneRepMaxKg: Float,
        val userOverrideKg: Float?,
        val profileId: String,
    )

    val savedSessions = mutableListOf<SavedSession>()
    private val assessments = mutableListOf<AssessmentResultEntity>()

    override suspend fun saveAssessment(
        exerciseId: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        sessionId: String?,
        userOverrideKg: Float?,
        profileId: String,
    ): Long {
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
        savedSessions += SavedSession(exerciseId, estimatedOneRepMaxKg, userOverrideKg, profileId)
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

- [ ] **Step 2: Write the failing ViewModel profile-propagation test**

```kotlin
package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssessmentViewModelProfileScopeTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    @Test
    fun acceptResult_forwardsExplicitProfileId() = runTest {
        val exerciseRepository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                ),
            )
        }
        val assessmentRepository = FakeAssessmentRepository()
        val viewModel = AssessmentViewModel(
            exerciseRepository = exerciseRepository,
            assessmentRepository = assessmentRepository,
            assessmentEngine = AssessmentEngine(),
        )

        advanceUntilIdle()
        viewModel.selectExerciseById("bench")
        advanceUntilIdle()
        viewModel.recordSet(40f, 3, 1.0f, 1.1f)
        viewModel.recordSet(80f, 3, 0.25f, 0.30f)
        viewModel.acceptResult(profileId = "athlete-a")
        advanceUntilIdle()

        assertEquals("athlete-a", assessmentRepository.savedSessions.single().profileId)
    }
}
```

- [ ] **Step 3: Run the focused test and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*AssessmentViewModelProfileScopeTest*" --console=plain
```

Expected: FAIL to compile because `acceptResult` does not yet accept `profileId`.

- [ ] **Step 4: Remove assessment profile defaults and forward the explicit ID**

Change both declarations in `AssessmentRepository` so the last argument is required:

```kotlin
profileId: String,
```

Change `AssessmentViewModel`:

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
            Logger.e("Failed to save assessment: ${e.message}")
            _currentStep.value = current
        }
    }
}
```

- [ ] **Step 5: Pass a Ready profile through the wizard and both routes**

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

In each assessment destination, gate composition on the data-foundation context:

```kotlin
val profileRepository: UserProfileRepository = koinInject()
val activeContext by profileRepository.activeProfileContext.collectAsState()
val ready = activeContext as? ActiveProfileContext.Ready
if (ready != null) {
    AssessmentWizardScreen(
        viewModel = assessmentViewModel,
        profileId = ready.profile.id,
        themeMode = themeMode,
        onNavigateBack = { navController.popBackStack() },
        metricsFlow = viewModel.currentMetric,
    )
}
```

For the preselected route, include `exerciseId = exerciseId` in the same call.

- [ ] **Step 6: Make repository tests explicit and add isolation coverage**

Add `profileId = "athlete-a"` to the existing save calls, then add:

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

- [ ] **Step 7: Run assessment tests and confirm green**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*AssessmentViewModelProfileScopeTest*" --tests "*SqlDelightAssessmentRepositoryTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; all assessment profile and unit tests pass.

- [ ] **Step 8: Commit the assessment ownership slice**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AssessmentWizardScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeAssessmentRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModelProfileScopeTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepositoryTest.kt
git commit -m "fix: scope strength assessments to active profile"
```

---

### Task 2: Add Bounded Exercise History Reads and the Shared Current-1RM Resolver

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCaseTest.kt`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq:652-814`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt:25-80`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt:523-530,1043-1135`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt:20-160`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt:58-164,278-380`

**Interfaces:**
- Consumes: explicit profile-safe Assessment APIs from Task 1; `VelocityOneRepMaxRepository.getLatestPassing`; `OneRepMaxCalculator.estimate`.
- Produces: `WorkoutRepository.getMostRecentCompletedExerciseId`, `WorkoutRepository.getRecentCompletedSessionsForExercise`, and `ResolveCurrentOneRepMaxUseCase.invoke(exerciseId, profileId): CurrentOneRepMax?` for Tasks 5 and 6.

- [ ] **Step 1: Write failing SQLDelight repository tests for eligibility and limit**

Add two tests using the test class's existing database/repository setup:

```kotlin
@Test
fun `recent completed sessions are profile exercise scoped newest first and limited`() = runTest {
    repository.saveSession(workoutSession(id = "a-old", profileId = "a", exerciseId = "bench", timestamp = 10L, workingReps = 5))
    repository.saveSession(workoutSession(id = "a-new", profileId = "a", exerciseId = "bench", timestamp = 30L, workingReps = 3))
    repository.saveSession(workoutSession(id = "a-other", profileId = "a", exerciseId = "squat", timestamp = 40L, workingReps = 5))
    repository.saveSession(workoutSession(id = "b-new", profileId = "b", exerciseId = "bench", timestamp = 50L, workingReps = 5))
    repository.saveSession(workoutSession(id = "a-zero", profileId = "a", exerciseId = "bench", timestamp = 60L, workingReps = 0, totalReps = 0))

    val result = repository.getRecentCompletedSessionsForExercise(
        exerciseId = "bench",
        profileId = "a",
        limit = 1,
    )

    assertEquals(listOf("a-new"), result.map { it.id })
}

@Test
fun `most recent completed exercise ignores zero rep rows`() = runTest {
    repository.saveSession(workoutSession(id = "bench", profileId = "a", exerciseId = "bench", timestamp = 10L, workingReps = 5))
    repository.saveSession(workoutSession(id = "ghost", profileId = "a", exerciseId = "squat", timestamp = 20L, workingReps = 0, totalReps = 0))

    assertEquals("bench", repository.getMostRecentCompletedExerciseId("a"))
}
```

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
ORDER BY timestamp DESC
LIMIT :limit;

selectMostRecentCompletedExerciseId:
SELECT exerciseId FROM WorkoutSession
WHERE profile_id = :profileId
  AND exerciseId IS NOT NULL
  AND exerciseId != ''
  AND deletedAt IS NULL
  AND (workingReps > 0 OR totalReps > 0)
ORDER BY timestamp DESC
LIMIT 1;
```

Add to `WorkoutRepository`:

```kotlin
suspend fun getRecentCompletedSessionsForExercise(
    exerciseId: String,
    profileId: String,
    limit: Int = 5,
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
): List<WorkoutSession> = withContext(Dispatchers.IO) {
    require(limit > 0) { "limit must be positive" }
    queries.selectRecentCompletedSessionsForExercise(
        profileId = profileId,
        exerciseId = exerciseId,
        limit = limit.toLong(),
        mapper = ::mapToSession,
    ).executeAsList()
}

override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? =
    withContext(Dispatchers.IO) {
        queries.selectMostRecentCompletedExerciseId(profileId).executeAsOneOrNull()
    }
```

Fake:

```kotlin
override suspend fun getRecentCompletedSessionsForExercise(
    exerciseId: String,
    profileId: String,
    limit: Int,
): List<WorkoutSession> = sessions.values
    .asSequence()
    .filter { it.profileId == profileId && it.exerciseId == exerciseId }
    .filter { it.workingReps > 0 || it.totalReps > 0 }
    .sortedByDescending { it.timestamp }
    .take(limit)
    .toList()

override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? =
    sessions.values
        .asSequence()
        .filter { it.profileId == profileId }
        .filter { it.workingReps > 0 || it.totalReps > 0 }
        .filter { !it.exerciseId.isNullOrBlank() }
        .maxByOrNull { it.timestamp }
        ?.exerciseId
```

- [ ] **Step 5: Generate SQLDelight interfaces and make query tests green**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:testAndroidHostTest --tests "*SqlDelightWorkoutRepositoryTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; both new repository tests pass.

- [ ] **Step 6: Write failing resolver precedence and normalization tests**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `session fallback uses canonical hybrid and never another profile`() = runTest {
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-b", timestamp = 40L)
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-a", timestamp = 20L)

        val result = resolver("bench", "athlete-a")

        assertEquals(112.5f, result?.perCableKg)
        assertEquals(CurrentOneRepMaxSource.SESSION, result?.source)
        assertEquals(20L, result?.measuredAt)
    }

    @Test
    fun `invalid sources fall through and no source returns null`() = runTest {
        velocity.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench",
            estimatedPerCableKg = Float.NaN,
            mvtUsedMs = 0.3f,
            r2 = 0.95f,
            distinctLoads = 3,
            passedQualityGate = true,
            computedAt = 30L,
            profileId = "athlete-a",
        )

        assertNull(resolver("bench", "athlete-a"))
    }

    private suspend fun seedAssessment(totalKg: Float) {
        assessments.saveAssessment("bench", totalKg, "[]", null, null, "athlete-a")
    }

    private fun seedSession(
        perCableKg: Float,
        reps: Int,
        profileId: String = "athlete-a",
        timestamp: Long = 10L,
    ) {
        workouts.addSession(
            WorkoutSession(
                id = "$profileId-$timestamp",
                timestamp = timestamp,
                mode = "OldSchool",
                reps = reps,
                weightPerCableKg = perCableKg,
                duration = 1_000L,
                totalReps = reps,
                workingReps = reps,
                exerciseId = "bench",
                exerciseName = "Bench Press",
                profileId = profileId,
            ),
        )
    }
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
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
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

class ResolveCurrentOneRepMaxUseCase(
    private val velocityRepository: VelocityOneRepMaxRepository,
    private val assessmentRepository: AssessmentRepository,
    private val workoutRepository: WorkoutRepository,
) {
    suspend operator fun invoke(exerciseId: String, profileId: String): CurrentOneRepMax? {
        require(exerciseId.isNotBlank())
        require(profileId.isNotBlank())

        velocityRepository.getLatestPassing(exerciseId, profileId)
            ?.takeIf { it.estimatedPerCableKg.isFinite() && it.estimatedPerCableKg > 0f }
            ?.let {
                return CurrentOneRepMax(
                    perCableKg = it.estimatedPerCableKg,
                    source = CurrentOneRepMaxSource.VELOCITY,
                    measuredAt = it.computedAt,
                )
            }

        assessmentRepository.getLatestAssessment(exerciseId, profileId)?.let { assessment ->
            val totalKg = assessment.userOverrideKg ?: assessment.estimatedOneRepMaxKg
            val perCableKg = totalKg / 2f
            if (perCableKg.isFinite() && perCableKg > 0f) {
                return CurrentOneRepMax(
                    perCableKg = perCableKg,
                    source = CurrentOneRepMaxSource.ASSESSMENT,
                    measuredAt = assessment.createdAt,
                )
            }
        }

        val session = workoutRepository
            .getRecentCompletedSessionsForExercise(exerciseId, profileId, limit = 1)
            .firstOrNull()
            ?: return null
        val reps = session.workingReps.takeIf { it > 0 } ?: session.totalReps
        val estimate = OneRepMaxCalculator.estimate(session.weightPerCableKg, reps)
        return estimate
            .takeIf { it.isFinite() && it > 0f }
            ?.let { CurrentOneRepMax(it, CurrentOneRepMaxSource.SESSION, session.timestamp) }
    }
}
```

- [ ] **Step 9: Register the use case and make resolver tests green**

Add to `DomainModule.kt`:

```kotlin
single { ResolveCurrentOneRepMaxUseCase(get(), get(), get()) }
```

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --tests "*KoinModuleVerifyTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; resolver and Koin graph tests pass.

- [ ] **Step 10: Replace Exercise Detail's split hero with the shared resolver**

Inject and load the resolution:

```kotlin
val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase = koinInject()
val profileId by viewModel.activeProfileId.collectAsState()
var currentResolution by remember(exerciseId, profileId) {
    mutableStateOf<CurrentOneRepMax?>(null)
}
LaunchedEffect(exerciseId, profileId, exerciseSessions) {
    currentResolution = resolveCurrentOneRepMax(exerciseId, profileId)
}
```

Replace `OneRepMaxCard`'s velocity argument with the resolution:

```kotlin
@Composable
private fun OneRepMaxCard(
    resolution: CurrentOneRepMax?,
    previousSessionOneRepMax: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    val sessionDelta = if (
        resolution?.source == CurrentOneRepMaxSource.SESSION &&
        previousSessionOneRepMax != null
    ) {
        resolution.perCableKg - previousSessionOneRepMax
    } else {
        null
    }
    val sourceLabel = when (resolution?.source) {
        CurrentOneRepMaxSource.VELOCITY -> "Velocity estimate"
        CurrentOneRepMaxSource.ASSESSMENT -> "Strength assessment"
        CurrentOneRepMaxSource.SESSION -> "Recent completed session"
        null -> "No profile-scoped estimate"
    }

    Card(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("CURRENT 1RM", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                resolution?.let { formatWeight(it.perCableKg, weightUnit) } ?: "No data",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(sourceLabel, style = MaterialTheme.typography.bodySmall)
            if (sessionDelta != null && sessionDelta != 0f) {
                Text(
                    "${if (sessionDelta > 0f) "+" else ""}${formatWeight(sessionDelta, weightUnit)} from last",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
```

Retain the assessment button and velocity/assessment history access regardless of `vbtEnabled`. Replace the hardcoded source strings with resource keys in Task 3.

- [ ] **Step 11: Run focused and Exercise Detail regression tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --tests "*VelocityOneRepMaxRepositoryTest*" --tests "*OneRepMaxCalculatorTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; all selected tests pass.

- [ ] **Step 12: Commit the query/resolver slice**

```powershell
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCase.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveCurrentOneRepMaxUseCaseTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt
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
- Produces: stable generated `Res.string.profile_*`, `nav_profile`, and `cd_profile*` keys used by Tasks 4–9.

- [ ] **Step 1: Write the failing locale resource contract test**

```kotlin
package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileResourceContractTest {
    private val files = listOf(
        "src/commonMain/composeResources/values/strings.xml",
        "src/commonMain/composeResources/values-nl/strings.xml",
        "src/commonMain/composeResources/values-de/strings.xml",
        "src/commonMain/composeResources/values-es/strings.xml",
        "src/commonMain/composeResources/values-fr/strings.xml",
    )

    private val keys = listOf(
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
        "profile_weight_unit",
        "profile_weight_increment",
        "profile_body_weight",
        "profile_manage_equipment_rack",
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
        "profile_led_color_scheme",
        "profile_velocity_loss_threshold",
        "profile_auto_end_velocity_loss",
        "profile_vbt_history_note",
    )

    @Test
    fun selectableLocalesContainProfileContract() {
        files.forEach { path ->
            val source = assertNotNull(readProjectFile(path), "Missing $path")
            keys.forEach { key ->
                assertTrue(source.contains("name=\"$key\""), "$path is missing $key")
            }
        }
    }
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
<string name="profile_delete_reassign_message">Delete "%1$s"? Its workouts, routines, records, badges, assessments, and progression data will move to Default. This cannot be undone.</string>
<string name="settings_video_behavior">Video Behavior</string>
<string name="settings_show_exercise_videos">Show Exercise Videos</string>
<string name="settings_show_exercise_videos_description">Display exercise demonstrations; turn this off on slower devices</string>
<string name="profile_weight_unit">Weight unit</string>
<string name="profile_weight_increment">Weight increment</string>
<string name="profile_body_weight">Body weight</string>
<string name="profile_manage_equipment_rack">Manage equipment rack</string>
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
<string name="profile_led_color_scheme">LED color scheme</string>
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
<string name="profile_delete_reassign_message">"%1$s" verwijderen? Trainingen, routines, records, badges, metingen en voortgang worden naar Default verplaatst. Dit kan niet ongedaan worden gemaakt.</string>
<string name="settings_video_behavior">Videogedrag</string>
<string name="settings_show_exercise_videos">Oefeningsvideo's tonen</string>
<string name="settings_show_exercise_videos_description">Toon oefendemonstraties; schakel dit uit op tragere apparaten</string>
<string name="profile_weight_unit">Gewichtseenheid</string>
<string name="profile_weight_increment">Gewichtsstap</string>
<string name="profile_body_weight">Lichaamsgewicht</string>
<string name="profile_manage_equipment_rack">Materiaalrek beheren</string>
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
<string name="profile_led_color_scheme">LED-kleurenschema</string>
<string name="profile_velocity_loss_threshold">Drempel voor snelheidsverlies</string>
<string name="profile_auto_end_velocity_loss">Automatisch stoppen bij snelheidsverlies</string>
<string name="profile_vbt_history_note">VBT uitschakelen beïnvloedt alleen live trainingen; opgeslagen schattingen en metingen blijven beschikbaar.</string>
```

- [ ] **Step 5: Add the complete German resource block**

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
<string name="profile_weight_unit">Gewichtseinheit</string>
<string name="profile_weight_increment">Gewichtsschritt</string>
<string name="profile_body_weight">Körpergewicht</string>
<string name="profile_manage_equipment_rack">Geräteablage verwalten</string>
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
<string name="profile_led_color_scheme">LED-Farbschema</string>
<string name="profile_velocity_loss_threshold">Schwelle für Geschwindigkeitsverlust</string>
<string name="profile_auto_end_velocity_loss">Bei Geschwindigkeitsverlust automatisch beenden</string>
<string name="profile_vbt_history_note">Das Ausschalten von VBT betrifft nur Live-Trainings; gespeicherte Schätzungen und Tests bleiben verfügbar.</string>
```

- [ ] **Step 6: Add the complete Spanish resource block**

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
<string name="profile_delete_reassign_message">¿Eliminar "%1$s"? Sus entrenamientos, rutinas, récords, insignias, evaluaciones y datos de progreso pasarán a Default. Esta acción no se puede deshacer.</string>
<string name="settings_video_behavior">Comportamiento del vídeo</string>
<string name="settings_show_exercise_videos">Mostrar vídeos de ejercicios</string>
<string name="settings_show_exercise_videos_description">Mostrar demostraciones; desactívalo en dispositivos más lentos</string>
<string name="profile_weight_unit">Unidad de peso</string>
<string name="profile_weight_increment">Incremento de peso</string>
<string name="profile_body_weight">Peso corporal</string>
<string name="profile_manage_equipment_rack">Gestionar accesorios</string>
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
<string name="profile_led_color_scheme">Esquema de color LED</string>
<string name="profile_velocity_loss_threshold">Umbral de pérdida de velocidad</string>
<string name="profile_auto_end_velocity_loss">Finalizar al perder velocidad</string>
<string name="profile_vbt_history_note">Desactivar VBT solo afecta a los entrenamientos en vivo; las estimaciones y evaluaciones guardadas siguen disponibles.</string>
```

- [ ] **Step 7: Add the complete French resource block**

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
<string name="profile_weight_unit">Unité de poids</string>
<string name="profile_weight_increment">Incrément de poids</string>
<string name="profile_body_weight">Poids corporel</string>
<string name="profile_manage_equipment_rack">Gérer les accessoires</string>
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
<string name="profile_led_color_scheme">Palette de couleurs LED</string>
<string name="profile_velocity_loss_threshold">Seuil de perte de vélocité</string>
<string name="profile_auto_end_velocity_loss">Arrêt automatique sur perte de vélocité</string>
<string name="profile_vbt_history_note">Désactiver le VBT ne concerne que les entraînements en direct ; les estimations et évaluations enregistrées restent disponibles.</string>
```

- [ ] **Step 8: Run resource generation and the locale contract**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateResourceAccessorsForCommonMain :shared:testAndroidHostTest --tests "*ProfileResourceContractTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; Compose resource generation and the locale contract pass.

- [ ] **Step 9: Commit the resource contract**

```powershell
git add shared/src/commonMain/composeResources/values/strings.xml shared/src/commonMain/composeResources/values-nl/strings.xml shared/src/commonMain/composeResources/values-de/strings.xml shared/src/commonMain/composeResources/values-es/strings.xml shared/src/commonMain/composeResources/values-fr/strings.xml shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileResourceContractTest.kt
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
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt:92,980`

**Interfaces:**
- Consumes: existing `UserProfile`, color resource names, and Material 3 sheet/dialog primitives.
- Produces: `ProfileColors`, `PROFILE_COLOR_COUNT`, `ProfileAvatar`, callback-only add/edit/delete dialogs, and a switch/create-only `ProfileSwitcherSheet` for Tasks 6–7.

- [ ] **Step 1: Write the failing identity policy test**

```kotlin
package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.repository.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileIdentityPolicyTest {
    @Test
    fun `suggested colors wrap the shared palette`() {
        assertEquals(0, suggestedProfileColorIndex(0))
        assertEquals(7, suggestedProfileColorIndex(7))
        assertEquals(0, suggestedProfileColorIndex(8))
    }

    @Test
    fun `only non-default profiles may be deleted`() {
        assertFalse(canDeleteProfile(profile("default")))
        assertTrue(canDeleteProfile(profile("athlete-a")))
    }

    private fun profile(id: String) = UserProfile(
        id = id,
        name = id,
        colorIndex = 0,
        createdAt = 1L,
        isActive = id == "default",
    )
}
```

- [ ] **Step 2: Run the policy test and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileIdentityPolicyTest*" --console=plain
```

Expected: FAIL to compile because the extracted policy functions do not exist.

- [ ] **Step 3: Extract the palette, avatar, and identity policy before deleting the speed dial**

Create `ProfileIdentityComponents.kt` with the palette currently defined in `ProfileSpeedDial.kt` and these stable helpers:

```kotlin
val ProfileColors = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444),
    Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFF97316),
)

const val PROFILE_COLOR_COUNT = 8

internal fun suggestedProfileColorIndex(profileCount: Int): Int =
    profileCount.coerceAtLeast(0) % PROFILE_COLOR_COUNT

internal fun canDeleteProfile(profile: UserProfile): Boolean = profile.id != "default"

@Composable
fun ProfileAvatar(
    profile: UserProfile,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Surface(
        modifier = modifier
            .size(size)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .shadow(if (isActive) 8.dp else 0.dp, CircleShape),
        shape = CircleShape,
        color = ProfileColors.getOrElse(profile.colorIndex) { ProfileColors.first() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = profile.name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
```

Remove only the duplicate palette/constants/avatar from `ProfileSpeedDial.kt` at this step. `RoutinesTab` remains source-compatible because the extracted symbols stay in the same package; replace any explicit import pointing at the obsolete file with the package-level import.

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

Use a full-width 56dp-minimum row, `ProfileAvatar`, the profile name, a check icon for `isActive`, and a 20dp `CircularProgressIndicator` for `switching`. The modifier uses `combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)` only while the nullable legacy callback is present; otherwise use ordinary `clickable(enabled = enabled && !isActive)`. Thus the new sheet has no context action while `ProfileSidePanel` continues compiling during the staged migration. Task 10 removes the nullable legacy callback and `combinedClickable` after deleting the last side-panel call.

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

Each dialog owns only transient text/color selection. Trim the name, disable confirmation for blank names or while submitting, and disable dismiss while submitting. `ProfileDeleteDialog` must `require(canDeleteProfile(profile))` and render `profile_delete_reassign_message`; it must not optimistically close. Render the eight color choices with the existing `color_*` names and `cd_select_profile_color` semantics. The `Profile*Dialog` names intentionally avoid colliding with the repository-coupled legacy dialogs until Task 10 deletes them.

- [ ] **Step 6: Build the shared switch/create-only sheet**

```kotlin
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.PROFILE_SWITCHER_SHEET),
    ) {
        Text(
            text = stringResource(Res.string.profiles_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(profiles, key = UserProfile::id) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    enabled = switchingTargetProfileId == null,
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
                        .clickable(enabled = switchingTargetProfileId == null, onClick = onAddProfile)
                        .testTag(TestTags.ACTION_ADD_PROFILE),
                )
            }
        }
    }
}
```

Add `PROFILE_SWITCHER_SHEET`, `ACTION_ADD_PROFILE`, `ACTION_EDIT_PROFILE`, and `ACTION_DELETE_PROFILE` to `TestTags`. The sheet deliberately has no edit/delete callbacks.

- [ ] **Step 7: Run focused tests and compile both KMP targets**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileIdentityPolicyTest*" :shared:compileKotlinIosArm64 --console=plain
```

Expected: BUILD SUCCESSFUL; identity policies pass and all extracted composables compile for iOS.

- [ ] **Step 8: Commit the reusable identity surface**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityPolicyTest.kt
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
- Consumes: `ActiveProfileContext`, `ExerciseRepository`, Task 2's bounded history methods and resolver, and `PersonalRecordRepository.getAllPRsForExercise`.
- Produces: one route-scoped `ProfileUiState`, per-profile in-memory selection restoration, three independently loadable insight blocks, and typed UI events used by Tasks 6 and 8.

- [ ] **Step 1: Add deterministic test controls to existing fakes**

Add these helpers without weakening their production interfaces:

```kotlin
// FakeUserProfileRepository
fun seedReadyProfileForTest(profileId: String, name: String = profileId): UserProfile {
    val profile = seedProfileWithDefaultPreferences(profileId = profileId, name = name)
    emitReadyForTest(profileId)
    return profile
}

fun emitSwitchingForTest(targetProfileId: String) {
    _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
}

fun emitReadyForTest(profileId: String) {
    val profile = profiles.getValue(profileId)
    _activeProfileContext.value = ActiveProfileContext.Ready(
        profile = profile,
        preferences = preferencesByProfile.getValue(profileId),
        localSafety = localSafetyByProfile.getValue(profileId),
    )
}

// FakePersonalRecordRepository
var getAllForExerciseFailure: Throwable? = null

override suspend fun getAllPRsForExercise(exerciseId: String, profileId: String): List<PersonalRecord> {
    getAllForExerciseFailure?.let { throw it }
    return records.values.filter { it.exerciseId == exerciseId && it.profileId == profileId }
}
```

The data-foundation plan owns the maps, `MutableStateFlow`, and its fake's internal default-preference seeding path. Name that internal path `seedProfileWithDefaultPreferences(profileId, name)` and reuse it from both this helper and the fake's create operation; do not duplicate section metadata construction in UI tests.

- [ ] **Step 2: Write failing tests for profile restoration, fallback, clearing, and partial failure**

Use `TestCoroutineRule` and the repository fakes. The core restoration test is:

```kotlin
@get:Rule
val coroutineRule = TestCoroutineRule()

@Test
fun `A to B to A restores each valid in-memory exercise selection`() = runTest {
    val bench = Exercise(name = "Bench", muscleGroup = "Chest", id = "bench")
    val squat = Exercise(name = "Squat", muscleGroup = "Legs", id = "squat")
    exercises.addExercise(bench)
    exercises.addExercise(squat)
    profiles.seedReadyProfileForTest("a", name = "A")
    profiles.seedReadyProfileForTest("b", name = "B")
    val viewModel = createViewModel()

    profiles.emitReadyForTest("a")
    advanceUntilIdle()
    viewModel.selectExercise(bench)
    advanceUntilIdle()

    profiles.emitSwitchingForTest("b")
    assertNull(viewModel.uiState.value.selectedExercise)
    profiles.emitReadyForTest("b")
    advanceUntilIdle()
    viewModel.selectExercise(squat)
    advanceUntilIdle()

    profiles.emitSwitchingForTest("a")
    profiles.emitReadyForTest("a")
    advanceUntilIdle()
    assertEquals("bench", viewModel.uiState.value.selectedExercise?.id)
}
```

Add three more tests:

1. No saved selection resolves `WorkoutRepository.getMostRecentCompletedExerciseId(profileId)` and then validates that ID through `ExerciseRepository.getExerciseById`.
2. A deleted saved exercise resolves the profile's most recent completed exercise; if that ID is also absent from the exercise repository, selection is `null` and the missing-exercise UI state is shown.
3. When `getAllPRsForExercise` throws, `prHighlights` becomes `Failed` while `currentOneRepMax` and `recentSessions` still become `Ready`.

- [ ] **Step 3: Run the ViewModel tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --console=plain
```

Expected: FAIL to compile because `ProfileViewModel`, its state, and fake controls are absent.

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

Do not put localized strings or raw JSON in the ViewModel.

- [ ] **Step 5: Implement profile-bound selection lifecycle**

Construct the ViewModel with:

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

Maintain `private val selectedExerciseIds = mutableMapOf<String, String>()` and one cancellable `insightsJob`. Collect `profiles.activeProfileContext` with `collectLatest`:

```kotlin
when (val context = context) {
    is ActiveProfileContext.Switching -> {
        insightsJob?.cancel()
        _uiState.value = ProfileUiState(context = context)
    }

    is ActiveProfileContext.Ready -> {
        val profileId = context.profile.id
        val savedId = selectedExerciseIds[profileId]
        val saved = savedId?.let { exercises.getExerciseById(it) }
        val fallbackId = if (saved == null) {
            workouts.getMostRecentCompletedExerciseId(profileId)
        } else {
            null
        }
        val fallback = fallbackId?.let { exercises.getExerciseById(it) }
        val selected = saved ?: fallback
        selected?.id?.let { selectedExerciseIds[profileId] = it }
        _uiState.value = ProfileUiState(
            context = context,
            selectedExercise = selected,
            missingExerciseId = (fallbackId ?: savedId).takeIf { selected == null },
        )
        selected?.let { loadInsights(context, it) }
    }
}
```

`selectExercise(exercise)` must accept only a non-null/nonblank ID and only while the current context is `Ready`; save the ID under that Ready profile and reload. Every load captures both profile ID and exercise ID, cancels the prior job, and checks that pair again before publishing results so slow A results cannot render after switching to B.

- [ ] **Step 6: Load 1RM, PR highlights, and five sessions independently**

Start all three branches in a `supervisorScope`; publish `Loading` first, catch `CancellationException` separately, and publish only the failed branch on ordinary exceptions. Use these mappings:

```kotlin
private fun List<PersonalRecord>.toHighlights() = ProfilePrHighlights(
    maxWeightPerCableKg = filter { it.prType == PRType.MAX_WEIGHT }
        .map { it.weightPerCableKg }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
    estimatedOneRepMaxPerCableKg = map { it.oneRepMax }.filter { it.isFinite() && it > 0f }.maxOrNull(),
    maxVolumeKg = filter { it.prType == PRType.MAX_VOLUME }
        .map { it.volume }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
)
```

```kotlin
val oneRepMax = resolveCurrentOneRepMax(exerciseId, profileId)
val records = personalRecords.getAllPRsForExercise(exerciseId, profileId)
val sessions = workouts.getRecentCompletedSessionsForExercise(
    exerciseId = exerciseId,
    profileId = profileId,
    limit = 5,
)
```

Treat a null resolver result as `ProfileLoadable.Empty`, not failure. Treat no PR rows as `Ready(ProfilePrHighlights(null, null, null))` and no sessions as `Ready(emptyList())`.

- [ ] **Step 7: Register the route-scoped factory and make tests green**

```kotlin
factory { ProfileViewModel(get(), get(), get(), get(), get(), get()) }
```

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --tests "*ResolveCurrentOneRepMaxUseCaseTest*" --console=plain
```

Expected: BUILD SUCCESSFUL; selection is isolated per profile, stale data clears during Switching, and one failed insight branch does not blank the others.

- [ ] **Step 8: Commit the profile state layer**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePersonalRecordRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt
git commit -m "feat: add profile scoped insights state"
```

---

### Task 6: Build the Compact Profile Header and Exercise Insights Surface

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`

**Interfaces:**
- Consumes: Task 4 identity/dialog components, Task 5 state, `ExercisePickerDialog`, `VolumeHistoryChart`, `WeightDisplayFormatter`, and `KmpUtils.formatTimestamp`.
- Produces: a compiling `ProfileScreen`, active-profile edit/delete actions, and compact independently resilient insight cards. Task 7 can now register the finished route directly.

- [ ] **Step 1: Write failing identity-mutation and screen-contract tests**

Add `updateProfileFailure` and `deleteProfileFailure` test controls to `FakeUserProfileRepository`, throwing before mutating when set. Then add ViewModel tests proving:

- `updateIdentity` trims the name, passes the current Ready profile ID, and emits `IdentityUpdated` only after repository success.
- an update exception emits `IdentityUpdateFailed` and leaves the Ready repository value authoritative.
- `deleteActiveProfile` never calls the repository for ID `default`.
- successful non-default deletion emits `ProfileDeleted`; `false` or an exception emits `IdentityUpdateFailed`.

Create the source contract test:

```kotlin
class ProfileScreenContractTest {
    @Test
    fun profileInsightsUseExistingPickerAndCompactHistory() {
        val screen = requireNotNull(readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
        ))
        val insights = requireNotNull(readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt",
        ))

        assertTrue(screen.contains("ExercisePickerDialog("))
        assertTrue(screen.contains("enableCustomExercises = false"))
        assertTrue(insights.contains("VolumeHistoryChart("))
        assertTrue(insights.contains("ProfileLoadable.Failed"))
        assertTrue(insights.contains("take(5)"))
    }
}
```

- [ ] **Step 2: Run the focused tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --tests "*ProfileScreenContractTest*" --console=plain
```

Expected: FAIL because the identity operations and Profile UI files are absent.

- [ ] **Step 3: Add repository-authoritative identity mutations to ProfileViewModel**

Add `identityMutationInFlight: Boolean` to `ProfileUiState`. Implement both actions through one helper that preserves cancellation and emits typed events:

```kotlin
fun updateIdentity(name: String, colorIndex: Int) {
    val ready = uiState.value.context as? ActiveProfileContext.Ready ?: return
    val trimmed = name.trim()
    if (trimmed.isEmpty() || identityJob?.isActive == true) return
    identityJob = viewModelScope.launch {
        _uiState.update { it.copy(identityMutationInFlight = true) }
        try {
            profiles.updateProfile(ready.profile.id, trimmed, colorIndex)
            _events.send(ProfileUiEvent.IdentityUpdated)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _events.send(ProfileUiEvent.IdentityUpdateFailed)
        } finally {
            _uiState.update { it.copy(identityMutationInFlight = false) }
        }
    }
}

fun deleteActiveProfile() {
    val ready = uiState.value.context as? ActiveProfileContext.Ready ?: return
    if (!canDeleteProfile(ready.profile) || identityJob?.isActive == true) return
    identityJob = viewModelScope.launch {
        _uiState.update { it.copy(identityMutationInFlight = true) }
        try {
            if (profiles.deleteProfile(ready.profile.id)) {
                _events.send(ProfileUiEvent.ProfileDeleted)
            } else {
                _events.send(ProfileUiEvent.IdentityUpdateFailed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _events.send(ProfileUiEvent.IdentityUpdateFailed)
        } finally {
            _uiState.update { it.copy(identityMutationInFlight = false) }
        }
    }
}
```

Expose events as a `Channel<ProfileUiEvent>(Channel.BUFFERED).receiveAsFlow()` so a success/error is consumed once, not replayed on recomposition.

- [ ] **Step 4: Build the compact insight component**

Use this boundary:

```kotlin
@Composable
fun ProfileExerciseInsights(
    selectedExercise: Exercise?,
    missingExerciseId: String?,
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
- If selection is null and `missingExerciseId != null`, render `profile_missing_exercise`; if both are null, render `profile_no_exercise_history`. Render no metric shells in either state.
- Current 1RM uses `WeightDisplayFormatter.formatPerCableWeight`, the selected unit suffix, and a source label mapped from `CurrentOneRepMaxSource` to the four `profile_one_rep_max_source_*` resources.
- PR highlights are three compact equal-width cells. Max weight and estimated 1RM are per-cable displays; max volume uses canonical total kg converted once to the selected unit.
- Recent history sorts newest-first defensively, calls `.take(5)`, maps points to `VolumePoint(KmpUtils.formatTimestamp(timestamp, "MMM d"), effectiveTotalVolumeKg())`, renders `VolumeHistoryChart` at 112dp, and shows a compact session list below it.
- A `Failed` branch renders `profile_insights_load_failed` only inside that metric card. Other cards remain visible.
- A nonempty recent list ends with `profile_view_full_history`; pass the nonblank selected exercise ID to the callback.

- [ ] **Step 5: Compose ProfileScreen with header, dialogs, picker, and one snackbar host**

Start with this route-ready API; Task 8 adds the preference callbacks below the insight component without changing navigation ownership:

```kotlin
@Composable
fun ProfileScreen(
    onOpenProfileSwitcher: () -> Unit,
    onNavigateToExerciseDetail: (String) -> Unit,
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    exerciseRepository: ExerciseRepository = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val ready = state.context as? ActiveProfileContext.Ready
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.testTag(TestTags.SCREEN_PROFILE),
    ) { padding ->
        if (ready == null) {
            LoadingIndicator(
                modifier = Modifier.fillMaxSize().padding(padding),
                message = stringResource(Res.string.profile_switching),
            )
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
                        onEdit = { showEdit = true },
                        onDelete = { showDelete = true },
                    )
                }
                item(key = "exercise-insights") {
                    ProfileExerciseInsights(
                        selectedExercise = state.selectedExercise,
                        missingExerciseId = state.missingExerciseId,
                        currentOneRepMax = state.currentOneRepMax,
                        prHighlights = state.prHighlights,
                        recentSessions = state.recentSessions,
                        weightUnit = ready.preferences.core.value.weightUnit,
                        onChooseExercise = { showPicker = true },
                        onViewFullHistory = onNavigateToExerciseDetail,
                    )
                }
            }
        }
    }
}
```

The header is a compact Material 3 card with a 56dp `ProfileAvatar`, active name, visible `Switch Profile` button, edit icon, and—only when `canDeleteProfile(ready.profile)`—delete icon. Attach the Task 4 action tags. Call `ProfileEditDialog` and `ProfileDeleteDialog` outside the lazy list; pass `state.identityMutationInFlight`. Close the relevant dialog only on `IdentityUpdated`/`ProfileDeleted`. On `IdentityUpdateFailed`, keep it open and show the localized update error exactly once.

Render `ExercisePickerDialog` outside the list with:

```kotlin
ExercisePickerDialog(
    showDialog = showPicker,
    onDismiss = { showPicker = false },
    onExerciseSelected = { exercise ->
        showPicker = false
        viewModel.selectExercise(exercise)
    },
    exerciseRepository = exerciseRepository,
    enableVideoPlayback = enableVideoPlayback,
    themeMode = themeMode,
    enableCustomExercises = false,
)
```

- [ ] **Step 6: Run focused tests and both target compilers**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --tests "*ProfileScreenContractTest*" :shared:compileKotlinIosArm64 --console=plain
```

Expected: BUILD SUCCESSFUL; the Profile surface compiles on Android/JVM and iOS and all mutation/contract tests pass.

- [ ] **Step 7: Commit the Profile identity and insight UI**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreenContractTest.kt
git commit -m "feat: build profile identity and exercise insights"
```

---

### Task 7: Add the Fifth Root Tab and Shared Long-Press Profile Switcher

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/navigation/ProfileNavigationContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt:9-113`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt:39-55,239-446`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt:145-155,228-240,384-549,552-600,871-934`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt`

**Interfaces:**
- Consumes: Tasks 3, 4, and 6's localized recovery copy/sheet/dialog/screen, `UserProfileRepository.createAndActivateProfile`, `UserProfileRepository.reconcileActiveProfileContext`, `ActiveProfileContext.Switching`, and `ProfileContextRecoveryException`.
- Produces: `NavigationRoutes.Profile`, canonical five-item order, normal tab navigation, an accessible haptic long press, the one root-owned switcher used by both entry points, and a non-dismissible recovery path for the exceptional case where repository rollback itself failed.

- [ ] **Step 1: Write the failing navigation source contract**

```kotlin
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

### Task 8: Move Typed Profile Preferences and Safety Controls onto ProfileScreen

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt:256-446`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`

**Interfaces:**
- Consumes: captured `ActiveProfileContext.Ready`, the data-foundation facade's `(profileId, typedValue)` mutations, profile-aware Equipment Rack route, existing safety/adult presentation, and MainViewModel only for transient device sound/disco actions.
- Produces: measurements, workout, rack, LED, VBT, verbal/adult, voice-stop, and safety UI that updates only the captured active profile and never serializes JSON.

- [ ] **Step 1: Write failing typed-mutation and stale-context tests**

Make the profile fake record `(profileId, value)` for each of its six typed update methods and expose a per-method failure control. Add tests like:

```kotlin
@Test
fun `typed updates carry the Ready profile id and complete section`() = runTest {
    profiles.seedReadyProfileForTest("a", "A")
    profiles.emitReadyForTest("a")
    val viewModel = createViewModel()
    advanceUntilIdle()
    val ready = viewModel.uiState.value.context as ActiveProfileContext.Ready

    viewModel.updateCore(ready.preferences.core.value.copy(bodyWeightKg = 82f))
    viewModel.updateRack(ready.preferences.rack.value.copy(items = listOf(rackItem("vest"))))
    viewModel.updateWorkout(ready.preferences.workout.value.copy(autoStartRoutine = true))
    viewModel.updateLed(ready.preferences.led.value.copy(colorScheme = 3))
    viewModel.updateVbt(ready.preferences.vbt.value.copy(enabled = false))
    viewModel.updateLocalSafety(ready.localSafety.copy(safeWord = "PHOENIX"))
    advanceUntilIdle()

    assertEquals("a", profiles.lastCoreUpdate?.first)
    assertEquals(82f, profiles.lastCoreUpdate?.second?.bodyWeightKg)
    assertEquals("a", profiles.lastRackUpdate?.first)
    assertEquals("a", profiles.lastWorkoutUpdate?.first)
    assertEquals("a", profiles.lastLedUpdate?.first)
    assertEquals("a", profiles.lastVbtUpdate?.first)
    assertEquals("a", profiles.lastLocalSafetyUpdate?.first)
}
```

Add tests proving:

- If the context switches to B after `updateCore` captures A, the fake receives A; its stale-active rejection causes `PreferenceUpdateFailed`, and B is unchanged.
- Two rapid updates to the same section do not overlap; the second control is disabled/ignored until the observed Ready section returns.
- Sections can update independently, and a Core failure does not mark VBT busy or mutate the UI optimistically.
- Adult confirmation sends LOCAL_SAFETY and VBT with one captured profile ID even if a switch is requested between repository calls.
- External body-weight attribution observes the Ready profile ID and clears during `Switching`.

- [ ] **Step 2: Run the mutation tests and confirm the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --console=plain
```

Expected: FAIL because the section mutations, busy-state keys, and measurement attribution are absent.

- [ ] **Step 3: Implement captured-ID, whole-section mutations in ProfileViewModel**

Add:

```kotlin
enum class ProfileMutationKey { CORE, RACK, WORKOUT, LED, VBT, LOCAL_SAFETY }

data class ProfileUiState(
    val context: ActiveProfileContext? = null,
    val selectedExercise: Exercise? = null,
    val missingExerciseId: String? = null,
    val currentOneRepMax: ProfileLoadable<CurrentOneRepMax> = ProfileLoadable.Empty,
    val prHighlights: ProfileLoadable<ProfilePrHighlights> = ProfileLoadable.Empty,
    val recentSessions: ProfileLoadable<List<WorkoutSession>> = ProfileLoadable.Empty,
    val identityMutationInFlight: Boolean = false,
    val preferenceMutations: Set<ProfileMutationKey> = emptySet(),
    val importedBodyWeightMeasuredAt: Long? = null,
)
```

Use one helper that marks a section synchronously before launching, captures Ready once, and always supplies its ID:

```kotlin
private fun updatePreference(
    section: ProfileMutationKey,
    update: suspend (ActiveProfileContext.Ready) -> Unit,
) {
    val ready = uiState.value.context as? ActiveProfileContext.Ready ?: return
    if (section in uiState.value.preferenceMutations) return
    _uiState.update { it.copy(preferenceMutations = it.preferenceMutations + section) }
    viewModelScope.launch {
        try {
            update(ready)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _events.send(ProfileUiEvent.PreferenceUpdateFailed)
        } finally {
            _uiState.update { it.copy(preferenceMutations = it.preferenceMutations - section) }
        }
    }
}

fun updateCore(value: CoreProfilePreferences) = updatePreference(ProfileMutationKey.CORE) {
    profiles.updateCore(it.profile.id, value)
}
fun updateRack(value: RackPreferences) = updatePreference(ProfileMutationKey.RACK) {
    profiles.updateRack(it.profile.id, value)
}
fun updateWorkout(value: WorkoutPreferences) = updatePreference(ProfileMutationKey.WORKOUT) {
    profiles.updateWorkout(it.profile.id, value)
}
fun updateLed(value: LedPreferences) = updatePreference(ProfileMutationKey.LED) {
    profiles.updateLed(it.profile.id, value)
}
fun updateVbt(value: VbtPreferences) = updatePreference(ProfileMutationKey.VBT) {
    profiles.updateVbt(it.profile.id, value)
}
fun updateLocalSafety(value: ProfileLocalSafetyPreferences) =
    updatePreference(ProfileMutationKey.LOCAL_SAFETY) {
        profiles.updateLocalSafety(it.profile.id, value)
    }
```

Do not copy a later `uiState.value.context` inside the coroutine. Repository stale-ID rejection is a user-visible save failure, not a retry against the newly active profile.

- [ ] **Step 4: Move body-weight integration attribution into the Profile state boundary**

For each Ready context, cancel the prior measurement collector and observe:

```kotlin
externalMeasurements.observeMeasurementsByType(
    profileId = ready.profile.id,
    measurementType = HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT,
).collect { measurements ->
    val bodyWeightKg = ready.preferences.core.value.bodyWeightKg
    val importedAt = measurements
        .asSequence()
        .filter { it.unit == HealthBodyWeightSyncManager.UNIT_KG }
        .filter { it.provider == IntegrationProvider.APPLE_HEALTH || it.provider == IntegrationProvider.GOOGLE_HEALTH }
        .filter { abs(it.value.toFloat() - bodyWeightKg) < 0.05f }
        .maxOfOrNull { it.measuredAt }
    publishIfCurrent(ready.profile.id) { it.copy(importedBodyWeightMeasuredAt = importedAt) }
}
```

Implement the publication guard as:

```kotlin
private inline fun publishIfCurrent(
    profileId: String,
    transform: (ProfileUiState) -> ProfileUiState,
) {
    val currentId = (uiState.value.context as? ActiveProfileContext.Ready)?.profile?.id
    if (currentId == profileId) _uiState.update(transform)
}
```

Cancel and clear it on `Switching`. This replaces `SettingsTab`'s direct repository injection and default-profile fallback.

- [ ] **Step 5: Create focused typed preference cards**

Use this public boundary in `ProfilePreferenceComponents.kt`:

```kotlin
@Composable
fun ProfilePreferenceSections(
    preferences: UserProfilePreferences,
    localSafety: ProfileLocalSafetyPreferences,
    importedBodyWeightMeasuredAt: Long?,
    busySections: Set<ProfileMutationKey>,
    isConnected: Boolean,
    discoModeActive: Boolean,
    onCoreChange: (CoreProfilePreferences) -> Unit,
    onWorkoutChange: (WorkoutPreferences) -> Unit,
    onLedChange: (LedPreferences) -> Unit,
    onVbtChange: (VbtPreferences) -> Unit,
    onLocalSafetyChange: (ProfileLocalSafetyPreferences) -> Unit,
    onConfirmAdultsOnlyAndEnableVulgar: (ProfileLocalSafetyPreferences, VbtPreferences) -> Unit,
    onManageEquipmentRack: () -> Unit,
    onDiscoModeToggle: (Boolean) -> Unit,
    onPlayDiscoUnlockSound: () -> Unit,
    onPlayDominatrixUnlockSound: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Render six compact cards. Every control copies its full current typed section before invoking its callback. Use the following exact mapping:

| Card | Control | Typed write |
|---|---|---|
| Measurements | kg/lb selector | `core.copy(weightUnit = value)` |
| Measurements | weight increment selector (`-1f` means automatic) | `core.copy(weightIncrement = value)` |
| Measurements | validated numeric body weight | display/parse in `core.weightUnit`, convert LB input with `UnitConverter.lbToKg`, validate stored kg as `0f` or `20f..300f`, then `core.copy(bodyWeightKg = kg)` |
| Equipment Rack | enabled/total item summary and Manage Equipment Rack row | navigate only; the profile-aware rack repository performs `rack.copy(items = ...)` |
| Workout | set-summary `-1,0,5..30`, autostart `2..10`, default rest `0 or 5..300` | copy the scalar; for rest use `workout.copy(justLiftDefaults = workout.justLiftDefaults.copy(restSeconds = value))` |
| Workout | auto-start routine, motion start, stop-at-top, stall detection, beeps, spoken reps, countdown beep, rep sound, gamification, weight suggestions | `workout.copy(autoStartRoutine = value)`, `.copy(motionStartEnabled = value)`, `.copy(stopAtTop = value)`, `.copy(stallDetectionEnabled = value)`, `.copy(beepsEnabled = value)`, `.copy(audioRepCountEnabled = value)`, `.copy(countdownBeepsEnabled = value)`, `.copy(repSoundEnabled = value)`, `.copy(gamificationEnabled = value)`, or `.copy(weightSuggestionsEnabled = value)` |
| Workout | rep timing | `workout.copy(repCountTiming = value)` |
| Workout | scaling basis and new-routine percentage toggle/50–120 slider | `workout.copy(defaultRoutineExerciseUsePercentOfPR = value)` and `workout.copy(defaultRoutineExerciseWeightPercentOfPR = value)`; scaling basis writes `vbt.copy(defaultScalingBasis = value)` because that field lives in `VbtPreferences` |
| LED | `ColorSchemes.ALL` selector | `led.copy(colorScheme = index)` |
| LED | seven rapid header taps within 2 seconds | `led.copy(discoModeUnlocked = true)`, then transient unlock sound |
| LED | Disco switch, visible only when unlocked and enabled only when connected | transient `onDiscoModeToggle`; do not persist active state |
| VBT | master enable | `vbt.copy(enabled = checked)` |
| VBT | velocity loss 10–50 and auto-end | `vbt.copy(velocityLossThresholdPercent = value)` or `vbt.copy(autoEndOnVelocityLoss = value)`; auto-end is enabled only when workout stall detection is on |
| VBT | verbal encouragement | when off, also set `vulgarModeEnabled = false` and `dominatrixModeActive = false` in the same copy |
| VBT | vulgar mode/tier | gate first enable through local 18+ prompt; when off, also set `dominatrixModeActive = false` |
| VBT | seven rapid header taps within 2 seconds | only count when verbal and vulgar are enabled; set `dominatrixModeUnlocked = true` and play transient sound |
| VBT | Dominatrix active | visible only when unlocked and confirmed; `vbt.copy(dominatrixModeActive = checked)` |
| Safety | voice stop | `workout.copy(voiceStopEnabled = checked)` |
| Safety | safe word/calibrated state | one complete `localSafety.copy(...)` write; changing the phrase sets `safeWordCalibrated = false` |

Disable only the card whose section key is busy. Use the localized keys from Task 3 and the existing safety/adult strings; do not carry hardcoded labels from Settings. Always show `profile_vbt_history_note`, including when VBT is disabled.

When `workout.voiceStopEnabled` is true but the local phrase is blank or `safeWordCalibrated` is false, show `settings_calibrate_first` plus the calibration action and label the feature as requiring local setup. Do not switch the synced intent back off; the data-foundation runtime computes effective voice stop as false until setup succeeds.

When synced vulgar or Dominatrix intent is true but `localSafety.adultsOnlyConfirmed` is false, render the existing adult-gate presentation and keep those effective controls locked. Do not rewrite the synced VBT section merely because this device lacks local consent.

When `vbt.enabled` is false, disable the live threshold/auto-end/feedback controls visually but retain and display their stored values. The master callback changes only `enabled`; re-enabling immediately restores the prior subordinate configuration, and insights/assessment entry points stay visible.

- [ ] **Step 6: Extract the safety and adult dialogs without changing behavior**

Move `SafeWordCalibrationDialog`, `AdultModeDialogCard`, the adult action helpers, `DominatrixUnlockDialog`, `AdultsOnlyConfirmDialog`, and `DiscoModeUnlockDialog` from `SettingsTab.kt` into `ProfileSafetyDialogs.kt`. Preserve microphone lifecycle cleanup and the existing `AdultModePresentation` rules. Expose callback-only signatures:

```kotlin
@Composable
fun SafeWordCalibrationDialog(safeWord: String, onCalibrated: () -> Unit, onDismiss: () -> Unit)

@Composable
fun AdultsOnlyConfirmDialog(onConfirm: () -> Unit, onDecline: () -> Unit)

@Composable
fun DominatrixUnlockDialog(onDismiss: () -> Unit)

@Composable
fun DiscoModeUnlockDialog(onDismiss: () -> Unit)
```

Add `ProfileViewModel.confirmAdultsOnlyAndEnableVulgar(localSafety, vbt)` and map it to `onConfirmAdultsOnlyAndEnableVulgar`. It captures one Ready/profile ID, marks both LOCAL_SAFETY and VBT busy, then calls `updateLocalSafety(capturedId, localSafety.copy(adultsOnlyConfirmed = true, adultsOnlyPrompted = true))` followed by `updateVbt(capturedId, vbt.copy(vulgarModeEnabled = true))` in the same coroutine. Both calls use the same captured ID; if a switch interleaves, the later call is rejected instead of writing B. On decline, invoke the ordinary one-section write with `localSafety.copy(adultsOnlyConfirmed = false, adultsOnlyPrompted = true)`; never re-show the one-shot prompt for that profile.

- [ ] **Step 7: Wire preferences into ProfileScreen and only transient device actions through NavGraph**

Append `ProfilePreferenceSections` below the insight item when context is Ready. Map all typed callbacks to `ProfileViewModel`. Capture `profile_update_failed` before collecting events and show it once on `PreferenceUpdateFailed`.

Expand only the Profile route callback surface with:

```kotlin
isConnected = connectionState is ConnectionState.Connected,
discoModeActive = discoModeActive,
onNavigateToEquipmentRack = { navController.navigate(NavigationRoutes.EquipmentRack.route) },
onNavigateToBadges = { navController.navigate(NavigationRoutes.Badges.route) },
onDiscoModeToggle = viewModel::toggleDiscoMode,
onPlayDiscoUnlockSound = viewModel::emitDiscoSound,
onPlayDominatrixUnlockSound = viewModel::emitDominatrixUnlockSound,
```

Place a compact Achievements row on Profile immediately before the Preferences heading, using the existing localized Achievements/badge resources and `TestTags.ACTION_BADGES`. It is navigation-only, remains visible even when gamification is disabled, and replaces the conditional Settings entry removed in Task 9.

`MainViewModel` must not receive body weight, rack, workout, LED selection/unlock, VBT, verbal, voice-stop, safe-word, or adult-consent writes from this route. Only transient hardware activity and sounds cross this boundary.

- [ ] **Step 8: Run typed preference, safety, and target compilation checks**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileViewModelTest*" --tests "*SafeWord*" --tests "*VerbalEncouragementPreferenceCascadeTest*" :shared:compileKotlinIosArm64 :androidApp:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL; every persisted control writes a whole typed section with the captured profile ID, safety behavior remains covered, and transient device actions still compile.

- [ ] **Step 9: Commit Profile preferences**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileViewModelTest.kt
git commit -m "feat: move typed preferences to profile"
```

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

### Task 10: Remove the Legacy Home and Just Lift Profile Selectors

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt:145-155,436-483`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt:169-174,806-822`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/ProfileSettingsSeparationContractTest.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EditProfileDialog.kt`
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DeleteProfileDialog.kt`

**Interfaces:**
- Consumes: the root-owned switcher from Task 7 and extracted identity/dialog symbols from Task 4.
- Produces: no edge-swipe, speed-dial, or workout-route profile selector; Profile switching is available only from root-tab UI.

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
