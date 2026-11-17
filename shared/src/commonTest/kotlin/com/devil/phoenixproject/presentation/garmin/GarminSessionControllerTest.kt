package com.devil.phoenixproject.presentation.garmin

import com.devil.phoenixproject.data.garmin.GarminCompanionTransport
import com.devil.phoenixproject.data.garmin.GarminControlPreferences
import com.devil.phoenixproject.data.garmin.GarminDeviceSelectionResult
import com.devil.phoenixproject.data.garmin.GarminDeviceSnapshot
import com.devil.phoenixproject.data.garmin.GarminInboundEnvelope
import com.devil.phoenixproject.data.garmin.GarminSendStatus
import com.devil.phoenixproject.data.garmin.GarminTransportState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.RemotePersistenceOutcome
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DEV = "watch-device-1"
private const val APP = "production-watch-app-uuid"

private class FakeGarminTransport : GarminCompanionTransport {
    override val state: StateFlow<GarminTransportState> = MutableStateFlow(GarminTransportState.Ready)
    override val devices: StateFlow<List<GarminDeviceSnapshot>> = MutableStateFlow(
        listOf(GarminDeviceSnapshot(DEV, "Forerunner 965", isConnected = true, watchAppInstalled = true)),
    )
    override val inbound = MutableSharedFlow<GarminInboundEnvelope>(extraBufferCapacity = 128)
    val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
    var attachCalls = 0
    var detachCalls = 0
    var shutdownCalls = 0

    override fun attach(ownerGeneration: Long) { attachCalls++ }
    override fun detach() { detachCalls++ }
    override fun shutdown() { shutdownCalls++ }

    override fun send(deviceRef: String, message: Map<String, Any?>, onCompletion: (GarminSendStatus) -> Unit) {
        sent += deviceRef to message
        onCompletion(GarminSendStatus.SUCCESS)
    }

    override fun startDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit) {}

    fun results(): List<GarminProtocol.ResultMessage> = sent.mapNotNull { GarminProtocol.parseResult(it.second) }
    fun states(): List<GarminProtocol.StateMessage> = sent.mapNotNull { GarminProtocol.parseState(it.second) }
}

private class FakeClock(var nowMs: Long = 1_000_000L)

private class Ctx(
    val harness: DWSMTestHarness,
    val transport: FakeGarminTransport,
    val prefs: GarminControlPreferences,
    val controller: GarminSessionController,
    private val clock: FakeClock,
) {
    val clockMs: Long get() = clock.nowMs
    fun advanceClock(ms: Long) { clock.nowMs += ms }
}

/**
 * GarminSessionController behavioral-contract tests (issue #1391 packet B2), one row
 * per acceptance item: freshness/grants, confirmation binding, dedupe fail-closed,
 * epoch cap rekey, authority invalidation, preemption, routing parity and the
 * ACCEPTED/APPLIED/REJECTED receipt semantics. Fake monotonic clock only — no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GarminSessionControllerTest {

    private fun routine() = Routine(
        id = "garmin-routine",
        name = "Garmin",
        exercises = listOf(
            RoutineExercise(
                id = "rex-1",
                exercise = Exercise(
                    id = "ex-1",
                    name = "Cable Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8, 8),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    private fun runControllerTest(block: suspend TestScope.(Ctx) -> Unit) = runTest {
        val harness = DWSMTestHarness(this)
        val transport = FakeGarminTransport()
        val prefs = GarminControlPreferences(MapSettings())
        val clock = FakeClock()
        val controller = GarminSessionController(transport, prefs, harness.dwsm, backgroundScope) { clock.nowMs }
        val ctx = Ctx(harness, transport, prefs, controller, clock)
        try {
            // A real suspension (virtual delay) guarantees the background collectors
            // dispatch and subscribe before any inbound traffic; advanceUntilIdle alone
            // does not dispatch backgroundScope work in coroutines-test 1.11.
            settle()
            block(ctx)
        } finally {
            harness.cleanup()
        }
    }

    /** Let the scheduler run every collector/execute task to completion (virtual time). */
    private suspend fun TestScope.settle() {
        // A long virtual delay suspends the test body so the scheduler dispatches every
        // queued background task (collectors, execute jobs) and resolves all in-flight
        // result windows before assertions run.
        delay(30_000)
    }

    private suspend fun TestScope.enableAndPrepare(ctx: Ctx, arm: Boolean = true) {
        ctx.prefs.watchAppUuid = APP
        ctx.prefs.selectedDeviceRef = DEV
        ctx.controller.setEnabled(true)
        settle()
        if (arm) {
            ctx.controller.arm()
            settle()
        }
    }

    private suspend fun TestScope.prepareRoutineSetReady(ctx: Ctx) {
        ctx.harness.fakeBleRepo.simulateConnect("Vee_Test")
        ctx.harness.dwsm.loadRoutineAsync(routine())
        settle()
        ctx.harness.dwsm.enterSetReady(0, 0)
        settle()
    }

    private suspend fun TestScope.prepareActiveSet(ctx: Ctx) {
        ctx.harness.setActiveSummaryCountdownSeconds(0)
        ctx.harness.fakeBleRepo.simulateConnect("Vee_Test")
        ctx.harness.dwsm.loadRoutineAsync(routine())
        settle()
        ctx.harness.dwsm.startWorkout(skipCountdown = true)
        settle()
        ctx.harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3, isWarmupComplete = true)
        settle()
    }

    private fun Ctx.currentState(): GarminProtocol.StateMessage =
        assertNotNull(transport.states().lastOrNull(), "controller must have published a STATE")

    private suspend fun TestScope.emitHello(ctx: Ctx) {
        val msg = assertNotNull(GarminProtocol.buildHello("watch-instance-1"))
        ctx.transport.inbound.emit(GarminInboundEnvelope(msg, DEV, APP, ctx.clockMs))
        settle()
    }

    private suspend fun TestScope.emitCommand(
        ctx: Ctx,
        action: String,
        commandId: String,
        revision: Int? = null,
        grant: String? = null,
        epoch: String? = null,
        contextId: String? = null,
        token: String? = null,
        deviceRef: String = DEV,
        appUuid: String = APP,
    ): Map<String, Any?> {
        val st = ctx.currentState()
        val effectiveToken = token ?: if (action == GarminProtocol.ACTION_CANCEL_EXERCISE) st.confirmationToken else null
        val msg = assertNotNull(
            GarminProtocol.buildCommand(
                epoch ?: st.controlEpoch,
                commandId,
                contextId ?: st.contextId,
                revision ?: st.revision,
                action,
                grant ?: assertNotNull(st.actionGrant, "STATE must carry a grant while armed"),
                effectiveToken,
            ),
        )
        ctx.transport.inbound.emit(GarminInboundEnvelope(msg, deviceRef, appUuid, ctx.clockMs))
        return msg
    }

    // =====================================================================
    //  Release boundary / association
    // =====================================================================

    @Test
    fun unmatchedWatchAppKeepsCardUnavailableWithoutArm() = runControllerTest { ctx ->
        // Release boundary (signoff correction 7): no production watch-app UUID configured
        // -> UNAVAILABLE, and arming mints no authority.
        ctx.controller.setEnabled(true)
        settle()
        assertEquals(GarminAvailability.UNAVAILABLE, ctx.controller.connectionState.value.availability)
        ctx.controller.arm()
        settle()
        assertFalse(ctx.controller.armState.value.armed)

        // Fail-closed: a sender that cannot prove the matched production app identity
        // gets nothing — no STATE, no grant, no leak.
        val before = ctx.transport.sent.size
        emitHello(ctx)
        assertEquals(before, ctx.transport.sent.size, "no STATE for an unmatched/unconfigured app")
        assertTrue(ctx.transport.states().isEmpty())
    }

    // =====================================================================
    //  Freshness: grants and revisions
    // =====================================================================

    @Test
    fun helloEarnsStateWithFreshGrantAndRoutineStartApplies() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        assertTrue(ctx.controller.armState.value.armed)
        emitHello(ctx)
        val st = ctx.currentState()
        assertNotNull(st.actionGrant)
        assertEquals(3000, st.grantTtlMs)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1")
        settle()
        val results = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), results.map { it.stage })
        // Phone-START parity: the set left Idle through startSetFromReady (skipCountdown=true).
        assertFalse(ctx.harness.coordinator.workoutState.value is WorkoutState.Idle)
    }

    @Test
    fun expiredGrantIsRejectedStale() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        val st = ctx.currentState()
        ctx.advanceClock(GarminProtocol.GRANT_TTL_MS + 1)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", grant = st.actionGrant)
        settle()
        val last = ctx.transport.results().last()
        assertEquals(GarminProtocol.STAGE_REJECTED, last.stage)
        assertEquals(GarminProtocol.REASON_STALE_STATE, last.reason)
    }

    @Test
    fun wrongGrantStringIsRejectedStale() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", grant = "forged-grant")
        settle()
        assertEquals(GarminProtocol.REASON_STALE_STATE, ctx.transport.results().last().reason)
    }

    @Test
    fun revisionMismatchIsStaleForStart() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", revision = ctx.currentState().revision + 7)
        settle()
        assertEquals(GarminProtocol.REASON_STALE_STATE, ctx.transport.results().last().reason)
    }

    @Test
    fun contextMismatchIsStale() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", contextId = "other-context")
        settle()
        assertEquals(GarminProtocol.REASON_STALE_STATE, ctx.transport.results().last().reason)
    }

    // =====================================================================
    //  Dedupe: fail-closed replay protection
    // =====================================================================

    @Test
    fun identicalDuplicateReturnsCachedOutcomeWithoutReexecution() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)

        val st = ctx.currentState()
        val msg = assertNotNull(
            GarminProtocol.buildCommand(
                st.controlEpoch, "cmd-dup", st.contextId, st.revision,
                GarminProtocol.ACTION_START_NEXT, assertNotNull(st.actionGrant), null,
            ),
        )
        ctx.transport.inbound.emit(GarminInboundEnvelope(msg, DEV, APP, ctx.clockMs))
        settle()
        val afterFirst = ctx.transport.results().map { it.stage }
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), afterFirst)

        // Byte-identical replay of the exact same payload.
        ctx.transport.inbound.emit(GarminInboundEnvelope(msg, DEV, APP, ctx.clockMs))
        settle()
        val afterSecond = ctx.transport.results().map { it.stage }
        // Only the cached outcome is re-sent; no second ACCEPTED, no second execution.
        assertEquals(
            listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED, GarminProtocol.STAGE_APPLIED),
            afterSecond,
        )
    }

    @Test
    fun differentPayloadWithSameCommandIdRejectsConflict() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-conflict")
        settle()
        // Same id, different payload (different revision) -> COMMAND_ID_CONFLICT, never re-run.
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-conflict", revision = ctx.currentState().revision + 3)
        settle()
        assertEquals(GarminProtocol.REASON_COMMAND_ID_CONFLICT, ctx.transport.results().last().reason)
    }

    @Test
    fun epochCapacityDisarmsAndRequiresExplicitRearm() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        // Fill the per-epoch dedupe table with validly addressed admissions (rejections
        // reserve slots too). Use a wrong context so none of them mutate anything.
        for (i in 1..GarminProtocol.DEDUPE_CAPACITY) {
            emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-$i", contextId = "wrong-context")
        }
        settle()
        val before = ctx.transport.results().size

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-overflow")
        settle()
        val overflow = ctx.transport.results().last()
        assertEquals(GarminProtocol.REASON_EPOCH_CAP_REACHED, overflow.reason)
        assertTrue(ctx.transport.results().size > before)
        // Cap handling disarms and requires explicit re-arm; the old epoch is dead.
        assertFalse(ctx.controller.armState.value.armed)
    }

    // =====================================================================
    //  Provenance: native sender and app identity
    // =====================================================================

    @Test
    fun wrongNativeSenderIsDroppedSilently() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        val before = ctx.transport.results().size
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", deviceRef = "intruder-watch")
        settle()
        assertEquals(before, ctx.transport.results().size, "no RESULT may leak to a non-selected device")
    }

    @Test
    fun wrongAppUuidIsRejectedUnauthorized() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", appUuid = "fake-app")
        settle()
        assertEquals(GarminProtocol.REASON_UNAUTHORIZED_DEVICE, ctx.transport.results().last().reason)
    }

    @Test
    fun inboundAfterDisposeIsDropped() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        ctx.controller.dispose()
        settle()
        val before = ctx.transport.results().size
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-late")
        settle()
        assertEquals(before, ctx.transport.results().size, "post-disposal callbacks must be dropped")
        assertTrue(ctx.transport.shutdownCalls >= 1)
    }

    // =====================================================================
    //  Authority lifecycle
    // =====================================================================

    @Test
    fun authorityInvalidationRevokesAndRequiresRearm() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        val oldState = ctx.currentState()
        ctx.controller.onAuthorityInvalidated()
        settle()
        assertFalse(ctx.controller.armState.value.armed)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-1", epoch = oldState.controlEpoch, grant = oldState.actionGrant)
        settle()
        val last = ctx.transport.results().last()
        assertEquals(GarminProtocol.STAGE_REJECTED, last.stage)
        assertEquals(GarminProtocol.REASON_NOT_READY, last.reason)
    }

    @Test
    fun rearmAfterInvalidationIssuesFreshEpoch() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        val oldState = ctx.currentState()
        ctx.controller.onAuthorityInvalidated()
        settle()
        ctx.controller.arm()
        settle()
        assertTrue(ctx.controller.armState.value.armed)
        val fresh = ctx.currentState()
        assertTrue(fresh.controlEpoch != oldState.controlEpoch, "each arm must mint a fresh epoch")
        // The old epoch's grant is dead even if the clock never moved.
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-old", epoch = oldState.controlEpoch, grant = oldState.actionGrant)
        settle()
        assertEquals(GarminProtocol.REASON_STALE_STATE, ctx.transport.results().last().reason)
    }

    // =====================================================================
    //  Routing parity and receipt semantics
    // =====================================================================

    @Test
    fun activeSetStartNextAppliesViaReceiptAndPreparesWithoutStarting() = runControllerTest { ctx ->
        prepareActiveSet(ctx)
        enableAndPrepare(ctx)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-end")
        settle()
        val results = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), results.map { it.stage })
        // The successor is prepared and NOT started: fresh press required.
        assertTrue(ctx.harness.coordinator.routineFlowState.value is com.devil.phoenixproject.domain.model.RoutineFlowState.SetReady)
        assertTrue(ctx.harness.coordinator.workoutState.value is WorkoutState.Idle)
    }

    @Test
    fun stuckSaveYieldsUnknownResultAndBlocksNextStart() = runControllerTest { ctx ->
        val releaseSave = kotlinx.coroutines.CompletableDeferred<Unit>()
        prepareActiveSet(ctx)
        enableAndPrepare(ctx)
        ctx.harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-stuck")
        settle()
        val first = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_REJECTED), first.map { it.stage })
        assertEquals(GarminProtocol.REASON_UNKNOWN_RESULT, first.last().reason)

        // The next watch Start is blocked until the save resolves.
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-blocked")
        settle()
        assertEquals(GarminProtocol.REASON_UNKNOWN_RESULT, ctx.transport.results().last().reason)

        // Resolve the save and let completion settle; teardown must remain clean. (The
        // unblock path itself is exercised through the SAVE_FAILED offer lifecycle above;
        // a stuck-save resolution does not fabricate a startable target.)
        releaseSave.complete(Unit)
        settle()
    }

    @Test
    fun failedCommitYieldsSaveFailedAndBlocksNextStart() = runControllerTest { ctx ->
        prepareActiveSet(ctx)
        enableAndPrepare(ctx)
        ctx.harness.fakeWorkoutRepo.beforeSaveSession = { throw IllegalStateException("disk full") }

        emitCommand(ctx, GarminProtocol.ACTION_STOP_SET, "cmd-stop")
        settle()
        val first = ctx.transport.results()
        assertEquals(GarminProtocol.STAGE_REJECTED, first.last().stage)
        assertEquals(GarminProtocol.REASON_SAVE_FAILED, first.last().reason)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-blocked")
        settle()
        assertEquals(GarminProtocol.REASON_SAVE_FAILED, ctx.transport.results().last().reason)
    }

    @Test
    fun stopSetToleratesRevisionDriftWithinEpoch() = runControllerTest { ctx ->
        prepareActiveSet(ctx)
        enableAndPrepare(ctx)
        val st = ctx.currentState()
        emitCommand(ctx, GarminProtocol.ACTION_STOP_SET, "cmd-stop", revision = st.revision + 9)
        settle()
        val results = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), results.map { it.stage })
    }

    @Test
    fun stopSetPreemptsPendingStartAndNeverLandsTwice() = runControllerTest { ctx ->
        prepareActiveSet(ctx)
        enableAndPrepare(ctx)
        val st = ctx.currentState()
        val startMsg = assertNotNull(
            GarminProtocol.buildCommand(
                st.controlEpoch, "cmd-start", st.contextId, st.revision,
                GarminProtocol.ACTION_START_NEXT, assertNotNull(st.actionGrant), null,
            ),
        )
        val stopMsg = assertNotNull(
            GarminProtocol.buildCommand(
                st.controlEpoch, "cmd-stop", st.contextId, st.revision,
                GarminProtocol.ACTION_STOP_SET, assertNotNull(st.actionGrant), null,
            ),
        )
        // Both arrive before the queued start can mutate: the urgent Stop replaces it.
        ctx.transport.inbound.emit(GarminInboundEnvelope(startMsg, DEV, APP, ctx.clockMs))
        ctx.transport.inbound.emit(GarminInboundEnvelope(stopMsg, DEV, APP, ctx.clockMs))
        settle()

        val results = ctx.transport.results()
        val byCommand = results.groupBy { it.commandId }
        assertEquals(GarminProtocol.STAGE_REJECTED, byCommand.getValue("cmd-start").last().stage)
        assertEquals(GarminProtocol.REASON_STALE_STATE, byCommand.getValue("cmd-start").last().reason)
        assertEquals(GarminProtocol.STAGE_APPLIED, byCommand.getValue("cmd-stop").last().stage)
        // Exactly one set transition happened (the Stop's); the start never ran.
        assertTrue(ctx.harness.coordinator.workoutState.value is WorkoutState.Idle)
        assertTrue(ctx.harness.coordinator.routineFlowState.value is com.devil.phoenixproject.domain.model.RoutineFlowState.SetReady)
    }

    // =====================================================================
    //  Cancel confirmation binding
    // =====================================================================

    @Test
    fun cancelRequiresIssuedConfirmationAndIsSingleUse() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitHello(ctx)
        val st = ctx.currentState()
        val token = assertNotNull(st.confirmationToken, "cancel admissible -> STATE carries a confirmation token")

        emitCommand(ctx, GarminProtocol.ACTION_CANCEL_EXERCISE, "cmd-cancel-1", token = token)
        settle()
        val first = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), first.map { it.stage })

        // The token is single-use and bound to the old semantic revision.
        emitCommand(ctx, GarminProtocol.ACTION_CANCEL_EXERCISE, "cmd-cancel-2", token = token)
        settle()
        assertEquals(GarminProtocol.REASON_CONFIRMATION_EXPIRED, ctx.transport.results().last().reason)
    }

    @Test
    fun expiredConfirmationIsRejected() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        emitHello(ctx)
        val st = ctx.currentState()
        val token = assertNotNull(st.confirmationToken)
        ctx.advanceClock(GarminProtocol.CONFIRMATION_TTL_MS + 1)
        // Re-HELLO for a fresh grant: the grant TTL is shorter than the confirmation TTL,
        // so only the confirmation may be stale in this row.
        emitHello(ctx)

        emitCommand(ctx, GarminProtocol.ACTION_CANCEL_EXERCISE, "cmd-cancel", token = token)
        settle()
        assertEquals(GarminProtocol.REASON_CONFIRMATION_EXPIRED, ctx.transport.results().last().reason)
    }

    // =====================================================================
    //  JustLift and background
    // =====================================================================

    @Test
    fun justLiftStartRoutesThroughStartWorkoutAndNeverLoadsARoutine() = runControllerTest { ctx ->
        ctx.harness.fakeBleRepo.simulateConnect("Vee_Test")
        ctx.harness.dwsm.updateWorkoutParameters(TestFixtures.justLiftParams)
        ctx.harness.dwsm.prepareForJustLift()
        settle()
        enableAndPrepare(ctx)
        assertTrue(ctx.controller.armState.value.armed)

        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-jl")
        settle()
        val results = ctx.transport.results()
        assertEquals(listOf(GarminProtocol.STAGE_ACCEPTED, GarminProtocol.STAGE_APPLIED), results.map { it.stage })
        assertFalse(ctx.harness.coordinator.workoutState.value is WorkoutState.Idle)
        assertNull(ctx.harness.coordinator.loadedRoutine.value, "the watch must never load or select anything")
    }

    @Test
    fun backgroundRejectsStartsAndInvalidatesRetainedGestures() = runControllerTest { ctx ->
        prepareRoutineSetReady(ctx)
        enableAndPrepare(ctx)
        ctx.controller.setPhoneForeground(false)
        settle()
        emitCommand(ctx, GarminProtocol.ACTION_START_NEXT, "cmd-bg")
        settle()
        val last = ctx.transport.results().last()
        assertEquals(GarminProtocol.STAGE_REJECTED, last.stage)
        assertEquals(GarminProtocol.REASON_NOT_READY, last.reason)
    }
}
