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
        assertEquals(setOf("press"), snapshot.workout.singleExerciseDefaults.keys)
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
}
