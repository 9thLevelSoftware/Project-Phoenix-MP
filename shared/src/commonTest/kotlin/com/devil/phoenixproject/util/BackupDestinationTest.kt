package com.devil.phoenixproject.util

import com.devil.phoenixproject.util.BackupDestination.Companion.fromJson
import com.devil.phoenixproject.util.BackupDestination.Companion.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for BackupDestination sealed class serialization and edge cases.
 *
 * Covers: JSON round-trip, error resilience, property behavior, forward compatibility.
 */
class BackupDestinationTest {

    // Helper: call the companion extension function toJson()
    private fun BackupDestination.serialize(): String = with(BackupDestination) { toJson() }

    // ===== Default serialization =====

    @Test
    fun default_serializesToJsonWithTypeDiscriminator() {
        val jsonStr = BackupDestination.Default.serialize()
        val obj = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals("default", obj["type"]?.jsonPrimitive?.content, "Expected 'default' type discriminator")
    }

    // ===== Custom serialization =====

    @Test
    fun custom_serializesWithUriAndDisplayName() {
        val custom = BackupDestination.Custom(
            uri = "content://com.android.externalstorage/tree/primary%3ABackups",
            displayName = "Backups",
        )
        val jsonStr = custom.serialize()
        val obj = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals("custom", obj["type"]?.jsonPrimitive?.content, "Expected 'custom' type discriminator")
        assertEquals(
            "content://com.android.externalstorage/tree/primary%3ABackups",
            obj["uri"]?.jsonPrimitive?.content,
            "Expected URI field to match",
        )
        assertEquals("Backups", obj["displayName"]?.jsonPrimitive?.content, "Expected displayName field to match")
    }

    @Test
    fun custom_withBookmarkData_serializesCorrectlyAndRoundTrips() {
        val bookmark = "SGVsbG9Xb3JsZA==" // Base64 encoded
        val custom = BackupDestination.Custom(
            uri = "file:///var/mobile/Documents/Backups",
            displayName = "iOS Backups",
            bookmarkData = bookmark,
        )
        val json = custom.serialize()
        assertTrue(json.contains(bookmark), "Expected bookmarkData in JSON: $json")

        val restored = fromJson(json)
        assertTrue(restored is BackupDestination.Custom)
        assertEquals(bookmark, restored.bookmarkData)
        assertEquals("file:///var/mobile/Documents/Backups", restored.uri)
        assertEquals("iOS Backups", restored.displayName)
    }

    @Test
    fun custom_withoutBookmarkData_hasNullFieldAfterRoundTrip() {
        val custom = BackupDestination.Custom(
            uri = "content://test",
            displayName = "Test",
        )
        val json = custom.serialize()
        val restored = fromJson(json)
        assertTrue(restored is BackupDestination.Custom)
        assertNull(restored.bookmarkData, "Expected null bookmarkData after round-trip without it")
    }

    // ===== Round-trip tests =====

    @Test
    fun default_roundTripsThroughJson() {
        val original = BackupDestination.Default
        val json = original.serialize()
        val restored = fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun custom_roundTripsThroughJsonWithAllFields() {
        val original = BackupDestination.Custom(
            uri = "content://com.android.providers.downloads.documents/tree/downloads",
            displayName = "Downloads",
            bookmarkData = "dGVzdERhdGE=",
        )
        val json = original.serialize()
        val restored = fromJson(json)
        assertEquals(original, restored)
    }

    // ===== Error resilience =====

    @Test
    fun fromJson_withInvalidJson_returnsDefault() {
        val result = fromJson("{invalid json!!")
        assertEquals(BackupDestination.Default, result, "Invalid JSON should fall back to Default")
    }

    @Test
    fun fromJson_withEmptyString_returnsDefault() {
        val result = fromJson("")
        assertEquals(BackupDestination.Default, result, "Empty string should fall back to Default")
    }

    @Test
    fun fromJson_withNullString_returnsDefault() {
        val result = fromJson(null)
        assertEquals(BackupDestination.Default, result, "Null should fall back to Default")
    }

    @Test
    fun fromJson_withBlankString_returnsDefault() {
        val result = fromJson("   ")
        assertEquals(BackupDestination.Default, result, "Blank string should fall back to Default")
    }

    // ===== Special characters =====

    @Test
    fun custom_withSpecialCharactersInDisplayName_roundTrips() {
        val original = BackupDestination.Custom(
            uri = "content://test/special",
            displayName = "My \"Backup\" Folder / <2024> & More’s",
        )
        val json = original.serialize()
        val restored = fromJson(json)
        assertEquals(original, restored)
    }

    // ===== Property tests =====

    @Test
    fun isCustom_returnsTrueForCustom() {
        val custom = BackupDestination.Custom(uri = "content://test", displayName = "Test")
        assertTrue(custom.isCustom, "Custom destination should report isCustom = true")
    }

    @Test
    fun isCustom_returnsFalseForDefault() {
        assertFalse(BackupDestination.Default.isCustom, "Default destination should report isCustom = false")
    }

    // ===== Forward compatibility =====

    @Test
    fun fromJson_withUnknownFields_ignoresThemGracefully() {
        // Simulate a JSON produced by a future version with extra fields
        val futureJson = """{"type":"custom","uri":"content://test","displayName":"Test","bookmarkData":null,"newFutureField":"hello","anotherField":42}"""
        val restored = fromJson(futureJson)
        assertTrue(restored is BackupDestination.Custom, "Should parse Custom despite unknown fields")
        assertEquals("content://test", restored.uri)
        assertEquals("Test", restored.displayName)
    }

    @Test
    fun fromJson_withUnknownTypeDiscriminator_returnsDefault() {
        // A future version might add a new subclass
        val futureTypeJson = """{"type":"cloud","provider":"s3","bucket":"my-bucket"}"""
        val result = fromJson(futureTypeJson)
        assertEquals(BackupDestination.Default, result, "Unknown type discriminator should fall back to Default")
    }
}
