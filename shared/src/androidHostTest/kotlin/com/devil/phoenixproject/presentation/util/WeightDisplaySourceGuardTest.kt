package com.devil.phoenixproject.presentation.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests that scan actual source files to prevent WeightDisplayFormatter
 * (or any cable multiplication) from leaking into layers where weight must remain per-cable.
 *
 * These tests verify the ABSENCE of cable multiplication in:
 * 1. Health Integration layer (has its own multiplication)
 * 2. Sync DTOs and adapters (portal multiplies separately)
 * 3. BLE command layer (machine expects per-cable values)
 *
 * Located in androidHostTest because source file scanning requires java.io.File,
 * which is not available in commonTest.
 */
class WeightDisplaySourceGuardTest {

    private val projectRoot: File by lazy {
        // Navigate from test class output to project root
        // androidHostTest runs from the project directory; find shared/src/commonMain
        var dir = File(System.getProperty("user.dir") ?: ".")
        // Walk up until we find the shared module marker
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val commonMainRoot: File
        get() = File(projectRoot, "shared/src/commonMain/kotlin")

    private fun readSourceFile(relativePath: String): String? {
        val file = File(commonMainRoot, relativePath)
        return if (file.exists()) file.readText() else null
    }

    private fun assertNoFormatterImport(source: String, fileName: String) {
        val importPattern = "import.*WeightDisplayFormatter"
        val hasImport = Regex(importPattern).containsMatchIn(source)
        assertFalse(
            hasImport,
            "GUARD VIOLATION: $fileName imports WeightDisplayFormatter. " +
                "This layer handles cable multiplication independently. " +
                "Adding WeightDisplayFormatter would cause double-multiplication.",
        )
    }

    // ===== Guard: Health Integration must NOT import WeightDisplayFormatter =====

    @Test
    fun healthIntegration_android_doesNotUseWeightDisplayFormatter() {
        val path = "com/devil/phoenixproject/data/integration/HealthIntegration.android.kt"
        // Check both commonMain and androidMain — actual file lives in androidMain
        val commonSource = readSourceFile(path)
        val androidRoot = File(projectRoot, "shared/src/androidMain/kotlin")
        val androidSource = File(androidRoot, path).let { if (it.exists()) it.readText() else null }

        if (commonSource != null) {
            assertNoFormatterImport(commonSource, "HealthIntegration.android.kt (commonMain)")
        }
        if (androidSource != null) {
            assertNoFormatterImport(androidSource, "HealthIntegration.android.kt (androidMain)")
        }
        if (commonSource == null && androidSource == null) {
            assertTrue(true, "HealthIntegration.android.kt not found; guard passes")
        }
    }

    @Test
    fun healthIntegration_ios_doesNotUseWeightDisplayFormatter() {
        // Check both commonMain and iosMain for the iOS implementation
        val commonPath = "com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt"
        val iosRoot = File(projectRoot, "shared/src/iosMain/kotlin")
        val iosPath = "com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt"

        val commonSource = readSourceFile(commonPath)
        val iosSource = File(iosRoot, iosPath).let { if (it.exists()) it.readText() else null }

        if (commonSource != null) {
            assertNoFormatterImport(commonSource, "HealthIntegration.ios.kt (commonMain)")
        }
        if (iosSource != null) {
            assertNoFormatterImport(iosSource, "HealthIntegration.ios.kt (iosMain)")
        }
        if (commonSource == null && iosSource == null) {
            assertTrue(true, "HealthIntegration.ios.kt not found; guard passes")
        }
    }

    // ===== Guard: Sync DTOs must stay per-cable =====

    @Test
    fun syncModels_doesNotUseWeightDisplayFormatter() {
        val path = "com/devil/phoenixproject/data/sync/SyncModels.kt"
        val source = readSourceFile(path)
        if (source != null) {
            assertNoFormatterImport(source, "SyncModels.kt")
        } else {
            assertTrue(true, "SyncModels.kt not found; guard passes")
        }
    }

    @Test
    fun syncAdapter_doesNotImportWeightDisplayFormatter() {
        // Scan all files in data/sync/ for formatter imports
        val syncDir = File(commonMainRoot, "com/devil/phoenixproject/data/sync")
        if (syncDir.exists()) {
            val violations = syncDir.listFiles()
                ?.filter { it.extension == "kt" }
                ?.filter { file ->
                    val content = file.readText()
                    Regex("import.*WeightDisplayFormatter").containsMatchIn(content)
                }
                ?.map { it.name }
                ?: emptyList()

            assertTrue(
                violations.isEmpty(),
                "GUARD VIOLATION: Files in data/sync/ import WeightDisplayFormatter: $violations. " +
                    "Sync layer sends per-cable values; portal multiplies separately.",
            )
        }
    }

    @Test
    fun syncDto_weightField_isNamedPerCable() {
        // Verify that the sync DTO keeps "PerCable" naming convention for weight fields
        val path = "com/devil/phoenixproject/data/sync/SyncModels.kt"
        val source = readSourceFile(path)
        if (source != null) {
            // Check that weight fields in DTOs use per-cable naming
            val weightFieldPattern = Regex("""val\s+weight\w*Kg\s*:""")
            val matches = weightFieldPattern.findAll(source)
            for (match in matches) {
                val fieldDecl = match.value
                // Allow "weightPerCableKg" and "weightKg" (the DTO field maps to per-cable)
                // Reject "totalWeightKg" which would signal someone changed the contract
                assertFalse(
                    fieldDecl.contains("totalWeight", ignoreCase = true),
                    "GUARD VIOLATION: SyncModels.kt contains a 'totalWeight' field: '$fieldDecl'. " +
                        "Sync DTOs must use per-cable weight values.",
                )
            }
        }
    }

    // ===== Guard: BLE command layer must stay per-cable =====

    @Test
    fun bleLayer_doesNotImportWeightDisplayFormatter() {
        // Scan all files in data/ble/ for formatter imports
        val bleDir = File(commonMainRoot, "com/devil/phoenixproject/data/ble")
        if (bleDir.exists()) {
            val violations = mutableListOf<String>()
            bleDir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    val content = file.readText()
                    if (Regex("import.*WeightDisplayFormatter").containsMatchIn(content)) {
                        violations.add(file.name)
                    }
                }

            assertTrue(
                violations.isEmpty(),
                "GUARD VIOLATION: Files in data/ble/ import WeightDisplayFormatter: $violations. " +
                    "BLE commands use per-cable weight values for machine firmware.",
            )
        }
    }

    @Test
    fun workoutHud_primaryDisplaysStayPerCable() {
        val path = "com/devil/phoenixproject/presentation/screen/WorkoutHud.kt"
        val source = readSourceFile(path)
        if (source != null) {
            assertFalse(
                source.contains("WeightDisplayFormatter"),
                "GUARD VIOLATION: WorkoutHud.kt must not use WeightDisplayFormatter. " +
                    "HUD primary weight display should format already-selected per-cable values directly.",
            )
            assertFalse(
                source.contains("workoutParameters.weightPerCableKg * liveDisplayMultiplier"),
                "GUARD VIOLATION: WorkoutHud.kt selected load display must stay per-cable.",
            )
            assertFalse(
                source.contains("perCableKg * liveDisplayMultiplier"),
                "GUARD VIOLATION: WorkoutHud.kt live force display must stay per-cable.",
            )
            assertFalse(
                source.contains("""subLabel = if (liveDisplayMultiplier == 2) "TOTAL" else "PER CABLE""""),
                "GUARD VIOLATION: WorkoutHud.kt primary force display should always be labeled per-cable.",
            )
            assertTrue(
                source.contains("formatWeight(selectedDisplayKg, weightUnit)"),
                "WorkoutHud.kt should format selectedDisplayKg directly for target weight display.",
            )
            assertTrue(
                source.contains("formatWeight(perCableKg, weightUnit)"),
                "WorkoutHud.kt should format perCableKg directly for live force display.",
            )
        } else {
            assertTrue(true, "WorkoutHud.kt not found; guard passes")
        }
    }

    @Test
    fun savedSessionPrimaryDisplays_doNotMultiplyByDisplayLoadMultiplier() {
        val primaryDisplayFiles = listOf(
            "com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt",
            "com/devil/phoenixproject/presentation/screen/HistoryTab.kt",
            "com/devil/phoenixproject/presentation/screen/HomeScreen.kt",
            "com/devil/phoenixproject/presentation/components/InsightCards.kt",
        )
        val forbiddenSnippets = listOf(
            "weightPerCableKg * session.displayLoadMultiplier()",
            "session.weightPerCableKg * session.displayLoadMultiplier()",
            "effectiveHeaviestKgPerCable() * session.displayLoadMultiplier().toFloat()",
            "WeightDisplayFormatter.formatDisplayWeight(session.weightPerCableKg, session.displayLoadMultiplier()",
            "WeightDisplayFormatter.formatDisplayWeight(session.weightPerCableKg, session.displayMultiplier ?: session.cableCount",
        )

        val violations = primaryDisplayFiles.flatMap { relativePath ->
            val source = readSourceFile(relativePath).orEmpty()
            forbiddenSnippets
                .filter { forbidden -> source.contains(forbidden) }
                .map { forbidden -> "$relativePath contains '$forbidden'" }
        }

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Saved-session primary display must stay per-cable. Violations: $violations",
        )
    }

    @Test
    fun ordinaryDisplay_doesNotAcceptCableCount() {
        val formatter = readSourceFile("com/devil/phoenixproject/presentation/util/WeightDisplayFormatter.kt")
            ?: error("WeightDisplayFormatter.kt not found")
        assertFalse(
            formatter.contains("fun toDisplayWeight(weightPerCableKg: Float, cableCount"),
            "Ordinary toDisplayWeight must not take cableCount",
        )
        assertFalse(
            formatter.contains("fun formatDisplayWeight(weightPerCableKg: Float, cableCount"),
            "Ordinary formatDisplayWeight must not take cableCount",
        )
        assertFalse(formatter.contains("@Suppress(\"UNUSED_PARAMETER\")"))
        assertTrue(formatter.contains("fun toDisplayWeight(weightPerCableKg: Float, unit: WeightUnit)"))
        assertTrue(formatter.contains("fun formatDisplayWeight(weightPerCableKg: Float, unit: WeightUnit)"))

        val callSites = listOf(
            "com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt",
            "com/devil/phoenixproject/presentation/screen/HistoryTab.kt",
            "com/devil/phoenixproject/presentation/screen/HomeScreen.kt",
            "com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
            "com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt",
            "com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt",
            "com/devil/phoenixproject/presentation/components/InsightCards.kt",
            "com/devil/phoenixproject/presentation/util/SmartInsightsWeightText.kt",
        )
        val cableArg = Regex("""(?:formatDisplayWeight|toDisplayWeight)\([^)]*cableCount""")
        val violations = callSites.mapNotNull { path ->
            val source = readSourceFile(path) ?: return@mapNotNull "$path missing"
            if (cableArg.containsMatchIn(source)) path else null
        }
        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Ordinary load text must not take cableCount. Violations: $violations",
        )
    }

    @Test
    fun smartInsightsSurfaces_doNotHardcodeKilogramDisplay() {
        val files = listOf(
            "com/devil/phoenixproject/presentation/screen/SmartInsightsTab.kt",
            "com/devil/phoenixproject/presentation/components/ReadinessBriefingCard.kt",
        )
        val hardcodedKg = Regex("""(?i)(total_kg|\}kg|"\s*kg")""")
        val violations = files.mapNotNull { path ->
            val source = readSourceFile(path) ?: return@mapNotNull "$path missing"
            val problems = mutableListOf<String>()
            if (!source.contains("SmartInsightsWeightText")) {
                problems.add("does not format through SmartInsightsWeightText")
            }
            if (hardcodedKg.containsMatchIn(source)) {
                problems.add("hard-codes a kg display")
            }
            if (problems.isEmpty()) null else "$path: ${problems.joinToString()}"
        }
        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Smart Insights weight text must follow the profile unit. $violations",
        )
    }

    @Test
    fun insightCards_convertPoundsThroughUnitConverter() {
        val source = readSourceFile("com/devil/phoenixproject/presentation/components/InsightCards.kt")
            ?: error("InsightCards.kt not found")
        assertFalse(
            source.contains("2.20462"),
            "GUARD VIOLATION: InsightCards must not multiply by a raw lb factor",
        )
        assertTrue(
            source.contains("UnitConverter.kgToLb"),
            "InsightCards pound conversion should go through UnitConverter.kgToLb",
        )
    }

    @Test
    fun setSummary_primaryWeightLabelsStayPerCable() {
        val path = "com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt"
        val source = readSourceFile(path)
        // A literal '$' is built via [dollar] so these strings stay source literals rather
        // than Kotlin string templates.
        val dollar = '$'
        if (source != null) {
            assertFalse(
                source.contains("unit = \"(${dollar}unitLabel total)\""),
                "GUARD VIOLATION: Set Summary set weight is per-cable and must not be labeled total.",
            )
            assertFalse(
                source.contains("\"${dollar}unitLabel total\""),
                "GUARD VIOLATION: Echo phase weights are per-cable and must not be labeled total.",
            )
        } else {
            assertTrue(true, "SetSummaryCard.kt not found; guard passes")
        }

        // The per-cable label now lives with the extracted presentation code (issue #1182),
        // so the literal is asserted where it is actually rendered from.
        val presentation = readSourceFile("com/devil/phoenixproject/presentation/util/AchievedLoadPresentation.kt")
            ?: error("AchievedLoadPresentation.kt not found")
        assertTrue(
            presentation.contains("\"(${dollar}unitLabel/cable)\""),
            "AchievedLoadPresentation should render the set weight labeled per-cable.",
        )
        assertTrue(
            presentation.contains("(${dollar}unitLabel total)").not(),
            "GUARD VIOLATION: AchievedLoadPresentation must not label a per-cable load as total.",
        )
    }

    /**
     * Issue #1182 (merge-gate R5c): the four reporting surfaces must render through
     * [AchievedLoadPresentation], which is the code covered by AchievedLoadPresentationTest.
     * Without this binding a surface could drift back to inline formatting and the
     * presentation tests would stop describing what the user sees.
     */
    @Test
    fun reportingSurfaces_renderThroughAchievedLoadPresentation() {
        val expectations = mapOf(
            "com/devil/phoenixproject/presentation/components/ExerciseQuickHistoryCard.kt" to
                "AchievedLoadPresentation.sessionLoadText(",
            "com/devil/phoenixproject/presentation/screen/HomeScreen.kt" to
                "AchievedLoadPresentation.homeRecentActivityLine(",
            "com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt" to
                "AchievedLoadPresentation.setSummaryPrimary(",
            "com/devil/phoenixproject/presentation/screen/HistoryTab.kt" to
                "AchievedLoadPresentation.historySetText(",
        )
        val violations = expectations.mapNotNull { (path, requiredCall) ->
            val source = readSourceFile(path)
                ?: return@mapNotNull "$path missing"
            if (!source.contains(requiredCall)) "$path does not call $requiredCall" else null
        }
        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: reporting surfaces must render through AchievedLoadPresentation. $violations",
        )
    }

    // ===== Guard: CSV export has its own multiplication =====

    @Test
    fun csvExport_doesNotImportWeightDisplayFormatter() {
        // Scan data/integration/ for formatter imports (CsvExporter lives here)
        val integrationDir = File(commonMainRoot, "com/devil/phoenixproject/data/integration")
        if (integrationDir.exists()) {
            val violations = integrationDir.listFiles()
                ?.filter { it.extension == "kt" }
                ?.filter { file ->
                    val content = file.readText()
                    Regex("import.*WeightDisplayFormatter").containsMatchIn(content)
                }
                ?.map { it.name }
                ?: emptyList()

            assertTrue(
                violations.isEmpty(),
                "GUARD VIOLATION: Files in data/integration/ import WeightDisplayFormatter: $violations. " +
                    "CsvExporter has its own WEIGHT_MULTIPLIER constant for export formatting.",
            )
        }
    }
}
