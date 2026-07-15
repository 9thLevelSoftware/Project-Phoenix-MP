package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.migration.RequiredMigrationFailedException
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent

class HealthBodyWeightSyncManagerTest {

    private class FakeMigrationGate(
        initialState: RequiredMigrationState = RequiredMigrationState.Ready,
    ) : RequiredMigrationGate {
        val state = MutableStateFlow(initialState)
        override val requiredMigrationState: StateFlow<RequiredMigrationState> =
            state

        override suspend fun awaitRequiredMigrations() {
            when (val terminal = state.first {
                it is RequiredMigrationState.Ready || it is RequiredMigrationState.Failed
            }) {
                RequiredMigrationState.Ready -> Unit
                is RequiredMigrationState.Failed -> throw RequiredMigrationFailedException(terminal.message)
                else -> error("Required migration gate returned a non-terminal state")
            }
        }
    }

    private class FakeHealthBodyWeightReader : HealthBodyWeightReader {
        var available = true
        var bodyWeightReadPermission = true
        var readResult: Result<HealthBodyWeightSample?> = Result.success(null)
        var readCallCount = 0
        var beforeRead: suspend () -> Unit = {}

        override suspend fun isAvailable(): Boolean = available

        override suspend fun hasBodyWeightReadPermission(): Boolean = bodyWeightReadPermission

        override suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?> {
            readCallCount++
            beforeRead()
            return readResult
        }
    }

    @Test
    fun disconnectedProviderDoesNotReadOrMutateManualPreference() = runTest {
        val harness = Harness()
        harness.preferences.setBodyWeightKg(72f)
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f))

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.NotConnected)
        assertEquals(0, harness.reader.readCallCount)
        assertEquals(72f, harness.preferences.preferencesFlow.value.bodyWeightKg)
        assertEquals(emptyList(), harness.measurements.measurements)
    }

    @Test
    fun missingReadPermissionDoesNotReadAndReportsPermissionStatus() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.preferences.setBodyWeightKg(72f)
        harness.reader.bodyWeightReadPermission = false

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.PermissionMissing)
        assertEquals(0, harness.reader.readCallCount)
        assertEquals(72f, harness.preferences.preferencesFlow.value.bodyWeightKg)
        assertEquals(emptyList(), harness.measurements.measurements)
        assertTrue(harness.activities.statusUpdates.last().errorMessage?.contains("permission", ignoreCase = true) == true)
    }

    @Test
    fun noEligibleSampleLeavesManualPreferenceAndUpdatesLastSyncAttempt() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.preferences.setBodyWeightKg(72f)
        harness.reader.readResult = Result.success(null)

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.NoEligibleSample)
        assertEquals(1, harness.reader.readCallCount)
        assertEquals(72f, harness.preferences.preferencesFlow.value.bodyWeightKg)
        assertEquals(emptyList(), harness.measurements.measurements)
        assertNotNull(harness.activities.statusUpdates.last().lastSyncAt)
    }

    @Test
    fun validSampleUpsertsMeasurementAndOverridesManualBodyWeight() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.preferences.setBodyWeightKg(72f)
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f, externalId = "scale-1"))

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.Synced)
        val ready = assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
        assertEquals(81.5f, ready.preferences.core.value.bodyWeightKg)
        assertEquals(72f, harness.preferences.preferencesFlow.value.bodyWeightKg)
        val measurement = harness.measurements.measurements.single()
        assertEquals("scale-1", measurement.externalId)
        assertEquals(IntegrationProvider.GOOGLE_HEALTH, measurement.provider)
        assertEquals("weight", measurement.measurementType)
        assertEquals(81.5, measurement.value)
        assertEquals("kg", measurement.unit)
        assertEquals("profile-1", measurement.profileId)
        assertNotNull(harness.activities.statusUpdates.last().lastSyncAt)
    }

    @Test
    fun measurementPersistenceFailureLeavesActiveBodyWeightUntouched() = runTest {
        val harness = Harness()
        harness.connectHealth()
        val ready = assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
        harness.profiles.updateCore(
            ready.profile.id,
            ready.preferences.core.value.copy(bodyWeightKg = 72f),
        )
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f))
        harness.measurements.upsertFailure = IllegalStateException("measurement upsert sentinel")

        val failure = assertFailsWith<IllegalStateException> {
            harness.manager.syncLatestFromConnectedPlatform()
        }

        assertEquals("measurement upsert sentinel", failure.message)
        assertEquals(
            72f,
            assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
                .preferences.core.value.bodyWeightKg,
        )
        assertEquals(emptyList(), harness.measurements.measurements)
    }

    @Test
    fun outOfRangeSampleDoesNotOverrideManualPreferenceOrInsertMeasurement() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.preferences.setBodyWeightKg(72f)
        harness.reader.readResult = Result.success(sample(weightKg = 450f))

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.RejectedOutOfRange)
        assertEquals(72f, harness.preferences.preferencesFlow.value.bodyWeightKg)
        assertEquals(emptyList(), harness.measurements.measurements)
        assertTrue(harness.activities.statusUpdates.last().errorMessage?.contains("range", ignoreCase = true) == true)
    }

    @Test
    fun duplicateExternalIdIsIdempotent() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f, externalId = "scale-1"))

        harness.manager.syncLatestFromConnectedPlatform()
        harness.reader.readResult = Result.success(sample(weightKg = 82f, externalId = "scale-1"))
        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertTrue(result is HealthBodyWeightSyncResult.Synced)
        assertEquals(
            82f,
            assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
                .preferences.core.value.bodyWeightKg,
        )
        assertEquals(1, harness.measurements.measurements.size)
        assertEquals(82.0, harness.measurements.measurements.single().value)
    }

    @Test
    fun profileTransitionDuringImportReturnsFailedWithoutInsertingMeasurement() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f))
        harness.reader.beforeRead = {
            harness.profiles.recoverPendingProfileTransitionForStartup()
        }

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertIs<HealthBodyWeightSyncResult.Failed>(result)
        assertEquals(emptyList(), harness.measurements.measurements)
    }

    @Test
    fun coreChangesDuringHealthReadArePreservedWhenBodyWeightIsWritten() = runTest {
        val harness = Harness()
        harness.connectHealth()
        harness.reader.readResult = Result.success(sample(weightKg = 81.5f))
        harness.reader.beforeRead = {
            val ready = assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
            harness.profiles.updateCore(
                ready.profile.id,
                ready.preferences.core.value.copy(
                    weightUnit = WeightUnit.LB,
                    weightIncrement = 5f,
                ),
            )
        }

        val result = harness.manager.syncLatestFromConnectedPlatform()

        assertIs<HealthBodyWeightSyncResult.Synced>(result)
        val core = assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
            .preferences.core.value
        assertEquals(81.5f, core.bodyWeightKg)
        assertEquals(WeightUnit.LB, core.weightUnit)
        assertEquals(5f, core.weightIncrement)
    }

    @Test
    fun healthImportWaitsForRequiredMigrationAndWritesActiveCore() = runTest {
        val gate = FakeMigrationGate(RequiredMigrationState.Applying)
        val harness = Harness(gate)
        harness.connectHealth()
        harness.reader.readResult = Result.success(sample(weightKg = 81f))

        val result = async { harness.manager.syncLatestFromConnectedPlatform() }
        runCurrent()
        assertEquals(
            0f,
            assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
                .preferences.core.value.bodyWeightKg,
        )

        gate.state.value = RequiredMigrationState.Ready

        assertIs<HealthBodyWeightSyncResult.Synced>(result.await())
        assertEquals(
            81f,
            assertIs<ActiveProfileContext.Ready>(harness.profiles.activeProfileContext.value)
                .preferences.core.value.bodyWeightKg,
        )
    }

    @Test
    fun failedRequiredMigrationReturnsFailedWithoutReadingHealthData() = runTest {
        val gate = FakeMigrationGate(RequiredMigrationState.Failed("migration failed"))
        val harness = Harness(gate)
        harness.connectHealth()
        harness.reader.readResult = Result.success(sample(weightKg = 81f))

        val result = harness.manager.syncLatestFromConnectedPlatform()

        val failed = assertIs<HealthBodyWeightSyncResult.Failed>(result)
        assertEquals("migration failed", failed.error.message)
        assertEquals(0, harness.reader.readCallCount)
        assertEquals(emptyList(), harness.measurements.measurements)
    }

    private class Harness(
        migrationGate: RequiredMigrationGate = FakeMigrationGate(),
    ) {
        val reader = FakeHealthBodyWeightReader()
        val activities = FakeExternalActivityRepository()
        val measurements = FakeExternalMeasurementRepository()
        val preferences = FakePreferencesManager()
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-1")
        }
        val manager = HealthBodyWeightSyncManager(
            bodyWeightReader = reader,
            externalActivityRepository = activities,
            externalMeasurementRepository = measurements,
            requiredMigrationGate = migrationGate,
            userProfileRepository = profiles,
            providerResolver = { IntegrationProvider.GOOGLE_HEALTH },
            nowProvider = { 123_456L },
        )

        suspend fun connectHealth() {
            activities.updateIntegrationStatus(
                provider = IntegrationProvider.GOOGLE_HEALTH,
                status = ConnectionStatus.CONNECTED,
                profileId = "profile-1",
            )
        }
    }

    private fun sample(
        weightKg: Float,
        externalId: String = "scale-1",
    ) = HealthBodyWeightSample(
        weightKg = weightKg,
        measuredAtMs = 100_000L,
        externalId = externalId,
        sourceName = "Withings",
        deviceMetadata = mapOf("model" to "Body Smart Scale"),
        rawMetadataJson = """{"source":"Withings"}""",
    )
}
