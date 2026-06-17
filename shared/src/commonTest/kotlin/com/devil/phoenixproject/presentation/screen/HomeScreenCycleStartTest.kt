package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeScreenCycleStartTest {
    @Test
    fun awaitLoadedRoutine_waitsUntilMatchingRoutineIdAppears() = runTest {
        val loadedRoutine = MutableStateFlow<Routine?>(null)
        val target = routine("routine-cycle-home")

        val observedId = coroutineScope {
            val waiter = async {
                awaitLoadedRoutine(loadedRoutine, target.id)
                loadedRoutine.value?.id
            }
            loadedRoutine.value = routine("stale-routine")
            loadedRoutine.value = target
            waiter.await()
        }

        assertEquals(target.id, observedId)
    }

    @Test
    fun awaitLoadedRoutine_ignoresStaleLoadedRoutine() = runTest {
        val loadedRoutine = MutableStateFlow<Routine?>(routine("stale-routine"))

        val observedId = coroutineScope {
            val waiter = async {
                awaitLoadedRoutine(loadedRoutine, "expected-routine")
                loadedRoutine.value?.id
            }
            loadedRoutine.value = routine("expected-routine")
            waiter.await()
        }

        assertEquals("expected-routine", observedId)
    }

    private fun routine(id: String): Routine = Routine(
        id = id,
        name = "Routine $id",
        exercises = listOf(
            RoutineExercise(
                id = "$id-ex",
                exercise = Exercise(
                    id = "$id-exercise",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    muscleGroups = "Chest",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = 20f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )
}
