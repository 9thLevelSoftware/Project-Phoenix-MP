package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #1367 T1/T3: enum normalization for the persisted weight text size.
 * Missing/unknown/corrupt/empty values load as STANDARD and never throw (SD6).
 */
class WeightTextScaleTest {

    @Test
    fun `known enum names parse`() {
        assertEquals(WeightTextScale.STANDARD, WeightTextScale.fromStorage("STANDARD"))
        assertEquals(WeightTextScale.LARGE, WeightTextScale.fromStorage("LARGE"))
        assertEquals(WeightTextScale.EXTRA_LARGE, WeightTextScale.fromStorage("EXTRA_LARGE"))
    }

    @Test
    fun `unknown corrupt empty or missing values normalize to STANDARD without throwing`() {
        val badValues = listOf("XL", "3", "", "standard", "Large ", "EXTRA LARGE", "\u0000", "null")
        for (value in badValues) {
            assertEquals(WeightTextScale.STANDARD, WeightTextScale.fromStorage(value), "value=$value")
        }
        assertEquals(WeightTextScale.STANDARD, WeightTextScale.fromStorage(null))
    }

    @Test
    fun `nominal preference factors are the signoff 1x 1 5x 2x mapping`() {
        assertEquals(1f, WeightTextScale.STANDARD.preferenceFactor)
        assertEquals(1.5f, WeightTextScale.LARGE.preferenceFactor)
        assertEquals(2f, WeightTextScale.EXTRA_LARGE.preferenceFactor)
    }
}
