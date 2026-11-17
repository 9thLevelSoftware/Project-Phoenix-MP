package com.devil.phoenixproject.presentation.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen v1 wire contract tests (issue #1391 packet B2). Validation rows mirror
 * integration-model.md §4 and the watch-side `garmin/tests/PhoenixProtocolTests.mc`.
 * Test names are plain alphanumeric on purpose: Kotlin/Native rejects punctuation in
 * backtick test names and `:shared:compileTestKotlinIosArm64` is a required CI check.
 */
class GarminProtocolTest {

    private fun id64(): String = "a".repeat(64)
    private fun id65(): String = "a".repeat(65)

    // ---- Round trips ----

    @Test
    fun helloRoundTrips() {
        val msg = assertNotNull(GarminProtocol.buildHello("watch-1"))
        val parsed = assertNotNull(GarminProtocol.parseHello(msg))
        assertEquals("watch-1", parsed.watchInstanceId)
        assertEquals(listOf(1), parsed.supportedVersions)
    }

    @Test
    fun commandRoundTripsWithAndWithoutToken() {
        val plain = assertNotNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 3, GarminProtocol.ACTION_START_NEXT, "grant-1", null),
        )
        val parsed = assertNotNull(GarminProtocol.parseCommand(plain))
        assertEquals("cmd-1", parsed.commandId)
        assertEquals(3, parsed.expectedRevision)
        assertNull(parsed.confirmationToken)

        val cancel = assertNotNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-2", "ctx-1", 3, GarminProtocol.ACTION_CANCEL_EXERCISE, "grant-1", "token-1"),
        )
        val parsedCancel = assertNotNull(GarminProtocol.parseCommand(cancel))
        assertEquals("token-1", parsedCancel.confirmationToken)
    }

    @Test
    fun stateRoundTripsAllOptionalFields() {
        val st = GarminProtocol.StateMessage(
            controlEpoch = "epoch-1", revision = 7, contextId = "ctx-1",
            allowedActions = listOf(GarminProtocol.ACTION_START_NEXT, GarminProtocol.ACTION_CANCEL_EXERCISE),
            readiness = GarminProtocol.READY_ARMED_READY,
            phoneForeground = true, foregroundRequired = true, backgroundStopSupported = false,
            displayLabel = "Cable Row", actionLabel = "Start set",
            actionGrant = "grant-1", grantTtlMs = 3000,
            confirmationToken = "token-1", confirmationTtlMs = 10000, confirmationLabel = "Cable Row",
        )
        val msg = assertNotNull(GarminProtocol.buildState(st))
        val parsed = assertNotNull(GarminProtocol.parseState(msg))
        assertEquals(st, parsed)
    }

    @Test
    fun resultRoundTripsAndRejectedRequiresReason() {
        val accepted = assertNotNull(
            GarminProtocol.buildResult(GarminProtocol.ResultMessage("cmd-1", "epoch-1", "ctx-1", GarminProtocol.STAGE_ACCEPTED)),
        )
        assertEquals(GarminProtocol.STAGE_ACCEPTED, assertNotNull(GarminProtocol.parseResult(accepted)).stage)

        val rejected = assertNotNull(
            GarminProtocol.buildResult(
                GarminProtocol.ResultMessage("cmd-1", "epoch-1", "ctx-1", GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_STALE_STATE),
            ),
        )
        assertEquals(GarminProtocol.REASON_STALE_STATE, assertNotNull(GarminProtocol.parseResult(rejected)).reason)

        // REJECTED without a reason is invalid per contract.
        assertFailsWith<IllegalArgumentException> {
            GarminProtocol.ResultMessage("cmd-1", "epoch-1", "ctx-1", GarminProtocol.STAGE_REJECTED)
        }
        val bareRejected = HashMap(accepted)
        bareRejected["stage"] = GarminProtocol.STAGE_REJECTED
        assertNull(GarminProtocol.parseResult(bareRejected))
    }

    // ---- Version / type / key-set rows ----

    @Test
    fun wrongOrMissingVersionFailsClosed() {
        val hello = assertNotNull(GarminProtocol.buildHello("watch-1"))
        assertNull(GarminProtocol.parseHello(hello + ("v" to 2)))
        assertNull(GarminProtocol.parseHello(hello - "v"))
    }

    @Test
    fun wrongTypeFailsClosed() {
        val hello = assertNotNull(GarminProtocol.buildHello("watch-1"))
        assertNull(GarminProtocol.parseHello(hello + ("type" to GarminProtocol.TYPE_COMMAND)))
    }

    @Test
    fun unknownKeysFailClosed() {
        val hello = assertNotNull(GarminProtocol.buildHello("watch-1"))
        assertNull(GarminProtocol.parseHello(hello + ("extra" to "x")))
    }

    @Test
    fun missingRequiredKeysFailClosed() {
        val cmd = assertNotNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 1, GarminProtocol.ACTION_STOP_SET, "grant-1", null),
        )
        assertNull(GarminProtocol.parseCommand(cmd - "actionGrant"))
        assertNull(GarminProtocol.parseCommand(cmd - "commandId"))
    }

    // ---- Identifier / label / enum limit rows ----

    @Test
    fun identifierLimitsAreEnforced() {
        assertTrue(GarminProtocol.isValidId(id64()))
        assertFalse(GarminProtocol.isValidId(id65()))
        assertFalse(GarminProtocol.isValidId(""))
        assertFalse(GarminProtocol.isValidId("nön-ascii"))
        assertNull(GarminProtocol.buildHello(id65()))
        assertNotNull(GarminProtocol.buildHello(id64()))
    }

    @Test
    fun labelUtf8ByteLimitIsEnforced() {
        // 40 two-byte characters = exactly 80 UTF-8 bytes: valid.
        val exact = "é".repeat(40)
        assertEquals(80, GarminProtocol.utf8Bytes(exact))
        assertTrue(GarminProtocol.isValidLabel(exact))
        // 41 two-byte characters = 82 bytes: invalid.
        assertFalse(GarminProtocol.isValidLabel("é".repeat(41)))
    }

    @Test
    fun enumStringLimitIsEnforced() {
        assertTrue(GarminProtocol.isValidEnum("x".repeat(32)))
        assertFalse(GarminProtocol.isValidEnum("x".repeat(33)))
    }

    @Test
    fun allowedActionsArrayIsBoundedAndTyped() {
        val base = GarminProtocol.StateMessage(
            controlEpoch = "epoch-1", revision = 1, contextId = "ctx-1",
            allowedActions = listOf(GarminProtocol.ACTION_START_NEXT, GarminProtocol.ACTION_STOP_SET, GarminProtocol.ACTION_CANCEL_EXERCISE),
            readiness = GarminProtocol.READY_ARMED_READY,
            phoneForeground = true, foregroundRequired = true, backgroundStopSupported = false,
            displayLabel = "L", actionLabel = "A",
        )
        assertNotNull(GarminProtocol.buildState(base))
        // > 3 array elements exceeds the frozen limit.
        assertNull(GarminProtocol.buildState(base.copy(allowedActions = listOf(GarminProtocol.ACTION_START_NEXT, GarminProtocol.ACTION_STOP_SET, GarminProtocol.ACTION_CANCEL_EXERCISE, GarminProtocol.ACTION_START_NEXT))))
        // Non-action strings fail closed.
        assertNull(GarminProtocol.buildState(base.copy(allowedActions = listOf("DANCE"))))
    }

    // ---- Container / size rows ----

    @Test
    fun nestedContainersFailClosed() {
        val nested = mapOf<String, Any?>("v" to 1, "type" to "HELLO", "watchInstanceId" to "w", "supportedVersions" to listOf(1), "inner" to mapOf<String, Any?>("a" to 1))
        assertFalse(GarminProtocol.hasValidContainers(nested))
        val nestedArray = mapOf<String, Any?>("a" to listOf(listOf(1)))
        assertFalse(GarminProtocol.hasValidContainers(nestedArray))
    }

    @Test
    fun dictionaryKeyCountIsBounded() {
        val many = (1..25).associate { "k$it" to it }
        assertFalse(GarminProtocol.hasValidContainers(many))
        val ok = (1..24).associate { "k$it" to it }
        assertTrue(GarminProtocol.hasValidContainers(ok))
    }

    @Test
    fun encodedSizeIsBoundedAndEstimatorNeverUnderestimates() {
        // String estimate: utf8 bytes + 2.
        assertEquals(7, GarminProtocol.estimateBytes("hello"))
        assertEquals(6, GarminProtocol.estimateBytes("é".repeat(2)))
        // Booleans and integers take the conservative slots from the watch codec.
        assertEquals(5, GarminProtocol.estimateBytes(true))
        assertEquals(12, GarminProtocol.estimateBytes(1))
        // Floats never enter the contract and are treated as fat.
        assertEquals(26, GarminProtocol.estimateBytes(1.5))
        // A large message exceeds 2048 bytes and fails the gate.
        val big = mapOf<String, Any?>("v" to 1, "type" to "HELLO", "watchInstanceId" to "a".repeat(64), "blob" to "b".repeat(2100))
        assertFalse(GarminProtocol.checkMessage(big))
    }

    @Test
    fun fractionalNumbersAreNotBoundedIntegers() {
        assertNull(GarminProtocol.asBoundedInt(1.5))
        assertNull(GarminProtocol.asBoundedInt("3"))
        assertEquals(3, GarminProtocol.asBoundedInt(3))
        assertEquals(3, GarminProtocol.asBoundedInt(3L))
        assertEquals(3, GarminProtocol.asBoundedInt(3.0))
    }

    @Test
    fun negativeRevisionFailsClosed() {
        assertNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", -1, GarminProtocol.ACTION_START_NEXT, "grant-1", null),
        )
        val msg = HashMap<String, Any?>(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 1, GarminProtocol.ACTION_START_NEXT, "grant-1", null)!!,
        )
        msg["expectedRevision"] = -1
        assertNull(GarminProtocol.parseCommand(msg))
    }

    // ---- Confirmation-token placement ----

    @Test
    fun tokenRidesOnlyOnConfirmedCancel() {
        assertNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 1, GarminProtocol.ACTION_START_NEXT, "grant-1", "token-1"),
        )
        assertNull(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 1, GarminProtocol.ACTION_CANCEL_EXERCISE, "grant-1", null),
        )
        val smuggled = HashMap<String, Any?>(
            GarminProtocol.buildCommand("epoch-1", "cmd-1", "ctx-1", 1, GarminProtocol.ACTION_START_NEXT, "grant-1", null)!!,
        )
        smuggled["confirmationToken"] = "token-1"
        assertNull(GarminProtocol.parseCommand(smuggled))
    }

    // ---- Reason enum ----

    @Test
    fun reasonEnumIsExact() {
        for (reason in listOf(
            GarminProtocol.REASON_STALE_STATE, GarminProtocol.REASON_NOT_READY, GarminProtocol.REASON_DISCONNECTED,
            GarminProtocol.REASON_UNSUPPORTED_VERSION, GarminProtocol.REASON_DISABLED, GarminProtocol.REASON_BUSY,
            GarminProtocol.REASON_RECOVERY_REQUIRED, GarminProtocol.REASON_SAVE_FAILED, GarminProtocol.REASON_UNKNOWN_RESULT,
            GarminProtocol.REASON_INVALID_MESSAGE, GarminProtocol.REASON_PAYLOAD_TOO_LARGE, GarminProtocol.REASON_UNAUTHORIZED_DEVICE,
            GarminProtocol.REASON_COMMAND_ID_CONFLICT, GarminProtocol.REASON_CONFIRMATION_EXPIRED, GarminProtocol.REASON_EPOCH_CAP_REACHED,
        )) {
            assertTrue(GarminProtocol.isReason(reason), reason)
        }
        assertFalse(GarminProtocol.isReason("WHATEVER"))
        assertFailsWith<IllegalArgumentException> {
            GarminProtocol.ResultMessage("cmd-1", "epoch-1", "ctx-1", GarminProtocol.STAGE_REJECTED, "WHATEVER")
        }
    }

    @Test
    fun watchMirrorConstantsMatch() {
        // Guard against drift between the phone codec and the frozen contract.
        assertEquals(2048, GarminProtocol.MAX_MESSAGE_BYTES)
        assertEquals(24, GarminProtocol.MAX_DICT_KEYS)
        assertEquals(3, GarminProtocol.MAX_ARRAY_ELEMENTS)
        assertEquals(64, GarminProtocol.MAX_ID_BYTES)
        assertEquals(80, GarminProtocol.MAX_LABEL_BYTES)
        assertEquals(32, GarminProtocol.MAX_ENUM_BYTES)
        assertEquals(3000L, GarminProtocol.GRANT_TTL_MS)
        assertEquals(10000L, GarminProtocol.CONFIRMATION_TTL_MS)
        assertEquals(5000L, GarminProtocol.RESULT_TIMEOUT_MS)
        assertEquals(256, GarminProtocol.DEDUPE_CAPACITY)
    }
}
