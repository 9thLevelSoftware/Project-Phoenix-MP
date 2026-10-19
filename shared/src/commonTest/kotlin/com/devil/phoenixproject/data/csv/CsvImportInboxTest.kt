package com.devil.phoenixproject.data.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #1242 delivery ownership: one shared file owns the inbox from intake until the host
 * finishes, a second file is turned away without touching it, and a delivery is taken once.
 */
class CsvImportInboxTest {
    private val read = CsvImportDeliveryResult.Read("csv")

    @Test
    fun aDeliveryRunsFromIntakeToFinish() {
        val inbox = CsvImportInbox()
        assertTrue(inbox.tryBegin("a"))
        assertEquals(CsvImportInbox.State.Reading("a"), inbox.state.value)
        assertTrue(inbox.deliver("a", read))
        val offer = assertIs<CsvImportInbox.State.Ready>(inbox.state.value).offer
        assertEquals(CsvImportOffer("a", read), offer)
        assertTrue(inbox.claim(offer))
        assertEquals(CsvImportInbox.State.Handling("a"), inbox.state.value)
        inbox.finish("a")
        assertEquals(CsvImportInbox.State.Idle, inbox.state.value)
    }

    @Test
    fun aSecondFileIsBusyWhileTheFirstIsBeingRead() {
        val inbox = CsvImportInbox()
        assertTrue(inbox.tryBegin("a"))
        // The second share during a slow first read is rejected; it never replaces the first.
        assertFalse(inbox.tryBegin("b"))
        assertFalse(inbox.deliver("b", read))
        inbox.abandon("b")
        assertEquals(CsvImportInbox.State.Reading("a"), inbox.state.value)
        assertTrue(inbox.deliver("a", read))
    }

    @Test
    fun aSecondFileIsBusyUntilTheHostFinishes() {
        val inbox = CsvImportInbox()
        inbox.tryBegin("a")
        inbox.deliver("a", read)
        assertFalse(inbox.tryBegin("b"), "busy while waiting for the host")
        inbox.claim(CsvImportOffer("a", read))
        assertFalse(inbox.tryBegin("b"), "busy while the preview is open")
        inbox.finish("b")
        assertEquals(CsvImportInbox.State.Handling("a"), inbox.state.value, "only the owner finishes")
        inbox.finish("a")
        assertTrue(inbox.tryBegin("b"))
    }

    @Test
    fun aReadyDeliveryIsClaimedAtMostOnce() {
        val inbox = CsvImportInbox()
        inbox.tryBegin("a")
        inbox.deliver("a", read)
        val offer = CsvImportOffer("a", read)
        assertTrue(inbox.claim(offer))
        // A recreated host replaying the same offer never takes it again.
        assertFalse(inbox.claim(offer))
        inbox.finish("a")
        assertFalse(inbox.claim(offer))
    }

    @Test
    fun anAbandonedReadFreesTheInboxButADeliveredOneDoesNot() {
        val inbox = CsvImportInbox()
        inbox.tryBegin("a")
        inbox.abandon("a")
        assertEquals(CsvImportInbox.State.Idle, inbox.state.value)

        inbox.tryBegin("b")
        inbox.deliver("b", read)
        inbox.abandon("b")
        assertIs<CsvImportInbox.State.Ready>(inbox.state.value)
    }

    @Test
    fun busyRejectionsAreCountedAndTakenOnce() {
        val inbox = CsvImportInbox()
        inbox.reportBusy()
        inbox.reportBusy()
        assertEquals(2, inbox.busyRejections.value)
        assertEquals(2, inbox.takeBusyRejections())
        assertEquals(0, inbox.takeBusyRejections())
    }
}
