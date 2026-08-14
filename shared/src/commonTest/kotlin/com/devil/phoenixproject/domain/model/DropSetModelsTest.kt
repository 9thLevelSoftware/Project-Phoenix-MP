package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DropSetModelsTest {
    private val json = Json

    @Test
    fun `logical set identity separates repeated library exercise occurrences set indexes and set kinds`() {
        val firstOccurrence = logicalSetKey(
            routineSessionId = "routine-session-41",
            routineExerciseId = "routine-exercise-press-a",
            setIndex = 0,
            setKind = SetType.STANDARD,
        )

        assertFalse(firstOccurrence == logicalSetKey("routine-session-41", "routine-exercise-press-b", 0, SetType.STANDARD))
        assertFalse(firstOccurrence == logicalSetKey("routine-session-41", "routine-exercise-press-a", 1, SetType.STANDARD))
        assertFalse(firstOccurrence == logicalSetKey("routine-session-41", "routine-exercise-press-a", 0, SetType.AMRAP))
    }

    @Test
    fun `logical set key is stable when a planned set id is absent`() {
        val key = logicalSetKey(
            routineSessionId = "routine-session-41",
            routineExerciseId = "routine-exercise-squat-a",
            setIndex = 2,
            setKind = SetType.STANDARD,
        )

        val encoded = json.encodeToString(key)

        assertEquals(
            "{\"routineSessionId\":\"routine-session-41\",\"routineExerciseId\":\"routine-exercise-squat-a\",\"setIndex\":2,\"setKind\":\"STANDARD\"}",
            encoded,
        )
        assertFalse(encoded.contains("plannedSetId"))
        assertEquals(key, json.decodeFromString<LogicalSetKey>(encoded))
    }

    @Test
    fun `manual repeat consumes its current attempt number and increments only that number`() {
        val initial = PlannedSetAttemptState(logicalSetKey("routine-session-41", "routine-exercise-row-a", 0, SetType.STANDARD))

        val consumed = initial.consumeRepeat(acceptedDrop = false)

        assertEquals(1, consumed.attemptNumber)
        assertEquals(2, consumed.nextState.nextAttemptNumber)
        assertEquals(0, consumed.nextState.acceptedDropCount)
    }

    @Test
    fun `accepted drops consume attempts increment the accepted count and cap it at two`() {
        val initial = PlannedSetAttemptState(logicalSetKey("routine-session-41", "routine-exercise-row-a", 0, SetType.STANDARD))

        val rejected = initial.consumeRepeat(acceptedDrop = false)
        val firstAccepted = rejected.nextState.consumeRepeat(acceptedDrop = true)
        val capped = firstAccepted.nextState
            .consumeRepeat(acceptedDrop = true)
            .nextState
            .consumeRepeat(acceptedDrop = true)

        assertEquals(1, rejected.attemptNumber)
        assertEquals(0, rejected.nextState.acceptedDropCount)
        assertEquals(2, firstAccepted.attemptNumber)
        assertEquals(1, firstAccepted.nextState.acceptedDropCount)
        assertEquals(4, capped.attemptNumber)
        assertEquals(2, capped.nextState.acceptedDropCount)
    }

    @Test
    fun `attempt state rejects accepted drop counts outside the supported range`() {
        val key = logicalSetKey("routine-session-41", "routine-exercise-row-a", 0, SetType.STANDARD)

        assertFailsWith<IllegalArgumentException> {
            PlannedSetAttemptState(logicalSetKey = key, acceptedDropCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedSetAttemptState(logicalSetKey = key, acceptedDropCount = 3)
        }
    }

    @Test
    fun `attempt state decoding rejects an out of range accepted drop count`() {
        val corrupt = "{\"logicalSetKey\":{\"routineSessionId\":\"routine-session-41\",\"routineExerciseId\":\"routine-exercise-row-a\",\"setIndex\":0,\"setKind\":\"STANDARD\"},\"nextAttemptNumber\":2,\"acceptedDropCount\":3}"

        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<PlannedSetAttemptState>(corrupt)
        }
    }

    @Test
    fun `occurrence load overlays compose geometrically without affecting another occurrence`() {
        val overlays = listOf(
            ExerciseLoadOverlay(routineExerciseId = "routine-exercise-press-a", multiplier = 0.8f),
            ExerciseLoadOverlay(routineExerciseId = "routine-exercise-press-a", multiplier = 0.8f),
            ExerciseLoadOverlay(routineExerciseId = "routine-exercise-press-b", multiplier = 0.9f),
        )

        assertEquals(0.64f, overlays.multiplierFor("routine-exercise-press-a"), absoluteTolerance = 0.0001f)
        assertEquals(0.9f, overlays.multiplierFor("routine-exercise-press-b"), absoluteTolerance = 0.0001f)
        assertEquals(1f, overlays.multiplierFor("routine-exercise-unmodified"), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `logical set decoding rejects an unknown persisted set type`() {
        val corrupt = "{\"routineSessionId\":\"routine-session-41\",\"routineExerciseId\":\"routine-exercise-row-a\",\"setIndex\":0,\"setKind\":\"FUTURE_DROP\"}"

        val failure = assertFailsWith<SerializationException> {
            json.decodeFromString<LogicalSetKey>(corrupt)
        }

        assertTrue(failure.message.orEmpty().contains("FUTURE_DROP"))
    }

    @Test
    fun `DWSM fixture key has stable defaults and permits explicit occurrence identity`() {
        val defaultKey = DWSMTestHarness.logicalSetKeyFixture()
        val explicitKey = DWSMTestHarness.logicalSetKeyFixture(
            routineSessionId = "routine-session-99",
            routineExerciseId = "routine-exercise-deadlift-a",
            setIndex = 3,
            setKind = SetType.AMRAP,
        )

        assertEquals("test-routine-session", defaultKey.routineSessionId)
        assertEquals("test-routine-exercise", defaultKey.routineExerciseId)
        assertEquals(0, defaultKey.setIndex)
        assertEquals(SetType.STANDARD, defaultKey.setKind)
        assertEquals("routine-session-99", explicitKey.routineSessionId)
        assertEquals("routine-exercise-deadlift-a", explicitKey.routineExerciseId)
        assertEquals(3, explicitKey.setIndex)
        assertEquals(SetType.AMRAP, explicitKey.setKind)
    }

    private fun logicalSetKey(
        routineSessionId: String,
        routineExerciseId: String,
        setIndex: Int,
        setKind: SetType,
    ) = LogicalSetKey(
        routineSessionId = routineSessionId,
        routineExerciseId = routineExerciseId,
        setIndex = setIndex,
        setKind = setKind,
    )
}
