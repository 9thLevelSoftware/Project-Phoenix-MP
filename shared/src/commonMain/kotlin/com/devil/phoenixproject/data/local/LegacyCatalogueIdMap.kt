package com.devil.phoenixproject.data.local

/**
 * Maps archived Vitruvian catalogue IDs onto the replacement free-exercise-db slugs
 * used by cycle templates and 5/3/1 detection. Name matching covers remaining stock
 * rows whose names are unchanged; [nameAliases] and [stemKey] cover reviewed
 * singular/plural renames such as Rack Pull → Rack Pulls.
 */
object LegacyCatalogueIdMap {
    val explicit: Map<String, String> = mapOf(
        "UjIGHxCav-lS9B2I" to "Barbell_Squat",
        "ZZ92N8QsBdp6HCh3" to "Barbell_Bench_Press_-_Medium_Grip",
        "cJt26IdtckFcJsq1" to "Bent_Over_Barbell_Row",
        "0040d53f-85c7-4564-b14e-9b38c979b461" to "Barbell_Shoulder_Press",
        "k-PGXPztgc5uS42S" to "Barbell_Curl",
        "j3Y1MpvaeGPy0o99" to "Standing_Calf_Raises",
        "e64c7837-52e2-4b97-b771-cf08ab861af1" to "Barbell_Deadlift",
        "aR4mXWcgsqNaxw4C" to "Barbell_Incline_Bench_Press_-_Medium_Grip",
        "cc7f2c3a-20bd-4a3e-8d5a-393420386c23" to "Reverse_Grip_Bent-Over_Rows",
        "8WHxwWifeoVP8vLq" to "Side_Lateral_Raise",
        "_i1E704BS8bngWrv" to "Standing_Overhead_Barbell_Triceps_Extension",
        "U9nn8f-vcAltrR-E" to "Plank",
        "rwTxzKiYAl8UENGp" to "Front_Barbell_Squat",
        "IAcN1MX1kIiF9wdo" to "Wide-Grip_Barbell_Bench_Press",
        "513b8b5b-5315-4510-87c6-04df95a51053" to "Upright_Barbell_Row",
        "3cd0a0cb-8e56-4b0e-83a0-d88ff369749f" to "Arnold_Dumbbell_Press",
        "05wiA4Eqtrj388Ui" to "Hammer_Curls",
        "1cAPp9FYOqgEFDfm" to "Barbell_Shrug",
        "lKxWrGuEzVcxLYqG" to "Face_Pull",
        "CZp8oeIT32m1oO8o" to "Face_Pull",
        "WAB_Z7EUGeUxF9ce" to "Romanian_Deadlift",
        "vvG84utDyVrhhcJB" to "Barbell_Lunge",
        "IIglddaLiD3aFW9a" to "Leg_Extensions",
        "-YjRuMgOttzv0yZW" to "One_Leg_Barbell_Squat",
        "xh7phUUawthAuF41" to "Lying_Leg_Curls",
        "cOaTQ1ljsuUom_cn" to "Lying_Triceps_Press",
        "useRdaf9DVqyjBD8" to "Bent_Over_Two-Dumbbell_Row",
        "e280829c-aa17-4812-b8fa-bcd0d89ad815" to "One-Legged_Cable_Kickback",
        "FLyfmJWYyxLus7e8" to "Crunches",
        "enuJ_FgAzXDLAweK" to "Good_Morning",
        "Pa7L7GPGmD7zSNOr" to "Rack_Pulls",
        "wgiwmR1yt3QJtiWs" to "Concentration_Curls",
        "2S9GLUWvISCI0RFC" to "Mountain_Climbers",
        "f9uw1qCFUhNlrPoj" to "Windmills",
        "bGY-qHuQ4SSqd2lB" to "Zercher_Squats",
    )

    /**
     * Lowercased display-name aliases for stock rows whose replacement name is a
     * reviewed singular/plural rename rather than an exact match.
     */
    val nameAliases: Map<String, String> = mapOf(
        "concentration curl" to "concentration curls",
        "crunch" to "crunches",
        "face pulls" to "face pull",
        "glute kickbacks" to "glute kickback",
        "hammer curl" to "hammer curls",
        "mountain climber" to "mountain climbers",
        "rack pull" to "rack pulls",
        "windmill" to "windmills",
        "zercher squat" to "zercher squats",
    )

    fun matchKey(name: String): String =
        name.lowercase().trim().replace(WHITESPACE, " ")

    /**
     * Stems the last word so unique singular/plural pairs share a key
     * (`Rack Pull` / `Rack Pulls` → `rack pull`). Applied only when that key
     * identifies a single active catalogue row.
     */
    fun stemKey(name: String): String {
        val key = matchKey(name)
        val lastSpace = key.lastIndexOf(' ')
        val last = if (lastSpace >= 0) key.substring(lastSpace + 1) else key
        val prefix = if (lastSpace >= 0) key.substring(0, lastSpace + 1) else ""
        val stemmedLast = when {
            last.endsWith("ies") && last.length > 4 -> last.dropLast(3) + "y"
            last.length > 4 && (
                last.endsWith("ches") ||
                    last.endsWith("shes") ||
                    last.endsWith("sses") ||
                    last.endsWith("xes")
                ) -> last.dropLast(2)
            last.endsWith("s") && !last.endsWith("ss") && last.length > 3 -> last.dropLast(1)
            else -> last
        }
        return prefix + stemmedLast
    }

    private val WHITESPACE = Regex("\\s+")
}
