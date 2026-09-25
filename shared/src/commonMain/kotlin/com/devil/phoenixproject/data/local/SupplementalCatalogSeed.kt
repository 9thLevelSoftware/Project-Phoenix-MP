package com.devil.phoenixproject.data.local

/**
 * Issue #883: supplemental stock rows the bundled free-exercise-db asset cannot supply.
 *
 * PR #706 swapped the retired Vitruvian exercise dump (which carried 22 BELT-equipped rows)
 * for the free-exercise-db catalogue, whose equipment vocabulary has no belt at all, so the
 * picker's Belt chip filtered zero stock rows and "Belt Squat" was undiscoverable. These rows
 * restore an active, stable-ID, BELT-equipped belt-squat family through the normal
 * [ExerciseImporter] seed path (deliberately not the naming-only CATALOG_OVERLAY).
 *
 * Provenance / licensing: original content authored for Project Phoenix. It is NOT copied
 * from the free-exercise-db catalogue (Unlicense) or the retired proprietary Vitruvian
 * exercise dump, and no proprietary legacy text, media, or IDs are republished here. The
 * stable IDs are new (never previously used by any shipped catalogue) because re-inserting
 * an archived legacy ID cannot resurrect it: `insertExerciseIfAbsent` is INSERT OR IGNORE and
 * `updateCatalogExercise` never touches `archived`.
 *
 * Cross-repo contract (architecture review condition A for #883): these IDs are shared with
 * the portal `exercise_catalog`, which must carry the same IDs with BELT equipment before the
 * mobile release lands — otherwise mobile-sync-push resolves the id to NULL and re-breaks
 * issue #404 exercise identity.
 */
object SupplementalCatalogSeed {
    const val BELT_SQUAT_ID = "Belt_Squat"
    const val SUMO_BELT_SQUAT_ID = "Sumo_Belt_Squat"
    const val BELT_SQUAT_PULSES_ID = "Belt_Squat_Pulses"

    val rows: List<FreeExerciseJson> = listOf(
        FreeExerciseJson(
            id = BELT_SQUAT_ID,
            name = "Belt Squat",
            force = "push",
            level = "beginner",
            mechanic = "compound",
            equipment = "belt",
            primaryMuscles = listOf("quadriceps"),
            secondaryMuscles = listOf("glutes", "hamstrings"),
            instructions = listOf(
                "Fasten a squat belt around your waist and clip it to the low pulley or belt squat platform.",
                "Stand tall with feet about shoulder-width apart, brace your core, and squat down until your thighs are at least parallel to the floor.",
                "Drive through your midfoot to stand back up, keeping your chest up and your knees tracking over your toes.",
            ),
            category = "strength",
            images = emptyList(),
        ),
        FreeExerciseJson(
            id = SUMO_BELT_SQUAT_ID,
            name = "Sumo Belt Squat",
            force = "push",
            level = "intermediate",
            mechanic = "compound",
            equipment = "belt",
            primaryMuscles = listOf("quadriceps"),
            secondaryMuscles = listOf("glutes", "adductors", "hamstrings"),
            instructions = listOf(
                "Fasten a squat belt around your waist and clip it to the low pulley or belt squat platform.",
                "Take a wide stance with your toes turned out and sit down between your hips until your thighs are at least parallel to the floor.",
                "Push the floor apart with your feet and stand back up, keeping your torso tall and knees tracking over your toes.",
            ),
            category = "strength",
            images = emptyList(),
        ),
        FreeExerciseJson(
            id = BELT_SQUAT_PULSES_ID,
            name = "Belt Squat Pulses",
            force = "push",
            level = "intermediate",
            mechanic = "compound",
            equipment = "belt",
            primaryMuscles = listOf("quadriceps"),
            secondaryMuscles = listOf("glutes", "hamstrings"),
            instructions = listOf(
                "Fasten a squat belt around your waist and clip it to the low pulley or belt squat platform.",
                "Squat down to about knee depth and stay there, pulsing a few inches up and down under control.",
                "Keep your core braced and your knees tracking over your toes for the whole set, then stand up to finish.",
            ),
            category = "strength",
            images = emptyList(),
        ),
    )
}
