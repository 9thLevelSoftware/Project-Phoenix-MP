package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RepMetricRepository JSON serialization helpers.
 *
 * These test the critical round-trip correctness of FloatArray/LongArray <-> JSON string
 * serialization used for storing curve data in TEXT columns.
 *
 * Full integration tests with SQLite would require a test database driver.
 * These unit tests cover the most critical correctness concern: data fidelity through serialization.
 */
class RepMetricRepositoryTest {

    // ========== FloatArray JSON round-trip ==========

    @Test
    fun `FloatArray round-trip with multiple values`() {
        val original = floatArrayOf(1.5f, 2.7f, 3.14f, 0.0f, -1.0f)
        val json = original.toJsonString()
        val restored = json.toFloatArrayFromJson()

        assertContentEquals(original, restored)
    }

    @Test
    fun `FloatArray round-trip with empty array`() {
        val original = floatArrayOf()
        val json = original.toJsonString()
        val restored = json.toFloatArrayFromJson()

        assertEquals("[]", json)
        assertContentEquals(original, restored)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `FloatArray round-trip with single element`() {
        val original = floatArrayOf(42.5f)
        val json = original.toJsonString()
        val restored = json.toFloatArrayFromJson()

        assertContentEquals(original, restored)
    }

    @Test
    fun `FloatArray toJsonString produces valid format`() {
        val array = floatArrayOf(1.0f, 2.0f, 3.0f)
        val json = array.toJsonString()

        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertEquals(3, json.removeSurrounding("[", "]").split(",").size)
    }

    // ========== LongArray JSON round-trip ==========

    @Test
    fun `LongArray round-trip with multiple values`() {
        val original = longArrayOf(100L, 200L, 300L, 0L, -50L)
        val json = original.toJsonString()
        val restored = json.toLongArrayFromJson()

        assertContentEquals(original, restored)
    }

    @Test
    fun `LongArray round-trip with empty array`() {
        val original = longArrayOf()
        val json = original.toJsonString()
        val restored = json.toLongArrayFromJson()

        assertEquals("[]", json)
        assertContentEquals(original, restored)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `LongArray round-trip with single element`() {
        val original = longArrayOf(1234567890L)
        val json = original.toJsonString()
        val restored = json.toLongArrayFromJson()

        assertContentEquals(original, restored)
    }

    // ========== Edge cases ==========

    @Test
    fun `FloatArray handles large number of samples`() {
        // Simulate 25Hz capture over 2 seconds = 50 samples
        val original = FloatArray(50) { it.toFloat() * 0.5f }
        val json = original.toJsonString()
        val restored = json.toFloatArrayFromJson()

        assertContentEquals(original, restored)
        assertEquals(50, restored.size)
    }

    @Test
    fun `LongArray handles large timestamp offsets`() {
        // Simulate timestamp offsets at 40ms intervals (25Hz)
        val original = LongArray(50) { it * 40L }
        val json = original.toJsonString()
        val restored = json.toLongArrayFromJson()

        assertContentEquals(original, restored)
        assertEquals(50, restored.size)
    }

    @Test
    fun `GATE-04 compliance - repository interface has no tier parameter`() {
        // GATE-04: Data capture happens for ALL tiers.
        // RepMetricRepository methods take only sessionId and data, never a SubscriptionTier.
        // This test verifies the principle by checking the interface contract compiles
        // with sessionId-only signatures. If someone adds a tier parameter, existing
        // callers (including this test) will break at compile time.
        val repo: RepMetricRepository? = null
        // These lines verify the method signatures have no tier parameter:
        // If the interface were changed to require SubscriptionTier, this would not compile.
        @Suppress("SENSELESS_COMPARISON")
        if (repo != null) {
            // Just verifying method signatures - not actually calling
            val _save: suspend (String, List<com.devil.phoenixproject.domain.model.RepMetricData>) -> Unit =
                repo::saveRepMetrics
            val _get: suspend (String) -> List<com.devil.phoenixproject.domain.model.RepMetricData> =
                repo::getRepMetrics
            val _delete: suspend (String) -> Unit = repo::deleteRepMetrics
            val _count: suspend (String) -> Long = repo::getRepMetricCount
        }
        // If we get here, the interface has no tier parameters (GATE-04 compliant)
        assertTrue(true, "RepMetricRepository interface has sessionId-only signatures (GATE-04)")
    }
}
