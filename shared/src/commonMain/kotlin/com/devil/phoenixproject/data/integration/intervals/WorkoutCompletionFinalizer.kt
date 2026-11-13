package com.devil.phoenixproject.data.integration.intervals

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.WorkoutCompletionMembership
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.WorkoutCompletionJournal as WorkoutCompletionJournalRow
import com.devil.phoenixproject.database.WorkoutCompletionJournalChild as WorkoutCompletionJournalChildRow
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ==================== engine seam (issue #1384 packet P2) ====================

/**
 * Immutable identity/timing facts of one completion as the producer knows them at the
 * finish moment. Consent/destination facts are NOT resolved here — they are captured
 * synchronously by [RoutineCompletionFinalizer.captureCompletionContext] at admission.
 */
data class RoutineCompletionRequest(
    val profileId: String,
    val localKind: IntervalsLocalKind,
    /** Local completion key: routineSessionId for routines, session id for standalone. */
    val completionId: String,
    val routineTitle: String?,
    val finishReason: IntervalsFinishReason,
    /** Literal wire identity: exactly routineSessionId, else standalone session id. */
    val externalId: String,
    val startEpochMs: Long,
    val startLocalIso: String,
    val timeZoneId: String,
    val utcOffsetMinutes: Int,
    val completedEpochMs: Long,
)

/** Completion-time consent/destination facts, resolved once and never re-read later. */
data class CompletionEligibility(
    val installationId: String,
    val eligible: Boolean,
    val resolvedAthleteId: String?,
    val connectionGeneration: Long,
) {
    companion object {
        /** Safe default: the completion is recorded with `eligible=false` and no job. */
        val Ineligible = CompletionEligibility(
            installationId = "",
            eligible = false,
            resolvedAthleteId = null,
            connectionGeneration = 0L,
        )
    }
}

/**
 * Resolves the completion-time eligibility/destination binding synchronously at admission.
 * Implemented by P3's connection store; tests inject deterministic fakes. Never consults a
 * mutable late active-profile lookup.
 */
fun interface CompletionEligibilityResolver {
    fun resolve(profileId: String, atEpochMs: Long): CompletionEligibility
}

/** One staged replay input for a child whose durable write is still pending at admission. */
data class PendingChildReplay(
    val childKey: String,
    val write: PendingChildWrite,
)

/** Result of one FINALIZE admission. Local only — never an HTTP outcome. */
sealed class AdmissionResult {
    /** Durably accepted: the completion UI may proceed and runtime cleanup may run. */
    data object Accepted : AdmissionResult()

    /** The exact same completion was already admitted. Idempotent success. */
    data object AlreadyAdmitted : AdmissionResult()

    /** Same completion key with a conflicting membership/destination/time digest. */
    data class Conflicting(val reason: String) : AdmissionResult()

    /** Local disk failure: surface persistence failure and preserve the owning state. */
    data class PersistenceFailed(val reason: String) : AdmissionResult()

    /** No qualifying work (zero-rep / abandoned): nothing admitted, no receipt ever. */
    data object NoQualifyingWork : AdmissionResult()
}

/**
 * Narrow finalization seam shared by every routine-finish path (ActiveSessionEngine,
 * DefaultWorkoutSessionManager). Both methods are deliberately non-suspending: they are
 * bounded local persistence run inline at the finish moment so the gate is real and no
 * finish path can acknowledge completion without durable acceptance. Nothing here waits
 * on HTTP; BLE teardown/machine safety never awaits this seam.
 */
interface RoutineCompletionFinalizer {
    /** Captures the immutable completion context (eligibility included) at this moment. */
    fun captureCompletionContext(request: RoutineCompletionRequest): IntervalsCompletionCapture

    /**
     * Durably admits the completion (`REQUESTED` journal + exact expected membership).
     * Returns [AdmissionResult.Accepted] / [AdmissionResult.AlreadyAdmitted] only when the
     * admission is durable; anything else must block the routine-finalized acknowledgement.
     */
    fun requestFinalize(
        capturedContext: IntervalsCompletionCapture,
        expectedChildren: List<IntervalsChildRef>,
        replayInputs: List<PendingChildReplay>,
    ): AdmissionResult

    companion object {
        /**
         * No-op default for construction sites not yet threaded (P3 wires the real
         * finalizer). It acknowledges without persisting, which is why every call site
         * must also handle [AdmissionResult] failures explicitly once wired.
         */
        val NoOp: RoutineCompletionFinalizer = object : RoutineCompletionFinalizer {
            override fun captureCompletionContext(
                request: RoutineCompletionRequest,
            ): IntervalsCompletionCapture = IntervalsCompletionCapture(
                key = IntervalsCompletionKey(
                    profileId = request.profileId,
                    localKind = request.localKind,
                    completionId = request.completionId,
                ),
                installationId = "",
                finishReason = request.finishReason,
                routineTitle = request.routineTitle,
                externalId = request.externalId,
                startEpochMs = request.startEpochMs,
                startLocalIso = request.startLocalIso,
                timeZoneId = request.timeZoneId,
                utcOffsetMinutes = request.utcOffsetMinutes,
                completedEpochMs = request.completedEpochMs,
                eligible = false,
                resolvedAthleteId = null,
                connectionGeneration = 0L,
            )

            override fun requestFinalize(
                capturedContext: IntervalsCompletionCapture,
                expectedChildren: List<IntervalsChildRef>,
                replayInputs: List<PendingChildReplay>,
            ): AdmissionResult = AdmissionResult.Accepted
        }
    }
}

/** Outcome of one local finalization pass over a `REQUESTED` journal row. */
sealed class FinalizeOutcome {
    data class Finalized(
        val key: IntervalsCompletionKey,
        val eligible: Boolean,
        val jobCreated: Boolean,
        val exportPayloadStored: Boolean,
        val builderFailure: String? = null,
        val externalIdCollision: Boolean = false,
    ) : FinalizeOutcome()

    /** Missing/malformed children: the journal stays `REQUESTED`, nothing is published. */
    data class Blocked(val key: IntervalsCompletionKey, val reason: String) : FinalizeOutcome()

    /** The receipt/outbox transaction rolled back; the request remains recoverable. */
    data class Failed(val key: IntervalsCompletionKey, val reason: String) : FinalizeOutcome()
}

// ==================== zone capture ====================

/**
 * Zone capture at completion admission. The builder never calls this — retries rebuild the
 * wire body from the persisted capture without re-reading the device zone (DST/travel safe).
 */
object CompletionTimeCapture {
    data class LocalStart(
        val startLocalIso: String,
        val timeZoneId: String,
        val utcOffsetMinutes: Int,
    )

    fun captureLocalStart(startEpochMs: Long): LocalStart {
        val zone = TimeZone.currentSystemDefault()
        val instant = Instant.fromEpochMilliseconds(startEpochMs)
        val local = instant.toLocalDateTime(zone)
        val offsetMinutes = (
            local.toInstant(TimeZone.of("UTC")).toEpochMilliseconds() - startEpochMs
            ) / 60_000L
        return LocalStart(
            startLocalIso = formatLocalIso(local),
            timeZoneId = zone.id,
            utcOffsetMinutes = offsetMinutes.toInt(),
        )
    }

    private fun formatLocalIso(local: kotlinx.datetime.LocalDateTime): String = buildString {
        append(local.year.toString().padStart(4, '0'))
        append('-')
        append(local.month.number.toString().padStart(2, '0'))
        append('-')
        append(local.day.toString().padStart(2, '0'))
        append('T')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
        append(':')
        append(local.second.toString().padStart(2, '0'))
    }
}

// ==================== finalizer ====================

/** Serializable placeholder payload for a job that could not build a real activity. */
@Serializable
private data class UnavailableJobPayload(val unavailable: String)

/**
 * FINALIZE admission + local finalize replay for the Intervals completion journal
 * (issue #1384, integration-model §3.2/§3.3). **No network I/O, ever.**
 *
 * Admission durably persists a `REQUESTED` journal row with the exact expected membership
 * and staged replay payloads for pending children; it is idempotent for the same
 * completion and a conflicting membership/destination/time digest is a local failure that
 * never replaces the stored admission. [finalizePending] replays pending child writes
 * through the completion-aware [WorkoutRepository] entry point and then, in ONE database
 * transaction, verifies every expected child and its ownership, freezes the canonical
 * export input via [IntervalsActivityBuilder], inserts the unique `FINALIZED` receipt,
 * reserves the literal wire identity, inserts the outbox job when eligible, and marks the
 * journal `FINALIZED`. Missing or malformed children block finalization — the surviving
 * subset is never published. Disabled/paused completions record `eligible=false` receipts
 * and create no job. Invalid payloads keep a final receipt plus a safe per-job failure.
 */
class WorkoutCompletionFinalizer(
    private val db: PhoenixDatabase,
    private val workoutRepository: WorkoutRepository,
    private val eligibilityResolver: CompletionEligibilityResolver =
        CompletionEligibilityResolver { _, _ -> CompletionEligibility.Ineligible },
    private val now: () -> Long = { currentTimeMillis() },
    /**
     * Fired after a completion is DURABLY admitted (journal + staged children committed).
     * Wired to [IntervalsExportCoordinator.signalCompletion] so a finished workout is
     * exported while the app stays open, instead of waiting for the next resume. The
     * callback must never be able to fail an admission: any throw is logged and dropped.
     */
    private val onDurableAdmission: () -> Unit = {},
) : RoutineCompletionFinalizer {

    private val queries: PhoenixDatabaseQueries = db.phoenixDatabaseQueries

    override fun captureCompletionContext(
        request: RoutineCompletionRequest,
    ): IntervalsCompletionCapture {
        val eligibility = eligibilityResolver.resolve(request.profileId, request.completedEpochMs)
        return IntervalsCompletionCapture(
            key = IntervalsCompletionKey(
                profileId = request.profileId,
                localKind = request.localKind,
                completionId = request.completionId,
            ),
            installationId = eligibility.installationId,
            finishReason = request.finishReason,
            routineTitle = request.routineTitle,
            externalId = request.externalId,
            startEpochMs = request.startEpochMs,
            startLocalIso = request.startLocalIso,
            timeZoneId = request.timeZoneId,
            utcOffsetMinutes = request.utcOffsetMinutes,
            completedEpochMs = request.completedEpochMs,
            eligible = eligibility.eligible,
            resolvedAthleteId = eligibility.resolvedAthleteId,
            connectionGeneration = eligibility.connectionGeneration,
        )
    }

    override fun requestFinalize(
        capturedContext: IntervalsCompletionCapture,
        expectedChildren: List<IntervalsChildRef>,
        replayInputs: List<PendingChildReplay>,
    ): AdmissionResult {
        if (expectedChildren.isEmpty()) return AdmissionResult.NoQualifyingWork
        val capture = capturedContext
        if (capture.externalId.isBlank() || capture.key.completionId.isBlank() || capture.key.profileId.isBlank()) {
            return AdmissionResult.Conflicting("empty completion identity")
        }
        if (expectedChildren.any { it.childKey.isBlank() || it.sessionId.isBlank() }) {
            return AdmissionResult.Conflicting("blank child identity")
        }
        if (expectedChildren.map { it.childKey }.toSet().size != expectedChildren.size) {
            return AdmissionResult.Conflicting("duplicate child key")
        }
        val replayByKey = replayInputs.associateBy { it.childKey }
        if (replayByKey.size != replayInputs.size) {
            return AdmissionResult.Conflicting("duplicate replay input")
        }
        val pendingKeys = expectedChildren.filter { it.pendingAtAdmission }.map { it.childKey }.toSet()
        if (replayByKey.keys != pendingKeys) {
            return AdmissionResult.Conflicting("replay inputs do not match pending children")
        }
        // A staged payload must be replayable NOW; a malformed one would block forever.
        replayInputs.forEach { replay ->
            val encoded = CompletionJournalCodec.encode(replay.write)
            if (CompletionJournalCodec.decode(encoded) == null) {
                return AdmissionResult.Conflicting("malformed replay payload for ${replay.childKey}")
            }
        }

        val membershipDigest = canonicalMembership(expectedChildren)
        val destinationDigest = canonicalDestination(capture)
        val nowMs = now()
        val outcome = try {
            db.transactionWithResult {
                val inserted = queries.insertCompletionJournalIfAbsent(
                    profile_id = capture.key.profileId,
                    local_kind = capture.key.localKind.name,
                    completion_id = capture.key.completionId,
                    finish_reason = capture.finishReason.name,
                    routine_title = capture.routineTitle,
                    expected_session_count = expectedChildren.map { it.sessionId }.toSet().size.toLong(),
                    expected_set_count = expectedChildren.count { it.childKind == IntervalsChildKind.COMPLETED_SET }.toLong(),
                    membership_digest = membershipDigest,
                    start_epoch_ms = capture.startEpochMs,
                    start_local_iso = capture.startLocalIso,
                    time_zone_id = capture.timeZoneId,
                    utc_offset_minutes = capture.utcOffsetMinutes.toLong(),
                    completed_epoch_ms = capture.completedEpochMs,
                    eligible = if (capture.eligible) 1L else 0L,
                    resolved_athlete_id = capture.resolvedAthleteId,
                    connection_generation = capture.connectionGeneration,
                    destination_digest = destinationDigest,
                    created_at = nowMs,
                ).value
                expectedChildren.sortedBy { it.childKey }.forEach { child ->
                    queries.insertCompletionJournalChildIfAbsent(
                        journal_profile_id = capture.key.profileId,
                        journal_local_kind = capture.key.localKind.name,
                        journal_completion_id = capture.key.completionId,
                        child_key = child.childKey,
                        child_kind = child.childKind.name,
                        session_id = child.sessionId,
                        codec_version = CompletionJournalCodec.CURRENT_VERSION.toLong(),
                        replay_payload = replayByKey[child.childKey]
                            ?.let { CompletionJournalCodec.encode(it.write) },
                    )
                }
                // Children whose writes already landed are recorded as committed within
                // this same admission transaction (their replay payloads are never staged).
                expectedChildren.filterNot { it.pendingAtAdmission }.forEach { child ->
                    queries.markCompletionChildCommitted(
                        committed_at = nowMs,
                        journal_profile_id = capture.key.profileId,
                        journal_local_kind = capture.key.localKind.name,
                        journal_completion_id = capture.key.completionId,
                        child_key = child.childKey,
                    )
                }
                // Readback after ignored inserts must confirm identical content.
                val stored = queries.selectCompletionJournal(
                    profile_id = capture.key.profileId,
                    local_kind = capture.key.localKind.name,
                    completion_id = capture.key.completionId,
                ).executeAsOneOrNull()
                val storedChildren = queries.selectCompletionJournalChildren(
                    journal_profile_id = capture.key.profileId,
                    journal_local_kind = capture.key.localKind.name,
                    journal_completion_id = capture.key.completionId,
                ).executeAsList()
                val identical = stored != null &&
                    stored.membership_digest == membershipDigest &&
                    stored.destination_digest == destinationDigest &&
                    stored.finish_reason == capture.finishReason.name &&
                    stored.start_epoch_ms == capture.startEpochMs &&
                    stored.start_local_iso == capture.startLocalIso &&
                    stored.time_zone_id == capture.timeZoneId &&
                    stored.utc_offset_minutes == capture.utcOffsetMinutes.toLong() &&
                    stored.completed_epoch_ms == capture.completedEpochMs &&
                    (stored.eligible == 1L) == capture.eligible &&
                    stored.resolved_athlete_id == capture.resolvedAthleteId &&
                    stored.connection_generation == capture.connectionGeneration &&
                    canonicalMembershipRows(storedChildren) == membershipDigest
                if (!identical) {
                    throw AdmissionRollback(
                        AdmissionResult.Conflicting("conflicting membership/destination/time digest on readback"),
                    )
                }
                if (inserted == 1L) AdmissionResult.Accepted else AdmissionResult.AlreadyAdmitted
            }
        } catch (rollback: AdmissionRollback) {
            rollback.result
        } catch (error: Exception) {
            Logger.e(error) { "Intervals completion admission failed for ${capture.key.completionId}" }
            AdmissionResult.PersistenceFailed(error.message ?: "admission write failed")
        }
        if (outcome is AdmissionResult.Accepted) {
            // A new durable admission is exactly the moment a finished workout becomes
            // exportable; the drain must not wait for the next app resume. A failing
            // signal must never fail the admission itself.
            runCatching { onDurableAdmission() }
                .onFailure { Logger.w(it) { "Intervals: completion drain signal failed" } }
        }
        return outcome
    }

    /**
     * Local finalize replay for every `REQUESTED` journal row. Replays staged pending
     * child writes through [WorkoutRepository.commitCompletedSetWithCompletion], then runs
     * the single receipt/outbox transaction per completion. Returns one outcome per row.
     *
     * Rows are isolated: a permanently failing row reports `Blocked` and the remaining
     * rows still finalize (one bad replay must never block every later completion).
     */
    suspend fun finalizePending(): List<FinalizeOutcome> =
        queries.selectRequestedCompletions().executeAsList().map { journal ->
            try {
                finalizeOne(journal)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Logger.e(t) {
                    "Intervals finalize failed for ${journal.completion_id}; continuing batch"
                }
                FinalizeOutcome.Blocked(
                    IntervalsCompletionKey(
                        profileId = journal.profile_id,
                        localKind = runCatching { IntervalsLocalKind.valueOf(journal.local_kind) }
                            .getOrDefault(IntervalsLocalKind.ROUTINE),
                        completionId = journal.completion_id,
                    ),
                    "finalization failed: ${t.message ?: t::class.simpleName ?: "unexpected error"}",
                )
            }
        }

    private suspend fun finalizeOne(journal: WorkoutCompletionJournalRow): FinalizeOutcome {
        val key = IntervalsCompletionKey(
            profileId = journal.profile_id,
            localKind = IntervalsLocalKind.valueOf(journal.local_kind),
            completionId = journal.completion_id,
        )

        // Phase 1: replay every staged pending child write (idempotent; same ids).
        val childrenAtStart = queries.selectCompletionJournalChildren(
            journal_profile_id = journal.profile_id,
            journal_local_kind = journal.local_kind,
            journal_completion_id = journal.completion_id,
        ).executeAsList()
        for (child in childrenAtStart) {
            if (child.committed == 1L) continue
            val payload = child.replay_payload
                ?: return FinalizeOutcome.Blocked(key, "child ${child.child_key} has no replay payload")
            val write = CompletionJournalCodec.decode(payload)
                ?: return FinalizeOutcome.Blocked(key, "child ${child.child_key} replay payload malformed")
            if (write.session.id != child.session_id) {
                return FinalizeOutcome.Blocked(key, "child ${child.child_key} payload session mismatch")
            }
            val membership = WorkoutCompletionMembership(
                profileId = journal.profile_id,
                localKind = key.localKind,
                completionId = journal.completion_id,
                sessionId = child.session_id,
                childKey = child.child_key,
                childKind = IntervalsChildKind.valueOf(child.child_kind),
                // Timed-mode evidence travels with the set row (TIMER_EXPIRED); the replay
                // must not invent repetitions, so no extra flag is needed here.
                timedModeEvidence = write.completedSet?.setEndReason == SetEndReason.TIMER_EXPIRED,
                standaloneCapture = null,
            )
            workoutRepository.commitCompletedSetWithCompletion(
                session = write.session,
                metrics = write.metrics,
                completedSet = write.completedSet,
                repMetrics = write.repMetrics,
                repBiomechanics = write.repBiomechanics,
                completion = membership,
            )
        }

        // Phase 2: ONE transaction — verify, build, receipt, reserve, job, FINALIZED.
        return try {
            db.transactionWithResult {
                val children = queries.selectCompletionJournalChildren(
                    journal_profile_id = journal.profile_id,
                    journal_local_kind = journal.local_kind,
                    journal_completion_id = journal.completion_id,
                ).executeAsList()
                if (children.isEmpty()) {
                    throw FinalizeRollback(FinalizeOutcome.Blocked(key, "journal has no children"))
                }
                if (canonicalMembershipRows(children) != journal.membership_digest) {
                    throw FinalizeRollback(FinalizeOutcome.Blocked(key, "membership drift since admission"))
                }
                val distinctSessions = children.map { it.session_id }.toSet()
                val setChildren = children.count { it.child_kind == IntervalsChildKind.COMPLETED_SET.name }
                if (distinctSessions.size.toLong() != journal.expected_session_count ||
                    setChildren.toLong() != journal.expected_set_count
                ) {
                    throw FinalizeRollback(FinalizeOutcome.Blocked(key, "expected membership counts drifted"))
                }

                val rows = mutableListOf<IntervalsActivitySessionRow>()
                for (sessionId in children.map { it.session_id }.distinct()) {
                    val sessionRow = queries.selectSessionById(sessionId).executeAsOneOrNull()
                        ?: throw FinalizeRollback(FinalizeOutcome.Blocked(key, "missing session $sessionId"))
                    if (sessionRow.profile_id != journal.profile_id) {
                        throw FinalizeRollback(
                            FinalizeOutcome.Blocked(key, "session $sessionId owned by ${sessionRow.profile_id}"),
                        )
                    }
                    val sets = children
                        .filter { it.session_id == sessionId && it.child_kind == IntervalsChildKind.COMPLETED_SET.name }
                        .map { child ->
                            val setRow = queries.selectCompletedSetById(child.child_key).executeAsOneOrNull()
                                ?: throw FinalizeRollback(
                                    FinalizeOutcome.Blocked(key, "missing completed set ${child.child_key}"),
                                )
                            if (setRow.session_id != child.session_id) {
                                throw FinalizeRollback(
                                    FinalizeOutcome.Blocked(key, "set ${child.child_key} session mismatch"),
                                )
                            }
                            setRow.toDomain()
                        }
                    rows += IntervalsActivitySessionRow(sessionRow.toDomainSession(), sets)
                }

                val capture = IntervalsCompletionCapture(
                    key = key,
                    installationId = "",
                    finishReason = IntervalsFinishReason.valueOf(journal.finish_reason),
                    routineTitle = journal.routine_title,
                    externalId = journal.completion_id,
                    startEpochMs = journal.start_epoch_ms,
                    startLocalIso = journal.start_local_iso,
                    timeZoneId = journal.time_zone_id,
                    utcOffsetMinutes = journal.utc_offset_minutes.toInt(),
                    completedEpochMs = journal.completed_epoch_ms,
                    eligible = journal.eligible == 1L,
                    resolvedAthleteId = journal.resolved_athlete_id,
                    connectionGeneration = journal.connection_generation ?: 0L,
                )

                val writeResult = runFinalizationWrites(
                    capture = capture,
                    membershipRows = children.map { ChildFact(it.child_key, it.child_kind, it.session_id) },
                    rows = rows,
                    nowMs = now(),
                )

                queries.markCompletionJournalFinalized(
                    now = now(),
                    profile_id = journal.profile_id,
                    local_kind = journal.local_kind,
                    completion_id = journal.completion_id,
                )
                writeResult
            }
        } catch (rollback: FinalizeRollback) {
            rollback.outcome
        } catch (error: Exception) {
            Logger.e(error) { "Intervals finalize transaction rolled back for ${journal.completion_id}" }
            FinalizeOutcome.Failed(key, error.message ?: "finalize transaction failed")
        }
    }

    /** One receipt row per finalized completion: immutable, including negative eligibility. */
    data class ChildFact(val childKey: String, val childKind: String, val sessionId: String)

    /**
     * The receipt/identity/outbox writes shared by journal finalization and the standalone
     * commit bridge. Runs inside the caller's transaction. Negative eligibility records a
     * receipt with `eligible=false` and creates NO job; invalid payloads keep the receipt
     * plus a safe per-job failure; a reservation collision with a different owner is a
     * visible permanent failure, never a silent reassignment.
     */
    internal fun runFinalizationWrites(
        capture: IntervalsCompletionCapture,
        membershipRows: List<ChildFact>,
        rows: List<IntervalsActivitySessionRow>,
        nowMs: Long,
    ): FinalizeOutcome.Finalized {
        val key = capture.key
        val eligible = capture.eligible
        val builderResult = if (eligible) {
            IntervalsActivityBuilder.build(capture, rows)
        } else {
            IntervalsActivityResult.Invalid("not eligible")
        }
        val builtActivity = (builderResult as? IntervalsActivityResult.Built)?.activity
        val exportPayload = if (eligible && builtActivity != null) {
            Json.encodeToString(IntervalsManualActivity.serializer(), builtActivity)
        } else {
            null
        }

        queries.insertCompletionReceiptIfAbsent(
            profile_id = key.profileId,
            local_kind = key.localKind.name,
            completion_id = key.completionId,
            external_id = capture.externalId,
            snapshot_version = SNAPSHOT_VERSION,
            membership_json = membershipJson(membershipRows),
            finish_reason = capture.finishReason.name,
            start_epoch_ms = capture.startEpochMs,
            start_local_iso = capture.startLocalIso,
            time_zone_id = capture.timeZoneId,
            utc_offset_minutes = capture.utcOffsetMinutes.toLong(),
            completed_epoch_ms = capture.completedEpochMs,
            eligible = if (eligible) 1L else 0L,
            resolved_athlete_id = capture.resolvedAthleteId,
            connection_generation = capture.connectionGeneration,
            export_payload = exportPayload,
            payload_digest = exportPayload?.let { payloadDigest(it) },
            created_at = nowMs,
        ).value

        val builderFailure = when (builderResult) {
            is IntervalsActivityResult.Built -> null
            is IntervalsActivityResult.PayloadTooLarge -> IntervalsReasonCode.PAYLOAD_TOO_LARGE.name
            is IntervalsActivityResult.Invalid -> builderResult.reason
        }

        var collision = false
        var jobCreated = false
        val athleteId = capture.resolvedAthleteId
        if (eligible && !athleteId.isNullOrBlank()) {
            queries.reserveIntervalsExternalId(
                resolved_athlete_id = athleteId,
                external_id = capture.externalId,
                owner_profile_id = key.profileId,
                owner_local_kind = key.localKind.name,
                owner_completion_id = key.completionId,
                created_at = nowMs,
            ).value
            val reservation = queries.selectIntervalsExternalIdReservation(
                resolved_athlete_id = athleteId,
                external_id = capture.externalId,
            ).executeAsOneOrNull()
            collision = reservation != null && (
                reservation.owner_profile_id != key.profileId ||
                    reservation.owner_local_kind != key.localKind.name ||
                    reservation.owner_completion_id != key.completionId
                )

            val dispatchable = exportPayload != null && !collision
            // Never hand-splice free-form text into JSON: the placeholder is built from a
            // serializable DTO so a reason string with quotes/backslashes cannot corrupt
            // the payload the replay decoder must parse.
            val payloadJson = exportPayload
                ?: Json.encodeToString(
                    UnavailableJobPayload.serializer(),
                    UnavailableJobPayload(builderFailure ?: "not-eligible"),
                )
            queries.insertIntervalsJobIfAbsent(
                profile_id = key.profileId,
                resolved_athlete_id = athleteId,
                external_id = capture.externalId,
                receipt_profile_id = key.profileId,
                receipt_local_kind = key.localKind.name,
                receipt_completion_id = key.completionId,
                payload_json = payloadJson,
                payload_version = SNAPSHOT_VERSION,
                payload_digest = payloadDigest(payloadJson),
                original_generation = capture.connectionGeneration,
                dispatch_auth_generation = 0L,
                created_at = nowMs,
            ).value
            jobCreated = true
            if (!dispatchable) {
                // A safe per-job failure: never dispatchable, visibly terminal. Inserted
                // PENDING by the frozen query, so claim + terminal-complete it here.
                val leaseToken = generateUUID()
                queries.claimIntervalsJob(
                    profile_id = key.profileId,
                    resolved_athlete_id = athleteId,
                    external_id = capture.externalId,
                    lease_token = leaseToken,
                    lease_expires_at = nowMs + IntervalsTransportPolicy.SQL_LEASE_MILLIS,
                    dispatch_auth_generation = 0L,
                    now = nowMs,
                ).value
                queries.completeIntervalsJob(
                    profile_id = key.profileId,
                    resolved_athlete_id = athleteId,
                    external_id = capture.externalId,
                    state = IntervalsJobState.PERMANENT_FAILURE.name,
                    reason_code = when {
                        collision -> IntervalsReasonCode.VALIDATION_REJECTED.name
                        builderResult is IntervalsActivityResult.PayloadTooLarge ->
                            IntervalsReasonCode.PAYLOAD_TOO_LARGE.name
                        else -> IntervalsReasonCode.VALIDATION_REJECTED.name
                    },
                    remote_activity_id = null,
                    next_attempt_at = null,
                    first_sent_at = null,
                    lease_token = leaseToken,
                    dispatch_auth_generation = 0L,
                    now = nowMs,
                ).value
            }
        }

        return FinalizeOutcome.Finalized(
            key = key,
            eligible = eligible,
            jobCreated = jobCreated,
            exportPayloadStored = exportPayload != null,
            builderFailure = builderFailure,
            externalIdCollision = collision,
        )
    }

    private class AdmissionRollback(val result: AdmissionResult) : Exception()
    private class FinalizeRollback(val outcome: FinalizeOutcome) : Exception()

    companion object {
        const val SNAPSHOT_VERSION = 1L

        /** Exact canonical membership string; compared verbatim on readback (no hash risk). */
        fun canonicalMembership(children: List<IntervalsChildRef>): String =
            children.sortedBy { it.childKey }
                .joinToString("\n") { "${it.childKey}|${it.childKind.name}|${it.sessionId}" }

        fun canonicalMembershipRows(children: List<WorkoutCompletionJournalChildRow>): String =
            children.sortedBy { it.child_key }
                .joinToString("\n") { "${it.child_key}|${it.child_kind}|${it.session_id}" }

        fun canonicalDestination(capture: IntervalsCompletionCapture): String =
            "${capture.resolvedAthleteId ?: ""}|${capture.connectionGeneration}|${capture.installationId}|${capture.eligible}"

        fun membershipJson(rows: List<ChildFact>): String = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ReceiptMembershipEntryDto.serializer()),
            rows.sortedBy { it.childKey }
                .map { ReceiptMembershipEntryDto(it.childKey, it.childKind, it.sessionId) },
        )

        /** Deterministic non-cryptographic payload digest (immutability check, not auth). */
        fun payloadDigest(text: String): String {
            var hash = -0x340d631b7bdddcdbL
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(16)
        }
    }
}

@Serializable
internal data class ReceiptMembershipEntryDto(
    val childKey: String,
    val childKind: String,
    val sessionId: String,
)

// ==================== standalone commit bridge ====================

/**
 * Standalone commit bridge (integration-model §3.2 item 2): a standalone set commit creates
 * its `FINALIZED` receipt and export intent atomically in the guarded export-side transaction
 * immediately following the durable set write. Zero-rep/abandoned writes never produce a
 * receipt. Runs strictly inside the caller's `db.transaction` — no suspension, no network.
 * Returns `true` iff a dispatchable `PENDING` outbox job was created.
 */
internal fun recordStandaloneCompletion(
    queries: PhoenixDatabaseQueries,
    membership: WorkoutCompletionMembership,
    capture: IntervalsCompletionCapture,
    session: WorkoutSession,
    completedSet: CompletedSet?,
    nowMs: Long,
    finalizer: WorkoutCompletionFinalizer,
): Boolean {
    if (!IntervalsActivityBuilder.qualifiesAsValidWork(session, completedSet, membership.timedModeEvidence)) {
        return false
    }
    val persistedSets = queries.selectCompletedSetsBySession(session.id)
        .executeAsList()
        .map { it.toDomain() }
    val membershipRows = listOf(
        WorkoutCompletionFinalizer.ChildFact(
            childKey = membership.childKey,
            childKind = membership.childKind.name,
            sessionId = membership.sessionId,
        ),
    )
    val outcome = finalizer.runFinalizationWrites(
        capture = capture,
        membershipRows = membershipRows,
        rows = listOf(IntervalsActivitySessionRow(session, persistedSets)),
        nowMs = nowMs,
    )
    return outcome.jobCreated && outcome.exportPayloadStored && !outcome.externalIdCollision
}

// ==================== row -> domain mapping ====================

internal fun com.devil.phoenixproject.database.CompletedSet.toDomain(): CompletedSet = CompletedSet(
    id = id,
    sessionId = session_id,
    plannedSetId = planned_set_id,
    setNumber = set_number.toInt(),
    setType = SetType.entries.firstOrNull { it.name == set_type } ?: SetType.STANDARD,
    actualReps = actual_reps.toInt(),
    actualWeightKg = actual_weight_kg.toFloat(),
    loggedRpe = logged_rpe?.toInt(),
    isPr = is_pr == 1L,
    completedAt = completed_at,
    setEndReason = SetEndReason.fromPersisted(set_end_reason),
    routineExerciseId = routine_exercise_id,
    attemptNumber = attempt_number.toInt(),
)

internal fun com.devil.phoenixproject.database.WorkoutSession.toDomainSession(): WorkoutSession = WorkoutSession(
    id = id,
    timestamp = timestamp,
    mode = mode,
    reps = targetReps.toInt(),
    weightPerCableKg = weightPerCableKg.toFloat(),
    progressionKg = progressionKg.toFloat(),
    duration = duration,
    totalReps = totalReps.toInt(),
    warmupReps = warmupReps.toInt(),
    workingReps = workingReps.toInt(),
    isJustLift = isJustLift == 1L,
    stopAtTop = stopAtTop == 1L,
    eccentricLoad = eccentricLoad.toInt(),
    echoLevel = echoLevel.toInt(),
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    routineSessionId = routineSessionId,
    routineName = routineName,
    routineId = routineId,
    routineModifierApplied = routineModifierApplied != 0L,
    safetyFlags = safetyFlags.toInt(),
    deloadWarningCount = deloadWarningCount.toInt(),
    romViolationCount = romViolationCount.toInt(),
    spotterActivations = spotterActivations.toInt(),
    peakForceConcentricA = peakForceConcentricA?.toFloat(),
    peakForceConcentricB = peakForceConcentricB?.toFloat(),
    peakForceEccentricA = peakForceEccentricA?.toFloat(),
    peakForceEccentricB = peakForceEccentricB?.toFloat(),
    avgForceConcentricA = avgForceConcentricA?.toFloat(),
    avgForceConcentricB = avgForceConcentricB?.toFloat(),
    avgForceEccentricA = avgForceEccentricA?.toFloat(),
    avgForceEccentricB = avgForceEccentricB?.toFloat(),
    heaviestLiftKg = heaviestLiftKg?.toFloat(),
    totalVolumeKg = totalVolumeKg?.toFloat(),
    cableCount = cableCount?.toInt(),
    displayMultiplier = display_multiplier?.toInt(),
    externalAddedLoadKg = externalAddedLoadKg.toFloat(),
    counterweightKg = counterweightKg.toFloat(),
    rackItemsJson = rackItemsJson,
    estimatedCalories = estimatedCalories?.toFloat(),
    warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
    workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
    burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
    peakWeightKg = peakWeightKg?.toFloat(),
    rpe = rpe?.toInt(),
    avgMcvMmS = avgMcvMmS?.toFloat(),
    avgAsymmetryPercent = avgAsymmetryPercent?.toFloat(),
    totalVelocityLossPercent = totalVelocityLossPercent?.toFloat(),
    dominantSide = dominantSide,
    strengthProfile = strengthProfile,
    formScore = formScore?.toInt(),
    profileId = profile_id,
    updatedAt = updatedAt,
    avgHeartRateBpm = avgHeartRateBpm?.toInt(),
    maxHeartRateBpm = maxHeartRateBpm?.toInt(),
    minHeartRateBpm = minHeartRateBpm?.toInt(),
    avgRrIntervalMs = avgRrIntervalMs?.toFloat(),
    rmssdMs = rmssdMs?.toFloat(),
    hrr60Bpm = hrr60Bpm?.toInt(),
    hrr300Bpm = hrr300Bpm?.toInt(),
    maxTorsoPitchDeg = maxTorsoPitchDeg?.toFloat(),
    maxTorsoRollDeg = maxTorsoRollDeg?.toFloat(),
)
