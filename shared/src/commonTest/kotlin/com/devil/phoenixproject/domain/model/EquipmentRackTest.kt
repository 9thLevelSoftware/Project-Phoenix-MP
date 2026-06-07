package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EquipmentRackTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `rack item defaults to enabled added resistance`() {
        val item = RackItem(
            id = "vest",
            name = "Weighted vest",
            category = RackItemCategory.WEIGHTED_VEST,
            weightKg = 10f,
        )

        assertEquals(RackItemBehavior.ADDED_RESISTANCE, item.behavior)
        assertTrue(item.enabled)
    }

    @Test
    fun `rack item json round trip preserves behavior and ordering fields`() {
        val original = RackItem(
            id = "belt",
            name = "Dip belt",
            category = RackItemCategory.DIP_BELT,
            weightKg = 12.5f,
            behavior = RackItemBehavior.COUNTERWEIGHT,
            enabled = false,
            sortOrder = 3,
        )

        val decoded = json.decodeFromString<RackItem>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `rack item rejects blank names and invalid weights`() {
        assertFailsWith<IllegalArgumentException> {
            RackItem(id = "blank", name = "   ", category = RackItemCategory.OTHER, weightKg = 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            RackItem(id = "negative", name = "Bad", category = RackItemCategory.OTHER, weightKg = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            RackItem(id = "nan", name = "Bad", category = RackItemCategory.OTHER, weightKg = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            RackItem(id = "infinite", name = "Bad", category = RackItemCategory.OTHER, weightKg = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `rack item decoding rejects invalid persisted weight`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<RackItem>(
                """{"id":"bad","name":"Bad","category":"OTHER","weightKg":-1.0}""",
            )
        }
    }

    @Test
    fun `active rack selection de duplicates ids while preserving order`() {
        val selection = ActiveRackSelection(listOf("vest", "belt", "vest", "chain", "belt"))

        assertEquals(listOf("vest", "belt", "chain"), selection.distinctItemIds)
        assertFalse(selection.isEmpty)
    }
}
