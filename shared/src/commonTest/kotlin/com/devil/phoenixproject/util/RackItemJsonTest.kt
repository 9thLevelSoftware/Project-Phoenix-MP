package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class RackItemJsonTest {
    @Test
    fun `rack snapshot encoder round trips typed items with defaults`() {
        val item = RackItem(
            id = "rack-item",
            name = "Weighted Vest",
            category = RackItemCategory.WEIGHTED_VEST,
            weightKg = 10f,
            behavior = RackItemBehavior.ADDED_RESISTANCE,
            enabled = true,
            sortOrder = 1,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val encoded = encodeRackItemsJson(listOf(item))

        assertEquals(listOf(item), Json.decodeFromString<List<RackItem>>(encoded))
        assertTrue(encoded.contains("\"enabled\":true"))
        assertTrue(encoded.contains("\"sortOrder\":1"))
    }
}
