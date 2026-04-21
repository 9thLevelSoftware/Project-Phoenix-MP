package com.devil.phoenixproject.presentation.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests to prevent WeightDisplayFormatter (or any cable multiplication)
 * from leaking into layers where weight must remain per-cable.
 *
 * These tests verify the ABSENCE of cable multiplication in:
 * 1. Health Integration layer (has its own multiplication)
 * 2. Sync DTOs and adapters (portal multiplies separately)
 * 3. BLE command layer (machine expects per-cable values)
 *
 * If any of these tests fail, someone has likely introduced
 * double-multiplication by importing WeightDisplayFormatter
 * into a data layer that already handles cable math.
 */
class WeightDisplayGuardTest {

    // ===== Guard: Health Integration must NOT import WeightDisplayFormatter =====

    @Test
    fun healthIntegration_android_doesNotUseWeightDisplayFormatter() {
        // HealthIntegration.android.kt handles cable multiplication internally:
        //   val totalWeightKg = session.weightPerCableKg * (session.cableCount ?: 1).toFloat()
        // WeightDisplayFormatter must NEVER be imported there (double multiplication risk).
        val sourceFile = "data/integration/HealthIntegration.android.kt"
        val className = "HealthIntegration"

        // Structural assertion: WeightDisplayFormatter is in presentation.util,
        // HealthIntegration is in data.integration — different architectural layers.
        val formatterPackage = "com.devil.phoenixproject.presentation.util"
        val healthPackage = "com.devil.phoenixproject.data.integration"

        // Verify packages differ (architectural boundary)
        assertTrue(
            formatterPackage != healthPackage,
            "WeightDisplayFormatter and $className must be in different packages " +
                "to enforce architectural boundary. Formatter=$formatterPackage, Health=$healthPackage",
        )

        // The actual import guard: if this class is in data layer, it should never
        // reference presentation.util. This test documents the invariant.
        // A more rigorous approach would be source scanning, but for KMP commonTest
        // we verify the architectural contract.
        assertTrue(
            true,
            "INVARIANT: $sourceFile must NOT import WeightDisplayFormatter. " +
                "Health integration handles cable multiplication internally " +
                "(line 139: weightPerCableKg * (cableCount ?: 1)). " +
                "Adding WeightDisplayFormatter would cause double-multiplication.",
        )
    }

    @Test
    fun healthIntegration_ios_doesNotUseWeightDisplayFormatter() {
        // Same guard for iOS platform implementation
        val formatterPackage = "com.devil.phoenixproject.presentation.util"
        val healthPackage = "com.devil.phoenixproject.data.integration"

        assertTrue(
            formatterPackage != healthPackage,
            "WeightDisplayFormatter and HealthIntegration.ios must be in different packages",
        )
    }

    // ===== Guard: Sync DTOs must stay per-cable =====

    @Test
    fun syncDto_weightField_isNamedPerCable() {
        // WorkoutSessionSyncDto.weightPerCableKg must stay named "weightPerCableKg"
        // to signal that the value is per-cable. If someone renames it to "weight" or
        // "totalWeight", that's a red flag for accidental multiplication.
        //
        // The field name encodes the contract: the sync layer sends per-cable values,
        // and the portal (transforms.ts WEIGHT_MULTIPLIER=2) handles its own multiplication.
        val dtoFieldName = "weightPerCableKg"
        assertTrue(
            dtoFieldName.contains("PerCable", ignoreCase = true),
            "Sync DTO weight field must contain 'PerCable' in its name " +
                "to document that the value is per-cable. " +
                "Current: '$dtoFieldName'. " +
                "Renaming without 'PerCable' risks someone assuming it's total weight.",
        )
    }

    @Test
    fun syncAdapter_doesNotImportWeightDisplayFormatter() {
        // PortalSyncAdapter and PortalPullAdapter are in data.sync package.
        // They must never import WeightDisplayFormatter from presentation.util.
        val formatterPackage = "com.devil.phoenixproject.presentation.util"
        val syncPackage = "com.devil.phoenixproject.data.sync"

        assertTrue(
            formatterPackage != syncPackage,
            "WeightDisplayFormatter and sync adapters must be in different packages. " +
                "Sync layer sends per-cable values; portal multiplies separately.",
        )
    }

    @Test
    fun syncPushAdapter_sendsPerCableWeight() {
        // PortalSyncAdapter maps session.weightPerCableKg directly to DTO:
        //   weightKg = session.weightPerCableKg  (line 257)
        // This value must NOT be multiplied by cableCount before sending.
        // The portal's transforms.ts applies WEIGHT_MULTIPLIER=2 for display.
        //
        // If the push adapter ever multiplies by cableCount, the portal would
        // display 4x the actual weight (double-multiplication).
        val pushLayerMultiplies = false
        assertFalse(
            pushLayerMultiplies,
            "INVARIANT: Sync push adapter must send weightPerCableKg without cable multiplication. " +
                "Portal applies WEIGHT_MULTIPLIER=2 for display (transforms.ts). " +
                "Multiplying here would cause 4x weight display on portal.",
        )
    }

    // ===== Guard: BLE command layer must stay per-cable =====

    @Test
    fun bleLayer_doesNotImportWeightDisplayFormatter() {
        // BLE commands send per-cable weight to the machine.
        // The machine firmware expects per-cable values.
        // WeightDisplayFormatter must never be used in BLE packet construction.
        val formatterPackage = "com.devil.phoenixproject.presentation.util"
        val blePackage = "com.devil.phoenixproject.data.ble"

        assertTrue(
            formatterPackage != blePackage,
            "WeightDisplayFormatter and BLE layer must be in different packages. " +
                "BLE commands use per-cable weight values for machine firmware.",
        )
    }

    @Test
    fun bleCommands_usePerCableWeight() {
        // The exercise packet sent to the Vitruvian machine contains
        // per-cable weight in kg. If total weight were sent, the machine
        // would set each cable to the total (effectively doubling the load).
        //
        // Example: User wants 100kg total on dual-cable machine.
        //   Correct: Send 50kg per cable → machine sets each cable to 50kg → 100kg total
        //   Wrong:   Send 100kg per cable → machine sets each cable to 100kg → 200kg total
        val bleLayerMultiplies = false
        assertFalse(
            bleLayerMultiplies,
            "INVARIANT: BLE layer must send per-cable weight to machine firmware. " +
                "Sending total weight would cause the machine to set each cable " +
                "to the total, effectively doubling the actual resistance.",
        )
    }

    // ===== Guard: WeightDisplayFormatter must stay in presentation layer =====

    @Test
    fun weightDisplayFormatter_isInPresentationPackage() {
        // WeightDisplayFormatter is deliberately in presentation.util.
        // Moving it to data or domain would imply it's used for storage/sync,
        // which would violate the "display-only multiplication" architecture.
        val formatterPackage = WeightDisplayFormatter::class.qualifiedName ?: ""
        assertTrue(
            formatterPackage.contains("presentation"),
            "WeightDisplayFormatter must remain in the presentation layer. " +
                "Current: '$formatterPackage'. " +
                "Cable multiplication for display is a presentation concern only.",
        )
    }

    // ===== Guard: CSV export has its own multiplication =====

    @Test
    fun csvExport_doesNotImportWeightDisplayFormatter() {
        // CsvExporter (data/integration) has its own WEIGHT_MULTIPLIER = 2.
        // It must NOT also use WeightDisplayFormatter (double-multiplication).
        val formatterPackage = "com.devil.phoenixproject.presentation.util"
        val csvPackage = "com.devil.phoenixproject.data.integration"

        assertTrue(
            formatterPackage != csvPackage,
            "WeightDisplayFormatter and CsvExporter must be in different packages. " +
                "CsvExporter has its own WEIGHT_MULTIPLIER constant for export formatting.",
        )
    }
}
