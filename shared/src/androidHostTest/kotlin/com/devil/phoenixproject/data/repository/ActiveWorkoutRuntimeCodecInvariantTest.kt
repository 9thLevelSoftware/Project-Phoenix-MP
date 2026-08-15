package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test

class ActiveWorkoutRuntimeCodecInvariantTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: VitruvianDatabase
    private lateinit var repository: ActiveWorkoutRuntimeRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        database = VitruvianDatabase(driver)
        repository = SqlDelightActiveWorkoutRuntimeRepository(database)
    }

    @Test
    fun everyTopLevelCurrentDocumentInvariantRejectsCorruptJsonByName() = runTest {
        val source = documentJson(acceptedRetry())
        val logicalKey = source.getValue("logicalSetKey").jsonObject
        val overlays = source.getValue("exerciseLoadOverlays").jsonArray
        val overlay = overlays.single().jsonObject
        val attempts = source.getValue("attemptStates").jsonArray
        val attempt = attempts.single().jsonObject
        val attemptKey = attempt.getValue("logicalSetKey").jsonObject
        val cases = listOf(
            case("blank profileId", source.with("profileId", JsonPrimitive(" "))),
            case("blank routineId", source.with("routineId", JsonPrimitive(" "))),
            case("blank routineSessionId", source.with("routineSessionId", JsonPrimitive(" "))),
            case("blank routineExerciseId", source.with("routineExerciseId", JsonPrimitive(" "))),
            case("blank sourceExecutionId", source.with("sourceExecutionId", JsonPrimitive(" "))),
            case("blank sourceStableSessionId", source.with("sourceStableSessionId", JsonPrimitive(" "))),
            case("non-positive sourceAttemptNumber", source.with("sourceAttemptNumber", JsonPrimitive(0))),
            case("blank optional plannedSetId", source.with("plannedSetId", JsonPrimitive(" "))),
            case("negative sourceExerciseIndex", source.with("sourceExerciseIndex", JsonPrimitive(-1))),
            case("negative sourceSetIndex", source.with("sourceSetIndex", JsonPrimitive(-1))),
            case(
                "logical key session mismatch",
                source.with("logicalSetKey", logicalKey.with("routineSessionId", JsonPrimitive("other-session"))),
            ),
            case(
                "logical key occurrence mismatch",
                source.with("logicalSetKey", logicalKey.with("routineExerciseId", JsonPrimitive("other-occurrence"))),
            ),
            case(
                "logical key set index mismatch",
                source.with("logicalSetKey", logicalKey.with("setIndex", JsonPrimitive(2))),
            ),
            case(
                "blank overlay occurrence",
                source.with("exerciseLoadOverlays", JsonArray(listOf(overlay.with("routineExerciseId", JsonPrimitive(" "))))),
            ),
            case(
                "non-positive overlay multiplier",
                source.with("exerciseLoadOverlays", JsonArray(listOf(overlay.with("multiplier", JsonPrimitive(0.0))))),
            ),
            case(
                "attempt state session mismatch",
                source.with(
                    "attemptStates",
                    JsonArray(listOf(attempt.with("logicalSetKey", attemptKey.with("routineSessionId", JsonPrimitive("other-session"))))),
                ),
            ),
            case(
                "attempt state blank occurrence",
                source.with(
                    "attemptStates",
                    JsonArray(listOf(attempt.with("logicalSetKey", attemptKey.with("routineExerciseId", JsonPrimitive(" "))))),
                ),
            ),
            case(
                "attempt state negative set index",
                source.with(
                    "attemptStates",
                    JsonArray(listOf(attempt.with("logicalSetKey", attemptKey.with("setIndex", JsonPrimitive(-1))))),
                ),
            ),
            case(
                "attempt state non-positive next attempt",
                source.with("attemptStates", JsonArray(listOf(attempt.with("nextAttemptNumber", JsonPrimitive(0))))),
            ),
            case(
                "attempt state negative accepted drop count",
                source.with("attemptStates", JsonArray(listOf(attempt.with("acceptedDropCount", JsonPrimitive(-1))))),
            ),
            case(
                "attempt state accepted drop count above limit",
                source.with("attemptStates", JsonArray(listOf(attempt.with("acceptedDropCount", JsonPrimitive(3))))),
            ),
            case("negative original rest duration", source.with("originalRestDurationSeconds", JsonPrimitive(-1))),
            case("deadline and paused seconds both present", source.with("pausedRestRemainingSeconds", JsonPrimitive(10))),
            case(
                "paused state without paused seconds",
                source.with("restDeadlineEpochMs", JsonNull).with("isRestPaused", JsonPrimitive(true)),
            ),
            case(
                "paused seconds exceed original duration",
                source
                    .with("restDeadlineEpochMs", JsonNull)
                    .with("pausedRestRemainingSeconds", JsonPrimitive(61))
                    .with("isRestPaused", JsonPrimitive(true)),
            ),
            case(
                "paused seconds are negative",
                source
                    .with("restDeadlineEpochMs", JsonNull)
                    .with("pausedRestRemainingSeconds", JsonPrimitive(-1))
                    .with("isRestPaused", JsonPrimitive(true)),
            ),
            case(
                "active state carries paused seconds",
                source
                    .with("restDeadlineEpochMs", JsonNull)
                    .with("pausedRestRemainingSeconds", JsonPrimitive(10))
                    .with("isRestPaused", JsonPrimitive(false)),
            ),
        )

        assertEveryCaseIsCorrupt(cases)
    }

    @Test
    fun everyRestTransitionPlanVariantInvariantRejectsCorruptJsonByName() = runTest {
        val normalDocument = documentJson(normalAdvance())
        val unresolvedDocument = documentJson(unresolvedOffer())
        val declinedDocument = documentJson(declined())
        val acceptedDocument = documentJson(acceptedRetry())
        val cases = buildList {
            add(planCase("normal blank transitionId", normalDocument) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("normal blank sourceExecutionId", normalDocument) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("normal blank plannedSetId", normalDocument) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("normal blank logical key session", normalDocument) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("normal blank logical key occurrence", normalDocument) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("normal negative logical key index", normalDocument) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("normal negative exercise coordinate", normalDocument) { it.withCoordinates(exerciseIndex = -1) })
            add(planCase("normal negative set coordinate", normalDocument) { it.withCoordinates(setIndex = -1) })
            add(planCase("normal coordinate and logical key mismatch", normalDocument) { it.withCoordinates(setIndex = 2) })
            add(planCase("normal negative rest duration", normalDocument) { it.with("restDurationSeconds", JsonPrimitive(-1)) })
            add(planCase("normal source differs from document", normalDocument) { it.with("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("normal planned set differs from document", normalDocument) { it.with("plannedSetId", JsonPrimitive("other-planned")) })
            add(planCase("normal coordinates differ from document", normalDocument) { it.withCoordinates(exerciseIndex = 4) })

            add(planCase("unresolved blank transitionId", unresolvedDocument) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank sourceExecutionId", unresolvedDocument) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank offerId", unresolvedDocument) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank plannedSetId", unresolvedDocument) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank logical key session", unresolvedDocument) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank logical key occurrence", unresolvedDocument) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("unresolved negative logical key index", unresolvedDocument) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("unresolved empty candidates", unresolvedDocument) { it.with("candidates", JsonArray(emptyList())) })
            add(planCase("unresolved candidate non-positive weight", unresolvedDocument) { it.withCandidate("resolvedWeightPerCableKg", 0.0) })
            add(planCase("unresolved candidate non-positive multiplier", unresolvedDocument) { it.withCandidate("resultingExerciseMultiplier", 0.0) })
            add(planCase("unresolved nested normal transition mismatch", unresolvedDocument) { it.withNormal("transitionId", JsonPrimitive("other-transition")) })
            add(planCase("unresolved nested normal source mismatch", unresolvedDocument) { it.withNormal("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("unresolved nested normal key mismatch", unresolvedDocument) { it.withNormalKey("routineExerciseId", JsonPrimitive("other-occurrence")) })
            add(planCase("unresolved nested normal planned mismatch", unresolvedDocument) { it.withNormal("plannedSetId", JsonPrimitive("other-planned")) })
            add(planCase("unresolved nested normal negative coordinate", unresolvedDocument) { it.withNormalCoordinates(exerciseIndex = -1) })
            add(planCase("unresolved nested normal negative set coordinate", unresolvedDocument) { it.withNormalCoordinates(setIndex = -1) })
            add(planCase("unresolved nested normal coordinate and key mismatch", unresolvedDocument) { it.withNormalCoordinates(setIndex = 2) })
            add(planCase("unresolved nested normal negative rest duration", unresolvedDocument) { it.withNormal("restDurationSeconds", JsonPrimitive(-1)) })

            add(planCase("declined blank transitionId", declinedDocument) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("declined blank sourceExecutionId", declinedDocument) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("declined blank offerId", declinedDocument) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("declined blank logical key session", declinedDocument) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("declined blank logical key occurrence", declinedDocument) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("declined negative logical key index", declinedDocument) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("declined nested normal transition mismatch", declinedDocument) { it.withNormal("transitionId", JsonPrimitive("other-transition")) })
            add(planCase("declined nested normal source mismatch", declinedDocument) { it.withNormal("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("declined nested normal key mismatch", declinedDocument) { it.withNormalKey("routineExerciseId", JsonPrimitive("other-occurrence")) })
            add(planCase("declined nested normal planned differs from document", declinedDocument) { it.withNormal("plannedSetId", JsonPrimitive("other-planned")) })
            add(planCase("declined nested normal negative coordinate", declinedDocument) { it.withNormalCoordinates(setIndex = -1) })
            add(planCase("declined nested normal negative exercise coordinate", declinedDocument) { it.withNormalCoordinates(exerciseIndex = -1) })
            add(planCase("declined nested normal coordinate and key mismatch", declinedDocument) { it.withNormalCoordinates(setIndex = 2) })
            add(planCase("declined nested normal negative rest duration", declinedDocument) { it.withNormal("restDurationSeconds", JsonPrimitive(-1)) })

            add(planCase("accepted blank transitionId", acceptedDocument) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank sourceExecutionId", acceptedDocument) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank offerId", acceptedDocument) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("accepted blank plannedSetId", acceptedDocument) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("accepted blank logical key session", acceptedDocument) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank logical key occurrence", acceptedDocument) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("accepted negative logical key index", acceptedDocument) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("accepted negative exercise coordinate", acceptedDocument) { it.withCoordinates(exerciseIndex = -1) })
            add(planCase("accepted negative set coordinate", acceptedDocument) { it.withCoordinates(setIndex = -1) })
            add(planCase("accepted coordinate and logical key mismatch", acceptedDocument) { it.withCoordinates(setIndex = 2) })
            add(planCase("accepted non-positive resolved weight", acceptedDocument) { it.with("resolvedWeightPerCableKg", JsonPrimitive(0.0)) })
            add(planCase("accepted non-positive resulting multiplier", acceptedDocument) { it.with("resultingExerciseMultiplier", JsonPrimitive(0.0)) })
            add(planCase("accepted non-positive next attempt", acceptedDocument) { it.with("nextAttemptNumber", JsonPrimitive(0)) })
            add(planCase("accepted source differs from document", acceptedDocument) { it.with("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("accepted key differs from document", acceptedDocument) { it.withPlanKey("routineExerciseId", JsonPrimitive("other-occurrence")) })
            add(planCase("accepted planned set differs from document", acceptedDocument) { it.with("plannedSetId", JsonPrimitive("other-planned")) })
            add(planCase("accepted coordinates differ from document", acceptedDocument) { it.withCoordinates(exerciseIndex = 4) })
        }

        assertEveryCaseIsCorrupt(cases)
    }

    private suspend fun assertEveryCaseIsCorrupt(cases: List<NamedPayload>) {
        cases.forEach { case ->
            insertRaw(case.payload)
            val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                repository.load("profile-a", "routine-session-a"),
                case.name,
            )
            assertEquals(ActiveWorkoutRuntimeRejection.CORRUPT_JSON, rejected.reason, case.name)
        }
    }

    private fun documentJson(plan: RestTransitionPlan): JsonObject = json.encodeToJsonElement(runtime(plan)).jsonObject

    private fun runtime(plan: RestTransitionPlan): ActiveWorkoutRuntimeDocument {
        val key = logicalKey()
        return ActiveWorkoutRuntimeDocument(
            profileId = "profile-a",
            routineId = "routine-a",
            routineSessionId = "routine-session-a",
            routineExerciseId = "routine-exercise-a",
            sourceExecutionId = "execution-a",
            sourceStableSessionId = "stable-session-a",
            sourceAttemptNumber = 2,
            logicalSetKey = key,
            plannedSetId = "planned-set-a",
            sourceExerciseIndex = 3,
            sourceSetIndex = 1,
            exerciseLoadOverlays = listOf(ExerciseLoadOverlay("routine-exercise-a", 0.8f)),
            attemptStates = listOf(PlannedSetAttemptState(key, nextAttemptNumber = 3, acceptedDropCount = 1)),
            restTransitionPlan = plan,
            restDeadlineEpochMs = 1_700_000_060_123,
            originalRestDurationSeconds = 60,
        )
    }

    private fun logicalKey() = LogicalSetKey("routine-session-a", "routine-exercise-a", 1, SetType.AMRAP)

    private fun normalAdvance() = RestTransitionPlan.NormalAdvance(
        transitionId = "transition-a",
        sourceExecutionId = "execution-a",
        logicalSetKey = logicalKey(),
        sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
        plannedSetId = "planned-set-a",
        restDurationSeconds = 60,
    )

    private fun unresolvedOffer() = RestTransitionPlan.UnresolvedDropOffer(
        transitionId = "transition-a",
        sourceExecutionId = "execution-a",
        logicalSetKey = logicalKey(),
        offerId = "offer-a",
        plannedSetId = "planned-set-a",
        candidates = listOf(DropSetCandidate(DropPercentage.TWENTY, 32f, 0.8f)),
        normalAdvance = normalAdvance(),
    )

    private fun declined() = RestTransitionPlan.Declined(
        transitionId = "transition-a",
        sourceExecutionId = "execution-a",
        logicalSetKey = logicalKey(),
        offerId = "offer-a",
        normalAdvance = normalAdvance(),
    )

    private fun acceptedRetry() = RestTransitionPlan.AcceptedRetry(
        transitionId = "transition-a",
        sourceExecutionId = "execution-a",
        logicalSetKey = logicalKey(),
        offerId = "offer-a",
        sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
        plannedSetId = "planned-set-a",
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 32f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 3,
    )

    private fun planCase(
        name: String,
        document: JsonObject,
        mutate: (JsonObject) -> JsonObject,
    ): NamedPayload {
        val plan = document.getValue("restTransitionPlan").jsonObject
        return case(name, document.with("restTransitionPlan", mutate(plan)))
    }

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = JsonObject(this + (key to value))

    private fun JsonObject.withCoordinates(
        exerciseIndex: Int? = null,
        setIndex: Int? = null,
    ): JsonObject {
        var coordinates = getValue("sourceCoordinates").jsonObject
        if (exerciseIndex != null) coordinates = coordinates.with("exerciseIndex", JsonPrimitive(exerciseIndex))
        if (setIndex != null) coordinates = coordinates.with("setIndex", JsonPrimitive(setIndex))
        return with("sourceCoordinates", coordinates)
    }

    private fun JsonObject.withPlanKey(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = with("logicalSetKey", getValue("logicalSetKey").jsonObject.with(key, value))

    private fun JsonObject.withNormal(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = with("normalAdvance", getValue("normalAdvance").jsonObject.with(key, value))

    private fun JsonObject.withNormalKey(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject {
        val normal = getValue("normalAdvance").jsonObject
        return with("normalAdvance", normal.withPlanKey(key, value))
    }

    private fun JsonObject.withNormalCoordinates(
        exerciseIndex: Int? = null,
        setIndex: Int? = null,
    ): JsonObject {
        val normal = getValue("normalAdvance").jsonObject
        return with("normalAdvance", normal.withCoordinates(exerciseIndex, setIndex))
    }

    private fun JsonObject.withCandidate(key: String, value: Double): JsonObject {
        val candidate = getValue("candidates").jsonArray.single().jsonObject.with(key, JsonPrimitive(value))
        return with("candidates", JsonArray(listOf(candidate)))
    }

    private fun insertRaw(payload: JsonObject) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO ActiveWorkoutRuntime(profile_id,routine_session_id,document_version,runtime_json,updated_at_epoch_ms) VALUES (?,?,?,?,?)",
            5,
        ) {
            bindString(0, "profile-a")
            bindString(1, "routine-session-a")
            bindLong(2, 1)
            bindString(3, payload.toString())
            bindLong(4, 1_700_000_000_000)
        }
    }

    private fun case(name: String, payload: JsonObject) = NamedPayload(name, payload)

    private data class NamedPayload(
        val name: String,
        val payload: JsonObject,
    )
}
