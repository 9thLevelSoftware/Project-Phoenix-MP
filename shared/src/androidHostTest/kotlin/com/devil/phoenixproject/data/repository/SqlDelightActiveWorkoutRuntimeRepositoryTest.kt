package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Before
import org.junit.Test

class SqlDelightActiveWorkoutRuntimeRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: VitruvianDatabase
    private lateinit var repository: ActiveWorkoutRuntimeRepository
    private var now = 1_700_000_000_123L
    private val json = Json { encodeDefaults = true }

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        database = VitruvianDatabase(driver)
        repository = SqlDelightActiveWorkoutRuntimeRepository(database, nowEpochMs = { now })
    }

    @Test
    fun replaceAndLoadRoundTripsTheFullDocumentIncludingTransition() = runTest {
        val document = runtime()

        repository.replace("profile-a", "routine-session-a", document)

        assertEquals(document, assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-a", "routine-session-a")).document)
        assertEquals("1700000000123", scalar("SELECT CAST(updated_at_epoch_ms AS TEXT) FROM ActiveWorkoutRuntime"))
    }

    @Test
    fun loadReturnsMissingWhenTheExactKeyHasNoRow() = runTest {
        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-a", "routine-session-a"))
    }

    @Test
    fun deleteRemovesOnlyTheExactCompositeKey() = runTest {
        repository.replace("profile-a", "routine-session-a", runtime())
        repository.replace("profile-a", "routine-session-b", runtime(routineSessionId = "routine-session-b"))

        repository.delete("profile-a", "routine-session-a")

        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-a", "routine-session-a"))
        assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-a", "routine-session-b"))
    }

    @Test
    fun profileAndRoutineSessionKeysAreIsolated() = runTest {
        repository.replace("profile-a", "routine-session-a", runtime())

        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-b", "routine-session-a"))
        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-a", "routine-session-b"))
    }

    @Test
    fun lastWriteReplacesTheSameKeyAndUpdatesItsTimestampWithoutTouchingAnotherKey() = runTest {
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 1))
        repository.replace("profile-b", "routine-session-b", runtime(profileId = "profile-b", routineSessionId = "routine-session-b"))
        now = 1_700_000_000_999L

        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 2))

        val replaced = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-a", "routine-session-a"))
        assertEquals(2, replaced.document.sourceAttemptNumber)
        assertEquals("1700000000999", scalar("SELECT CAST(updated_at_epoch_ms AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a'"))
        assertEquals("1", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a' AND routine_session_id='routine-session-a'"))
        assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-b", "routine-session-b"))
    }

    @Test
    fun rapidConcurrentReplacementLeavesOneValidRowAndDoesNotTouchAnotherKey() = runTest {
        repository.replace("profile-b", "routine-session-b", runtime(profileId = "profile-b", routineSessionId = "routine-session-b"))
        (1..40).map { attempt ->
            async { repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = attempt)) }
        }.awaitAll()
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 41))

        assertEquals("1", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a' AND routine_session_id='routine-session-a'"))
        assertEquals(41, assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-a", "routine-session-a")).document.sourceAttemptNumber)
        assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-b", "routine-session-b"))
    }

    @Test
    fun malformedJsonIsRejectedAsCorrupt() = runTest {
        insertRaw(1, "{not-json")
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun missingRequiredFieldIsRejectedAsCorrupt() = runTest {
        insertRaw(1, JsonObject(objectJson().filterKeys { it != "routineId" }))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun wrongFieldTypeIsRejectedAsCorrupt() = runTest {
        insertRaw(1, JsonObject(objectJson() + ("sourceAttemptNumber" to JsonPrimitive("one"))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun unknownPropertyIsRejectedAsCorrupt() = runTest {
        insertRaw(1, JsonObject(objectJson() + ("futureField" to JsonPrimitive(true))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun unknownEnumIsRejectedAsCorrupt() = runTest {
        val logicalKey = objectJson().getValue("logicalSetKey") as JsonObject
        insertRaw(
            1,
            JsonObject(
                objectJson() +
                    ("logicalSetKey" to JsonObject(logicalKey + ("setKind" to JsonPrimitive("FUTURE_SET")))),
            ),
        )
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun constructorInvariantFailureIsRejectedAsCorrupt() = runTest {
        insertRaw(1, JsonObject(objectJson() + ("originalRestDurationSeconds" to JsonPrimitive(-1))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun structurallyImpossibleCurrentDocumentsFailClosedAsCorrupt() = runTest {
        val source = objectJson()
        val logicalKey = source.getValue("logicalSetKey") as JsonObject
        val transition = source.getValue("restTransitionPlan") as JsonObject
        val coordinates = transition.getValue("sourceCoordinates") as JsonObject
        val corruptPayloads = listOf(
            JsonObject(source + ("profileId" to JsonPrimitive(" "))),
            JsonObject(source + ("sourceAttemptNumber" to JsonPrimitive(0))),
            JsonObject(
                source +
                    ("logicalSetKey" to JsonObject(logicalKey + ("routineSessionId" to JsonPrimitive("other-session")))),
            ),
            JsonObject(
                source +
                    ("logicalSetKey" to JsonObject(logicalKey + ("routineExerciseId" to JsonPrimitive("other-occurrence")))),
            ),
            JsonObject(
                source +
                    (
                        "restTransitionPlan" to
                            JsonObject(
                                transition +
                                    ("sourceCoordinates" to JsonObject(coordinates + ("exerciseIndex" to JsonPrimitive(4)))),
                            )
                    ),
            ),
            JsonObject(
                source +
                    (
                        "restTransitionPlan" to
                            JsonObject(transition + ("plannedSetId" to JsonPrimitive("other-planned-set")))
                    ),
            ),
            JsonObject(
                source +
                    ("pausedRestRemainingSeconds" to JsonPrimitive(10)) +
                    ("isRestPaused" to JsonPrimitive(false)),
            ),
            JsonObject(
                source +
                    ("restDeadlineEpochMs" to JsonNull) +
                    ("pausedRestRemainingSeconds" to JsonPrimitive(61)) +
                    ("isRestPaused" to JsonPrimitive(true)),
            ),
            JsonObject(
                source +
                    ("pausedRestRemainingSeconds" to JsonPrimitive(10)) +
                    ("isRestPaused" to JsonPrimitive(true)),
            ),
        )

        corruptPayloads.forEach { payload ->
            insertRaw(1, payload)
            assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        }
    }

    @Test
    fun loadLeavesSqlKeyIdentityMismatchForValidatedResumeWithoutRewritingTheDocument() = runTest {
        val document = runtime()
        insertRaw(
            documentVersion = 1,
            payload = json.encodeToString(ActiveWorkoutRuntimeDocument.serializer(), document),
            profileId = "different-sql-profile",
            routineSessionId = "different-sql-session",
        )

        val loaded = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("different-sql-profile", "different-sql-session"),
        )

        assertEquals("profile-a", loaded.document.profileId)
        assertEquals("routine-session-a", loaded.document.routineSessionId)
    }

    @Test
    fun unsupportedStoredColumnVersionIsRejectedBeforeCurrentDecode() = runTest {
        insertRaw(2, "{\"version\":1,\"future\":true}")
        assertRejected(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION)
    }

    @Test
    fun unsupportedPayloadVersionIsRejectedBeforeCurrentDecode() = runTest {
        insertRaw(1, "{\"version\":2,\"future\":true}")
        assertRejected(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION)
    }

    @Test
    fun currentVersionWithNonIntegerPayloadVersionIsRejectedAsCorrupt() = runTest {
        insertRaw(1, JsonObject(objectJson() + ("version" to JsonPrimitive(1.0))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun cancellationIsRethrownInsteadOfClassifiedAsCorrupt() = runTest {
        repository.replace("profile-a", "routine-session-a", runtime())
        val cancelled = Job().apply { cancel(CancellationException("test cancellation")) }

        val thrown = runCatching {
            withContext(cancelled) { repository.load("profile-a", "routine-session-a") }
        }.exceptionOrNull()

        assertIs<CancellationException>(thrown)
    }

    private suspend fun assertRejected(expected: ActiveWorkoutRuntimeRejection) {
        val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(repository.load("profile-a", "routine-session-a"))
        assertEquals(expected, rejected.reason)
    }

    private fun runtime(
        profileId: String = "profile-a",
        routineSessionId: String = "routine-session-a",
        sourceAttemptNumber: Int = 2,
    ): ActiveWorkoutRuntimeDocument {
        val key = LogicalSetKey(routineSessionId, "routine-exercise-a", 1, SetType.AMRAP)
        val normalAdvance = RestTransitionPlan.NormalAdvance(
            transitionId = "transition-a",
            sourceExecutionId = "execution-a",
            logicalSetKey = key,
            sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
            plannedSetId = "planned-set-a",
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = "routine-a",
            routineSessionId = routineSessionId,
            routineExerciseId = "routine-exercise-a",
            sourceExecutionId = "execution-a",
            sourceStableSessionId = "stable-session-a",
            sourceAttemptNumber = sourceAttemptNumber,
            logicalSetKey = key,
            plannedSetId = "planned-set-a",
            sourceExerciseIndex = 3,
            sourceSetIndex = 1,
            exerciseLoadOverlays = listOf(ExerciseLoadOverlay("routine-exercise-a", 0.8f)),
            attemptStates = listOf(PlannedSetAttemptState(key, nextAttemptNumber = 3, acceptedDropCount = 1)),
            restTransitionPlan = RestTransitionPlan.AcceptedRetry(
                transitionId = "transition-a",
                sourceExecutionId = "execution-a",
                logicalSetKey = key,
                offerId = "offer-a",
                sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
                plannedSetId = "planned-set-a",
                percentage = DropPercentage.TWENTY,
                resolvedWeightPerCableKg = 32f,
                resultingExerciseMultiplier = 0.8f,
                nextAttemptNumber = 3,
            ),
            restDeadlineEpochMs = 1_700_000_060_123L,
            originalRestDurationSeconds = normalAdvance.restDurationSeconds,
        )
    }

    private fun objectJson(): JsonObject = json.encodeToJsonElement(runtime()) as JsonObject

    private fun insertRaw(documentVersion: Long, payload: JsonObject) = insertRaw(documentVersion, payload.toString())

    private fun insertRaw(
        documentVersion: Long,
        payload: String,
        profileId: String = "profile-a",
        routineSessionId: String = "routine-session-a",
    ) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO ActiveWorkoutRuntime(profile_id,routine_session_id,document_version,runtime_json,updated_at_epoch_ms) VALUES (?,?,?,?,?)",
            5,
        ) {
            bindString(0, profileId)
            bindString(1, routineSessionId)
            bindLong(2, documentVersion)
            bindString(3, payload)
            bindLong(4, now)
        }
    }

    private fun scalar(sql: String): String? {
        var value: String? = null
        driver.executeQuery(null, sql, { cursor ->
            if (cursor.next().value) value = cursor.getString(0)
            QueryResult.Value(Unit)
        }, 0)
        return value
    }
}
