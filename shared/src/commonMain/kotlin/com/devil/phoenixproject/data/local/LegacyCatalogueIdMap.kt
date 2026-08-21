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
        "b5d0f3d1-994b-4589-9d2b-b3f36f1412c7" to "Barbell_Bench_Press_-_Medium_Grip",
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
        "rC0baJJTFuQdbwng" to "Bent_Over_Barbell_Row",
        "67195cbd-7e2b-4d96-804b-0182b8bf2bab" to "Bent_Over_Barbell_Row",
        "31ea9d9e-669c-4752-9f44-80c3fa864021" to "Bent_Over_Barbell_Row",
        "BRd2HV_4zo1M5V1e" to "Reverse_Grip_Bent-Over_Rows",
        "aea28a4b-d442-4bba-8c64-1e1780d243dd" to "Reverse_Grip_Bent-Over_Rows",
        "2b09f28a-b765-4aab-90ec-8c14318d63eb" to "Bent_Over_Two-Dumbbell_Row",
        "4e3de525-1be3-4b1c-a95c-07e74697f7b4" to "Bent_Over_Two-Dumbbell_Row",
        "b2fb6fcd-3f47-403d-bad1-0f5e3f62d048" to "Barbell_Curl",
        "fc5ca114-3cb6-462a-ab54-7ef1233b5fc2" to "Barbell_Curl",
        "2R1uXL45E-ZnUYYx" to "One_Leg_Barbell_Squat",
        "ePpAzISZjVFNQfSM" to "One_Leg_Barbell_Squat",
        "7tufZzs2Sq8JFCdw" to "One_Leg_Barbell_Squat",
        "VY_wFggrTF1Mg-PR" to "Standing_Calf_Raises",
        "e74RvoLRZs5arglE" to "Standing_Calf_Raises",
        "SfPmFe9Tm9CVQ9hC" to "Standing_Calf_Raises",
        "957be087-320a-4e24-ac49-daef883cc6f9" to "Standing_Calf_Raises",
        "287a9e39-fd0f-426f-bac1-2b121befb397" to "Standing_Calf_Raises",
        "66ggji_PJQIsQbS0" to "Crunches",
        "cGKByiAR77dtK3lu" to "Front_Barbell_Squat",
        "b254f76e-3109-4db3-bae7-63ea400e63f1" to "Front_Barbell_Squat",
        "HFrc4ELZxjSKX8PW" to "Good_Morning",
        "466aeaab-97f1-4a25-804e-67a87a9e5e75" to "Good_Morning",
        "77f8d4e5-d97c-43ac-b4fc-d5ff35f67f8d" to "Hammer_Curls",
        "99dda2a6-96fa-4d82-970d-58b69425d4db" to "Barbell_Incline_Bench_Press_-_Medium_Grip",
        "IRJrP1HLEvLeAYwt" to "Barbell_Incline_Bench_Press_-_Medium_Grip",
        "8a1690db-cb4f-4e0a-9f66-425e0d5fc3c3" to "Barbell_Incline_Bench_Press_-_Medium_Grip",
        "Y030qNm3LisddglM" to "Side_Lateral_Raise",
        "qfeYOQWqoOIAw_0H" to "Side_Lateral_Raise",
        "rIjnJFQUK3mDbwBW" to "Side_Lateral_Raise",
        "cJkTi7rZz0DmvcIF" to "Lying_Leg_Curls",
        "K-YedLFEl0jIZ0Oy" to "Leg_Extensions",
        "yEp_qL0Rdlu8lURJ" to "Leg_Extensions",
        "Xcaj16Dwf3CFZp7G" to "Leg_Extensions",
        "128a2325-bee2-46e5-9124-1d2f2f44f44c" to "Leg_Extensions",
        "L3m5K_4X7ztA2yXV" to "Standing_Overhead_Barbell_Triceps_Extension",
        "RASpI84AvAi3ucvr" to "Standing_Overhead_Barbell_Triceps_Extension",
        "e3c59976-efa7-4e7d-8848-77e864ba1d0f" to "Standing_Overhead_Barbell_Triceps_Extension",
        "bf1437e9-f8ca-4bfe-b766-af5fee1834a5" to "Standing_Overhead_Barbell_Triceps_Extension",
        "1c4f037a-1cab-4133-b72a-458be3e7018d" to "Romanian_Deadlift",
        "rMYzHKGP3u9agdih" to "Romanian_Deadlift",
        "49c4594c-f997-4d70-8008-b293e247e2e4" to "Romanian_Deadlift",
        "68c3b06f-1767-44fd-9676-e1a4d1c7699b" to "Barbell_Shoulder_Press",
        "149484fe-3baa-4601-97c5-8d273a6b455a" to "Barbell_Shrug",
        "e1e6eda5-42e7-45ee-82e6-ce97746ae0ca" to "Barbell_Shrug",
        "NTtSMyHmJmTE7iQ5" to "Lying_Triceps_Press",
        "vI7FQn2KAPJxBS-n" to "Lying_Triceps_Press",
        "a1f4e3d7-8ab4-442d-ba06-8dfd9c11e248" to "Lying_Triceps_Press",
        "6hCTlg47HzDa8KMH" to "Lying_Triceps_Press",
        "d99a9738-3828-42a6-815e-459c1f825ff9" to "Lying_Triceps_Press",
        "lpnNPw86Vud67vWQ" to "Barbell_Squat",
        "6Q9E-cUbwO7mhAbG" to "Upright_Barbell_Row",
        "dolYIaKI1o1wn_Oh" to "Upright_Barbell_Row",
        "7d141cfb-fdde-4320-8553-1e03f6b4e9bd" to "Upright_Barbell_Row",
        "JgArpiAO2lQCWput" to "Windmills",
    )

    /**
     * Lowercased display-name aliases for stock rows whose replacement name is a
     * reviewed singular/plural rename rather than an exact match.
     */
    val nameAliases: Map<String, String> = mapOf(
        "arnold press" to "arnold dumbbell press",
        "bench press" to "barbell bench press - medium grip",
        "bench press - wide grip" to "wide-grip barbell bench press",
        "bent over row" to "bent over barbell row",
        "bent over row - reverse grip" to "reverse grip bent-over rows",
        "bent over row - wide grip" to "bent over two-dumbbell row",
        "bicep curl" to "barbell curl",
        "bulgarian split squat" to "one leg barbell squat",
        "calf raise" to "standing calf raises",
        "concentration curl" to "concentration curls",
        "conventional deadlift" to "barbell deadlift",
        "crunch" to "crunches",
        "face pulls" to "face pull",
        "front squat" to "front barbell squat",
        "glute kickbacks" to "glute kickback",
        "hammer curl" to "hammer curls",
        "incline bench press" to "barbell incline bench press - medium grip",
        "lateral raise" to "side lateral raise",
        "lunge" to "barbell lunge",
        "lying hamstring curl" to "lying leg curls",
        "lying leg extension" to "leg extensions",
        "mountain climber" to "mountain climbers",
        "overhead tricep extension" to "standing overhead barbell triceps extension",
        "rack pull" to "rack pulls",
        "shoulder press" to "barbell shoulder press",
        "shrug" to "barbell shrug",
        "skull crusher" to "lying triceps press",
        "squat" to "barbell squat",
        "upright row" to "upright barbell row",
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
