package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test

class ActiveWorkoutRuntimeCodecInvariantTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: VitruvianDatabase
    private lateinit var repository: ActiveWorkoutRuntimeRepository
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = true
    }

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        database = VitruvianDatabase(driver)
        repository = SqlDelightActiveWorkoutRuntimeRepository(database)
    }

    @Test
    fun everyTopLevelCurrentDocumentInvariantRejectsCorruptJsonByName() = runTest {
        val source = documentJson()
        val logicalKey = source.getValue("logicalSetKey").jsonObject
        val overlay = json.encodeToJsonElement(ExerciseLoadOverlay("routine-exercise-a", 0.8f)).jsonObject
        val attempt = json.encodeToJsonElement(PlannedSetAttemptState(logicalKey(), 3, 1)).jsonObject
        val attemptKey = attempt.getValue("logicalSetKey").jsonObject
        val cases = listOf(
            case("blank profileId", source.with("profileId", JsonPrimitive(" "))),
            case("blank routineId", source.with("routineId", JsonPrimitive(" "))),
            case(
                "blank routineSessionId",
                source
                    .with("routineSessionId", JsonPrimitive(" "))
                    .with("logicalSetKey", logicalKey.with("routineSessionId", JsonPrimitive(" "))),
            ),
            case(
                "blank routineExerciseId",
                source
                    .with("routineExerciseId", JsonPrimitive(" "))
                    .with("logicalSetKey", logicalKey.with("routineExerciseId", JsonPrimitive(" "))),
            ),
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
            case(
                "deadline and paused seconds both present",
                source
                    .with("restDeadlineEpochMs", JsonPrimitive(1_700_000_060_123))
                    .with("pausedRestRemainingSeconds", JsonPrimitive(10)),
            ),
            case("paused state without paused seconds", source.with("isRestPaused", JsonPrimitive(true))),
            case(
                "paused seconds exceed original duration",
                source
                    .with("pausedRestRemainingSeconds", JsonPrimitive(61))
                    .with("isRestPaused", JsonPrimitive(true)),
            ),
            case(
                "paused seconds are negative",
                source
                    .with("pausedRestRemainingSeconds", JsonPrimitive(-1))
                    .with("isRestPaused", JsonPrimitive(true)),
            ),
            case(
                "active state carries paused seconds",
                source.with("pausedRestRemainingSeconds", JsonPrimitive(10)),
            ),
        )

        assertEveryDocumentCaseIsCorrupt(cases)
    }

    @Test
    fun everyV2SourceAndTeardownAuthorityInvariantRejectsCorruptJsonByName() = runTest {
        val document = documentJson()
        val authority = document.getValue("sourceAuthority").jsonObject
        val identity = authority.getValue("routineIdentity").jsonObject
        val identityKey = identity.getValue("logicalSetKey").jsonObject
        val template = authority.getValue("commandTemplate").jsonObject
        val teardown = document.getValue("teardownSeed").jsonObject
        fun withAuthority(updated: JsonObject) = document.with("sourceAuthority", updated)
        fun withIdentity(updated: JsonObject) = withAuthority(authority.with("routineIdentity", updated))
        fun withTemplate(updated: JsonObject) = withAuthority(authority.with("commandTemplate", updated))
        fun withTeardown(updated: JsonObject) = document.with("teardownSeed", updated)
        fun withCoordinatedExecutionId(executionId: Long): JsonObject = document
            .with("sourceExecutionId", JsonPrimitive(executionId.toString()))
            .with(
                "sourceAuthority",
                authority.with("sourceExecutionId", JsonPrimitive(executionId.toString())),
            )
            .with("teardownSeed", teardown.with("sourceExecutionId", JsonPrimitive(executionId)))
        val positiveOverflow = Json.parseToJsonElement("1e400")
        val negativeOverflow = Json.parseToJsonElement("-1e400")

        val otherProfileIdentity = identity.with("profileId", JsonPrimitive("profile-other"))
        val otherSessionIdentity = identity
            .with("routineSessionId", JsonPrimitive("session-other"))
            .with(
                "logicalSetKey",
                identityKey.with("routineSessionId", JsonPrimitive("session-other")),
            )
        val otherOccurrenceIdentity = identity
            .with("routineExerciseId", JsonPrimitive("occurrence-other"))
            .with(
                "logicalSetKey",
                identityKey.with("routineExerciseId", JsonPrimitive("occurrence-other")),
            )
        val standardKey = identityKey.with("setKind", JsonPrimitive(SetType.STANDARD.name))
        val standardIdentity = identity.with("logicalSetKey", standardKey)
        val otherSetIdentity = identity
            .with("setIndex", JsonPrimitive(2))
            .with("logicalSetKey", identityKey.with("setIndex", JsonPrimitive(2)))

        val cases = listOf(
            case(
                "outer stable session differs from source",
                withAuthority(authority.with("sourceStableSessionId", JsonPrimitive("stable-other"))),
            ),
            case(
                "outer execution differs from source",
                withAuthority(authority.with("sourceExecutionId", JsonPrimitive("43"))),
            ),
            case(
                "outer profile differs from source and source identity",
                withAuthority(
                    authority
                        .with("profileId", JsonPrimitive("profile-other"))
                        .with("routineIdentity", otherProfileIdentity),
                ),
            ),
            case(
                "outer routine differs from source identity",
                withIdentity(identity.with("routineId", JsonPrimitive("routine-other"))),
            ),
            case("outer routine session differs from source identity", withIdentity(otherSessionIdentity)),
            case("outer occurrence differs from source identity", withIdentity(otherOccurrenceIdentity)),
            case(
                "outer logical key differs from internally valid source key",
                withAuthority(
                    authority
                        .with("plannedSetTypeName", JsonPrimitive(SetType.STANDARD.name))
                        .with("routineIdentity", standardIdentity),
                ),
            ),
            case(
                "outer planned set differs from source identity",
                withIdentity(identity.with("plannedSetId", JsonPrimitive("planned-other"))),
            ),
            case(
                "outer exercise coordinate differs from source identity",
                withIdentity(identity.with("exerciseIndex", JsonPrimitive(4))),
            ),
            case("outer set coordinate differs from source identity", withIdentity(otherSetIdentity)),
            case(
                "outer attempt differs from source",
                withAuthority(authority.with("attemptNumber", JsonPrimitive(3))),
            ),
            case(
                "outer stable session differs from teardown",
                withTeardown(teardown.with("sourceStableSessionId", JsonPrimitive("stable-other"))),
            ),
            case(
                "outer execution differs from teardown",
                withTeardown(teardown.with("sourceExecutionId", JsonPrimitive(43))),
            ),
            case("teardown zero execution id", withCoordinatedExecutionId(0L)),
            case("teardown negative execution id", withCoordinatedExecutionId(-1L)),
            case(
                "outer profile differs from teardown",
                withTeardown(teardown.with("profileId", JsonPrimitive("profile-other"))),
            ),
            case(
                "source cable classification differs from teardown",
                withTeardown(teardown.with("requiresMachine", JsonPrimitive(false))),
            ),
            case(
                "current runtime rejects unknown source reason",
                withAuthority(authority.with("reasonName", JsonPrimitive(SetEndReason.UNKNOWN.name))),
            ),
            case(
                "source set type differs from logical key",
                withAuthority(authority.with("plannedSetTypeName", JsonPrimitive(SetType.STANDARD.name))),
            ),
            case(
                "source program mode differs from command template",
                withAuthority(authority.with("programModeName", JsonPrimitive(ProgramMode.Pump.toSnapshotName()))),
            ),
            case("source non-positive attempt", withAuthority(authority.with("attemptNumber", JsonPrimitive(0)))),
            case("source negative accepted drop count", withAuthority(authority.with("acceptedDropCount", JsonPrimitive(-1)))),
            case("source drop count above limit", withAuthority(authority.with("acceptedDropCount", JsonPrimitive(3)))),
            case(
                "source negative programmed base weight",
                withAuthority(authority.with("programmedBaseWeightPerCableKg", JsonPrimitive(-1.0))),
            ),
            case(
                "source negative configured start weight",
                withAuthority(authority.with("configuredStartWeightPerCableKg", JsonPrimitive(-1.0))),
            ),
            case("source positive-overflow progression", withAuthority(authority.with("progressionKg", positiveOverflow))),
            case("source negative-overflow progression", withAuthority(authority.with("progressionKg", negativeOverflow))),
            case("source negative actual reps", withAuthority(authority.with("actualReps", JsonPrimitive(-1)))),
            case("source zero target reps", withAuthority(authority.with("targetReps", JsonPrimitive(0)))),
            case("source zero physical cable count", withAuthority(authority.with("physicalCableCount", JsonPrimitive(0)))),
            case("template unknown program mode", withTemplate(template.with("programModeName", JsonPrimitive("FUTURE")))),
            case("template negative reps", withTemplate(template.with("reps", JsonPrimitive(-1)))),
            case("template negative cable weight", withTemplate(template.with("weightPerCableKg", JsonPrimitive(-1.0)))),
            case(
                "template positive-overflow external added load",
                withTemplate(template.with("externalAddedLoadKg", positiveOverflow)),
            ),
            case(
                "template negative-overflow counterweight",
                withTemplate(template.with("counterweightKg", negativeOverflow)),
            ),
            case(
                "template positive-overflow progression regression",
                withTemplate(template.with("progressionRegressionKg", positiveOverflow)),
            ),
            case(
                "template blank rack id",
                withTemplate(template.with("activeRackItemIds", JsonArray(listOf(JsonPrimitive(" "))))),
            ),
            case(
                "template duplicate rack ids",
                withTemplate(
                    template.with(
                        "activeRackItemIds",
                        JsonArray(listOf(JsonPrimitive("rack-a"), JsonPrimitive("rack-a"))),
                    ),
                ),
            ),
            case("template negative warmup reps", withTemplate(template.with("warmupReps", JsonPrimitive(-1)))),
            case(
                "template blank selected exercise",
                withTemplate(template.with("selectedExerciseId", JsonPrimitive(" "))),
            ),
            case(
                "template negative last used weight",
                withTemplate(template.with("lastUsedWeightKg", JsonPrimitive(-1.0))),
            ),
            case("template negative pr weight", withTemplate(template.with("prWeightKg", JsonPrimitive(-1.0)))),
            case(
                "template unknown rep count timing",
                withTemplate(template.with("repCountTimingName", JsonPrimitive("FUTURE"))),
            ),
            case("template unknown echo level", withTemplate(template.with("echoLevelName", JsonPrimitive("FUTURE")))),
            case(
                "template unknown eccentric load",
                withTemplate(template.with("eccentricLoadName", JsonPrimitive("FUTURE"))),
            ),
            case(
                "template negative just lift rest",
                withTemplate(template.with("justLiftRestSeconds", JsonPrimitive(-1))),
            ),
        )

        assertEveryDocumentCaseIsCorrupt(cases)
    }

    @Test
    fun directConstructorsRejectNegativeCountsNonPositiveTeardownAndEveryNonFiniteWeightByName() {
        val runtime = runtime()
        val authority = runtime.sourceAuthority
        val template = authority.commandTemplate
        val teardown = runtime.teardownSeed
        val cases = listOf(
            constructorCase("source negative accepted drop count") {
                authority.copy(acceptedDropCount = -1)
            },
            constructorCase("source NaN programmed base weight") {
                authority.copy(programmedBaseWeightPerCableKg = Float.NaN)
            },
            constructorCase("source positive-infinite programmed base weight") {
                authority.copy(programmedBaseWeightPerCableKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("source negative-infinite programmed base weight") {
                authority.copy(programmedBaseWeightPerCableKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("source NaN configured start weight") {
                authority.copy(configuredStartWeightPerCableKg = Float.NaN)
            },
            constructorCase("source positive-infinite configured start weight") {
                authority.copy(configuredStartWeightPerCableKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("source negative-infinite configured start weight") {
                authority.copy(configuredStartWeightPerCableKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("source NaN progression") {
                authority.copy(progressionKg = Float.NaN)
            },
            constructorCase("source positive-infinite progression") {
                authority.copy(progressionKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("source negative-infinite progression") {
                authority.copy(progressionKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("teardown zero execution id") {
                teardown.copy(sourceExecutionId = 0L)
            },
            constructorCase("teardown negative execution id") {
                teardown.copy(sourceExecutionId = -1L)
            },
            constructorCase("template NaN cable weight") {
                template.copy(weightPerCableKg = Float.NaN)
            },
            constructorCase("template positive-infinite cable weight") {
                template.copy(weightPerCableKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite cable weight") {
                template.copy(weightPerCableKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("template NaN external added load") {
                template.copy(externalAddedLoadKg = Float.NaN)
            },
            constructorCase("template positive-infinite external added load") {
                template.copy(externalAddedLoadKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite external added load") {
                template.copy(externalAddedLoadKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("template NaN counterweight") {
                template.copy(counterweightKg = Float.NaN)
            },
            constructorCase("template positive-infinite counterweight") {
                template.copy(counterweightKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite counterweight") {
                template.copy(counterweightKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("template NaN progression regression") {
                template.copy(progressionRegressionKg = Float.NaN)
            },
            constructorCase("template positive-infinite progression regression") {
                template.copy(progressionRegressionKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite progression regression") {
                template.copy(progressionRegressionKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("template NaN last used weight") {
                template.copy(lastUsedWeightKg = Float.NaN)
            },
            constructorCase("template positive-infinite last used weight") {
                template.copy(lastUsedWeightKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite last used weight") {
                template.copy(lastUsedWeightKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("template NaN PR weight") {
                template.copy(prWeightKg = Float.NaN)
            },
            constructorCase("template positive-infinite PR weight") {
                template.copy(prWeightKg = Float.POSITIVE_INFINITY)
            },
            constructorCase("template negative-infinite PR weight") {
                template.copy(prWeightKg = Float.NEGATIVE_INFINITY)
            },
            constructorCase("document NaN exercise-load overlay multiplier") {
                runtime.copy(
                    exerciseLoadOverlays = listOf(ExerciseLoadOverlay("routine-exercise-a", Float.NaN)),
                )
            },
            constructorCase("document positive-infinite exercise-load overlay multiplier") {
                runtime.copy(
                    exerciseLoadOverlays = listOf(
                        ExerciseLoadOverlay("routine-exercise-a", Float.POSITIVE_INFINITY),
                    ),
                )
            },
            constructorCase("document negative-infinite exercise-load overlay multiplier") {
                runtime.copy(
                    exerciseLoadOverlays = listOf(
                        ExerciseLoadOverlay("routine-exercise-a", Float.NEGATIVE_INFINITY),
                    ),
                )
            },
        )

        cases.forEach { case ->
            assertFailsWith<IllegalArgumentException>(case.name) {
                case.construct()
            }
        }
    }

    @Test
    fun everyRestTransitionPlanVariantInvariantRejectsDirectDecodeByName() {
        val normal = planJson(normalAdvance())
        val unresolved = planJson(unresolvedOffer())
        val declined = planJson(declined())
        val accepted = planJson(acceptedRetry())
        val cases = buildList {
            add(planCase("normal blank transitionId", normal) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("normal blank sourceExecutionId", normal) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("normal blank plannedSetId", normal) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("normal blank logical key session", normal) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("normal blank logical key occurrence", normal) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("normal negative logical key index", normal) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("normal negative exercise coordinate", normal) { it.withCoordinates(exerciseIndex = -1) })
            add(planCase("normal negative set coordinate", normal) { it.withCoordinates(setIndex = -1) })
            add(planCase("normal coordinate and logical key mismatch", normal) { it.withCoordinates(setIndex = 2) })
            add(planCase("normal negative rest duration", normal) { it.with("restDurationSeconds", JsonPrimitive(-1)) })

            add(planCase("unresolved blank transitionId", unresolved) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank sourceExecutionId", unresolved) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank offerId", unresolved) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank plannedSetId", unresolved) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank logical key session", unresolved) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("unresolved blank logical key occurrence", unresolved) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("unresolved negative logical key index", unresolved) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("unresolved empty candidates", unresolved) { it.with("candidates", JsonArray(emptyList())) })
            add(planCase("unresolved candidate non-positive weight", unresolved) { it.withCandidate("resolvedWeightPerCableKg", 0.0) })
            add(planCase("unresolved candidate non-positive multiplier", unresolved) { it.withCandidate("resultingExerciseMultiplier", 0.0) })
            add(planCase("unresolved nested normal blank transition", unresolved) { it.withNormal("transitionId", JsonPrimitive(" ")) })
            add(planCase("unresolved nested normal blank source", unresolved) { it.withNormal("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("unresolved nested normal blank planned set", unresolved) { it.withNormal("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("unresolved nested normal blank key session", unresolved) { it.withNormalKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("unresolved nested normal blank key occurrence", unresolved) { it.withNormalKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("unresolved nested normal negative key index", unresolved) { it.withNormalKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("unresolved nested normal transition mismatch", unresolved) { it.withNormal("transitionId", JsonPrimitive("other-transition")) })
            add(planCase("unresolved nested normal source mismatch", unresolved) { it.withNormal("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("unresolved nested normal key mismatch", unresolved) { it.withNormalKey("routineExerciseId", JsonPrimitive("other-occurrence")) })
            add(planCase("unresolved nested normal planned mismatch", unresolved) { it.withNormal("plannedSetId", JsonPrimitive("other-planned")) })
            add(planCase("unresolved nested normal negative exercise coordinate", unresolved) { it.withNormalCoordinates(exerciseIndex = -1) })
            add(planCase("unresolved nested normal negative set coordinate", unresolved) { it.withNormalCoordinates(setIndex = -1) })
            add(planCase("unresolved nested normal coordinate and key mismatch", unresolved) { it.withNormalCoordinates(setIndex = 2) })
            add(planCase("unresolved nested normal negative rest duration", unresolved) { it.withNormal("restDurationSeconds", JsonPrimitive(-1)) })

            add(planCase("declined blank transitionId", declined) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("declined blank sourceExecutionId", declined) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("declined blank offerId", declined) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("declined blank logical key session", declined) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("declined blank logical key occurrence", declined) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("declined negative logical key index", declined) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("declined nested normal blank transition", declined) { it.withNormal("transitionId", JsonPrimitive(" ")) })
            add(planCase("declined nested normal blank source", declined) { it.withNormal("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("declined nested normal blank planned set", declined) { it.withNormal("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("declined nested normal blank key session", declined) { it.withNormalKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("declined nested normal blank key occurrence", declined) { it.withNormalKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("declined nested normal negative key index", declined) { it.withNormalKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("declined nested normal transition mismatch", declined) { it.withNormal("transitionId", JsonPrimitive("other-transition")) })
            add(planCase("declined nested normal source mismatch", declined) { it.withNormal("sourceExecutionId", JsonPrimitive("other-source")) })
            add(planCase("declined nested normal key mismatch", declined) { it.withNormalKey("routineExerciseId", JsonPrimitive("other-occurrence")) })
            add(planCase("declined nested normal negative exercise coordinate", declined) { it.withNormalCoordinates(exerciseIndex = -1) })
            add(planCase("declined nested normal negative set coordinate", declined) { it.withNormalCoordinates(setIndex = -1) })
            add(planCase("declined nested normal coordinate and key mismatch", declined) { it.withNormalCoordinates(setIndex = 2) })
            add(planCase("declined nested normal negative rest duration", declined) { it.withNormal("restDurationSeconds", JsonPrimitive(-1)) })

            add(planCase("accepted blank transitionId", accepted) { it.with("transitionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank sourceExecutionId", accepted) { it.with("sourceExecutionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank offerId", accepted) { it.with("offerId", JsonPrimitive(" ")) })
            add(planCase("accepted blank plannedSetId", accepted) { it.with("plannedSetId", JsonPrimitive(" ")) })
            add(planCase("accepted blank logical key session", accepted) { it.withPlanKey("routineSessionId", JsonPrimitive(" ")) })
            add(planCase("accepted blank logical key occurrence", accepted) { it.withPlanKey("routineExerciseId", JsonPrimitive(" ")) })
            add(planCase("accepted negative logical key index", accepted) { it.withPlanKey("setIndex", JsonPrimitive(-1)) })
            add(planCase("accepted negative exercise coordinate", accepted) { it.withCoordinates(exerciseIndex = -1) })
            add(planCase("accepted negative set coordinate", accepted) { it.withCoordinates(setIndex = -1) })
            add(planCase("accepted coordinate and logical key mismatch", accepted) { it.withCoordinates(setIndex = 2) })
            add(planCase("accepted non-positive resolved weight", accepted) { it.with("resolvedWeightPerCableKg", JsonPrimitive(0.0)) })
            add(planCase("accepted non-positive resulting multiplier", accepted) { it.with("resultingExerciseMultiplier", JsonPrimitive(0.0)) })
            add(planCase("accepted non-positive next attempt", accepted) { it.with("nextAttemptNumber", JsonPrimitive(0)) })
        }

        cases.forEach { case ->
            assertFails(case.name) {
                json.decodeFromJsonElement(RestTransitionPlan.serializer(), case.payload)
            }
        }
    }

    @Test
    fun everyOuterDocumentToPlanConsistencyInvariantRejectsCorruptJsonByName() = runTest {
        val source = documentJson()
        val otherKey = LogicalSetKey("routine-session-a", "routine-exercise-other", 1, SetType.AMRAP)
        val cases = listOf(
            outerCase(
                "common plan source execution differs from document",
                source,
                acceptedRetry(sourceExecutionId = "execution-other"),
            ),
            outerCase(
                "common plan logical key differs from document",
                source,
                acceptedRetry(logicalSetKey = otherKey),
            ),
            outerCase(
                "accepted planned set differs from document",
                source,
                acceptedRetry(plannedSetId = "planned-set-other"),
            ),
            outerCase(
                "accepted coordinates differ from document",
                source,
                acceptedRetry(sourceCoordinates = RestTransitionPlan.Coordinates(4, 1)),
            ),
            outerCase(
                "normal planned set differs from document",
                source,
                normalAdvance(plannedSetId = "planned-set-other"),
            ),
            outerCase(
                "normal coordinates differ from document",
                source,
                normalAdvance(sourceCoordinates = RestTransitionPlan.Coordinates(4, 1)),
            ),
            outerCase(
                "unresolved planned set differs from document",
                source,
                unresolvedOffer(plannedSetId = "planned-set-other"),
            ),
            outerCase(
                "unresolved nested normal coordinates differ from document",
                source,
                unresolvedOffer(normalCoordinates = RestTransitionPlan.Coordinates(4, 1)),
            ),
            outerCase(
                "declined nested normal planned set differs from document",
                source,
                declined(normalPlannedSetId = "planned-set-other"),
            ),
            outerCase(
                "declined nested normal coordinates differ from document",
                source,
                declined(normalCoordinates = RestTransitionPlan.Coordinates(4, 1)),
            ),
        )

        assertEveryDocumentCaseIsCorrupt(cases)
    }

    private suspend fun assertEveryDocumentCaseIsCorrupt(cases: List<NamedPayload>) {
        cases.forEach { case ->
            insertRaw(case.payload)
            val rejected = assertIs<ActiveWorkoutRuntimeLoadResult.Rejected>(
                repository.load("profile-a", "routine-session-a"),
                case.name,
            )
            assertEquals(ActiveWorkoutRuntimeRejection.CORRUPT_JSON, rejected.reason, case.name)
        }
    }

    private fun documentJson(): JsonObject = json.encodeToJsonElement(runtime()).jsonObject

    private fun runtime(): ActiveWorkoutRuntimeDocument = ActiveWorkoutRuntimeDocument(
        profileId = "profile-a",
        routineId = "routine-a",
        routineSessionId = "routine-session-a",
        routineExerciseId = "routine-exercise-a",
        sourceExecutionId = "42",
        sourceStableSessionId = "stable-session-a",
        sourceAttemptNumber = 2,
        logicalSetKey = logicalKey(),
        plannedSetId = "planned-set-a",
        sourceExerciseIndex = 3,
        sourceSetIndex = 1,
        sourceAuthority = RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = "stable-session-a",
            sourceExecutionId = "42",
            profileId = "profile-a",
            routineIdentity = RoutineExecutionIdentity(
                profileId = "profile-a",
                routineId = "routine-a",
                routineSessionId = "routine-session-a",
                routineExerciseId = "routine-exercise-a",
                logicalSetKey = logicalKey(),
                plannedSetId = "planned-set-a",
                exerciseIndex = 3,
                setIndex = 1,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 2,
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
            profileId = "profile-a",
            requiresMachine = true,
        ),
        exerciseLoadOverlays = emptyList(),
        attemptStates = emptyList(),
        restTransitionPlan = null,
        restDeadlineEpochMs = null,
        originalRestDurationSeconds = 60,
    )

    private fun logicalKey() = LogicalSetKey("routine-session-a", "routine-exercise-a", 1, SetType.AMRAP)

    private fun normalAdvance(
        sourceExecutionId: String = "42",
        logicalSetKey: LogicalSetKey = logicalKey(),
        sourceCoordinates: RestTransitionPlan.Coordinates = RestTransitionPlan.Coordinates(3, 1),
        plannedSetId: String? = "planned-set-a",
    ) = RestTransitionPlan.NormalAdvance(
        transitionId = "transition-a",
        sourceExecutionId = sourceExecutionId,
        logicalSetKey = logicalSetKey,
        sourceCoordinates = sourceCoordinates,
        plannedSetId = plannedSetId,
        restDurationSeconds = 60,
    )

    private fun unresolvedOffer(
        plannedSetId: String? = "planned-set-a",
        normalCoordinates: RestTransitionPlan.Coordinates = RestTransitionPlan.Coordinates(3, 1),
    ) = RestTransitionPlan.UnresolvedDropOffer(
        transitionId = "transition-a",
        sourceExecutionId = "42",
        logicalSetKey = logicalKey(),
        offerId = "offer-a",
        plannedSetId = plannedSetId,
        candidates = listOf(DropSetCandidate(DropPercentage.TWENTY, 32f, 0.8f)),
        normalAdvance = normalAdvance(plannedSetId = plannedSetId, sourceCoordinates = normalCoordinates),
    )

    private fun declined(
        normalPlannedSetId: String? = "planned-set-a",
        normalCoordinates: RestTransitionPlan.Coordinates = RestTransitionPlan.Coordinates(3, 1),
    ) = RestTransitionPlan.Declined(
        transitionId = "transition-a",
        sourceExecutionId = "42",
        logicalSetKey = logicalKey(),
        offerId = "offer-a",
        normalAdvance = normalAdvance(plannedSetId = normalPlannedSetId, sourceCoordinates = normalCoordinates),
    )

    private fun acceptedRetry(
        sourceExecutionId: String = "42",
        logicalSetKey: LogicalSetKey = logicalKey(),
        sourceCoordinates: RestTransitionPlan.Coordinates = RestTransitionPlan.Coordinates(3, 1),
        plannedSetId: String? = "planned-set-a",
    ) = RestTransitionPlan.AcceptedRetry(
        transitionId = "transition-a",
        sourceExecutionId = sourceExecutionId,
        logicalSetKey = logicalSetKey,
        offerId = "offer-a",
        sourceCoordinates = sourceCoordinates,
        plannedSetId = plannedSetId,
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 32f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 3,
    )

    private fun planJson(plan: RestTransitionPlan): JsonObject = json.encodeToJsonElement(RestTransitionPlan.serializer(), plan).jsonObject

    private fun outerCase(
        name: String,
        document: JsonObject,
        internallyValidPlan: RestTransitionPlan,
    ) = case(name, document.with("restTransitionPlan", planJson(internallyValidPlan)))

    private fun planCase(
        name: String,
        plan: JsonObject,
        mutate: (JsonObject) -> JsonObject,
    ) = case(name, mutate(plan))

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

    private fun JsonObject.withCoordinates(
        exerciseIndex: Int? = null,
        setIndex: Int? = null,
    ): JsonObject {
        var coordinates = getValue("sourceCoordinates").jsonObject
        if (exerciseIndex != null) coordinates = coordinates.with("exerciseIndex", JsonPrimitive(exerciseIndex))
        if (setIndex != null) coordinates = coordinates.with("setIndex", JsonPrimitive(setIndex))
        return with("sourceCoordinates", coordinates)
    }

    private fun JsonObject.withPlanKey(key: String, value: JsonElement): JsonObject = with("logicalSetKey", getValue("logicalSetKey").jsonObject.with(key, value))

    private fun JsonObject.withNormal(key: String, value: JsonElement): JsonObject = with("normalAdvance", getValue("normalAdvance").jsonObject.with(key, value))

    private fun JsonObject.withNormalKey(key: String, value: JsonElement): JsonObject {
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
            bindLong(2, ActiveWorkoutRuntimeDocument.CURRENT_VERSION.toLong())
            bindString(3, payload.toString())
            bindLong(4, 1_700_000_000_000)
        }
    }

    private fun case(name: String, payload: JsonObject) = NamedPayload(name, payload)

    private fun constructorCase(name: String, construct: () -> Unit) = NamedConstructorCase(name, construct)

    private data class NamedPayload(
        val name: String,
        val payload: JsonObject,
    )

    private data class NamedConstructorCase(
        val name: String,
        val construct: () -> Unit,
    )
}
