package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.WeightUnit
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyProfilePreferencesReaderTest {
    @Test
    fun corruptLegacyFieldsNormalizeWithoutFailingMigration() {
        val settings = MapSettings().apply {
            putString("weight_unit", "STONE")
            putFloat("body_weight_kg", Float.NaN)
            putString("equipment_rack_items_v1", "[{\"id\":\"x\"},{\"id\":\"x\"}]")
            putString("exercise_defaults_broken", "{broken")
        }
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )

        val snapshot = reader.readNormalized()

        assertEquals(WeightUnit.LB, snapshot.core.weightUnit)
        assertEquals(0f, snapshot.core.bodyWeightKg)
        assertEquals(emptyList(), snapshot.rack.items)
        assertEquals(emptyMap(), snapshot.workout.singleExerciseDefaults)
        assertTrue(snapshot.vbt.enabled)
    }

    @Test
    fun invalidNestedEntriesAreDroppedWithoutDiscardingValidSiblings() {
        val settings = MapSettings().apply {
            putString(
                "equipment_rack_items_v1",
                """[
                    {"id":"plate","name":"Plate","weightKg":10.0},
                    {"id":"plate","name":"Duplicate","weightKg":5.0},
                    {"id":"invalid","name":"","weightKg":5.0},
                    {"id":"band","name":"Band","weightKg":0.0}
                ]""".trimIndent(),
            )
            putString(
                "exercise_defaults_press",
                """{
                    "exerciseId":"press",
                    "setReps":[5,null],
                    "weightPerCableKg":20.0,
                    "setWeightsPerCableKg":[20.0,22.5],
                    "progressionKg":2.5,
                    "setRestSeconds":[60,60],
                    "workoutModeId":0,
                    "eccentricLoadPercentage":100,
                    "echoLevelValue":1,
                    "duration":0,
                    "isAMRAP":false,
                    "perSetRestTime":true,
                    "defaultRackItemIds":["plate"]
                }""".trimIndent(),
            )
            putString(
                "exercise_defaults_invalid",
                """{
                    "exerciseId":"invalid",
                    "setReps":[-1],
                    "weightPerCableKg":20.0,
                    "setWeightsPerCableKg":[20.0],
                    "progressionKg":2.5,
                    "setRestSeconds":[60],
                    "workoutModeId":0,
                    "eccentricLoadPercentage":100,
                    "echoLevelValue":1,
                    "duration":0,
                    "isAMRAP":false,
                    "perSetRestTime":true
                }""".trimIndent(),
            )
        }
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )

        val snapshot = reader.readNormalized()

        assertEquals(listOf("plate", "band"), snapshot.rack.items.map { it.id })
        assertEquals("Plate", snapshot.rack.items.first().name)
        assertEquals(10f, snapshot.rack.items.first().weightKg)
        assertEquals(setOf("press"), snapshot.workout.singleExerciseDefaults.keys)
    }

    @Test
    fun justLiftInvalidFieldFallsBackWithoutDiscardingValidSibling() {
        val settings = MapSettings().apply {
            putString(
                "just_lift_defaults",
                """{"weightPerCableKg":-5.0,"restSeconds":120}""",
            )
        }
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )

        val defaults = reader.readNormalized().workout.justLiftDefaults

        assertEquals(20f, defaults.weightPerCableKg)
        assertEquals(120, defaults.restSeconds)
    }

    @Test
    fun exerciseDefaultsUseEstablishedDeterministicKeyPrecedence() {
        val competingEntries = listOf(
            "exercise_defaults_row_DOUBLE" to exerciseDefaultsJson("row", 20f),
            "exercise_defaults_row" to exerciseDefaultsJson("row", 10f),
            "exercise_defaults_press_EITHER" to exerciseDefaultsJson("press", 40f),
            "exercise_defaults_press_SINGLE" to exerciseDefaultsJson("press", 30f),
            "exercise_defaults_squat_EITHER" to exerciseDefaultsJson("squat", 70f),
            "exercise_defaults_squat_SINGLE" to exerciseDefaultsJson("squat", 60f),
            "exercise_defaults_squat_DOUBLE" to exerciseDefaultsJson("squat", 50f),
            "exercise_defaults_z_for_curl" to exerciseDefaultsJson("curl", 90f),
            "exercise_defaults_a_for_curl" to exerciseDefaultsJson("curl", 80f),
        )

        fun read(entries: List<Pair<String, String>>) = MapSettings()
            .also { settings -> entries.forEach { (key, value) -> settings.putString(key, value) } }
            .let { settings ->
                SettingsLegacyProfilePreferencesReader(
                    SettingsPreferencesManager(settings),
                    settings,
                ).readNormalized().workout.singleExerciseDefaults
            }

        val insertedOutOfPrecedenceOrder = read(competingEntries)
        val insertedInReverseOrder = read(competingEntries.reversed())

        assertEquals(insertedOutOfPrecedenceOrder, insertedInReverseOrder)
        assertEquals(10f, insertedOutOfPrecedenceOrder.getValue("row").weightPerCableKg)
        assertEquals(30f, insertedOutOfPrecedenceOrder.getValue("press").weightPerCableKg)
        assertEquals(50f, insertedOutOfPrecedenceOrder.getValue("squat").weightPerCableKg)
        assertEquals(80f, insertedOutOfPrecedenceOrder.getValue("curl").weightPerCableKg)
    }

    @Test
    fun outOfRangeFieldsUseDocumentedDefaultsInsteadOfLegacyClamps() {
        val settings = MapSettings().apply {
            putInt("velocity_loss_threshold_percent", 999)
            putInt("default_routine_exercise_weight_percent_of_pr", 999)
        }
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )

        val snapshot = reader.readNormalized()

        assertEquals(20, snapshot.vbt.velocityLossThresholdPercent)
        assertEquals(80, snapshot.workout.defaultRoutineExerciseWeightPercentOfPR)
    }

    @Test
    fun echoHardPlaceholderMigrationHonorsExistingOneShotMarkers() {
        fun settings(markersComplete: Boolean) = MapSettings().apply {
            putString(
                "just_lift_defaults",
                """{"workoutModeId":10,"echoLevelValue":0}""",
            )
            putString("exercise_defaults_press", echoExerciseDefaultsJson())
            if (markersComplete) {
                putBoolean("echo_hard_default_migrated_just_lift", true)
                putBoolean("echo_hard_default_migrated_exercise_press", true)
            }
        }

        val legacySettings = settings(markersComplete = false)
        val legacySnapshot = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(legacySettings),
            legacySettings,
        ).readNormalized()
        val explicitlySavedSettings = settings(markersComplete = true)
        val explicitlySavedSnapshot = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(explicitlySavedSettings),
            explicitlySavedSettings,
        ).readNormalized()

        assertEquals(1, legacySnapshot.workout.justLiftDefaults.echoLevelValue)
        assertEquals(1, legacySnapshot.workout.singleExerciseDefaults.getValue("press").echoLevelValue)
        assertEquals(0, explicitlySavedSnapshot.workout.justLiftDefaults.echoLevelValue)
        assertEquals(0, explicitlySavedSnapshot.workout.singleExerciseDefaults.getValue("press").echoLevelValue)
    }

    @Test
    fun rackItemsWithoutStableIdsAreDroppedWithoutGeneratingMigrationValues() {
        val settings = MapSettings().apply {
            putString(
                "equipment_rack_items_v1",
                """[
                    {"name":"Missing id","weightKg":5.0},
                    {"id":"stable","name":"Stable","weightKg":10.0}
                ]""".trimIndent(),
            )
        }
        val reader = SettingsLegacyProfilePreferencesReader(
            SettingsPreferencesManager(settings),
            settings,
        )

        val first = reader.readNormalized().rack.items
        val second = reader.readNormalized().rack.items

        assertEquals(listOf("stable"), first.map { it.id })
        assertEquals(first, second)
    }

    private fun echoExerciseDefaultsJson() = """{
        "exerciseId":"press",
        "setReps":[5],
        "weightPerCableKg":20.0,
        "setWeightsPerCableKg":[20.0],
        "progressionKg":2.5,
        "setRestSeconds":[60],
        "workoutModeId":10,
        "eccentricLoadPercentage":100,
        "echoLevelValue":0,
        "duration":0,
        "isAMRAP":false,
        "perSetRestTime":true
    }""".trimIndent()

    private fun exerciseDefaultsJson(exerciseId: String, weightPerCableKg: Float) = """{
        "exerciseId":"$exerciseId",
        "setReps":[5],
        "weightPerCableKg":$weightPerCableKg,
        "setWeightsPerCableKg":[$weightPerCableKg],
        "progressionKg":2.5,
        "setRestSeconds":[60],
        "workoutModeId":0,
        "eccentricLoadPercentage":100,
        "echoLevelValue":1,
        "duration":0,
        "isAMRAP":false,
        "perSetRestTime":true
    }""".trimIndent()
}
