package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.CsvExporter

/**
 * Fake CsvExporter for UI tests.
 * Avoids file IO while returning deterministic URIs.
 */
class FakeCsvExporter : CsvExporter {
    override fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return Result.success("test://personal_records.csv")
    }

    override fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return Result.success("test://workout_history.csv")
    }

    override fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return Result.success("test://pr_progression.csv")
    }

    override fun shareCSV(fileUri: String, fileName: String) {
        // No-op in tests.
    }
}
