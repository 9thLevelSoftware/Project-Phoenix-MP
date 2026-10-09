package com.devil.phoenixproject.data.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B4 delivery ownership (#1242): one active delivery at a time, at most one consumption per
 * delivery, and a fresh identity per genuine delivery — a replay after commit, resume or
 * Activity recreation must never re-commit, while a re-share of the same file imports again.
 */
class CsvImportIntakeOwnershipTest {
    @Test
    fun aSecondDeliveryWhileBusyIsRejectedWithoutTouchingInFlightState() {
        val ownership = CsvImportIntakeOwnership()
        assertTrue(ownership.begin("d1"))
        ownership.moveTo(CsvImportIntakePhase.PREVIEW)

        assertFalse(ownership.begin("d2"), "busy: the second delivery is rejected")
        assertEquals("d1", ownership.activeDeliveryId)
        assertEquals(CsvImportIntakePhase.PREVIEW, ownership.phase, "the in-flight state machine is untouched")
    }

    @Test
    fun aConsumedDeliveryNeverReCommits() {
        val ownership = CsvImportIntakeOwnership()
        assertTrue(ownership.begin("d1"))
        ownership.markConsumed()
        assertFalse(ownership.isBusy())

        // Replay of the same delivery id (commit result, resume, recreation): rejected.
        assertFalse(ownership.begin("d1"), "a consumed delivery is never taken again")
        assertTrue(ownership.isConsumed("d1"))
    }

    @Test
    fun aDeliberateReshareOfTheSameFileImportsAgain() {
        val ownership = CsvImportIntakeOwnership()
        assertTrue(ownership.begin("d1"))
        ownership.markConsumed()
        // A genuine delivery always gets a new id; nothing keys on content.
        assertTrue(ownership.begin("d2"))
        assertTrue(ownership.isBusy())
    }

    @Test
    fun consumptionHappensAtMostOnce() {
        val ownership = CsvImportIntakeOwnership()
        assertTrue(ownership.begin("d1"))
        ownership.markConsumed()
        ownership.markConsumed()
        assertFalse(ownership.begin("d1"))
        assertEquals(CsvImportIntakePhase.CONSUMED, ownership.phase)
    }

    @Test
    fun phasesOnlyMoveForTheActiveDelivery() {
        val ownership = CsvImportIntakeOwnership()
        ownership.moveTo(CsvImportIntakePhase.PREVIEW)
        assertEquals(CsvImportIntakePhase.IDLE, ownership.phase, "no delivery, no phase change")

        assertTrue(ownership.begin("d1"))
        assertEquals(CsvImportIntakePhase.READING, ownership.phase)
        ownership.moveTo(CsvImportIntakePhase.COMMITTING)
        assertEquals(CsvImportIntakePhase.COMMITTING, ownership.phase)
    }
}
