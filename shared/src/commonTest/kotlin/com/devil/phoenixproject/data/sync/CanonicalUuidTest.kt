package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalUuidTest {

    @Test
    fun matchesLowerAndUpperHexAndTheNilSentinel() {
        val lower = "42345678-1234-4abc-8def-1234567890ab"
        assertTrue(CanonicalUuid.matches(lower))
        assertTrue(CanonicalUuid.matches(lower.uppercase()))
        assertTrue(CanonicalUuid.matches("AaBbCcDd-EeFf-1122-3344-5566778899aa"))
        assertTrue(CanonicalUuid.matches(SyncManager.NIL_UUID_SENTINEL))
    }

    @Test
    fun matchesAnyHexNibbleNotOnlyRfcVersionAndVariant() {
        // Migration 40's backfill regex is lowercase v4 with an 8/9/a/b variant.
        // The sync gate is the wider generic hex pattern both callers copied.
        assertTrue(CanonicalUuid.matches("12345678-1234-1234-1234-123456789abc"))
        assertTrue(CanonicalUuid.matches("12345678-1234-4234-0234-123456789abc"))
    }

    @Test
    fun rejectsOpaqueBlankWrappedAndPaddedIds() {
        val uuid = "55555555-5555-4555-a555-555555555555"
        assertFalse(CanonicalUuid.matches(""))
        assertFalse(CanonicalUuid.matches("cycle_routine_$uuid"))
        assertFalse(CanonicalUuid.matches("{$uuid}"))
        assertFalse(CanonicalUuid.matches(" $uuid"))
        assertFalse(CanonicalUuid.matches("$uuid "))
        assertFalse(CanonicalUuid.matches(uuid.replace("-", "")))
        assertFalse(CanonicalUuid.matches(uuid.dropLast(1)))
        assertFalse(CanonicalUuid.matches("${uuid}0"))
        assertFalse(CanonicalUuid.matches("12345678-1234-4234-8234-123456789abg"))
    }

    @Test
    fun canonicalOrNullLowercasesAMatchAndDropsTheRest() {
        assertEquals(
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            CanonicalUuid.canonicalOrNull("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"),
        )
        val lower = "42345678-1234-4abc-8def-1234567890ab"
        assertEquals(lower, CanonicalUuid.canonicalOrNull(lower))
        assertEquals(SyncManager.NIL_UUID_SENTINEL, CanonicalUuid.canonicalOrNull(SyncManager.NIL_UUID_SENTINEL))
        assertNull(CanonicalUuid.canonicalOrNull("cycle_routine_$lower"))
        assertNull(CanonicalUuid.canonicalOrNull(" $lower"))
        assertNull(CanonicalUuid.canonicalOrNull(""))
    }
}
