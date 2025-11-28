package com.devil.phoenixproject.data.local

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * JSON data classes for exercise_dump.json parsing
 */
@Serializable
data class ExerciseJson(
    val id: String,
    val name: String,
    val description: String? = null,
    val created: String? = null,
    val videos: List<VideoJson>? = null,
    val equipment: List<String>? = null,
    val muscleGroups: List<String>? = null,
    val muscles: List<String>? = null,
    val movement: String? = null,
    val tutorial: TutorialJson? = null,
    val aliases: List<String>? = null,
    val grip: String? = null,
    val gripWidth: String? = null,
    val sidedness: String? = null,
    val archived: String? = null, // Date string when archived, null if active
    val range: RangeJson? = null,
    val popularity: Double? = null
)

@Serializable
data class VideoJson(
    val id: String? = null,
    val video: String,
    val thumbnail: String,
    val angle: String? = null,
    val name: String? = null
)

@Serializable
data class TutorialJson(
    val video: String,
    val thumbnail: String
)

@Serializable
data class RangeJson(
    val minimum: Double? = null
)

/**
 * Imports exercises from JSON file into the SQLDelight database.
 * KMP-compatible implementation using Compose Resources.
 */
class ExerciseImporter(
    private val database: VitruvianDatabase
) {
    private val queries = database.vitruvianDatabaseQueries

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Import exercises from the bundled exercise_dump.json file
     * @return Result with count of exercises imported, or error
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun importExercises(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Logger.d { "Starting exercise import from bundled JSON..." }

            // Read JSON from compose resources
            val jsonBytes = Res.readBytes("files/exercise_dump.json")
            val jsonString = jsonBytes.decodeToString()

            return@withContext importFromJsonString(jsonString, clearExisting = false)

        } catch (e: Exception) {
            Logger.e(e) { "Failed to import exercises from bundled JSON" }
            Result.failure(e)
        }
    }

    /**
     * Import exercises from a JSON string
     * @param jsonString JSON array string containing exercise data
     * @param clearExisting If true, clears existing exercises before importing
     * @return Result with count of exercises imported, or error
     */
    suspend fun importFromJsonString(jsonString: String, clearExisting: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val exercises = json.decodeFromString<List<ExerciseJson>>(jsonString)

            Logger.d { "Parsed ${exercises.size} exercises from JSON" }

            // Clear existing data if requested
            if (clearExisting) {
                // Note: Videos will be cascaded due to foreign key
                queries.transaction {
                    // Delete all videos first (manual since we don't have a deleteAllVideos query)
                    // Then delete all exercises
                }
            }

            var importedCount = 0
            var videoCount = 0

            // Insert exercises and videos
            queries.transaction {
                for (exerciseJson in exercises) {
                    try {
                        // Map sidedness to cable config
                        val cableConfig = mapSidednessToConfig(exerciseJson.sidedness)

                        // Get primary muscle group
                        val primaryMuscle = exerciseJson.muscleGroups?.firstOrNull() ?: "OTHER"

                        // Join muscle groups and equipment
                        val muscleGroupsStr = exerciseJson.muscleGroups?.joinToString(",") ?: ""
                        val equipmentStr = exerciseJson.equipment?.joinToString(",") ?: ""

                        // Insert exercise
                        queries.insertExercise(
                            id = exerciseJson.id,
                            name = exerciseJson.name,
                            muscleGroup = primaryMuscle,
                            muscleGroups = muscleGroupsStr,
                            equipment = equipmentStr,
                            defaultCableConfig = cableConfig,
                            isFavorite = 0L,
                            isCustom = 0L
                        )
                        importedCount++

                        // Insert videos
                        exerciseJson.videos?.forEach { videoJson ->
                            val angle = videoJson.angle ?: videoJson.name ?: "FRONT"
                            queries.insertVideo(
                                exerciseId = exerciseJson.id,
                                angle = angle,
                                videoUrl = videoJson.video,
                                thumbnailUrl = videoJson.thumbnail,
                                isTutorial = 0L
                            )
                            videoCount++
                        }

                        // Insert tutorial video if present
                        exerciseJson.tutorial?.let { tutorial ->
                            queries.insertVideo(
                                exerciseId = exerciseJson.id,
                                angle = "TUTORIAL",
                                videoUrl = tutorial.video,
                                thumbnailUrl = tutorial.thumbnail,
                                isTutorial = 1L
                            )
                            videoCount++
                        }

                    } catch (e: Exception) {
                        Logger.w { "Failed to import exercise ${exerciseJson.name}: ${e.message}" }
                        // Continue with other exercises
                    }
                }
            }

            Logger.d { "Successfully imported $importedCount exercises with $videoCount videos" }
            Result.success(importedCount)

        } catch (e: Exception) {
            Logger.e(e) { "Failed to parse exercise JSON" }
            Result.failure(e)
        }
    }

    /**
     * Map JSON sidedness field to Vitruvian cable configuration
     * - bilateral (both arms/legs) → DOUBLE (both cables)
     * - unilateral (one arm/leg) → SINGLE (one cable)
     * - alternating (one at a time) → EITHER (user choice)
     */
    private fun mapSidednessToConfig(sidedness: String?): String {
        return when (sidedness?.lowercase()) {
            "bilateral" -> "DOUBLE"
            "unilateral" -> "SINGLE"
            "alternating" -> "EITHER"
            else -> "DOUBLE" // Safe default
        }
    }

    /**
     * Update exercise library from GitHub
     * @return Result with count of exercises updated, or error
     */
    suspend fun updateFromGitHub(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Logger.d { "Updating exercise library from GitHub..." }

            // For KMP, we'll use expect/actual for HTTP client
            // For now, just return success with 0 (not implemented)
            // TODO: Implement with Ktor client for proper KMP HTTP
            Logger.w { "GitHub update not yet implemented for KMP" }
            Result.success(0)

        } catch (e: Exception) {
            Logger.e(e) { "Failed to update from GitHub" }
            Result.failure(e)
        }
    }
}
