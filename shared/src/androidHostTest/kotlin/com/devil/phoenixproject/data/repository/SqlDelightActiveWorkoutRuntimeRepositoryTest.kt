package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
    fun discoverReturnsNewestDecodedExactProfileAndRoutineWithItsSqlKey() = runTest {
        now = 100L
        repository.replace("profile-a", "routine-session-a", runtime(routineSessionId = "routine-session-a"))
        now = 200L
        repository.replace("profile-a", "routine-session-b", runtime(routineSessionId = "routine-session-b"))
        now = 300L
        repository.replace(
            "profile-b",
            "routine-session-c",
            runtime(profileId = "profile-b", routineSessionId = "routine-session-c"),
        )

        val found = assertIs<ActiveWorkoutRuntimeDiscoveryResult.Found>(
            repository.discover("profile-a", "routine-a"),
        )

        assertEquals(ActiveWorkoutRuntimeLookupKey("profile-a", "routine-session-b"), found.lookupKey)
        assertEquals(
            "routine-session-b",
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(found.loadResult).document.routineSessionId,
        )
    }

    @Test
    fun discoverIgnoresNewerRejectedAndEmbeddedProfileMismatchRows() = runTest {
        now = 100L
        repository.replace("profile-a", "routine-session-valid", runtime(routineSessionId = "routine-session-valid"))
        now = 200L
        val wrongProfile = runtime(profileId = "embedded-other", routineSessionId = "embedded-session")
        insertRaw(
            documentVersion = 2,
            payload = json.encodeToString(ActiveWorkoutRuntimeDocument.serializer(), wrongProfile),
            profileId = "profile-a",
            routineSessionId = "sql-mismatch-session",
        )
        now = 300L
        insertRaw(
            documentVersion = 2,
            payload = "{not-json",
            profileId = "profile-a",
            routineSessionId = "rejected-session",
        )

        val found = assertIs<ActiveWorkoutRuntimeDiscoveryResult.Found>(
            repository.discover("profile-a", "routine-a"),
        )

        assertEquals(ActiveWorkoutRuntimeLookupKey("profile-a", "routine-session-valid"), found.lookupKey)
    }

    @Test
    fun discoverReturnsTypedUnsupportedExactEnvelopeWithItsSqlKeyAndRevision() = runTest {
        now = 400L
        val v1Payload = JsonObject(objectJson() + ("version" to JsonPrimitive(1)))
        insertRaw(
            documentVersion = 1,
            payload = v1Payload.toString(),
            profileId = "profile-a",
            routineSessionId = "captured-v1-sql-key",
        )

        val found = assertIs<ActiveWorkoutRuntimeDiscoveryResult.Found>(
            repository.discover("profile-a", "routine-a"),
        )
        val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(found.loadResult)
        val attribution = assertIs<ActiveWorkoutRuntimeAttributionEnvelope>(rejected.attribution)

        assertEquals(
            ActiveWorkoutRuntimeLookupKey("profile-a", "captured-v1-sql-key"),
            found.lookupKey,
        )
        assertEquals(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION, rejected.reason)
        assertEquals("profile-a", attribution.profileId)
        assertEquals("routine-a", attribution.routineId)
        assertEquals(400L, rejected.rowRevision.updatedAtEpochMs)
    }

    @Test
    fun discoverReturnsNewerAttributableCurrentCorruptRowInsteadOfOlderExecutableRow() = runTest {
        now = 100L
        repository.replace(
            "profile-a",
            "routine-session-valid",
            runtime(routineSessionId = "routine-session-valid"),
        )
        val sourceAuthority = objectJson().getValue("sourceAuthority") as JsonObject
        val attributableCurrentCorrupt = JsonObject(
            objectJson() +
                (
                    "sourceAuthority" to JsonObject(
                        sourceAuthority + ("reasonName" to JsonPrimitive(SetEndReason.UNKNOWN.name)),
                    )
                    ),
        )
        now = 200L
        insertRaw(
            documentVersion = 2,
            payload = attributableCurrentCorrupt.toString(),
            profileId = "profile-a",
            routineSessionId = "routine-session-current-corrupt",
        )

        val found = assertIs<ActiveWorkoutRuntimeDiscoveryResult.Found>(
            repository.discover("profile-a", "routine-a"),
        )
        val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(found.loadResult)

        assertEquals(
            ActiveWorkoutRuntimeLookupKey("profile-a", "routine-session-current-corrupt"),
            found.lookupKey,
        )
        assertEquals(ActiveWorkoutRuntimeRejection.CORRUPT_JSON, rejected.reason)
        assertEquals("profile-a", rejected.attribution?.profileId)
        assertEquals("routine-a", rejected.attribution?.routineId)
        assertEquals(200L, rejected.rowRevision.updatedAtEpochMs)
    }

    @Test
    fun discoverLeavesUnattributableAndWrongRoutineUnsupportedRowsUnassociated() = runTest {
        now = 500L
        insertRaw(
            documentVersion = 1,
            payload = """{"version":1,"profileId":"profile-a"}""",
            profileId = "profile-a",
            routineSessionId = "missing-routine-envelope",
        )
        now = 600L
        val wrongRoutine = JsonObject(
            objectJson() +
                ("version" to JsonPrimitive(1)) +
                ("routineId" to JsonPrimitive("routine-other")),
        )
        insertRaw(
            documentVersion = 1,
            payload = wrongRoutine.toString(),
            profileId = "profile-a",
            routineSessionId = "wrong-routine-envelope",
        )
        now = 700L
        insertRaw(
            documentVersion = 1,
            payload = "{not-json",
            profileId = "profile-a",
            routineSessionId = "corrupt-envelope",
        )

        assertIs<ActiveWorkoutRuntimeDiscoveryResult.Missing>(
            repository.discover("profile-a", "routine-a"),
        )
        assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
            repository.load("profile-a", "missing-routine-envelope"),
        )
        assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
            repository.load("profile-a", "wrong-routine-envelope"),
        )
        assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
            repository.load("profile-a", "corrupt-envelope"),
        )
    }

    @Test
    fun discoverBreaksEqualTimestampTiesByDescendingRoutineSessionId() = runTest {
        now = 800L
        repository.replace("profile-a", "routine-session-a", runtime(routineSessionId = "routine-session-a"))
        repository.replace("profile-a", "routine-session-z", runtime(routineSessionId = "routine-session-z"))

        val found = assertIs<ActiveWorkoutRuntimeDiscoveryResult.Found>(
            repository.discover("profile-a", "routine-a"),
        )

        assertEquals(ActiveWorkoutRuntimeLookupKey("profile-a", "routine-session-z"), found.lookupKey)
    }

    @Test
    fun exactLoadRevisionChangesWhenTheCapturedRowIsReplaced() = runTest {
        now = 100L
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 2))
        val first = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )
        now = 101L
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 3))
        val replacement = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )

        assertNotEquals(first.rowRevision, replacement.rowRevision)
        assertEquals(100L, first.rowRevision.updatedAtEpochMs)
        assertEquals(101L, replacement.rowRevision.updatedAtEpochMs)
    }

    @Test
    fun exactLoadRevisionChangesForSameMillisecondPayloadReplacement() = runTest {
        now = 900L
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 2))
        val first = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )

        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 3))
        val replacement = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )

        assertEquals(first.rowRevision.updatedAtEpochMs, replacement.rowRevision.updatedAtEpochMs)
        assertNotEquals(first.rowRevision, replacement.rowRevision)
        assertNotEquals(first.rowRevision.encodedPayloadIdentity, replacement.rowRevision.encodedPayloadIdentity)
    }

    @Test
    fun conditionalDeleteUsesTheFullSameMillisecondRowRevisionAsAnAtomicCompareAndSwap() = runTest {
        now = 901L
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 2))
        val captured = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )
        repository.replace("profile-a", "routine-session-a", runtime(sourceAttemptNumber = 3))
        val replacement = assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
            repository.load("profile-a", "routine-session-a"),
        )

        assertFalse(
            repository.deleteIfRevisionMatches(
                profileId = "profile-a",
                routineSessionId = "routine-session-a",
                expectedRevision = captured.rowRevision,
            ),
        )
        assertEquals(
            3,
            assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(
                repository.load("profile-a", "routine-session-a"),
            ).document.sourceAttemptNumber,
        )
        assertTrue(
            repository.deleteIfRevisionMatches(
                profileId = "profile-a",
                routineSessionId = "routine-session-a",
                expectedRevision = replacement.rowRevision,
            ),
        )
        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
            repository.load("profile-a", "routine-session-a"),
        )
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
    fun replaceRejectsAMismatchedProfileKeyWithoutWritingEitherIdentity() = runTest {
        val document = runtime()

        assertFailsWith<IllegalArgumentException> {
            repository.replace("supplied-profile", document.routineSessionId, document)
        }

        assertEquals("0", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='supplied-profile' AND routine_session_id='routine-session-a'"))
        assertEquals("0", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a' AND routine_session_id='routine-session-a'"))
    }

    @Test
    fun replaceRejectsAMismatchedRoutineSessionKeyWithoutWritingEitherIdentity() = runTest {
        val document = runtime()

        assertFailsWith<IllegalArgumentException> {
            repository.replace(document.profileId, "supplied-routine-session", document)
        }

        assertEquals("0", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a' AND routine_session_id='supplied-routine-session'"))
        assertEquals("0", scalar("SELECT CAST(COUNT(*) AS TEXT) FROM ActiveWorkoutRuntime WHERE profile_id='profile-a' AND routine_session_id='routine-session-a'"))
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
    fun generatedDeleteByProfileRemovesEveryRuntimeForOnlyThatProfile() = runTest {
        repository.replace("profile-a", "routine-session-a", runtime())
        repository.replace("profile-a", "routine-session-b", runtime(routineSessionId = "routine-session-b"))
        repository.replace(
            "profile-b",
            "routine-session-c",
            runtime(profileId = "profile-b", routineSessionId = "routine-session-c"),
        )

        database.vitruvianDatabaseQueries.deleteActiveWorkoutRuntimeByProfile("profile-a")

        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-a", "routine-session-a"))
        assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(repository.load("profile-a", "routine-session-b"))
        assertIs<ActiveWorkoutRuntimeLoadResult.Loaded>(repository.load("profile-b", "routine-session-c"))
    }

    @Test
    fun replaceExecutesOneWriteAndNoPreliminaryRead() = runTest {
        val countingDriver = CountingSqlDriver(driver)
        val countedRepository = SqlDelightActiveWorkoutRuntimeRepository(
            VitruvianDatabase(countingDriver),
            nowEpochMs = { now },
        )

        countedRepository.replace("profile-a", "routine-session-a", runtime())

        assertEquals(0, countingDriver.readStatements.size)
        assertEquals(1, countingDriver.writeStatements.size)
        assertEquals(
            true,
            countingDriver.writeStatements.single().contains("INSERT OR REPLACE INTO ActiveWorkoutRuntime"),
        )
    }

    @Test
    fun malformedJsonIsRejectedAsCorrupt() = runTest {
        insertRaw(2, "{not-json")
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun missingRequiredFieldIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson().filterKeys { it != "routineId" }))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun missingV2SourceAuthorityIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson().filterKeys { it != "sourceAuthority" }))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun unknownV2SourceAuthorityFieldIsRejectedAsCorrupt() = runTest {
        val sourceAuthority = objectJson().getValue("sourceAuthority") as JsonObject
        insertRaw(
            2,
            JsonObject(
                objectJson() +
                    ("sourceAuthority" to JsonObject(sourceAuthority + ("futureAuthority" to JsonPrimitive(true)))),
            ),
        )
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun invalidV2SourceAuthorityEnumIsRejectedAsCorrupt() = runTest {
        val sourceAuthority = objectJson().getValue("sourceAuthority") as JsonObject
        insertRaw(
            2,
            JsonObject(
                objectJson() +
                    ("sourceAuthority" to JsonObject(sourceAuthority + ("reasonName" to JsonPrimitive("FUTURE")))),
            ),
        )
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun missingOrInvalidV2TeardownSeedIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson().filterKeys { it != "teardownSeed" }))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        val teardownSeed = objectJson().getValue("teardownSeed") as JsonObject
        insertRaw(
            2,
            JsonObject(
                objectJson() +
                    ("teardownSeed" to JsonObject(teardownSeed + ("sourceExecutionId" to JsonPrimitive(0)))),
            ),
        )
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun wrongFieldTypeIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson() + ("sourceAttemptNumber" to JsonPrimitive("one"))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun unknownPropertyIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson() + ("futureField" to JsonPrimitive(true))))
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun unknownEnumIsRejectedAsCorrupt() = runTest {
        val logicalKey = objectJson().getValue("logicalSetKey") as JsonObject
        insertRaw(
            2,
            JsonObject(
                objectJson() +
                    ("logicalSetKey" to JsonObject(logicalKey + ("setKind" to JsonPrimitive("FUTURE_SET")))),
            ),
        )
        assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
    }

    @Test
    fun constructorInvariantFailureIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson() + ("originalRestDurationSeconds" to JsonPrimitive(-1))))
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
            insertRaw(2, payload)
            assertRejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        }
    }

    @Test
    fun loadLeavesSqlKeyIdentityMismatchForValidatedResumeWithoutRewritingTheDocument() = runTest {
        val document = runtime()
        insertRaw(
            documentVersion = 2,
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
        insertRaw(1, "{\"version\":2,\"future\":true}")
        assertRejected(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION)
    }

    @Test
    fun unsupportedPayloadVersionIsRejectedBeforeCurrentDecode() = runTest {
        insertRaw(2, "{\"version\":1,\"future\":true}")
        assertRejected(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION)
    }

    @Test
    fun currentVersionWithNonIntegerPayloadVersionIsRejectedAsCorrupt() = runTest {
        insertRaw(2, JsonObject(objectJson() + ("version" to JsonPrimitive(2.0))))
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

    @Test
    fun cancellationFromEveryCodecStageIsRethrownInsteadOfClassified() = runTest {
        insertRaw(2, objectJson())

        CodecStage.entries.forEach { stage ->
            val cancellingRepository = SqlDelightActiveWorkoutRuntimeRepository(
                database = database,
                nowEpochMs = { now },
                codec = CancellingCodec(stage),
            )

            val thrown = runCatching {
                cancellingRepository.load("profile-a", "routine-session-a")
            }.exceptionOrNull()

            assertIs<CancellationException>(thrown, stage.name)
        }
    }

    private suspend fun assertRejected(expected: ActiveWorkoutRuntimeRejection) {
        val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(repository.load("profile-a", "routine-session-a"))
        assertEquals(expected, rejected.reason)
    }

    private fun runtime(
        profileId: String = "profile-a",
        routineId: String = "routine-a",
        routineSessionId: String = "routine-session-a",
        sourceAttemptNumber: Int = 2,
    ): ActiveWorkoutRuntimeDocument {
        val key = LogicalSetKey(routineSessionId, "routine-exercise-a", 1, SetType.AMRAP)
        val normalAdvance = RestTransitionPlan.NormalAdvance(
            transitionId = "transition-a",
            sourceExecutionId = "42",
            logicalSetKey = key,
            sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
            plannedSetId = "planned-set-a",
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routineId,
            routineSessionId = routineSessionId,
            routineExerciseId = "routine-exercise-a",
            sourceExecutionId = "42",
            sourceStableSessionId = "stable-session-a",
            sourceAttemptNumber = sourceAttemptNumber,
            logicalSetKey = key,
            plannedSetId = "planned-set-a",
            sourceExerciseIndex = 3,
            sourceSetIndex = 1,
            sourceAuthority = RestoredRetrySourceAuthoritySnapshot(
                sourceStableSessionId = "stable-session-a",
                sourceExecutionId = "42",
                profileId = profileId,
                routineIdentity = RoutineExecutionIdentity(
                    profileId = profileId,
                    routineId = routineId,
                    routineSessionId = routineSessionId,
                    routineExerciseId = "routine-exercise-a",
                    logicalSetKey = key,
                    plannedSetId = "planned-set-a",
                    exerciseIndex = 3,
                    setIndex = 1,
                ),
                reasonName = SetEndReason.STALL_FAILURE.name,
                attemptNumber = sourceAttemptNumber,
                acceptedDropCount = 1,
                plannedSetTypeName = SetType.AMRAP.name,
                programModeName = ProgramMode.OldSchool.toSnapshotName(),
                programmedBaseWeightPerCableKg = 40f,
                configuredStartWeightPerCableKg = 40f,
                progressionKg = 0f,
                actualReps = 6,
                targetReps = 10,
                isWarmup = false,
                isEcho = false,
                isJustLift = false,
                isBodyweight = false,
                isTimed = false,
                isAmrap = true,
                isCableExercise = true,
                physicalCableCount = 2,
                commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(
                    WorkoutParameters(ProgramMode.OldSchool, reps = 10, weightPerCableKg = 40f),
                ),
            ),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = 42L,
                sourceStableSessionId = "stable-session-a",
                profileId = profileId,
                requiresMachine = true,
            ),
            exerciseLoadOverlays = listOf(ExerciseLoadOverlay("routine-exercise-a", 0.8f)),
            attemptStates = listOf(PlannedSetAttemptState(key, nextAttemptNumber = 3, acceptedDropCount = 1)),
            restTransitionPlan = RestTransitionPlan.AcceptedRetry(
                transitionId = "transition-a",
                sourceExecutionId = "42",
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

    private enum class CodecStage { PARSE, VERSION, DECODE }

    private inner class CancellingCodec(
        private val stage: CodecStage,
    ) : ActiveWorkoutRuntimeJsonCodec {
        override fun encode(document: ActiveWorkoutRuntimeDocument): String = json.encodeToString(ActiveWorkoutRuntimeDocument.serializer(), document)

        override fun parseObject(payload: String): JsonObject? {
            if (stage == CodecStage.PARSE) throw CancellationException("cancel during parse")
            return objectJson()
        }

        override fun version(document: JsonObject): Int? {
            if (stage == CodecStage.VERSION) throw CancellationException("cancel during version")
            return ActiveWorkoutRuntimeDocument.CURRENT_VERSION
        }

        override fun decode(payload: String): ActiveWorkoutRuntimeDocument {
            if (stage == CodecStage.DECODE) throw CancellationException("cancel during decode")
            return runtime()
        }
    }

    private class CountingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        val writeStatements = mutableListOf<String>()
        val readStatements = mutableListOf<String>()

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            writeStatements += sql
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            readStatements += sql
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }
}
