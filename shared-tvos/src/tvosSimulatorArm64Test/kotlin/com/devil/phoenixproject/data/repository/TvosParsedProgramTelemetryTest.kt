package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.presentation.manager.ExecutionLease
import com.devil.phoenixproject.presentation.manager.RepFreshnessDecision
import com.devil.phoenixproject.presentation.manager.RepNotificationFreshnessGate
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.CommandLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Real Phantom transport and production packet builders/counter; no UI completion shortcuts. */
class TvosParsedProgramTelemetryTest {
    @Test
    fun defaultPhonePollingDoesNotGenerateParsedProgramReps() = runTest {
        withContext(Dispatchers.Default) {
            assertFalse(PhantomBleConfig.Default.simulateParsedProgramReps)
            withFixture(PhantomBleConfig(repDelayMs = 100L)) { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                repository.startActiveWorkoutPolling()
                assertSilent(events)
                // The pre-existing simulator-local API keeps its original counter semantics.
                repository.startWorkout(parameters()).getOrThrow()
                val first = next(events)
                assertEquals(1, first.repsRomCount)
                assertEquals(1, first.repsSetCount)
            }
        }
    }

    @Test
    fun parsedRegularProgramSeparatesWarmupAndWorkingRepsAndCompletesTheRealCounter() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                assertEquals(3, repository.currentProgram?.warmupReps)
                assertEquals(2, repository.currentProgram?.workingReps)
                assertSilent(events) // Sending CONFIG alone must not start the stream.
                val lease = ExecutionLease(
                    executionId = 1L,
                    sessionId = "tv-telemetry-test",
                    profileId = "tv-telemetry-test",
                    requiresMachine = true,
                    workingRepTarget = 2,
                    isBodyweight = false,
                    isJustLift = false,
                    isAmrap = false,
                    isTimedCable = false,
                    activationCutoverTimestampMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                )
                val freshness = RepNotificationFreshnessGate().apply { resetFor(lease) }
                repository.startActiveWorkoutPolling()
                val counter = RepCounterFromMachine().apply {
                    configure(warmupTarget = 3, workingTarget = 2, isJustLift = false, stopAtTop = false)
                }
                for (rep in 0..5) {
                    val event = next(events)
                    assertEquals(rep, event.topCounter)
                    assertEquals(rep, event.completeCounter)
                    assertEquals(minOf(rep, 3), event.repsRomCount)
                    assertEquals(3, event.repsRomTotal)
                    assertEquals((rep - 3).coerceAtLeast(0), event.repsSetCount)
                    assertEquals(2, event.repsSetTotal)
                    assertFalse(event.isLegacyFormat)
                    assertTrue(event.rawData.isEmpty(), "Synthetic events must not pretend to be captured wire bytes")
                    assertEquals(
                        if (rep == 0) RepFreshnessDecision.BaselineOnly else RepFreshnessDecision.Process,
                        freshness.evaluate(lease, event),
                    )
                    counter.process(
                        repsRomCount = event.repsRomCount,
                        repsRomTotal = event.repsRomTotal,
                        repsSetCount = event.repsSetCount,
                        repsSetTotal = event.repsSetTotal,
                        up = event.topCounter,
                        down = event.completeCounter,
                    )
                    assertEquals(rep == 5, counter.shouldStopWorkout())
                    if (rep == 1) repository.startActiveWorkoutPolling() // Must not rewind an active replay.
                }
                assertEquals(2, counter.getRepCount().workingReps)
                repository.startActiveWorkoutPolling() // A completed CONFIG is also one-shot.
                assertSilent(events)
                repository.stopWorkout().getOrThrow()
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                repository.startActiveWorkoutPolling()
                assertEquals(0, next(events).completeCounter) // Explicit next CONFIG starts a fresh set.
                assertEquals(1, next(events).completeCounter)
            }
        }
    }

    @Test
    fun echoProgramUsesParsedTargetsIncludingZeroWarmup() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(
                    BlePacketFactory.createEchoControl(level = EchoLevel.HARDER, warmupReps = 0, targetReps = 1),
                ).getOrThrow()
                repository.startActiveWorkoutPolling()
                val baseline = next(events)
                assertEquals(0, baseline.completeCounter)
                assertEquals(0, baseline.repsRomTotal)
                assertEquals(1, baseline.repsSetTotal)
                val final = next(events)
                assertEquals(0, final.repsRomCount)
                assertEquals(1, final.repsSetCount)
                assertSilent(events)
            }
        }
    }

    @Test
    fun unlimitedProgramContinuesWithoutAReportedFiniteTarget() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand(isAMRAP = true)).getOrThrow()
                repository.startActiveWorkoutPolling()
                for (rep in 0..7) {
                    val event = next(events)
                    assertEquals(rep, event.completeCounter)
                    assertEquals((rep - 3).coerceAtLeast(0), event.repsSetCount)
                    // Firmware reports the command's 0xFF unlimited sentinel as 252.
                    assertEquals(252, event.repsSetTotal)
                }
            }
        }
    }

    @Test
    fun missingProgramAndDisconnectedOrCancelledConnectionsCannotReplay() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                repository.startActiveWorkoutPolling()
                assertSilent(events)
                connect(repository)
                repository.startActiveWorkoutPolling()
                assertSilent(events) // A disconnected CONFIG cannot migrate into a connection.
                repository.disconnect()
                connect(repository)
                repository.sendWorkoutCommand(BlePacketFactory.createResetCommand()).getOrThrow()
                repository.startActiveWorkoutPolling()
                assertSilent(events) // No parsed workout program.
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                val reconnect = async { repository.connect(device()) }
                repository.connectionState.first { it == ConnectionState.Connecting }
                repository.startActiveWorkoutPolling()
                reconnect.cancelAndJoin()
                connect(repository)
                repository.startActiveWorkoutPolling()
                assertSilent(events)
            }
        }
    }

    @Test
    fun everyTeardownCancelsReplayAndPollingCannotResurrectIt() = runTest {
        withContext(Dispatchers.Default) {
            for (teardown in listOf("polling", "workout", "disconnect", "shutdown")) {
                withFixture { repository, events ->
                    connect(repository)
                    repository.sendWorkoutCommand(regularCommand(isAMRAP = true)).getOrThrow()
                    repository.startActiveWorkoutPolling()
                    assertEquals(0, next(events).completeCounter)
                    assertEquals(1, next(events).completeCounter)
                    when (teardown) {
                        "polling" -> repository.stopPolling()
                        "workout" -> repository.stopWorkout().getOrThrow()
                        "disconnect" -> repository.disconnect()
                        "shutdown" -> repository.shutdown()
                    }
                    // Ignore only events already queued before teardown; observe a fresh quiet window.
                    while (events.tryReceive().isSuccess) Unit
                    repository.startActiveWorkoutPolling()
                    assertSilent(events)
                    if (teardown == "disconnect") {
                        connect(repository)
                        repository.startActiveWorkoutPolling()
                        assertSilent(events)
                    }
                    if (teardown == "shutdown") {
                        assertTrue(repository.connect(device()).isFailure)
                        assertTrue(repository.sendWorkoutCommand(regularCommand()).isFailure)
                    }
                }
            }
        }
    }

    @Test
    fun stopPollingBeforeStartupDisarmsPendingProgram() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                repository.stopPolling()
                repository.startActiveWorkoutPolling()
                assertSilent(events)
            }
        }
    }

    @Test
    fun reentrantStopDuringActivePollingHandoffCannotLaunchReplay() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand()).getOrThrow()
                val stopOnGrab = async(Dispatchers.Unconfined) {
                    repository.handleState.first {
                        if (it == HandleState.Grabbed) {
                            repository.stopPolling()
                            true
                        } else false
                    }
                }
                repository.startActiveWorkoutPolling()
                withTimeout(3_000L) { stopOnGrab.await() }
                assertSilent(events)
            }
        }
    }

    @Test
    fun reentrantStopDuringCommandCannotArmReplay() = runTest {
        withContext(Dispatchers.Default) {
            val logs = ConnectionLogRepository()
            withFixture(logRepo = logs) { repository, events ->
                connect(repository)
                val stopOnCommand = async(Dispatchers.Unconfined) {
                    logs.logs.first { entries ->
                        if (entries.any { it.message == "Phantom received raw workout command" }) {
                            repository.stopPolling()
                            true
                        } else false
                    }
                }
                assertTrue(repository.sendWorkoutCommand(regularCommand()).isFailure)
                withTimeout(3_000L) { stopOnCommand.await() }
                repository.startActiveWorkoutPolling()
                assertSilent(events)
            }
        }
    }

    @Test
    fun directReconnectCannotReplayPriorProgram() = runTest {
        withContext(Dispatchers.Default) {
            withFixture { repository, events ->
                connect(repository)
                repository.sendWorkoutCommand(regularCommand(isAMRAP = true)).getOrThrow()
                repository.startActiveWorkoutPolling()
                next(events)
                next(events)
                connect(repository) // No intervening disconnect/teardown.
                while (events.tryReceive().isSuccess) Unit
                repository.startActiveWorkoutPolling()
                assertSilent(events)
            }
        }
    }

    private fun parameters(isAMRAP: Boolean = false) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 2,
        warmupReps = 3,
        weightPerCableKg = 20f,
        isAMRAP = isAMRAP,
    )

    private fun regularCommand(isAMRAP: Boolean = false): ByteArray = BlePacketFactory.createProgramParams(
        parameters(isAMRAP),
        maxWeightPerCableKg = CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG,
    )

    private fun device() = ScannedDevice(
        PhantomBleRepository.PHANTOM_DEVICE_NAME,
        PhantomBleRepository.PHANTOM_DEVICE_ADDRESS,
    )

    private suspend fun connect(repository: PhantomBleRepository) {
        repository.connect(device()).getOrThrow()
    }

    private suspend fun next(events: ReceiveChannel<RepNotification>): RepNotification =
        withTimeout(3_000L) { events.receive() }

    private suspend fun assertSilent(events: ReceiveChannel<RepNotification>) {
        assertNull(withTimeoutOrNull(350L) { events.receive() }, "Unexpected simulated rep after lifecycle boundary")
    }

    private suspend fun withFixture(
        config: PhantomBleConfig = PhantomBleConfig(repDelayMs = 100L, simulateParsedProgramReps = true),
        logRepo: ConnectionLogRepository = ConnectionLogRepository(),
        block: suspend (PhantomBleRepository, ReceiveChannel<RepNotification>) -> Unit,
    ) = coroutineScope {
        val repository = PhantomBleRepository(logRepo, config)
        val events = Channel<RepNotification>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.repEvents.collect { events.send(it) }
        }
        try {
            block(repository, events)
        } finally {
            repository.shutdown()
            collector.cancelAndJoin()
            events.cancel()
        }
    }
}
