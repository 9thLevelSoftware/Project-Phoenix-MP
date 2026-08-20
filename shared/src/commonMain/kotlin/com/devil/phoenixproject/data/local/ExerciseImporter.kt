package com.devil.phoenixproject.data.local

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import vitruvianprojectphoenix.shared.generated.resources.Res

@Serializable
data class FreeExerciseJson(
    val id: String,
    val name: String,
    val force: String? = null,
    val level: String? = null,
    val mechanic: String? = null,
    val equipment: String? = null,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val category: String? = null,
    val images: List<String> = emptyList(),
)

@Serializable
private data class WgerPage(
    val next: String? = null,
    val results: List<WgerExerciseInfo> = emptyList(),
)

@Serializable
private data class WgerExerciseInfo(
    val id: Int,
    val translations: List<WgerTranslation> = emptyList(),
    val muscles: List<WgerMuscle> = emptyList(),
    @SerialName("muscles_secondary") val musclesSecondary: List<WgerMuscle> = emptyList(),
    val equipment: List<WgerNamed> = emptyList(),
    val images: List<WgerImage> = emptyList(),
    val category: WgerNamed? = null,
    val license: WgerLicense? = null,
    @SerialName("license_author") val licenseAuthor: String? = null,
)

@Serializable
private data class WgerTranslation(
    val language: Int? = null,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
private data class WgerMuscle(
    val name: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
)

@Serializable
private data class WgerNamed(
    val name: String? = null,
)

@Serializable
private data class WgerImage(
    val image: String? = null,
    @SerialName("is_main") val isMain: Boolean = false,
)

@Serializable
private data class WgerLicense(
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("short_name") val shortName: String? = null,
)

/**
 * Imports the bundled free-exercise-db catalogue and optionally merges wger rows.
 */
class ExerciseImporter(private val database: VitruvianDatabase) {
    private val queries = database.vitruvianDatabaseQueries

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun importExercises(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Logger.d { "Starting exercise import from bundled free-exercise-db JSON..." }
            val jsonBytes = Res.readBytes("files/exercises.json")
            importFromFreeExerciseJson(jsonBytes.decodeToString())
        } catch (e: Exception) {
            Logger.e(e) { "Failed to import exercises from bundled JSON" }
            Result.failure(e)
        }
    }

    suspend fun importFromFreeExerciseJson(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val exercises = json.decodeFromString<List<FreeExerciseJson>>(jsonString)
            val displayNames = generateDisplayNames(exercises)
            Logger.d { "Parsed ${exercises.size} exercises from free-exercise-db" }

            var importedCount = 0
            var imageCount = 0

            queries.transaction {
                for (exercise in exercises) {
                    try {
                        val muscleNames = (exercise.primaryMuscles + exercise.secondaryMuscles)
                            .map { mapMuscleGroup(it) }
                            .distinct()
                        val primaryMuscle = muscleNames.firstOrNull() ?: "Other"
                        val equipmentLabel = (exercise.equipment ?: "").trim()
                        val isBodyweight = equipmentLabel.isEmpty() ||
                            equipmentLabel.equals("body only", ignoreCase = true)

                        queries.insertExercise(
                            id = exercise.id,
                            name = exercise.name.trim(),
                            displayName = displayNames[exercise.id],
                            description = exercise.instructions.joinToString("\n").ifBlank { null },
                            created = 0L,
                            muscleGroup = primaryMuscle,
                            muscleGroups = muscleNames.joinToString(","),
                            muscles = (exercise.primaryMuscles + exercise.secondaryMuscles)
                                .joinToString(",")
                                .ifBlank { null },
                            equipment = equipmentLabel,
                            movement = exercise.category,
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
                            defaultCableConfig = "EITHER",
                            one_rep_max_kg = null,
                            mvtOverrideMs = null,
                            isBodyweight = if (isBodyweight) 1L else 0L,
                        )
                        importedCount++

                        queries.deleteImagesForExercise(exercise.id)
                        exercise.images.forEachIndexed { index, path ->
                            val url = if (path.startsWith("http://") || path.startsWith("https://")) {
                                path
                            } else {
                                "$FREE_EXERCISE_IMAGE_BASE$path"
                            }
                            queries.insertImage(
                                exerciseId = exercise.id,
                                url = url,
                                sortOrder = index.toLong(),
                            )
                            imageCount++
                        }
                    } catch (e: Exception) {
                        Logger.w { "Failed to import exercise ${exercise.name}: ${e.message}" }
                    }
                }
            }

            Logger.d { "Successfully imported $importedCount exercises with $imageCount images" }
            Result.success(importedCount)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to parse free-exercise-db JSON" }
            Result.failure(e)
        }
    }

    /**
     * Optional refresh from wger. Inserts only `wger_<id>` rows; never overwrites
     * bundled free-exercise-db catalogue entries.
     */
    suspend fun updateFromWger(): Result<Int> = withContext(Dispatchers.IO) {
        val client = HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
        }

        try {
            Logger.d { "Fetching exercise catalogue from wger..." }
            var nextUrl: String? = WGER_EXERCISE_INFO_URL
            var inserted = 0

            while (nextUrl != null) {
                val response: HttpResponse = client.get(nextUrl)
                if (response.status.value !in 200..299) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch wger exercises: HTTP ${response.status.value}"),
                    )
                }
                val page = json.decodeFromString<WgerPage>(response.bodyAsText())
                queries.transaction {
                    for (info in page.results) {
                        val translation = info.translations.firstOrNull { it.language == WGER_ENGLISH_LANGUAGE }
                            ?: info.translations.firstOrNull()
                        val name = translation?.name?.trim().orEmpty()
                        if (name.isEmpty()) continue

                        val id = "wger_${info.id}"
                        if (queries.selectExerciseById(id).executeAsOneOrNull() != null) {
                            continue
                        }

                        val muscleNames = (info.muscles + info.musclesSecondary)
                            .mapNotNull { it.nameEn ?: it.name }
                            .map { mapMuscleGroup(it) }
                            .distinct()
                        val equipmentLabel = info.equipment.mapNotNull { it.name }
                            .joinToString(",") { it.lowercase() }
                        val isBodyweight = equipmentLabel.isBlank() ||
                            equipmentLabel.contains("bodyweight") ||
                            equipmentLabel.contains("body only")
                        val licenseName = info.license?.shortName ?: info.license?.fullName ?: "CC-BY-SA 4.0"
                        val author = info.licenseAuthor?.takeIf { it.isNotBlank() }
                        val attribution = buildString {
                            append("Source: wger — ")
                            append(licenseName)
                            if (author != null) {
                                append(" (")
                                append(author)
                                append(")")
                            }
                        }
                        val description = listOfNotNull(
                            translation?.description?.trim()?.ifBlank { null },
                            attribution,
                        ).joinToString("\n\n")

                        queries.insertExercise(
                            id = id,
                            name = name,
                            displayName = name,
                            description = description,
                            created = 0L,
                            muscleGroup = muscleNames.firstOrNull() ?: "Other",
                            muscleGroups = muscleNames.joinToString(","),
                            muscles = (info.muscles + info.musclesSecondary)
                                .mapNotNull { it.nameEn ?: it.name }
                                .joinToString(",")
                                .ifBlank { null },
                            equipment = equipmentLabel,
                            movement = info.category?.name,
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
                            defaultCableConfig = "EITHER",
                            one_rep_max_kg = null,
                            mvtOverrideMs = null,
                            isBodyweight = if (isBodyweight) 1L else 0L,
                        )
                        info.images
                            .sortedByDescending { it.isMain }
                            .mapNotNull { it.image }
                            .forEachIndexed { index, url ->
                                queries.insertImage(
                                    exerciseId = id,
                                    url = url,
                                    sortOrder = index.toLong(),
                                )
                            }
                        inserted++
                    }
                }
                nextUrl = page.next
            }

            Logger.d { "Inserted $inserted wger exercises" }
            Result.success(inserted)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to update from wger: ${e.message}" }
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    private fun generateDisplayNames(exercises: List<FreeExerciseJson>): Map<String, String> {
        val grouped = exercises.groupBy { it.name.lowercase().trim() }
        return exercises.associate { exercise ->
            val siblings = grouped[exercise.name.lowercase().trim()] ?: listOf(exercise)
            val displayName = if (siblings.size > 1) {
                val equipment = exercise.equipment?.trim().orEmpty()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                if (equipment.isNotEmpty()) {
                    "${exercise.name.trim()} ($equipment)"
                } else {
                    exercise.name.trim()
                }
            } else {
                exercise.name.trim()
            }
            exercise.id to displayName
        }
    }

    companion object {
        const val BUNDLED_CATALOG_SOURCE = "free-exercise-db@unlicense-1"
        const val FREE_EXERCISE_IMAGE_BASE =
            "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"
        private const val WGER_EXERCISE_INFO_URL =
            "https://wger.de/api/v2/exerciseinfo/?language=2&limit=100"
        private const val WGER_ENGLISH_LANGUAGE = 2

        internal fun mapMuscleGroup(raw: String): String = when (raw.trim().lowercase()) {
            "abdominals", "abs", "core" -> "Core"
            "chest" -> "Chest"
            "lats", "middle back", "lower back", "traps", "back" -> "Back"
            "shoulders", "neck", "deltoids" -> "Shoulders"
            "biceps", "triceps", "forearms", "arms" -> "Arms"
            "quadriceps", "hamstrings", "calves", "adductors", "abductors", "legs" -> "Legs"
            "glutes", "gluteus" -> "Legs"
            else -> raw.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
