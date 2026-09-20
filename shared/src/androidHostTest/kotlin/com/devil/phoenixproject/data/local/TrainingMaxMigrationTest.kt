package com.devil.phoenixproject.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.SqlDelightExerciseRepository
import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.data.repository.SqlDelightVelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.TrainingMaxSource
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.usecase.ResolveRoutineScalingBaselineUseCase
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * The migration 49 legacy copy (`backfillExerciseTrainingMaxes`).
 *
 * The rule it has to obey is a safety rule, not a convenience one: the pre-49
 * `Exercise.one_rep_max_kg` was one global value that any profile's PR save could write,
 * so handing it to the wrong profile silently changes what the machine is told to pull.
 * It is therefore copied ONLY where exactly one profile can be shown to own it, and an
 * unattributable value is left unassigned — never given to `default` — until a profile
 * claims it through the one-time prompt.
 *
 * The copy lives in a post-open repair rather than in `49.sqm`, because attributing a
 * value needs tables and `profile_id` columns that `SchemaManifest` heals rather than a
 * numbered migration creating them. These tests run it the way `MigrationManager` does:
 * against a whole schema, repeatedly, with the legacy column still present.
 */
class TrainingMaxMigrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase

    @Before
    fun setup() {
        driver = createTestDriver()
        database = PhoenixDatabase(driver)
        seedExercise("bench")
    }

    @Test
    fun `a single-profile database carries the value to that profile`() {
        seedProfile("solo")
        seedLegacyOneRepMax("bench", 100.0)

        backfill()

        assertEquals(listOf("solo" to 100.0), trainingMaxes("bench"))
    }

    @Test
    fun `two profiles carry the value to the one whose PR matches it`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 120.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 120.0)
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 90.0)

        backfill()

        assertEquals(listOf("bob" to 120.0), trainingMaxes("bench"))
    }

    @Test
    fun `two profiles carry the value to the one that owns the assessment`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 75.0)
        // No PR matches; only alice ever assessed this lift, so the value is hers.
        seedAssessment(profileId = "alice", estimatedOneRepMaxKg = 150.0)

        backfill()

        assertEquals(listOf("alice" to 75.0), trainingMaxes("bench"))
    }

    @Test
    fun `the 5_3_1 cycle owner gets a manually typed value no PR or assessment explains`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 140.0)
        // A hand-typed training max matches no PR and no assessment. Only alice runs 5/3/1
        // on this lift, so rule 4 is the only signal that can attribute it.
        seedFiveThreeOneCycle(profileId = "alice", cycleId = "cycle-a", routineId = "routine-a")

        backfill()

        assertEquals(listOf("alice" to 140.0), trainingMaxes("bench"))
    }

    @Test
    fun `two profiles both running 5_3_1 on the lift leave the value unassigned`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 140.0)
        seedFiveThreeOneCycle(profileId = "alice", cycleId = "cycle-a", routineId = "routine-a")
        seedFiveThreeOneCycle(profileId = "bob", cycleId = "cycle-b", routineId = "routine-b")

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `a soft-deleted 5_3_1 cycle is not ownership evidence`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 140.0)
        seedFiveThreeOneCycle(
            profileId = "alice",
            cycleId = "cycle-a",
            routineId = "routine-a",
            deletedAt = 5_000L,
        )

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `owning a velocity estimate is not ownership of the value`() {
        // Nothing ever wrote a velocity estimate into the legacy column, so a VBT row says
        // only "this member trained this lift". Treating it as attribution handed alice's
        // typed max to bob whenever bob was the only one with velocity data (review R-2).
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 140.0)
        seedVelocityEstimate(profileId = "bob", estimatedPerCableKg = 62.5)
        seedFiveThreeOneCycle(profileId = "alice", cycleId = "cycle-a", routineId = "routine-a")

        backfill()

        // Rule 4 decides, and it picks the cycle owner.
        assertEquals(listOf("alice" to 140.0), trainingMaxes("bench"))
    }

    @Test
    fun `an assessment attributes only when its per-cable value matches`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 75.0)
        // SqlDelightAssessmentRepository stored COALESCE(userOverrideKg, estimated) / 2.
        // bob's assessment halves to 60, not 75, so it explains nothing.
        seedAssessment(profileId = "bob", estimatedOneRepMaxKg = 120.0)

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `two profiles whose PR matches do not fall through to a weaker rule`() {
        // A rule with two candidates TERMINATES: it must not lose to a third profile that
        // merely owns an assessment or a cycle (review R-2, safer reading of R-5).
        seedProfile("alice")
        seedProfile("bob")
        seedProfile("carol")
        seedLegacyOneRepMax("bench", 120.0)
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 120.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 120.0)
        seedFiveThreeOneCycle(profileId = "carol", cycleId = "cycle-c", routineId = "routine-c")

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `an attributed legacy value stops being offered, even after its owner clears it`() {
        // Without the clear, setTrainingMax(null) makes NOT EXISTS true again and re-offers
        // alice's number to bob as ownerless (review R-20).
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 140.0)
        seedFiveThreeOneCycle(profileId = "alice", cycleId = "cycle-a", routineId = "routine-a")

        backfill()
        clearAttributed()
        assertEquals(listOf("alice" to 140.0), trainingMaxes("bench"))

        // alice re-measures and clears her value.
        database.phoenixDatabaseQueries.deleteTrainingMax(exerciseId = "bench", profileId = "alice")

        assertNull(
            database.phoenixDatabaseQueries.selectUnassignedLegacyTrainingMax("bench").executeAsOneOrNull(),
        )
    }

    @Test
    fun `a value nobody could be shown to own stays offerable after the repair`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 111.0)
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 90.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 95.0)

        backfill()
        clearAttributed()

        assertEquals(
            111.0,
            database.phoenixDatabaseQueries.selectUnassignedLegacyTrainingMax("bench").executeAsOneOrNull(),
        )
    }

    @Test
    fun `an unmatched value is assigned to nobody, and never to default`() {
        seedProfile("default")
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 111.0)
        // Both members have PRs, neither matches the stored value.
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 90.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 95.0)

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `an ambiguous value is assigned to nobody even when two profiles both match`() {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 120.0)
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 120.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 120.0)

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `a database with no UserProfile row gets no orphan rows`() {
        seedLegacyOneRepMax("bench", 100.0)

        backfill()

        assertEquals(emptyList(), trainingMaxes("bench"))
    }

    @Test
    fun `the copy is idempotent and never overwrites a value a profile already owns`() {
        seedProfile("solo")
        seedLegacyOneRepMax("bench", 100.0)

        backfill()
        // The user has since corrected it. A replay (a failed run retries on the next
        // open) must not put the legacy number back.
        database.phoenixDatabaseQueries.upsertTrainingMax(
            exerciseId = "bench",
            profileId = "solo",
            oneRepMaxKg = 130.0,
            source = TrainingMaxSource.MANUAL.name,
            updatedAt = 1L,
        )
        backfill()
        backfill()

        assertEquals(listOf("solo" to 130.0), trainingMaxes("bench"))
    }

    @Test
    fun `an unassigned value feeds no profile's baseline until one profile claims it`() = runTest {
        seedProfile("alice")
        seedProfile("bob")
        seedLegacyOneRepMax("bench", 111.0)
        seedCombinedWeightPr(profileId = "alice", oneRepMax = 90.0)
        seedCombinedWeightPr(profileId = "bob", oneRepMax = 95.0)
        backfill()

        val exercises: ExerciseRepository = SqlDelightExerciseRepository(
            database,
            ExerciseImporter(database),
            FakePreferencesManager(),
        )
        val resolve = ResolveRoutineScalingBaselineUseCase(
            SqlDelightPersonalRecordRepository(database),
            exercises,
            SqlDelightVelocityOneRepMaxRepository(database),
        )
        suspend fun estimatedBaseline(profileId: String) = resolve(
            exerciseId = "bench",
            mode = ProgramMode.OldSchool,
            profileId = profileId,
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        // Unclaimed: neither profile scales from the stored 111. Both fall back to their
        // own max-weight PR load (80 kg/cable), which is what the seeded PRs carry.
        assertEquals(80f, estimatedBaseline("alice")?.weightPerCableKg)
        assertEquals(80f, estimatedBaseline("bob")?.weightPerCableKg)
        // It is offered to whoever opens the exercise next.
        assertEquals(111f, exercises.getUnassignedLegacyTrainingMax("bench"))

        exercises.setTrainingMax("bench", "alice", 111f, TrainingMaxSource.CLAIMED_LEGACY)

        // Alice now scales from it; bob still does not, and nobody is offered it again.
        assertEquals(111f, estimatedBaseline("alice")?.weightPerCableKg)
        assertEquals(80f, estimatedBaseline("bob")?.weightPerCableKg)
        assertNull(exercises.getUnassignedLegacyTrainingMax("bench"))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun backfill() = database.phoenixDatabaseQueries.backfillExerciseTrainingMaxes()

    private fun clearAttributed() = database.phoenixDatabaseQueries.clearAttributedLegacyTrainingMaxes()

    private fun trainingMaxes(exerciseId: String): List<Pair<String, Double>> =
        database.phoenixDatabaseQueries.selectTrainingMaxRowsForTest(exerciseId)
            .executeAsList()
            .map { it.profile_id to it.one_rep_max_kg }

    private fun seedExercise(id: String) {
        database.phoenixDatabaseQueries.insertExerciseIfAbsent(
            id = id,
            name = "Bench Press",
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }

    private fun seedProfile(id: String) {
        database.phoenixDatabaseQueries.insertUserProfileIgnore(
            id = id,
            name = id,
            colorIndex = 0L,
            createdAt = 0L,
            isActive = 0L,
        )
    }

    /** Nothing writes the legacy column any more, so a pre-49 value needs raw SQL. */
    private fun seedLegacyOneRepMax(exerciseId: String, value: Double) {
        driver.execute(null, "UPDATE Exercise SET one_rep_max_kg = $value WHERE id = '$exerciseId'", 0)
    }

    private fun seedCombinedWeightPr(profileId: String, oneRepMax: Double) {
        driver.execute(
            null,
            "INSERT INTO PersonalRecord(exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, " +
                "workoutMode, prType, volume, phase, profile_id) " +
                "VALUES('bench', 'Bench Press', 80.0, 5, $oneRepMax, 1000, 'Old School', 'MAX_WEIGHT', " +
                "400.0, 'COMBINED', '$profileId')",
            0,
        )
    }

    private fun seedVelocityEstimate(profileId: String, estimatedPerCableKg: Double) {
        driver.execute(
            null,
            "INSERT INTO VelocityOneRepMaxEstimate(exerciseId, estimatedPerCableKg, mvtUsedMs, r2, " +
                "distinctLoads, passedQualityGate, computedAt, profile_id) " +
                "VALUES('bench', $estimatedPerCableKg, 200.0, 0.95, 3, 1, 1000, '$profileId')",
            0,
        )
    }

    /** A `template_531` cycle whose single day's routine contains the exercise (owner rule 4). */
    private fun seedFiveThreeOneCycle(
        profileId: String,
        cycleId: String,
        routineId: String,
        deletedAt: Long? = null,
    ) {
        driver.execute(
            null,
            "INSERT INTO Routine(id, name, createdAt, profile_id) " +
                "VALUES('$routineId', 'Bench Day', 0, '$profileId')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO RoutineExercise(id, routineId, exerciseName, exerciseId, orderIndex) " +
                "VALUES('re-$routineId', '$routineId', 'Bench Press', 'bench', 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO TrainingCycle(id, name, created_at, profile_id, template_id, deletedAt) " +
                "VALUES('$cycleId', '5/3/1', 0, '$profileId', 'template_531', ${deletedAt ?: "NULL"})",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO CycleDay(id, cycle_id, day_number, routine_id) " +
                "VALUES('day-$cycleId', '$cycleId', 1, '$routineId')",
            0,
        )
    }

    private fun seedAssessment(profileId: String, estimatedOneRepMaxKg: Double) {
        driver.execute(
            null,
            "INSERT INTO AssessmentResult(exerciseId, estimatedOneRepMaxKg, loadVelocityData, createdAt, profile_id) " +
                "VALUES('bench', $estimatedOneRepMaxKg, '[]', 1000, '$profileId')",
            0,
        )
    }
}
