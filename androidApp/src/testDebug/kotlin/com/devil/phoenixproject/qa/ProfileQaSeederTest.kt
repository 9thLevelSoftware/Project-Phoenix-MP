package com.devil.phoenixproject.qa

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.AssessmentResultEntity
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.util.OneRepMaxCalculator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileQaSeederTest {
    @Test
    fun `concurrent seed requests serialize into one idempotent fixture`() = runTest {
        val fixture = SeederFixture()
        val seeder = fixture.seeder()

        awaitAll(
            async { seeder.seed() },
            async { seeder.seed() },
        )

        assertEquals(1, fixture.allProfiles.value.count { it.name == "[QA] Profile A" })
        assertEquals(1, fixture.allProfiles.value.count { it.name == "[QA] Profile B" })
        assertEquals(10, fixture.sessions.size)
        assertEquals(2, fixture.assessments.size)
        assertEquals(2, fixture.velocity.size)
    }

    @Test
    fun `fixture PR writes restore the preexisting catalog one rep max`() = runTest {
        val fixture = SeederFixture()

        fixture.seeder().seed()

        assertEquals(42f, fixture.catalogOneRepMaxKg)
    }

    @Test
    fun `two seed runs reuse exact profiles and leave five fixed completed sessions each`() = runTest {
        val fixture = SeederFixture()
        val seeder = fixture.seeder()

        val first = seeder.seed()
        val second = seeder.seed()

        assertEquals("PROFILE_QA_SEED_OK", first.marker)
        assertEquals(first.profileAId, second.profileAId)
        assertEquals(first.profileBId, second.profileBId)
        assertEquals(
            listOf("[QA] Profile A", "[QA] Profile B"),
            fixture.allProfiles.value.filter { it.name.startsWith("[QA]") }.map { it.name },
        )
        assertTrue("QA profiles must never be reset through profile deletion", fixture.deletedProfiles.isEmpty())

        listOf(first.profileAId, first.profileBId).forEachIndexed { profileIndex, profileId ->
            val expectedIds = (1..5).map { "profile-qa-${if (profileIndex == 0) "a" else "b"}-session-$it" }
            val sessions = fixture.sessions.values.filter { it.profileId == profileId }.sortedBy { it.timestamp }
            assertEquals(expectedIds, sessions.map { it.id })
            assertEquals(
                listOf(1_700_100_000_000L, 1_700_200_000_000L, 1_700_300_000_000L, 1_700_400_000_000L, 1_700_500_000_000L),
                sessions.map { it.timestamp },
            )
            assertEquals(listOf(30f, 35f, 40f, 45f, 50f), sessions.map { it.weightPerCableKg })
            assertTrue(sessions.all { it.exerciseId == "bench-press" && it.exerciseName == "Bench Press" })
            assertTrue(sessions.all { it.workingReps > 0 && it.totalReps == it.workingReps })
            assertTrue(sessions.all { (it.avgMcvMmS ?: 0f) > 0f })
            assertTrue(sessions.all { (it.peakForceConcentricA ?: 0f) > 0f })
            assertTrue(sessions.all { (it.totalVolumeKg ?: 0f) > 0f })
            sessions.forEach { session ->
                assertTrue(session.rackItemsJson.contains("\"name\""))
                assertTrue(session.rackItemsJson.contains("\"weightKg\""))
                assertTrue(
                    session.rackItemsJson.contains(
                        if (profileIndex == 0) "profile-qa-a-rack-vest" else "profile-qa-b-rack-assist",
                    ),
                )
            }

            sessions.forEach { session ->
                val metrics = fixture.repMetrics.getValue(session.id)
                assertEquals(session.workingReps, metrics.size)
                assertEquals((1..session.workingReps).toList(), metrics.map { it.repNumber })
                assertTrue(metrics.all { it.startTimestamp >= session.timestamp })
                assertTrue(metrics.all { it.peakVelocity > 0f && it.peakForceA > 0f && it.peakPowerWatts > 0f })
            }
        }
    }

    @Test
    fun `profiles receive complete visibly inverse typed preferences and deterministic racks`() = runTest {
        val fixture = SeederFixture()
        val result = fixture.seeder().seed()
        val a = result.profileAId
        val b = result.profileBId

        assertEquals(CoreProfilePreferences(82.5f, WeightUnit.KG, 2.5f), fixture.core.getValue(a))
        assertEquals(CoreProfilePreferences(165f, WeightUnit.LB, 5f), fixture.core.getValue(b))

        val rackA = fixture.rack.getValue(a)
        val rackB = fixture.rack.getValue(b)
        assertEquals(listOf("profile-qa-a-rack-vest"), rackA.items.map { it.id })
        assertEquals(listOf("profile-qa-b-rack-assist"), rackB.items.map { it.id })
        assertEquals(RackItemBehavior.ADDED_RESISTANCE, rackA.items.single().behavior)
        assertEquals(RackItemBehavior.COUNTERWEIGHT, rackB.items.single().behavior)
        assertNotEquals(rackA, rackB)

        val workoutA = fixture.workout.getValue(a)
        val workoutB = fixture.workout.getValue(b)
        assertTrue(workoutA.stopAtTop)
        assertFalse(workoutB.stopAtTop)
        assertTrue(workoutA.beepsEnabled)
        assertFalse(workoutB.beepsEnabled)
        assertTrue(workoutA.weightSuggestionsEnabled)
        assertFalse(workoutB.weightSuggestionsEnabled)
        assertEquals(15, workoutA.summaryCountdownSeconds)
        assertEquals(5, workoutB.summaryCountdownSeconds)
        assertNotEquals(workoutA, workoutB)

        assertEquals(LedPreferences(colorScheme = 1, discoModeUnlocked = false), fixture.led.getValue(a))
        assertEquals(LedPreferences(colorScheme = 4, discoModeUnlocked = true), fixture.led.getValue(b))

        val vbtA = fixture.vbt.getValue(a)
        val vbtB = fixture.vbt.getValue(b)
        assertTrue(vbtA.enabled)
        assertFalse(vbtB.enabled)
        assertEquals(ScalingBasis.ESTIMATED_1RM, vbtA.defaultScalingBasis)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, vbtB.defaultScalingBasis)
        assertNotEquals(vbtA, vbtB)

        assertEquals(
            ProfileLocalSafetyPreferences(
                safeWord = "Phoenix Alpha",
                safeWordCalibrated = true,
                adultsOnlyConfirmed = true,
                adultsOnlyPrompted = true,
            ),
            fixture.localSafety.getValue(a),
        )
        assertEquals(
            ProfileLocalSafetyPreferences(
                safeWord = "Phoenix Bravo",
                safeWordCalibrated = false,
                adultsOnlyConfirmed = false,
                adultsOnlyPrompted = false,
            ),
            fixture.localSafety.getValue(b),
        )
        assertTrue(
            fixture.preferenceWriteOrder.indexOf(a to "localSafety") <
                fixture.preferenceWriteOrder.indexOf(a to "vbt"),
        )
        assertFalse(result.toString().contains("Phoenix Alpha"))
        assertFalse(result.toString().contains("Phoenix Bravo"))
    }

    @Test
    fun `two PR types expose three highlights and VBT history survives disabled profile B`() = runTest {
        val fixture = SeederFixture()
        val result = fixture.seeder().run { seed().also { seed() } }

        listOf(result.profileAId, result.profileBId).forEach { profileId ->
            val prs = fixture.personalRecords.filter { it.profileId == profileId }
            assertEquals(2, prs.size)
            val weight = prs.single { it.prType == PRType.MAX_WEIGHT }
            val volume = prs.single { it.prType == PRType.MAX_VOLUME }
            assertEquals(50f, weight.weightPerCableKg)
            assertEquals(3, weight.reps)
            assertEquals(40f, volume.weightPerCableKg)
            assertEquals(12, volume.reps)
            assertEquals(480f, volume.volume)
            val highlights = Triple(
                prs.maxOf { it.weightPerCableKg },
                prs.maxOf { it.oneRepMax },
                prs.maxOf { it.volume },
            )
            assertEquals(50f, highlights.first)
            assertEquals(OneRepMaxCalculator.estimate(40f, 12), highlights.second)
            assertEquals(480f, highlights.third)

            val assessment = fixture.assessments.single { it.profileId == profileId }
            assertEquals(120f, assessment.estimatedOneRepMaxKg)
            assertTrue(assessment.loadVelocityData.contains("velocityMs"))

            val velocity = fixture.velocity.single { it.profileId == profileId }
            assertEquals(77f, velocity.estimatedPerCableKg)
            assertEquals(0.30f, velocity.mvtUsedMs)
            assertEquals(0.97f, velocity.r2)
            assertEquals(3, velocity.distinctLoads)
            assertTrue(velocity.passedQualityGate)
            assertTrue(velocity.computedAt > assessment.createdAt)
            assertTrue(velocity.estimatedPerCableKg > assessment.estimatedOneRepMaxKg / 2f)
            val sessionFallback = fixture.sessions.values
                .filter { it.profileId == profileId }
                .maxOf { OneRepMaxCalculator.estimate(it.weightPerCableKg, it.workingReps) }
            assertTrue(velocity.estimatedPerCableKg > sessionFallback)
        }

        assertFalse(fixture.vbt.getValue(result.profileBId).enabled)
        assertEquals(1, fixture.assessments.count { it.profileId == result.profileBId })
        assertEquals(1, fixture.velocity.count { it.profileId == result.profileBId })
    }

    private class SeederFixture {
        val profiles = mockk<UserProfileRepository>(relaxed = true)
        val exercises = mockk<ExerciseRepository>(relaxed = true)
        val workouts = mockk<WorkoutRepository>(relaxed = true)
        val repMetricRepository = mockk<RepMetricRepository>(relaxed = true)
        val personalRecordRepository = mockk<PersonalRecordRepository>(relaxed = true)
        val assessmentRepository = mockk<AssessmentRepository>(relaxed = true)
        val velocityRepository = mockk<VelocityOneRepMaxRepository>(relaxed = true)
        val database = mockk<VitruvianDatabase>(relaxed = true)

        val allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
        val deletedProfiles = mutableListOf<String>()
        val core = mutableMapOf<String, CoreProfilePreferences>()
        val rack = mutableMapOf<String, RackPreferences>()
        val workout = mutableMapOf<String, WorkoutPreferences>()
        val led = mutableMapOf<String, LedPreferences>()
        val vbt = mutableMapOf<String, VbtPreferences>()
        val localSafety = mutableMapOf<String, ProfileLocalSafetyPreferences>()
        val preferenceWriteOrder = mutableListOf<Pair<String, String>>()
        val sessions = linkedMapOf<String, WorkoutSession>()
        val repMetrics = mutableMapOf<String, List<com.devil.phoenixproject.domain.model.RepMetricData>>()
        val personalRecords = mutableListOf<PersonalRecord>()
        val assessments = mutableListOf<AssessmentResultEntity>()
        val velocity = mutableListOf<VelocityOneRepMaxEntity>()
        var catalogOneRepMaxKg: Float? = 42f

        private var nextProfile = 1
        private var nextPr = 1L
        private var nextAssessment = 1L
        private var nextVelocity = 1L

        init {
            every { profiles.allProfiles } returns allProfiles
            coEvery { profiles.createProfile(any(), any()) } coAnswers {
                yield()
                UserProfile(
                    id = "qa-created-${nextProfile++}",
                    name = firstArg(),
                    colorIndex = secondArg(),
                    createdAt = 1_700_000_000_000L,
                    isActive = false,
                ).also { allProfiles.value = allProfiles.value + it }
            }
            coEvery { profiles.updateProfile(any(), any(), any()) } coAnswers {
                val id = firstArg<String>()
                allProfiles.value = allProfiles.value.map {
                    if (it.id == id) it.copy(name = secondArg(), colorIndex = thirdArg()) else it
                }
            }
            coEvery { profiles.deleteProfile(any()) } coAnswers {
                deletedProfiles += firstArg<String>()
                true
            }
            coEvery { profiles.updateCore(any(), any()) } coAnswers { core[firstArg()] = secondArg() }
            coEvery { profiles.updateRack(any(), any()) } coAnswers { rack[firstArg()] = secondArg() }
            coEvery { profiles.updateWorkout(any(), any()) } coAnswers { workout[firstArg()] = secondArg() }
            coEvery { profiles.updateLed(any(), any()) } coAnswers { led[firstArg()] = secondArg() }
            coEvery { profiles.updateVbt(any(), any()) } coAnswers {
                vbt[firstArg()] = secondArg()
                preferenceWriteOrder += firstArg<String>() to "vbt"
            }
            coEvery { profiles.updateLocalSafety(any(), any()) } coAnswers {
                localSafety[firstArg()] = secondArg()
                preferenceWriteOrder += firstArg<String>() to "localSafety"
            }

            coEvery { exercises.findByName("Bench Press") } coAnswers {
                Exercise(
                    id = "bench-press",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                    oneRepMaxKg = catalogOneRepMaxKg,
                )
            }
            coEvery { exercises.updateOneRepMax("bench-press", any()) } coAnswers {
                catalogOneRepMaxKg = secondArg()
            }

            coEvery { workouts.deleteSession(any()) } coAnswers { sessions.remove(firstArg()) }
            coEvery { workouts.saveSession(any()) } coAnswers {
                val session = firstArg<WorkoutSession>()
                sessions[session.id] = session
            }
            coEvery { repMetricRepository.deleteRepMetrics(any()) } coAnswers { repMetrics.remove(firstArg()) }
            coEvery { repMetricRepository.saveRepMetrics(any(), any()) } coAnswers {
                repMetrics[firstArg()] = secondArg()
            }

            coEvery { personalRecordRepository.getAllPRsForExercise(any(), any()) } coAnswers {
                personalRecords.filter { it.exerciseId == firstArg<String>() && it.profileId == secondArg<String>() }
            }
            coEvery {
                personalRecordRepository.updatePRsIfBetter(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                val exerciseId = arg<String>(0)
                val weightForWeightPr = arg<Float>(1)
                val weightForVolumePr = arg<Float>(2)
                val reps = arg<Int>(3)
                val workoutMode = arg<String>(4)
                val timestamp = arg<Long>(5)
                val profileId = arg<String>(6)
                val cableCount = arg<Int?>(7)
                val broken = mutableListOf<PRType>()
                fun replace(type: PRType, weight: Float, volume: Float) {
                    val current = personalRecords.firstOrNull {
                        it.profileId == profileId && it.exerciseId == exerciseId &&
                            it.workoutMode == workoutMode && it.prType == type
                    }
                    val improves = current == null || when (type) {
                        PRType.MAX_WEIGHT -> weight > current.weightPerCableKg
                        PRType.MAX_VOLUME -> volume > current.volume
                    }
                    if (improves) {
                        personalRecords.remove(current)
                        personalRecords += PersonalRecord(
                            id = nextPr++,
                            exerciseId = exerciseId,
                            exerciseName = "Bench Press",
                            weightPerCableKg = weight,
                            reps = reps,
                            oneRepMax = OneRepMaxCalculator.estimate(weightForWeightPr, reps),
                            timestamp = timestamp,
                            workoutMode = workoutMode,
                            prType = type,
                            volume = volume,
                            profileId = profileId,
                            cableCount = cableCount,
                        )
                        broken += type
                    }
                }
                replace(PRType.MAX_WEIGHT, weightForWeightPr, weightForWeightPr * reps)
                replace(PRType.MAX_VOLUME, weightForVolumePr, weightForVolumePr * reps)
                if (broken.isNotEmpty()) {
                    catalogOneRepMaxKg = maxOf(
                        catalogOneRepMaxKg ?: 0f,
                        OneRepMaxCalculator.estimate(weightForWeightPr, reps),
                    )
                }
                Result.success(broken)
            }

            every { assessmentRepository.getAssessmentsByExercise(any(), any()) } answers {
                flowOf(assessments.filter { it.exerciseId == firstArg<String>() && it.profileId == secondArg<String>() })
            }
            coEvery { assessmentRepository.deleteAssessment(any()) } coAnswers {
                assessments.removeAll { it.id == firstArg<Long>() }
            }
            coEvery { assessmentRepository.saveAssessment(any(), any(), any(), any(), any(), any()) } coAnswers {
                val id = nextAssessment++
                assessments += AssessmentResultEntity(
                    id = id,
                    exerciseId = arg(0),
                    estimatedOneRepMaxKg = arg(1),
                    loadVelocityData = arg(2),
                    assessmentSessionId = arg(3),
                    userOverrideKg = arg(4),
                    createdAt = 1_800_000_000_000L,
                    profileId = arg(5),
                )
                id
            }

            every { velocityRepository.getHistory(any(), any()) } answers {
                flowOf(velocity.filter { it.exerciseId == firstArg<String>() && it.profileId == secondArg<String>() })
            }
            coEvery { velocityRepository.insert(any(), any(), any(), any()) } coAnswers {
                val result = firstArg<VelocityOneRepMaxResult>()
                velocity += VelocityOneRepMaxEntity(
                    id = nextVelocity++,
                    exerciseId = secondArg(),
                    estimatedPerCableKg = result.estimatedPerCableKg,
                    mvtUsedMs = result.mvtUsedMs,
                    r2 = result.r2,
                    distinctLoads = result.distinctLoads,
                    passedQualityGate = result.passedQualityGate,
                    computedAt = arg(2),
                    profileId = arg(3),
                )
            }

        }

        fun seeder() = ProfileQaSeeder(
            userProfileRepository = profiles,
            exerciseRepository = exercises,
            workoutRepository = workouts,
            repMetricRepository = repMetricRepository,
            personalRecordRepository = personalRecordRepository,
            assessmentRepository = assessmentRepository,
            velocityOneRepMaxRepository = velocityRepository,
            database = database,
            fixtureRowCleanup = object : ProfileQaFixtureRowCleanup {
                override fun deletePersonalRecord(id: Long) {
                    personalRecords.removeAll { it.id == id }
                }

                override fun deleteVelocityOneRepMax(id: Long) {
                    velocity.removeAll { it.id == id }
                }
            },
        )
    }
}
