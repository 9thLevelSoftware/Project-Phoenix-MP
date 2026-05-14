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

    // ===== Guard: WeightDisplayFormatter must stay in presentation layer =====

    @Test
    fun weightDisplayFormatter_isInPresentationPackage() {
        val formatterPackage = WeightDisplayFormatter::class.qualifiedName ?: ""
        assertTrue(
            formatterPackage.contains("presentation"),
            "WeightDisplayFormatter must remain in the presentation layer. " +
                "Current: '$formatterPackage'. " +
                "Cable multiplication for display is a presentation concern only.",
        )
    }

    @Test
    fun workoutHud_liveForceDisplayRemainsPerCable() {
        val path = "com/devil/phoenixproject/presentation/screen/WorkoutHud.kt"
        val source = readSourceFile(path)
        if (source != null) {
            assertFalse(
                source.contains("WeightDisplayFormatter"),
                "GUARD VIOLATION: WorkoutHud.kt must not use WeightDisplayFormatter. " +
                    "The live HUD displays selected and measured force per cable; " +
                    "total-weight formatting belongs only on explicitly labeled total fields.",
            )
            assertFalse(
                source.contains("""subLabel = "TOTAL""""),
                "GUARD VIOLATION: WorkoutHud.kt labels live force as TOTAL. " +
                    "Official live force displays are per-cable.",
            )
            assertTrue(
                source.contains("""subLabel = "PER CABLE""""),
                "WorkoutHud.kt should label the circular force gauge as PER CABLE.",
            )
            assertTrue(
                source.contains("formatWeight(perCableKg, weightUnit)"),
                "WorkoutHud.kt should format measured live force directly from perCableKg.",
            )
            assertTrue(
                source.contains("formatWeight(workoutParameters.weightPerCableKg, weightUnit)"),
                "WorkoutHud.kt should format selected live weight directly from weightPerCableKg.",
            )
        } else {
            assertTrue(true, "WorkoutHud.kt not found; guard passes")
        }
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
