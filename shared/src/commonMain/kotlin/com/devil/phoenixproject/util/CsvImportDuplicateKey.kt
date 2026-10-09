package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Duplicate identity for one Phoenix workout-history CSV row: timestamp plus exercise name.
 *
 * History files ([PhoenixCsvCodec.HISTORY_HEADER]) have an Exercise column and no exercise
 * id. [PhoenixCsvCodec.parseWorkoutHistory] copies that column into
 * [WorkoutSession.exerciseName] and leaves [WorkoutSession.exerciseId] null, so an incoming
 * row cannot carry an id.
 *
 * Existing sessions can have a null name and a non-null id. Keying those rows with
 * `exerciseName ?: exerciseId` and incoming rows with `exerciseName ?: ""` made a null name
 * miss on re-import. Using the id as the shared fallback cannot close that gap: the incoming
 * id is always null, so the two keys still differ.
 *
 * Both sides use the name, and `""` when the name is null. That empty string is the only
 * exercise identity an incoming row can carry. Nameless rows at the same timestamp share
 * one key, because the file has nothing else that tells them apart.
 */
internal data class CsvImportDuplicateKey(
    val timestamp: Long,
    val exerciseName: String,
)

internal fun csvImportDuplicateKey(session: WorkoutSession): CsvImportDuplicateKey = CsvImportDuplicateKey(
    timestamp = session.timestamp,
    exerciseName = session.exerciseName ?: "",
)
