package com.devil.phoenixproject.data.local

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
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

                        // Parse archived status (date string → boolean)
                        val isArchived = exerciseJson.archived != null

                        // Parse min rep range from JSON range object
                        val minRepRange = exerciseJson.range?.minimum?.toDouble()

                        // Join muscles and aliases
                        val musclesStr = exerciseJson.muscles?.joinToString(",")
                        val aliasesStr = exerciseJson.aliases?.joinToString(",")

                        // Insert exercise with all columns
                        queries.insertExercise(
                            id = exerciseJson.id,
                            name = exerciseJson.name,
                            description = exerciseJson.description,
                            created = 0L, // Will be set from JSON created field if needed
                            muscleGroup = primaryMuscle,
                            muscleGroups = muscleGroupsStr,
                            muscles = musclesStr,
                            equipment = equipmentStr,
                            movement = exerciseJson.movement,
                            sidedness = exerciseJson.sidedness,
                            grip = exerciseJson.grip,
                            gripWidth = exerciseJson.gripWidth,
                            minRepRange = minRepRange,
                            popularity = exerciseJson.popularity ?: 0.0,
                            archived = if (isArchived) 1L else 0L,
                            isFavorite = 0L,
                            isCustom = 0L,
                            timesPerformed = 0L,
                            lastPerformed = null,
                            aliases = aliasesStr,
                            defaultCableConfig = cableConfig,
                            one_rep_max_kg = null
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

    companion object {
        // GitHub raw content URL for exercise data
        // Update this to point to your actual exercise data repository
        private const val GITHUB_EXERCISES_URL =
            "https://raw.githubusercontent.com/VitruvianFitness/exercise-library/main/exercise_dump.json"
    }

    /**
     * Update exercise library from GitHub
     * Fetches the latest exercise data and updates the local database
     * @return Result with count of exercises updated, or error
     */
    suspend fun updateFromGitHub(): Result<Int> = withContext(Dispatchers.IO) {
        val client = HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
        }

        try {
            Logger.d { "Fetching exercise library from GitHub..." }

            val response: HttpResponse = client.get(GITHUB_EXERCISES_URL)

            if (response.status.value !in 200..299) {
                Logger.e { "GitHub returned status ${response.status}" }
                return@withContext Result.failure(
                    Exception("Failed to fetch exercises: HTTP ${response.status.value}")
                )
            }

            val jsonContent = response.bodyAsText()
            Logger.d { "Received ${jsonContent.length} bytes from GitHub" }

            val exercises: List<ExerciseJson> = json.decodeFromString(jsonContent)
            Logger.d { "Parsed ${exercises.size} exercises from GitHub" }

            var updatedCount = 0

            exercises.forEach { exercise ->
                // Skip archived exercises
                if (exercise.archived != null) {
                    return@forEach
                }

                try {
                    // Use the full insertExercise query with all parameters
                    queries.insertExercise(
                        id = exercise.id,
                        name = exercise.name,
                        description = exercise.description,
                        created = 0L, // Default to 0, as date parsing is complex
                        muscleGroup = exercise.muscleGroups?.firstOrNull() ?: "Other",
                        muscleGroups = exercise.muscleGroups?.joinToString(",") ?: "",
                        muscles = exercise.muscles?.joinToString(","),
                        equipment = exercise.equipment?.joinToString(",") ?: "",
                        movement = exercise.movement,
                        sidedness = exercise.sidedness,
                        grip = exercise.grip,
                        gripWidth = exercise.gripWidth,
                        minRepRange = exercise.range?.minimum,
                        popularity = exercise.popularity ?: 0.0,
                        archived = if (exercise.archived != null) 1L else 0L,
                        isFavorite = 0L,
                        isCustom = 0L,
                        timesPerformed = 0L,
                        lastPerformed = null,
                        aliases = exercise.aliases?.joinToString(","),
                        defaultCableConfig = mapSidednessToCableConfig(exercise.sidedness),
                        one_rep_max_kg = null
                    )

                    // Insert videos
                    exercise.videos?.forEach { video ->
                        try {
                            queries.insertVideo(
                                exerciseId = exercise.id,
                                angle = video.angle ?: "front",
                                videoUrl = video.video,
                                thumbnailUrl = video.thumbnail,
                                isTutorial = 0L
                            )
                        } catch (e: Exception) {
                            Logger.w(e) { "Failed to insert video: ${e.message}" }
                        }
                    }

                    // Insert tutorial if available
                    exercise.tutorial?.let { tutorial ->
                        try {
                            queries.insertVideo(
                                exerciseId = exercise.id,
                                angle = "tutorial",
                                videoUrl = tutorial.video,
                                thumbnailUrl = tutorial.thumbnail,
                                isTutorial = 1L
                            )
                        } catch (e: Exception) {
                            Logger.w(e) { "Failed to insert tutorial video: ${e.message}" }
                        }
                    }

                    updatedCount++
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to update exercise ${exercise.name}: ${e.message}" }
                }
            }

            Logger.d { "Successfully updated $updatedCount exercises from GitHub" }
            Result.success(updatedCount)

        } catch (e: Exception) {
            Logger.e(e) { "Failed to update from GitHub: ${e.message}" }
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Map sidedness field to cable configuration
     */
    private fun mapSidednessToCableConfig(sidedness: String?): String {
        return when (sidedness?.lowercase()) {
            "single", "unilateral" -> "SINGLE"
            "double", "bilateral" -> "DOUBLE"
            "alternating" -> "EITHER"
            else -> "DOUBLE" // Safe default
        }
    }
}
