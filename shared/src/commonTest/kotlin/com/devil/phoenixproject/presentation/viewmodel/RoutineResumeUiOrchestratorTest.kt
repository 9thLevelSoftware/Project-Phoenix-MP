package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLookupKey
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRowRevision
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscardResult
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.manager.RoutineResumeManagerGeneration
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class RoutineResumeUiOrchestratorTest {
    private val selectedRoutine = WorkoutStateFixtures.createTestRoutine(
        exerciseCount = 1,
        setsPerExercise = 2,
    )

    @Test
    fun `persisted restored and manual routes never connect or start a workout`() = runTest {
        listOf(
            RoutineResumeEntryPoint.DAILY_ROUTINES,
            RoutineResumeEntryPoint.HOME_CYCLE,
            RoutineResumeEntryPoint.TRAINING_CYCLES,
        ).forEach { entryPoint ->
            val handle = persistedHandle(entryPoint)
            val port = FakeRoutineResumeUiPort().apply {
                resumeResult = ActiveWorkoutRuntimeResumeResult.RestoredRest
            }

            assertEquals(
                RoutineResumeUiOutcome.NavigateActiveWorkout,
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(handle),
                    authority = currentAuthority(entryPoint),
                    port = port,
                ),
                entryPoint.name,
            )
            assertEquals(0, port.connectionCalls, entryPoint.name)
            assertEquals(0, port.dailyLoadCalls, entryPoint.name)
            assertEquals(0, port.cycleLoadCalls, entryPoint.name)

            port.resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
            val manual = runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(handle),
                authority = currentAuthority(entryPoint),
                port = port,
            )
            assertEquals(RoutineResumeUiOutcome.EnterSetReady(0, 1), manual, entryPoint.name)
            assertEquals(0, port.connectionCalls, entryPoint.name)
            assertSame(selectedRoutine, port.lastLoadedRoutine, entryPoint.name)
            if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                assertEquals(1, port.dailyLoadCalls, entryPoint.name)
                assertEquals(0, port.cycleLoadCalls, entryPoint.name)
            } else {
                assertEquals(0, port.dailyLoadCalls, entryPoint.name)
                assertEquals(1, port.cycleLoadCalls, entryPoint.name)
                assertEquals("cycle-id", port.lastCycleId, entryPoint.name)
                assertEquals(2, port.lastCycleDayNumber, entryPoint.name)
            }
        }
    }

    @Test
    fun `in memory Resume routes every entry point after exactly one connection and no routine reload`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            val port = FakeRoutineResumeUiPort().apply {
                resumeResult = ActiveWorkoutRuntimeResumeResult.Missing
            }
            val handle = inMemoryHandle(entryPoint)
            val expected = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                RoutineResumeUiOutcome.StartAndNavigateActiveWorkout
            } else {
                RoutineResumeUiOutcome.EnterSetReady(handle.exerciseIndex, handle.setIndex)
            }

            assertEquals(
                expected,
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(handle),
                    authority = currentAuthority(entryPoint),
                    port = port,
                ),
                entryPoint.name,
            )
            assertEquals(1, port.connectionCalls, entryPoint.name)
            assertEquals(0, port.dailyLoadCalls, entryPoint.name)
            assertEquals(0, port.cycleLoadCalls, entryPoint.name)
        }
    }

    @Test
    fun `Restart routes every entry point through discard before its exact fresh path`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            val port = FakeRoutineResumeUiPort().apply {
                discardResult = RoutineResumeDiscardResult.Missing
            }
            val handle = persistedHandle(entryPoint)
            val expected = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                RoutineResumeUiOutcome.EnterDailyOverview(selectedRoutine)
            } else {
                RoutineResumeUiOutcome.EnterSetReady(0, 0)
            }

            assertEquals(
                expected,
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Restart(handle),
                    authority = currentAuthority(entryPoint),
                    port = port,
                ),
                entryPoint.name,
            )
            if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
                assertEquals(0, port.connectionCalls, entryPoint.name)
                assertEquals(0, port.dailyLoadCalls, entryPoint.name)
                assertEquals(0, port.cycleLoadCalls, entryPoint.name)
            } else {
                assertEquals(1, port.connectionCalls, entryPoint.name)
                assertEquals(1, port.cycleLoadCalls, entryPoint.name)
                assertSame(selectedRoutine, port.lastLoadedRoutine, entryPoint.name)
            }
        }
    }

    @Test
    fun `retryable and superseded outcomes never connect or load for any entry point`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            val handle = persistedHandle(entryPoint)
            listOf(
                ActiveWorkoutRuntimeResumeResult.RetryableFailure to
                    RoutineResumeUiOutcome.RetainDialog(RoutineResumeRetryAction.RESUME),
                ActiveWorkoutRuntimeResumeResult.Superseded to RoutineResumeUiOutcome.DismissDialog,
            ).forEach { (resumeResult, expected) ->
                val port = FakeRoutineResumeUiPort().apply { this.resumeResult = resumeResult }
                assertEquals(
                    expected,
                    runRoutineResumeUiOperation(
                        operation = RoutineResumeUiOperation.Resume(handle),
                        authority = currentAuthority(entryPoint),
                        port = port,
                    ),
                    "$entryPoint/$resumeResult",
                )
                assertEquals(0, port.connectionCalls)
                assertEquals(0, port.dailyLoadCalls)
                assertEquals(0, port.cycleLoadCalls)
            }

            listOf(
                RoutineResumeDiscardResult.RetryableFailure to
                    RoutineResumeUiOutcome.RetainDialog(RoutineResumeRetryAction.DISCARD),
                RoutineResumeDiscardResult.Superseded to RoutineResumeUiOutcome.DismissDialog,
            ).forEach { (discardResult, expected) ->
                val port = FakeRoutineResumeUiPort().apply { this.discardResult = discardResult }
                assertEquals(
                    expected,
                    runRoutineResumeUiOperation(
                        operation = RoutineResumeUiOperation.Restart(handle),
                        authority = currentAuthority(entryPoint),
                        port = port,
                    ),
                    "$entryPoint/$discardResult",
                )
                assertEquals(0, port.connectionCalls)
                assertEquals(0, port.dailyLoadCalls)
                assertEquals(0, port.cycleLoadCalls)
            }
        }
    }

    @Test
    fun `restart waits for discard and only cleanup success enters the exact fresh path`() = runTest {
        val discardEntered = CompletableDeferred<Unit>()
        val releaseDiscard = CompletableDeferred<Unit>()
        val port = FakeRoutineResumeUiPort().apply {
            discardBlock = {
                discardEntered.complete(Unit)
                releaseDiscard.await()
                RoutineResumeDiscardResult.Discarded
            }
        }
        val handle = persistedHandle(RoutineResumeEntryPoint.HOME_CYCLE)
        val operation = async {
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Restart(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.HOME_CYCLE),
                port = port,
            )
        }

        discardEntered.await()
        assertEquals(0, port.connectionCalls)
        assertEquals(0, port.cycleLoadCalls)
        releaseDiscard.complete(Unit)

        assertEquals(RoutineResumeUiOutcome.EnterSetReady(0, 0), operation.await())
        assertEquals(1, port.connectionCalls)
        assertEquals(1, port.cycleLoadCalls)
        assertSame(selectedRoutine, port.lastLoadedRoutine)

        port.discardBlock = null
        port.discardResult = RoutineResumeDiscardResult.RetryableFailure
        assertEquals(
            RoutineResumeUiOutcome.RetainDialog(RoutineResumeRetryAction.DISCARD),
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Restart(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.HOME_CYCLE),
                port = port,
            ),
        )
        assertEquals(1, port.connectionCalls)
        assertEquals(1, port.cycleLoadCalls)
    }

    @Test
    fun `late in-memory connection callback after token or handle loss is inert`() = runTest {
        listOf(false, true).forEach { loseHandleInsteadOfToken ->
            var token = 7
            val connectionEntered = CompletableDeferred<Unit>()
            val releaseConnection = CompletableDeferred<Unit>()
            val port = FakeRoutineResumeUiPort().apply {
                resumeResult = ActiveWorkoutRuntimeResumeResult.Missing
                connectionBlock = {
                    connectionEntered.complete(Unit)
                    releaseConnection.await()
                    true
                }
            }
            val handle = inMemoryHandle(RoutineResumeEntryPoint.DAILY_ROUTINES)
            val operation = async {
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(handle),
                    authority = RoutineResumeActionAuthority(
                        entryPoint = RoutineResumeEntryPoint.DAILY_ROUTINES,
                        actionToken = token,
                        currentToken = { token },
                        contextIsCurrent = { true },
                    ),
                    port = port,
                )
            }

            connectionEntered.await()
            if (loseHandleInsteadOfToken) port.inMemoryHandleCurrent = false else token += 1
            releaseConnection.complete(Unit)

            val expected = if (loseHandleInsteadOfToken) {
                RoutineResumeUiOutcome.DismissDialog
            } else {
                RoutineResumeUiOutcome.StaleNoOp
            }
            assertEquals(expected, operation.await(), "loseHandle=$loseHandleInsteadOfToken")
            assertEquals(0, port.dailyLoadCalls)
            assertEquals(0, port.cycleLoadCalls)
        }
    }

    @Test
    fun `in memory handle loss while Resume validation is suspended prevents BLE entirely`() = runTest {
        val resumeEntered = CompletableDeferred<Unit>()
        val releaseResume = CompletableDeferred<Unit>()
        val port = FakeRoutineResumeUiPort().apply {
            resumeBlock = {
                resumeEntered.complete(Unit)
                releaseResume.await()
                ActiveWorkoutRuntimeResumeResult.Missing
            }
        }
        val handle = inMemoryHandle(RoutineResumeEntryPoint.HOME_CYCLE)
        val result = async {
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.HOME_CYCLE),
                port = port,
            )
        }

        resumeEntered.await()
        port.inMemoryHandleCurrent = false
        releaseResume.complete(Unit)

        assertEquals(RoutineResumeUiOutcome.DismissDialog, result.await())
        assertEquals(0, port.connectionCalls)
        assertEquals(0, port.dailyLoadCalls)
        assertEquals(0, port.cycleLoadCalls)
    }

    @Test
    fun `manual load completion after token loss is stale and cannot dismiss a newer dialog`() = runTest {
        listOf(
            RoutineResumeEntryPoint.DAILY_ROUTINES,
            RoutineResumeEntryPoint.HOME_CYCLE,
            RoutineResumeEntryPoint.TRAINING_CYCLES,
        ).forEach { entryPoint ->
            var token = 12
            val loadEntered = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val port = FakeRoutineResumeUiPort().apply {
                resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
                loadBlock = { stillCurrent ->
                    loadEntered.complete(Unit)
                    releaseLoad.await()
                    assertFalse(stillCurrent(), entryPoint.name)
                    true
                }
            }
            val operation = async {
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(persistedHandle(entryPoint)),
                    authority = RoutineResumeActionAuthority(
                        entryPoint = entryPoint,
                        actionToken = token,
                        currentToken = { token },
                        contextIsCurrent = { true },
                    ),
                    port = port,
                )
            }

            loadEntered.await()
            token += 1
            releaseLoad.complete(Unit)

            assertEquals(RoutineResumeUiOutcome.StaleNoOp, operation.await(), entryPoint.name)
            assertSame(selectedRoutine, port.lastLoadedRoutine, entryPoint.name)
        }
    }

    @Test
    fun `manual load completion after profile loss is stale without a screen token change`() = runTest {
        var profileIsCurrent = true
        val loadEntered = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val port = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
            loadBlock = { stillCurrent ->
                loadEntered.complete(Unit)
                releaseLoad.await()
                assertFalse(stillCurrent())
                true
            }
        }
        val operation = async {
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(
                    persistedHandle(RoutineResumeEntryPoint.DAILY_ROUTINES),
                ),
                authority = RoutineResumeActionAuthority(
                    entryPoint = RoutineResumeEntryPoint.DAILY_ROUTINES,
                    actionToken = 5,
                    currentToken = { 5 },
                    contextIsCurrent = { profileIsCurrent },
                ),
                port = port,
            )
        }

        loadEntered.await()
        profileIsCurrent = false
        releaseLoad.complete(Unit)

        assertEquals(RoutineResumeUiOutcome.StaleNoOp, operation.await())
        assertSame(selectedRoutine, port.lastLoadedRoutine)
    }

    @Test
    fun `in memory handle loss outranks a failed late connection callback`() = runTest {
        val connectionEntered = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val port = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.Missing
            connectionBlock = {
                connectionEntered.complete(Unit)
                releaseConnection.await()
                false
            }
        }
        val handle = inMemoryHandle(RoutineResumeEntryPoint.DAILY_ROUTINES)
        val operation = async {
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.DAILY_ROUTINES),
                port = port,
            )
        }

        connectionEntered.await()
        port.inMemoryHandleCurrent = false
        releaseConnection.complete(Unit)

        assertEquals(RoutineResumeUiOutcome.DismissDialog, operation.await())
    }

    @Test
    fun `same-token invalid cycle dismisses while a stale invalid cycle is a no-op`() = runTest {
        val invalidHandle = persistedHandle(RoutineResumeEntryPoint.HOME_CYCLE).copy(
            cycleId = "",
            cycleDayNumber = 0,
        )
        val port = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
        }
        assertEquals(
            RoutineResumeUiOutcome.DismissDialog,
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(invalidHandle),
                authority = currentAuthority(RoutineResumeEntryPoint.HOME_CYCLE),
                port = port,
            ),
        )

        var token = 1
        token += 1
        assertEquals(
            RoutineResumeUiOutcome.StaleNoOp,
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(invalidHandle),
                authority = RoutineResumeActionAuthority(
                    entryPoint = RoutineResumeEntryPoint.HOME_CYCLE,
                    actionToken = 1,
                    currentToken = { token },
                    contextIsCurrent = { true },
                ),
                port = port,
            ),
        )
        assertEquals(0, port.connectionCalls)
        assertEquals(0, port.cycleLoadCalls)
    }

    @Test
    fun `fresh cycle discovery uses the exact selected routine and becomes inert after load authority loss`() = runTest {
        listOf(
            RoutineResumeEntryPoint.HOME_CYCLE,
            RoutineResumeEntryPoint.TRAINING_CYCLES,
        ).forEach { entryPoint ->
            var token = 19
            val loadEntered = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val port = FakeRoutineResumeUiPort().apply {
                loadBlock = { stillCurrent ->
                    loadEntered.complete(Unit)
                    releaseLoad.await()
                    assertFalse(stillCurrent(), entryPoint.name)
                    true
                }
            }
            val result = async {
                runFreshCycleUiOperation(
                    routine = selectedRoutine,
                    cycleId = "cycle-id",
                    dayNumber = 2,
                    authority = RoutineResumeActionAuthority(
                        entryPoint = entryPoint,
                        actionToken = token,
                        currentToken = { token },
                        contextIsCurrent = { true },
                    ),
                    port = port,
                )
            }

            loadEntered.await()
            token += 1
            releaseLoad.complete(Unit)

            assertEquals(RoutineResumeUiOutcome.StaleNoOp, result.await(), entryPoint.name)
            assertEquals(1, port.connectionCalls, entryPoint.name)
            assertEquals(1, port.cycleLoadCalls, entryPoint.name)
            assertSame(selectedRoutine, port.lastLoadedRoutine, entryPoint.name)
        }
    }

    @Test
    fun `routine load failures are typed while cancellation remains causal`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            val handle = persistedHandle(entryPoint)
            val manualPort = FakeRoutineResumeUiPort().apply {
                resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
                loadFailure = IllegalStateException("load failed")
            }
            val manualFailure = assertIs<RoutineResumeUiOutcome.LoadFailed>(
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(handle),
                    authority = currentAuthority(entryPoint),
                    port = manualPort,
                ),
            )
            assertEquals(
                RoutineResumeUiOperation.RetryManualLoad(handle, 0, 1),
                manualFailure.retryOperation,
                "manual/$entryPoint",
            )

            if (entryPoint != RoutineResumeEntryPoint.DAILY_ROUTINES) {
                val restartPort = FakeRoutineResumeUiPort().apply {
                    discardResult = RoutineResumeDiscardResult.Missing
                    loadFailure = IllegalStateException("fresh load failed")
                }
                assertEquals(
                    RoutineResumeUiOutcome.LoadFailed(),
                    runRoutineResumeUiOperation(
                        operation = RoutineResumeUiOperation.Restart(handle),
                        authority = currentAuthority(entryPoint),
                        port = restartPort,
                    ),
                    "restart/$entryPoint",
                )
                assertEquals(1, restartPort.connectionCalls, entryPoint.name)
            }
        }

        val cancellation = CancellationException("loader cancelled")
        val cancellationPort = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
            loadFailure = cancellation
        }
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                runRoutineResumeUiOperation(
                    operation = RoutineResumeUiOperation.Resume(
                        persistedHandle(RoutineResumeEntryPoint.DAILY_ROUTINES),
                    ),
                    authority = currentAuthority(RoutineResumeEntryPoint.DAILY_ROUTINES),
                    port = cancellationPort,
                )
            },
        )
    }

    @Test
    fun `manual recovery coordinates survive a loader failure after durable cleanup`() = runTest {
        val handle = persistedHandle(RoutineResumeEntryPoint.DAILY_ROUTINES).copy(
            manualRecoveryCoordinates = RestTransitionPlan.Coordinates(
                exerciseIndex = 0,
                setIndex = 1,
            ),
        )
        val port = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.ManualSetReady(0, 1)
            loadFailure = IllegalStateException("first exact load failed")
        }

        val missingPort = FakeRoutineResumeUiPort().apply {
            resumeResult = ActiveWorkoutRuntimeResumeResult.Missing
        }
        assertEquals(
            RoutineResumeUiOutcome.EnterDailyOverview(selectedRoutine),
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.DAILY_ROUTINES),
                port = missingPort,
            ),
        )

        val failure = assertIs<RoutineResumeUiOutcome.LoadFailed>(
            runRoutineResumeUiOperation(
                operation = RoutineResumeUiOperation.Resume(handle),
                authority = currentAuthority(RoutineResumeEntryPoint.DAILY_ROUTINES),
                port = port,
            ),
        )
        val retry = assertIs<RoutineResumeUiOperation.RetryManualLoad>(failure.retryOperation)
        assertEquals(1, port.resumeCalls)
        port.loadFailure = null
        port.resumeResult = ActiveWorkoutRuntimeResumeResult.Missing
        assertEquals(
            RoutineResumeUiOutcome.EnterSetReady(0, 1),
            runRoutineResumeUiOperation(
                operation = retry,
                authority = currentAuthority(RoutineResumeEntryPoint.DAILY_ROUTINES),
                port = port,
            ),
        )
        assertEquals(1, port.resumeCalls, "manual retry must not query the already-deleted runtime again")
    }

    private fun currentAuthority(entryPoint: RoutineResumeEntryPoint) = RoutineResumeActionAuthority(
        entryPoint = entryPoint,
        actionToken = 1,
        currentToken = { 1 },
        contextIsCurrent = { true },
    )

    private fun persistedHandle(entryPoint: RoutineResumeEntryPoint) = RoutineResumeHandle.Persisted(
        selectedProfileId = selectedRoutine.profileId,
        selectedRoutine = selectedRoutine,
        lookupKey = ActiveWorkoutRuntimeLookupKey(
            profileId = selectedRoutine.profileId,
            routineSessionId = "routine-session",
        ),
        rowRevision = ActiveWorkoutRuntimeRowRevision(
            documentVersion = 2L,
            updatedAtEpochMs = 3L,
            encodedPayloadIdentity = "payload-identity",
        ),
        progressInfo = progressInfo(),
        launchOrigin = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
            RoutineLaunchOrigin.DAILY_ROUTINES
        } else {
            RoutineLaunchOrigin.TRAINING_CYCLES
        },
        cycleId = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) null else "cycle-id",
        cycleDayNumber = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) null else 2,
        managerGeneration = generation(),
        manualRecoveryCoordinates = null,
    )

    private fun inMemoryHandle(entryPoint: RoutineResumeEntryPoint) = RoutineResumeHandle.InMemory(
        selectedProfileId = selectedRoutine.profileId,
        selectedRoutine = selectedRoutine,
        activeRoutineSnapshot = selectedRoutine,
        progressInfo = progressInfo(),
        launchOrigin = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
            RoutineLaunchOrigin.DAILY_ROUTINES
        } else {
            RoutineLaunchOrigin.TRAINING_CYCLES
        },
        cycleId = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) null else "cycle-id",
        cycleDayNumber = if (entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) null else 2,
        managerGeneration = generation(),
        exerciseIndex = 0,
        setIndex = 1,
        routineSessionId = "routine-session",
        activeLaunchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
        activeCycleId = null,
        activeCycleDayNumber = null,
    )

    private fun progressInfo() = ResumableProgressInfo(
        exerciseName = "Exercise",
        currentSet = 2,
        totalSets = 2,
        currentExercise = 1,
        totalExercises = 1,
    )

    private fun generation() = RoutineResumeManagerGeneration(
        configurationInputEpoch = 11L,
        recoveryPublicationEpoch = 12L,
    )

    private class FakeRoutineResumeUiPort : RoutineResumeUiPort {
        var resumeResult: ActiveWorkoutRuntimeResumeResult = ActiveWorkoutRuntimeResumeResult.Missing
        var resumeBlock: (suspend () -> ActiveWorkoutRuntimeResumeResult)? = null
        var discardResult: RoutineResumeDiscardResult = RoutineResumeDiscardResult.Missing
        var discardBlock: (suspend () -> RoutineResumeDiscardResult)? = null
        var connectionBlock: (suspend () -> Boolean)? = null
        var loadBlock: (suspend (() -> Boolean) -> Boolean)? = null
        var loadFailure: Throwable? = null
        var inMemoryHandleCurrent = true
        var connectionCalls = 0
        var resumeCalls = 0
        var dailyLoadCalls = 0
        var cycleLoadCalls = 0
        var lastLoadedRoutine: Routine? = null
        var lastCycleId: String? = null
        var lastCycleDayNumber: Int? = null

        override suspend fun resume(handle: RoutineResumeHandle): ActiveWorkoutRuntimeResumeResult {
            resumeCalls += 1
            return resumeBlock?.invoke() ?: resumeResult
        }

        override suspend fun discard(handle: RoutineResumeHandle): RoutineResumeDiscardResult = discardBlock?.invoke() ?: discardResult

        override suspend fun awaitConnection(): Boolean {
            connectionCalls += 1
            return connectionBlock?.invoke() ?: true
        }

        override fun isInMemoryHandleCurrent(handle: RoutineResumeHandle.InMemory): Boolean = inMemoryHandleCurrent

        override suspend fun loadDailyRoutine(
            routine: Routine,
            publicationStillCurrent: () -> Boolean,
        ): Boolean {
            dailyLoadCalls += 1
            lastLoadedRoutine = routine
            loadFailure?.let { throw it }
            return loadBlock?.invoke(publicationStillCurrent) ?: publicationStillCurrent()
        }

        override suspend fun loadCycleRoutine(
            routine: Routine,
            cycleId: String,
            dayNumber: Int,
            publicationStillCurrent: () -> Boolean,
        ): Boolean {
            cycleLoadCalls += 1
            lastLoadedRoutine = routine
            lastCycleId = cycleId
            lastCycleDayNumber = dayNumber
            loadFailure?.let { throw it }
            return loadBlock?.invoke(publicationStillCurrent) ?: publicationStillCurrent()
        }
    }
}
