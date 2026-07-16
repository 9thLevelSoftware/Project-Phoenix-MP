package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSection
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.ProfileSectionMetadata
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.util.BackupDestination
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfilePreferencesCodecTest {
    @Test
    fun defaultsMatchVersionOneContract() {
        assertEquals(WeightUnit.LB, CoreProfilePreferences().weightUnit)
        assertEquals(-1f, CoreProfilePreferences().weightIncrement)
        assertEquals(10, WorkoutPreferences().summaryCountdownSeconds)
        assertEquals(5, WorkoutPreferences().autoStartCountdownSeconds)
        assertTrue(VbtPreferences().enabled)
        assertEquals(20, VbtPreferences().velocityLossThresholdPercent)
        assertEquals(1, RackPreferences().version)

        val safety = ProfileLocalSafetyPreferences()
        assertEquals(null, safety.safeWord)
        assertFalse(safety.safeWordCalibrated)
        assertFalse(safety.adultsOnlyConfirmed)
        assertFalse(safety.adultsOnlyPrompted)
    }

    @Test
    fun compatibilityPreferencesKeepGlobalFieldsAndEnableVbtByDefault() {
        val preferences = UserPreferences()

        assertTrue(preferences.vbtEnabled)
        assertTrue(preferences.enableVideoPlayback)
        assertFalse(preferences.autoBackupEnabled)
        assertEquals(BackupDestination.Default, preferences.backupDestination)
        assertEquals("en", preferences.language)
        assertEquals(BleCompatibilitySetting.AUTO, preferences.bleCompatibilityMode)
        assertFalse(preferences.velocityOneRepMaxBackfillDone)
    }

    @Test
    fun typedProfileSectionsCarryIndependentValuesAndMetadata() {
        val metadata = ProfileSectionMetadata(
            updatedAt = 11L,
            localGeneration = 12L,
            serverRevision = 13L,
            dirty = true,
        )
        val profile = UserProfilePreferences(
            profileId = "profile-1",
            schemaVersion = 1,
            legacyMigrationVersion = 0,
            core = ProfilePreferenceSection(CoreProfilePreferences(), validity = ProfilePreferenceValidity.Valid, metadata = metadata),
            rack = ProfilePreferenceSection(RackPreferences(), validity = ProfilePreferenceValidity.Valid, metadata = metadata),
            workout = ProfilePreferenceSection(WorkoutPreferences(), validity = ProfilePreferenceValidity.Valid, metadata = metadata),
            led = ProfilePreferenceSection(LedPreferences(), validity = ProfilePreferenceValidity.Valid, metadata = metadata),
            vbt = ProfilePreferenceSection(VbtPreferences(), validity = ProfilePreferenceValidity.Valid, metadata = metadata),
        )

        assertEquals("profile-1", profile.profileId)
        assertEquals(null, profile.core.raw)
        assertEquals(metadata, profile.vbt.metadata)
    }

    @Test
    fun malformedWorkoutDoesNotInvalidateRack() {
        val rack = ProfilePreferencesCodec.decodeRack(
            """{"version":1,"items":[]}""",
        )
        val workout = ProfilePreferencesCodec.decodeWorkout("{not-json")

        assertEquals(ProfilePreferenceValidity.Valid, rack.validity)
        assertIs<ProfilePreferenceValidity.Invalid>(workout.validity)
        assertEquals(WorkoutPreferences(), workout.value)
        assertEquals("{not-json", workout.raw)
    }

    @Test
    fun semanticallyInvalidWorkoutRetainsRawAndReturnsFallback() {
        val raw = """{"version":1,"summaryCountdownSeconds":7}"""

        val decoded = ProfilePreferencesCodec.decodeWorkout(raw)

        val invalid = assertIs<ProfilePreferenceValidity.Invalid>(decoded.validity)
        assertEquals("summaryCountdownSeconds", invalid.reason)
        assertEquals(WorkoutPreferences(), decoded.value)
        assertEquals(raw, decoded.raw)
    }

    @Test
    fun coreBodyWeightBoundaryTable() {
        listOf(0f, 20f, 300f).forEach { bodyWeightKg ->
            assertValid(
                ProfilePreferencesValidator.core(CoreProfilePreferences(bodyWeightKg = bodyWeightKg)),
                "bodyWeightKg=$bodyWeightKg",
            )
        }

        listOf(19.99f, 300.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bodyWeightKg ->
            assertInvalidField(
                ProfilePreferencesValidator.core(CoreProfilePreferences(bodyWeightKg = bodyWeightKg)),
                "bodyWeightKg",
                "bodyWeightKg=$bodyWeightKg",
            )
        }
    }

    @Test
    fun coreWeightIncrementBoundaryTable() {
        listOf(-1f, 0.01f, Float.MIN_VALUE).forEach { increment ->
            assertValid(
                ProfilePreferencesValidator.core(CoreProfilePreferences(weightIncrement = increment)),
                "weightIncrement=$increment",
            )
        }

        listOf(0f, -0.01f, -2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { increment ->
            assertInvalidField(
                ProfilePreferencesValidator.core(CoreProfilePreferences(weightIncrement = increment)),
                "weightIncrement",
                "weightIncrement=$increment",
            )
        }
    }

    @Test
    fun workoutTimerBoundaryTables() {
        listOf(-1, 0, 5, 10, 15, 20, 25, 30).forEach { seconds ->
            assertValid(
                ProfilePreferencesValidator.workout(WorkoutPreferences(summaryCountdownSeconds = seconds)),
                "summaryCountdownSeconds=$seconds",
            )
        }
        listOf(-2, 1, 4, 6, 29, 31).forEach { seconds ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(WorkoutPreferences(summaryCountdownSeconds = seconds)),
                "summaryCountdownSeconds",
                "summaryCountdownSeconds=$seconds",
            )
        }

        listOf(2, 10).forEach { seconds ->
            assertValid(
                ProfilePreferencesValidator.workout(WorkoutPreferences(autoStartCountdownSeconds = seconds)),
                "autoStartCountdownSeconds=$seconds",
            )
        }
        listOf(1, 11).forEach { seconds ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(WorkoutPreferences(autoStartCountdownSeconds = seconds)),
                "autoStartCountdownSeconds",
                "autoStartCountdownSeconds=$seconds",
            )
        }

        listOf(0, 5, 300).forEach { seconds ->
            assertValid(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(justLiftDefaults = JustLiftDefaultsDocument(restSeconds = seconds)),
                ),
                "justLiftDefaults.restSeconds=$seconds",
            )
        }
        listOf(1, 4, 301).forEach { seconds ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(justLiftDefaults = JustLiftDefaultsDocument(restSeconds = seconds)),
                ),
                "justLiftDefaults.restSeconds",
                "justLiftDefaults.restSeconds=$seconds",
            )
        }
    }

    @Test
    fun percentageBoundaryTables() {
        listOf(50, 120).forEach { percent ->
            assertValid(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(defaultRoutineExerciseWeightPercentOfPR = percent),
                ),
                "defaultRoutineExerciseWeightPercentOfPR=$percent",
            )
        }
        listOf(49, 121).forEach { percent ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(defaultRoutineExerciseWeightPercentOfPR = percent),
                ),
                "defaultRoutineExerciseWeightPercentOfPR",
                "defaultRoutineExerciseWeightPercentOfPR=$percent",
            )
        }

        listOf(0, 150).forEach { percent ->
            assertValid(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(justLiftDefaults = JustLiftDefaultsDocument(eccentricLoadPercentage = percent)),
                ),
                "justLiftDefaults.eccentricLoadPercentage=$percent",
            )
        }
        listOf(-1, 151).forEach { percent ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(justLiftDefaults = JustLiftDefaultsDocument(eccentricLoadPercentage = percent)),
                ),
                "justLiftDefaults.eccentricLoadPercentage",
                "justLiftDefaults.eccentricLoadPercentage=$percent",
            )
        }

        listOf(10, 50).forEach { percent ->
            assertValid(ProfilePreferencesValidator.vbt(VbtPreferences(velocityLossThresholdPercent = percent)), "vbt=$percent")
        }
        listOf(9, 51).forEach { percent ->
            assertInvalidField(
                ProfilePreferencesValidator.vbt(VbtPreferences(velocityLossThresholdPercent = percent)),
                "velocityLossThresholdPercent",
                "vbt=$percent",
            )
        }
    }

    @Test
    fun nonFiniteWorkoutWeightsAreInvalid() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f).forEach { weight ->
            assertInvalidField(
                ProfilePreferencesValidator.workout(
                    WorkoutPreferences(justLiftDefaults = JustLiftDefaultsDocument(weightPerCableKg = weight)),
                ),
                "justLiftDefaults.weightPerCableKg",
                "justLiftDefaults.weightPerCableKg=$weight",
            )
        }
    }

    @Test
    fun duplicateRackIdsAreInvalid() {
        val rack = RackPreferences(
            items = listOf(
                RackItem(id = "duplicate", name = "Vest", weightKg = 10f),
                RackItem(id = "duplicate", name = "Belt", weightKg = 5f),
            ),
        )

        assertInvalidField(ProfilePreferencesValidator.rack(rack), "duplicateRackItemId", "duplicate rack IDs")
    }

    @Test
    fun unsupportedVersionsReturnFallbackAndRetainRaw() {
        val rackRaw = """{"version":2,"items":[]}"""
        val workoutRaw = """{"version":2}"""
        val ledRaw = """{"version":2,"discoModeUnlocked":true}"""
        val vbtRaw = """{"version":2,"velocityLossThresholdPercent":25}"""

        val rack = ProfilePreferencesCodec.decodeRack(rackRaw)
        val workout = ProfilePreferencesCodec.decodeWorkout(workoutRaw)
        val led = ProfilePreferencesCodec.decodeLed(ledRaw, colorScheme = 4)
        val vbt = ProfilePreferencesCodec.decodeVbt(vbtRaw, enabled = false)

        assertIs<ProfilePreferenceValidity.Invalid>(rack.validity)
        assertEquals(RackPreferences(), rack.value)
        assertEquals(rackRaw, rack.raw)
        assertIs<ProfilePreferenceValidity.Invalid>(workout.validity)
        assertEquals(WorkoutPreferences(), workout.value)
        assertEquals(workoutRaw, workout.raw)
        assertIs<ProfilePreferenceValidity.Invalid>(led.validity)
        assertEquals(LedPreferences(colorScheme = 4), led.value)
        assertEquals(ledRaw, led.raw)
        assertIs<ProfilePreferenceValidity.Invalid>(vbt.validity)
        assertEquals(VbtPreferences(enabled = false), vbt.value)
        assertEquals(vbtRaw, vbt.raw)
    }

    @Test
    fun unknownFieldsAreIgnoredAndMissingOptionalFieldsUseDefaults() {
        val rack = ProfilePreferencesCodec.decodeRack("""{"version":1,"future":true}""")
        val workout = ProfilePreferencesCodec.decodeWorkout("""{"version":1,"future":{"nested":1}}""")
        val led = ProfilePreferencesCodec.decodeLed("""{"future":"value"}""", colorScheme = 7)
        val vbt = ProfilePreferencesCodec.decodeVbt("""{"future":[1,2]}""", enabled = false)

        assertEquals(ProfilePreferenceValidity.Valid, rack.validity)
        assertEquals(RackPreferences(), rack.value)
        assertEquals(ProfilePreferenceValidity.Valid, workout.validity)
        assertEquals(WorkoutPreferences(), workout.value)
        assertEquals(ProfilePreferenceValidity.Valid, led.validity)
        assertEquals(LedPreferences(colorScheme = 7), led.value)
        assertEquals(ProfilePreferenceValidity.Valid, vbt.validity)
        assertEquals(VbtPreferences(enabled = false), vbt.value)
    }

    @Test
    fun localLedJsonHasExactKeysAndOmitsRowOwnedColorScheme() {
        val encoded = ProfilePreferencesCodec.encodeLed(
            LedPreferences(colorScheme = 9, discoModeUnlocked = true),
        )

        assertEquals(
            setOf("version", "discoModeUnlocked"),
            Json.parseToJsonElement(encoded).jsonObject.keys,
        )
    }

    @Test
    fun localVbtJsonHasExactKeysAndOmitsRowOwnedEnabled() {
        val encoded = ProfilePreferencesCodec.encodeVbt(VbtPreferences(enabled = false))

        assertEquals(
            setOf(
                "version",
                "velocityLossThresholdPercent",
                "autoEndOnVelocityLoss",
                "defaultScalingBasis",
                "verbalEncouragementEnabled",
                "vulgarModeEnabled",
                "vulgarTier",
                "dominatrixModeUnlocked",
                "dominatrixModeActive",
            ),
            Json.parseToJsonElement(encoded).jsonObject.keys,
        )
    }

    @Test
    fun rowOwnedValuesAreAppliedWhenLocalDocumentsDecode() {
        val led = ProfilePreferencesCodec.decodeLed(
            """{"version":1,"discoModeUnlocked":true}""",
            colorScheme = 6,
        )
        val vbt = ProfilePreferencesCodec.decodeVbt(
            """{"version":1,"velocityLossThresholdPercent":35,"vulgarTier":"MILD"}""",
            enabled = false,
        )

        assertEquals(LedPreferences(colorScheme = 6, discoModeUnlocked = true), led.value)
        assertEquals(6, led.value.colorScheme)
        assertFalse(vbt.value.enabled)
        assertEquals(35, vbt.value.velocityLossThresholdPercent)
        assertEquals(VulgarTier.MILD, vbt.value.vulgarTier)
    }

    @Test
    fun persistedEnumsSerializeAsUppercaseNames() {
        assertEquals("\"KG\"", Json.encodeToString(WeightUnit.KG))
        assertEquals("\"BOTTOM\"", Json.encodeToString(RepCountTiming.BOTTOM))
        assertEquals("\"MIX\"", Json.encodeToString(VulgarTier.MIX))
        assertEquals("\"ESTIMATED_1RM\"", Json.encodeToString(ScalingBasis.ESTIMATED_1RM))
    }

    @Test
    fun singleExerciseDefaultsMapToDocumentAndBackWithoutLoss() {
        val legacy = SingleExerciseDefaults(
            exerciseId = "exercise-1",
            setReps = listOf(8, null, 10),
            weightPerCableKg = 21.5f,
            setWeightsPerCableKg = listOf(21.5f, 22f, 22.5f),
            progressionKg = 0.5f,
            setRestSeconds = listOf(30, 45, 60),
            workoutModeId = 10,
            eccentricLoadPercentage = 120,
            echoLevelValue = 2,
            duration = 90,
            isAMRAP = true,
            perSetRestTime = true,
            defaultRackItemIds = listOf("rack-1", "rack-2"),
        )

        val document = legacy.toDocument()

        assertEquals(legacy, document.toLegacySingleExerciseDefaults())
    }

    @Test
    fun justLiftDefaultsMapToDocumentWithoutLoss() {
        val legacy = JustLiftDefaults(
            workoutModeId = 10,
            weightPerCableKg = 24.5f,
            weightChangePerRep = -0.5f,
            eccentricLoadPercentage = 130,
            echoLevelValue = 3,
            stallDetectionEnabled = false,
            repCountTimingName = "BOTTOM",
            restSeconds = 75,
        )

        val document = legacy.toDocument()

        assertEquals(legacy.workoutModeId, document.workoutModeId)
        assertEquals(legacy.weightPerCableKg, document.weightPerCableKg)
        assertEquals(legacy.weightChangePerRep, document.weightChangePerRep)
        assertEquals(legacy.eccentricLoadPercentage, document.eccentricLoadPercentage)
        assertEquals(legacy.echoLevelValue, document.echoLevelValue)
        assertEquals(legacy.stallDetectionEnabled, document.stallDetectionEnabled)
        assertEquals(legacy.repCountTimingName, document.repCountTimingName)
        assertEquals(legacy.restSeconds, document.restSeconds)
    }

    private fun assertValid(errors: List<String>, case: String) {
        assertTrue(errors.isEmpty(), "Expected valid $case, got $errors")
    }

    private fun assertInvalidField(errors: List<String>, field: String, case: String) {
        assertTrue(field in errors, "Expected $field for $case, got $errors")
    }
}
