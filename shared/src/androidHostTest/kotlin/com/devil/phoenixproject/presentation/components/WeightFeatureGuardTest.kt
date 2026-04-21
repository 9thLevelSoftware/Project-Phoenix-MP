package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests for Phase 38 weight features: BulkWeightAdjustDialog and
 * effectiveWeightIncrement must NOT leak into BLE, sync, or health-integration layers.
 *
 * These are structural boundary guards that scan source files for forbidden imports
 * and references. They prevent presentation-only weight features from contaminating
 * layers where weight must remain in raw per-cable kilograms.
 *
 * Pattern: Matches WeightDisplaySourceGuardTest from Phase 37.
 * Located in androidHostTest because source file scanning requires java.io.File.
 *
 * Guard boundaries:
 * 1. BLE layer (data/ble/) - machine firmware expects per-cable kg
 * 2. BlePacketFactory (util/) - binary protocol frames, per-cable only
 * 3. Sync layer (data/sync/) - portal multiplies independently
 * 4. Health integration (data/integration/) - has its own multiplier
 * 5. WeightDisplayFormatter - must not reference Phase 38 features
 */
class WeightFeatureGuardTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val commonMainRoot: File
        get() = File(projectRoot, "shared/src/commonMain/kotlin")

    private val androidMainRoot: File
        get() = File(projectRoot, "shared/src/androidMain/kotlin")

    private val iosMainRoot: File
        get() = File(projectRoot, "shared/src/iosMain/kotlin")

    private fun readSourceFile(relativePath: String): String? {
        val file = File(commonMainRoot, relativePath)
        return if (file.exists()) file.readText() else null
    }

    /**
     * Scan all .kt files in a directory tree for any of the given patterns.
     * Returns list of (filename, matched pattern) pairs for violations.
     */
    private fun scanDirectoryForPatterns(
        dir: File,
        patterns: List<Regex>,
    ): List<Pair<String, String>> {
        if (!dir.exists()) return emptyList()
        val violations = mutableListOf<Pair<String, String>>()
        dir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                for (pattern in patterns) {
                    if (pattern.containsMatchIn(content)) {
                        violations.add(file.name to pattern.pattern)
                    }
                }
            }
        return violations
    }

    // Patterns that indicate BulkWeightAdjust leaking into a layer
    private val bulkAdjustPatterns = listOf(
        Regex("import.*BulkWeightAdjust"),
        Regex("import.*applyBulkAdjust"),
        Regex("import.*BulkAdjustMode"),
        Regex("\\bBulkWeightAdjust\\w*"),
        Regex("\\bapplyBulkAdjust\\b"),
        Regex("\\bBulkAdjustMode\\b"),
    )

    // Patterns that indicate effectiveWeightIncrement leaking into a layer
    private val weightIncrementPatterns = listOf(
        Regex("import.*effectiveWeightIncrement"),
        Regex("\\beffectiveWeightIncrement\\b"),
        Regex("\\beffectiveWeightIncrementKg\\b"),
    )

    // ===== BLE Boundary Guards =====

    @Test
    fun bulkAdjustDialog_notReferencedInBleLayer() {
        val bleDir = File(commonMainRoot, "com/devil/phoenixproject/data/ble")
        val violations = scanDirectoryForPatterns(bleDir, bulkAdjustPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: BLE layer references BulkWeightAdjust: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "BLE commands use raw per-cable weight values for machine firmware. " +
                "Bulk adjust is a presentation-layer routine editing feature.",
        )
    }

    @Test
    fun weightIncrement_notReferencedInBleLayer() {
        val bleDir = File(commonMainRoot, "com/devil/phoenixproject/data/ble")
        val violations = scanDirectoryForPatterns(bleDir, weightIncrementPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: BLE layer references effectiveWeightIncrement: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "BLE packet construction must use raw per-cable kg values. " +
                "User-configurable weight increments are a UI preference, not a BLE concern.",
        )
    }

    @Test
    fun blePacketFactory_usesRawPerCableWeight() {
        // BlePacketFactory lives in util/, not data/ble/, but it builds BLE protocol frames
        val factoryPath = "com/devil/phoenixproject/util/BlePacketFactory.kt"
        val source = readSourceFile(factoryPath)
        if (source != null) {
            val allForbiddenPatterns = bulkAdjustPatterns + weightIncrementPatterns
            for (pattern in allForbiddenPatterns) {
                assertFalse(
                    pattern.containsMatchIn(source),
                    "GUARD VIOLATION: BlePacketFactory.kt contains '${pattern.pattern}'. " +
                        "BLE packet factory builds binary frames with raw per-cable kg. " +
                        "User increment preferences and bulk adjust are presentation concerns.",
                )
            }
        } else {
            assertTrue(true, "BlePacketFactory.kt not found; guard passes vacuously")
        }
    }

    // ===== Sync Boundary Guards =====

    @Test
    fun bulkAdjust_notReferencedInSyncModels() {
        val path = "com/devil/phoenixproject/data/sync/SyncModels.kt"
        val source = readSourceFile(path)
        if (source != null) {
            for (pattern in bulkAdjustPatterns) {
                assertFalse(
                    pattern.containsMatchIn(source),
                    "GUARD VIOLATION: SyncModels.kt contains '${pattern.pattern}'. " +
                        "Sync DTOs transmit raw per-cable kg values. " +
                        "Portal multiplies weight independently for display.",
                )
            }
        } else {
            assertTrue(true, "SyncModels.kt not found; guard passes vacuously")
        }
    }

    @Test
    fun bulkAdjust_notReferencedInSyncLayer() {
        val syncDir = File(commonMainRoot, "com/devil/phoenixproject/data/sync")
        val violations = scanDirectoryForPatterns(syncDir, bulkAdjustPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Sync layer references BulkWeightAdjust: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "Sync sends raw per-cable values; portal multiplies for display.",
        )
    }

    @Test
    fun weightIncrement_notReferencedInSyncLayer() {
        val syncDir = File(commonMainRoot, "com/devil/phoenixproject/data/sync")
        val violations = scanDirectoryForPatterns(syncDir, weightIncrementPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Sync layer references effectiveWeightIncrement: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "User-configurable weight increments are a UI preference. " +
                "Sync DTOs must transmit raw per-cable kg values.",
        )
    }

    // ===== Health Integration Guards =====

    @Test
    fun bulkAdjust_notReferencedInHealthIntegration() {
        // Check all three source sets: commonMain, androidMain, iosMain
        val integrationDirCommon = File(commonMainRoot, "com/devil/phoenixproject/data/integration")
        val integrationDirAndroid = File(androidMainRoot, "com/devil/phoenixproject/data/integration")
        val integrationDirIos = File(iosMainRoot, "com/devil/phoenixproject/data/integration")

        val violations = scanDirectoryForPatterns(integrationDirCommon, bulkAdjustPatterns) +
            scanDirectoryForPatterns(integrationDirAndroid, bulkAdjustPatterns) +
            scanDirectoryForPatterns(integrationDirIos, bulkAdjustPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Health integration references BulkWeightAdjust: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "Health integration layer has its own WEIGHT_MULTIPLIER. " +
                "Bulk adjust is a presentation-layer routine editing feature.",
        )
    }

    @Test
    fun weightIncrement_notReferencedInHealthIntegration() {
        val integrationDirCommon = File(commonMainRoot, "com/devil/phoenixproject/data/integration")
        val integrationDirAndroid = File(androidMainRoot, "com/devil/phoenixproject/data/integration")
        val integrationDirIos = File(iosMainRoot, "com/devil/phoenixproject/data/integration")

        val violations = scanDirectoryForPatterns(integrationDirCommon, weightIncrementPatterns) +
            scanDirectoryForPatterns(integrationDirAndroid, weightIncrementPatterns) +
            scanDirectoryForPatterns(integrationDirIos, weightIncrementPatterns)

        assertTrue(
            violations.isEmpty(),
            "GUARD VIOLATION: Health integration references effectiveWeightIncrement: " +
                "${violations.joinToString { "${it.first} (${it.second})" }}. " +
                "Health integration layer handles weight conversion independently. " +
                "User-configurable increments are a UI preference.",
        )
    }

    // ===== Cross-Phase Guard: WeightDisplayFormatter =====

    @Test
    fun weightDisplayFormatter_notModifiedByPhase38() {
        // WeightDisplayFormatter (Phase 37) must not reference any Phase 38 features.
        // This guards against cross-contamination between display formatting and
        // bulk adjust / increment preference features.
        val formatterPath = "com/devil/phoenixproject/presentation/util/WeightDisplayFormatter.kt"
        val source = readSourceFile(formatterPath)
        if (source != null) {
            val phase38Patterns = bulkAdjustPatterns + weightIncrementPatterns
            for (pattern in phase38Patterns) {
                assertFalse(
                    pattern.containsMatchIn(source),
                    "GUARD VIOLATION: WeightDisplayFormatter.kt contains '${pattern.pattern}'. " +
                        "Phase 37 formatter converts per-cable to display weight (x2 multiplier). " +
                        "Phase 38 features (bulk adjust, weight increments) must not leak into it.",
                )
            }
        } else {
            assertTrue(true, "WeightDisplayFormatter.kt not found; guard passes vacuously")
        }
    }
}
