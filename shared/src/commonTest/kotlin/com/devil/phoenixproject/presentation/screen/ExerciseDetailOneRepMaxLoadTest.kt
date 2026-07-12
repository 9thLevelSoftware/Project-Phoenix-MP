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
        assertEquals(
            listOf<ExerciseDetailOneRepMaxState>(ExerciseDetailOneRepMaxState.Loading),
            states,
        )
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
