package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileContextUnavailableException
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.data.sync.ProfilePreferenceSyncCodec
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TvManualStartProfileRepositoryWriteTest {
    @Test
    fun justLiftSavePersistsDefaultsWithoutExportingManualOnlyControls() = runTest {
        val f = fixture(backgroundScope)
        runCurrent()
        val before = f.ready()
        val document = JustLiftDefaultsDocument(weightPerCableKg = 42f, restSeconds = 90)
        assertNotEquals(document, before.preferences.workout.value.justLiftDefaults)
        f.assertManualOnly()

        f.settings.saveJustLiftDefaultsDocument(document)
        runCurrent()

        assertEquals(document, f.ready().preferences.workout.value.justLiftDefaults)
        assertEquals(document, f.settings.getJustLiftDefaultsDocument())
        f.assertSaved(before.preferences.workout.value.copy(justLiftDefaults = document))
        assertTrue(f.ready().preferences.workout.metadata.localGeneration > before.preferences.workout.metadata.localGeneration)
        assertEquals(before.profile, f.ready().profile)
    }

    @Test
    fun singleExerciseSavePersistsDefaultsWithoutExportingManualOnlyControls() = runTest {
        val f = fixture(backgroundScope)
        val other = singleExerciseDefaults("other-exercise")
        f.source.mutateWorkout(PROFILE_ID) { it.copy(singleExerciseDefaults = mapOf(other.exerciseId to other)) }
        runCurrent()
        val before = f.ready().preferences.workout.value
        val document = singleExerciseDefaults("cable-row")
        assertFalse(document.exerciseId in before.singleExerciseDefaults)
        f.assertManualOnly()

        f.settings.saveSingleExerciseDefaultsDocument(document)
        runCurrent()

        assertEquals(document, f.ready().preferences.workout.value.singleExerciseDefaults[document.exerciseId])
        assertEquals(document, f.settings.getSingleExerciseDefaultsDocument(document.exerciseId))
        f.assertSaved(before.copy(singleExerciseDefaults = before.singleExerciseDefaults + (document.exerciseId to document)))
    }

    @Test
    fun updateWorkoutPreservesSourceControlsChangedWhileTheWriteWaits() = runTest {
        val f = fixture(backgroundScope)
        runCurrent()
        val submitted = assertIs<ActiveProfileContext.Ready>(f.view.activeProfileContext.value)
            .preferences.workout.value.copy(justLiftDefaults = JustLiftDefaultsDocument(weightPerCableKg = 37f))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source.beforeWorkoutMutation = {
            f.source.beforeWorkoutMutation = null
            entered.complete(Unit)
            release.await()
        }
        val save = async { f.view.updateWorkout(PROFILE_ID, submitted) }
        entered.await()
        f.source.mutateWorkout(PROFILE_ID) {
            it.copy(summaryCountdownSeconds = -1, autoStartRoutine = false, motionStartEnabled = true)
        }
        release.complete(Unit)
        save.await()
        runCurrent()

        assertEquals(37f, f.ready().preferences.workout.value.justLiftDefaults.weightPerCableKg)
        f.assertSaved(submitted.copy(summaryCountdownSeconds = -1, autoStartRoutine = false, motionStartEnabled = true))
    }

    @Test
    fun settingsMutationSeesManualOnlyControlsButCannotPersistThem() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready().preferences.workout.value
        var observed: WorkoutPreferences? = null

        f.settings.mutateWorkout(PROFILE_ID) { current ->
            observed = current
            current.copy(beepsEnabled = false, summaryCountdownSeconds = 0, autoStartRoutine = false, motionStartEnabled = false)
        }
        runCurrent()

        assertFalse(f.ready().preferences.workout.value.beepsEnabled)
        f.assertSaved(before.copy(beepsEnabled = false))
        assertManualControls(assertNotNull(observed))
    }

    @Test
    fun settingsMutationUsesLatestSourcePreferencesAfterWaiting() = runTest {
        val f = fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source.beforeWorkoutMutation = {
            f.source.beforeWorkoutMutation = null
            entered.complete(Unit)
            release.await()
        }
        var observed: WorkoutPreferences? = null
        val document = JustLiftDefaultsDocument(weightPerCableKg = 31f)
        val save = async {
            f.settings.mutateWorkout(PROFILE_ID) {
                observed = it
                it.copy(justLiftDefaults = document, summaryCountdownSeconds = 30, autoStartRoutine = true, motionStartEnabled = true)
            }
        }
        entered.await()
        f.source.mutateWorkout(PROFILE_ID) {
            it.copy(summaryCountdownSeconds = -1, autoStartRoutine = false, motionStartEnabled = false, beepsEnabled = false)
        }
        val latest = f.ready().preferences.workout.value
        release.complete(Unit)
        save.await()
        runCurrent()

        assertEquals(document, f.ready().preferences.workout.value.justLiftDefaults)
        f.assertSaved(latest.copy(justLiftDefaults = document))
        assertFalse(assertNotNull(observed).beepsEnabled)
        assertManualControls(assertNotNull(observed))
    }

    @Test
    fun defaultsSavesDoNotFollowAProfileSwitchAtTheSourceWriteBoundary() = runTest {
        val saves: List<(SettingsManager) -> Unit> = listOf(
            { it.saveJustLiftDefaultsDocument(JustLiftDefaultsDocument(weightPerCableKg = 42f)) },
            { it.saveSingleExerciseDefaultsDocument(singleExerciseDefaults("cable-row")) },
        )
        for (save in saves) {
            val f = fixture(backgroundScope)
            runCurrent()
            val before = f.ready().preferences
            val other = f.source.createProfile("Other account", 1)
            f.source.linkToSupabase(other.id, "other-owner")
            var entered = false
            f.source.beforeWorkoutMutation = { expectedId ->
                assertEquals(PROFILE_ID, expectedId)
                entered = true
                f.source.setActiveProfile(other.id)
            }

            save(f.settings)
            runCurrent()

            assertTrue(entered, "The save must reach the source, not silently do nothing")
            assertEquals(other.id, f.ready().profile.id)
            assertEquals("other-owner", f.ready().profile.supabaseUserId)
            assertEquals(WorkoutPreferences(), f.ready().preferences.workout.value)
            f.source.emitReadyForTest(PROFILE_ID)
            assertEquals(before, f.ready().preferences)
        }
    }

    @Test
    fun updateWorkoutRejectsTheOldIdentityEvenWhenTheTvViewHasNotCaughtUp() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready().preferences
        val submitted = assertIs<ActiveProfileContext.Ready>(f.view.activeProfileContext.value).preferences.workout.value
        f.source.setActiveProfileForTest(id = "other-profile", supabaseUserId = "other-owner")
        val other = f.ready()

        assertFailsWith<StaleProfileContextException> {
            f.view.updateWorkout(PROFILE_ID, submitted.copy(beepsEnabled = false))
        }

        assertEquals(other, f.ready())
        f.source.emitReadyForTest(PROFILE_ID)
        assertEquals(before, f.ready().preferences)
    }

    @Test
    fun mutateWorkoutRejectsTheOldIdentityWithoutRunningTheTransform() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready().preferences
        f.source.setActiveProfileForTest(id = "other-profile", supabaseUserId = "other-owner")
        val other = f.ready()
        var transformed = false

        assertFailsWith<StaleProfileContextException> {
            f.settings.mutateWorkout(PROFILE_ID) {
                transformed = true
                it.copy(beepsEnabled = false)
            }
        }

        assertFalse(transformed)
        assertEquals(other, f.ready())
        f.source.emitReadyForTest(PROFILE_ID)
        assertEquals(before, f.ready().preferences)
    }

    @Test
    fun bothWritesRejectSwitchingAndTheReadViewPreservesTheTransition() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready().preferences
        f.source.emitSwitchingForTest("other-profile")

        assertFailsWith<ProfileContextUnavailableException> {
            f.view.updateWorkout(PROFILE_ID, before.workout.value.copy(beepsEnabled = false))
        }
        assertFailsWith<ProfileContextUnavailableException> {
            f.view.mutateWorkout(PROFILE_ID) { it.copy(beepsEnabled = false) }
        }
        runCurrent()
        assertEquals(ActiveProfileContext.Switching("other-profile"), f.view.activeProfileContext.value)
        f.source.emitReadyForTest(PROFILE_ID)
        assertEquals(before, f.ready().preferences)
    }

    @Test
    fun bothWritesPropagateSourceFailureAndCancellationWithoutChangingPreferences() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready()
        for (failure in listOf(IllegalStateException("write failed"), CancellationException("write cancelled"))) {
            f.source.updateWorkoutFailure = failure

            assertSame(failure, runCatching {
                f.view.updateWorkout(PROFILE_ID, before.preferences.workout.value.copy(beepsEnabled = false))
            }.exceptionOrNull())
            assertSame(failure, runCatching {
                f.settings.mutateWorkout(PROFILE_ID) { it.copy(beepsEnabled = false) }
            }.exceptionOrNull())
            assertEquals(before, f.ready())
        }
    }

    @Test
    fun transformFailureAndInvalidDefaultsLeaveTheSourceUntouched() = runTest {
        val f = fixture(backgroundScope)
        val before = f.ready()
        val failure = IllegalStateException("transform failed")

        assertSame(failure, runCatching {
            f.view.mutateWorkout(PROFILE_ID) { throw failure }
        }.exceptionOrNull())
        assertFailsWith<IllegalArgumentException> {
            f.view.updateWorkout(PROFILE_ID, before.preferences.workout.value.copy(
                justLiftDefaults = JustLiftDefaultsDocument(weightPerCableKg = -1f),
            ))
        }
        assertEquals(before, f.ready())
    }

    private suspend fun fixture(scope: CoroutineScope): Fixture {
        val source = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = PROFILE_ID, supabaseUserId = "source-owner")
        }
        source.mutateWorkout(PROFILE_ID) {
            it.copy(summaryCountdownSeconds = 25, autoStartRoutine = true, motionStartEnabled = true)
        }
        return Fixture(source, scope)
    }

    private class Fixture(val source: FakeUserProfileRepository, scope: CoroutineScope) {
        val globals = FakePreferencesManager()
        private val initialGlobals = globals.preferencesFlow.value
        val view = TvManualStartProfileRepository(source, scope)
        val settings = SettingsManager(globals, view, scope)
        private val phone = SettingsManager(globals, source, scope)

        fun ready(): ActiveProfileContext.Ready = assertIs(source.activeProfileContext.value)

        fun assertManualOnly() {
            val runtime = assertIs<ActiveProfileContext.Ready>(view.activeProfileContext.value)
            assertManualControls(runtime.preferences.workout.value)
            assertEquals(ready().profile, runtime.profile)
            assertEquals(ready().preferences.workout.metadata, runtime.preferences.workout.metadata)
            assertEquals(0, settings.userPreferences.value.summaryCountdownSeconds)
            assertFalse(settings.userPreferences.value.autoStartRoutine)
            assertFalse(settings.userPreferences.value.motionStartEnabled)
            assertFalse(settings.autoplayEnabled.value)
        }

        fun assertSaved(expected: WorkoutPreferences) {
            val section = ready().preferences.workout
            assertEquals(expected, section.value)
            assertEquals(expected, ProfilePreferencesCodec.decodeWorkout(assertNotNull(section.raw)).value)
            val sync = ProfilePreferenceSyncCodec().workoutPayload(section.value)
            assertEquals(expected, ProfilePreferencesCodec.decodeWorkout(sync.toString()).value)
            assertTrue(section.metadata.dirty)
            assertEquals(expected.summaryCountdownSeconds, phone.userPreferences.value.summaryCountdownSeconds)
            assertEquals(expected.autoStartRoutine, phone.userPreferences.value.autoStartRoutine)
            assertEquals(expected.motionStartEnabled, phone.userPreferences.value.motionStartEnabled)
            assertEquals(initialGlobals, globals.preferencesFlow.value)
            assertManualOnly()
        }
    }

    private fun singleExerciseDefaults(id: String) = SingleExerciseDefaultsDocument(
        exerciseId = id,
        setReps = listOf(8, 10),
        weightPerCableKg = 32f,
        setWeightsPerCableKg = listOf(32f, 34f),
        progressionKg = 2f,
        setRestSeconds = listOf(60, 90),
        workoutModeId = 0,
        eccentricLoadPercentage = 100,
        echoLevelValue = 1,
        duration = 0,
        isAMRAP = false,
        perSetRestTime = true,
    )

    private companion object {
        const val PROFILE_ID = "source-profile"

        fun assertManualControls(value: WorkoutPreferences) {
            assertEquals(0, value.summaryCountdownSeconds)
            assertFalse(value.autoStartRoutine)
            assertFalse(value.motionStartEnabled)
        }
    }
}
