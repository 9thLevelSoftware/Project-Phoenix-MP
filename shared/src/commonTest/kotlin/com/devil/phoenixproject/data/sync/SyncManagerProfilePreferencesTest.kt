package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.russhwolf.settings.MapSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncManagerProfilePreferencesTest {
    private val harness = Harness()

    @Test
    fun `ordinary metadata push precedes preference-only chunks`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(2, harness.api.pushPayloads.size)
        val metadataPayload = harness.api.pushPayloads[0]
        val sentMetadataProfileIds = metadataPayload.allProfiles.orEmpty().mapTo(linkedSetOf()) { it.id }
        assertTrue("profile-a" in sentMetadataProfileIds)
        assertNull(metadataPayload.profilePreferenceSections)
        val preferencePayload = harness.api.pushPayloads[1]
        assertNull(preferencePayload.profileId)
        assertNull(preferencePayload.profileName)
        assertNull(preferencePayload.allProfiles)
        assertTrue(preferencePayload.sessions.isEmpty())
        assertTrue(preferencePayload.routines.isEmpty())
        assertTrue(preferencePayload.personalRecords.isEmpty())
        assertEquals(1, preferencePayload.profilePreferenceSections?.size)
        assertEquals(
            "profile-a",
            preferencePayload.profilePreferenceSections?.single()?.localProfileId,
        )
        assertTrue(
            preferencePayload.profilePreferenceSections.orEmpty()
                .mapTo(linkedSetOf()) { it.localProfileId }
                .all { it in sentMetadataProfileIds },
        )
    }

    @Test
    fun `migration readiness is read dynamically and only Ready enables preferences`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
            ),
        )
        var migrationState: RequiredMigrationState = RequiredMigrationState.NotStarted
        val manager = harness.manager(
            migrationReady = { migrationState is RequiredMigrationState.Ready },
        )

        assertTrue(manager.sync().isSuccess)

        assertEquals(1, harness.api.pushPayloads.size)
        assertEquals(0, harness.preferenceSyncRepository.snapshotCallCount)
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())

        migrationState = RequiredMigrationState.Ready
        assertTrue(manager.sync().isSuccess)

        assertEquals(3, harness.api.pushPayloads.size)
        assertEquals(1, harness.preferenceSyncRepository.snapshotCallCount)
        assertEquals(
            listOf("profile-a"),
            harness.api.pushPayloads.last().profilePreferenceSections?.map { it.localProfileId },
        )
    }

    @Test
    fun `snapshot profiles absent from the sent metadata set are deferred`() = runTest {
        val newProfileSection = workoutSection(generation = 2).copy(
            key = ProfilePreferenceSectionKey(
                "profile-created-during-ordinary-push",
                ProfilePreferenceSectionName.WORKOUT,
            ),
        )
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1), newProfileSection),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        val sentMetadataProfileIds = harness.api.pushPayloads[0].allProfiles.orEmpty()
            .mapTo(linkedSetOf()) { it.id }
        val preferenceParentIds = harness.api.pushPayloads[1].profilePreferenceSections.orEmpty()
            .mapTo(linkedSetOf()) { it.localProfileId }
        assertTrue("profile-a" in sentMetadataProfileIds)
        assertEquals(setOf("profile-a"), preferenceParentIds)
        assertTrue(preferenceParentIds.all { it in sentMetadataProfileIds })
        assertFalse(newProfileSection.key.localProfileId in sentMetadataProfileIds)
        assertTrue(
            harness.preferenceSyncRepository.appliedPushOutcomes
                .flatten()
                .none { it.key == newProfileSection.key },
        )
    }

    @Test
    fun `ordinary push failure never snapshots or sends preferences`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            Result.failure(PortalApiException("ordinary sentinel", statusCode = 503)),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isFailure)

        assertEquals(1, harness.api.pushPayloads.size)
        assertEquals(0, harness.preferenceSyncRepository.snapshotCallCount)
        assertTrue(harness.api.pushPayloads.none { it.profilePreferenceSections != null })
    }

    @Test
    fun `legacy backend leaves every preference section dirty`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1), workoutSection(generation = 2)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(successResponse(), successResponse())

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(2, harness.api.pushPayloads.size)
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
    }

    @Test
    fun `strict legacy 400 leaves preferences dirty and preserves ordinary acknowledgement`() = runTest {
        val ordinary = ordinarySession()
        harness.syncRepository.workoutSessionsToReturn = listOf(ordinary)
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            Result.failure(PortalApiException("legacy body sentinel", statusCode = 400)),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(2, harness.api.pushPayloads.size)
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
        assertEquals(listOf(ordinary.id), harness.syncRepository.updateSessionTimestampCalls)
    }

    @Test
    fun `accepted canonical carries sent generation into repository outcome`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 8)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 4)),
            ),
        )

        harness.manager(migrationReady = { true }).sync()

        val outcome = harness.preferenceSyncRepository.appliedPushOutcomes.single().single()
        assertEquals(8L, outcome.sentLocalGeneration)
        assertEquals(4L, outcome.serverRevision)
        assertNull(outcome.rejectionReason)
    }

    @Test
    fun `canonical conflict is applied only through generation ledger`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 11)),
            unsyncable = emptyList(),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                profilePreferenceRejections = listOf(
                    ProfilePreferenceSectionRejectionDto(
                        localProfileId = "profile-a",
                        section = "CORE",
                        serverRevision = 6,
                        reason = "REVISION_CONFLICT",
                        canonicalSection = coreCanonical(revision = 6),
                    ),
                ),
            ),
        )

        harness.manager(migrationReady = { true }).sync()

        val outcome = harness.preferenceSyncRepository.appliedPushOutcomes.single().single()
        assertEquals(11L, outcome.sentLocalGeneration)
        assertEquals("REVISION_CONFLICT", outcome.rejectionReason)
        assertEquals(6L, outcome.canonical?.serverRevision)
    }

    @Test
    fun `first preference chunk failure is nonfatal and attempts no later chunk`() = runTest {
        val ordinary = ordinarySession()
        harness.syncRepository.workoutSessionsToReturn = listOf(ordinary)
        harness.preferenceSyncRepository.dirtySnapshot = multiChunkSnapshot()
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            Result.failure(PortalApiException("first chunk sentinel", statusCode = 503)),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(2, harness.api.pushPayloads.size)
        assertEquals(1, harness.api.pushPayloads.count { it.profilePreferenceSections != null })
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
        assertEquals(listOf(ordinary.id), harness.syncRepository.updateSessionTimestampCalls)
    }

    @Test
    fun `later preference chunk failure keeps earlier outcome and ordinary acknowledgement`() = runTest {
        val ordinary = ordinarySession()
        harness.syncRepository.workoutSessionsToReturn = listOf(ordinary)
        harness.preferenceSyncRepository.dirtySnapshot = multiChunkSnapshot()
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
            ),
            Result.failure(PortalApiException("later chunk sentinel", statusCode = 503)),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(3, harness.api.pushPayloads.size)
        assertEquals(
            listOf(ProfilePreferenceSectionName.CORE),
            harness.preferenceSyncRepository.appliedPushOutcomes
                .flatten()
                .map { it.key.section },
        )
        assertEquals(listOf(ordinary.id), harness.syncRepository.updateSessionTimestampCalls)
    }

    @Test
    fun `lost preference response applies nothing and unchanged retry converges by conflict`() = runTest {
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 7)),
            unsyncable = emptyList(),
        )
        val conflict = ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 1,
            reason = "REVISION_CONFLICT",
            canonicalSection = coreCanonical(revision = 1),
        )
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            Result.failure(PortalApiException("lost response sentinel", statusCode = 503)),
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                profilePreferenceRejections = listOf(conflict),
            ),
        )
        val manager = harness.manager(migrationReady = { true })

        assertTrue(manager.sync().isSuccess)
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())

        assertTrue(manager.sync().isSuccess)
        val outcome = harness.preferenceSyncRepository.appliedPushOutcomes.single().single()
        assertEquals(7L, outcome.sentLocalGeneration)
        assertEquals("REVISION_CONFLICT", outcome.rejectionReason)
        assertEquals(1L, outcome.canonical?.serverRevision)
    }

    @Test
    fun `preference local failures never erase the successful ordinary acknowledgement`() = runTest {
        val ordinary = ordinarySession()
        harness.syncRepository.workoutSessionsToReturn = listOf(ordinary)
        harness.preferenceSyncRepository.snapshotFailure = IllegalStateException("snapshot sentinel")
        harness.api.pushResultsQueue = mutableListOf(successResponse())

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)
        assertEquals(listOf(ordinary.id), harness.syncRepository.updateSessionTimestampCalls)
        assertEquals(1, harness.api.pushPayloads.size)
    }

    @Test
    fun `outcome apply failure is isolated after a complete preference response`() = runTest {
        val ordinary = ordinarySession()
        harness.syncRepository.workoutSessionsToReturn = listOf(ordinary)
        harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSection(generation = 1)),
            unsyncable = emptyList(),
        )
        harness.preferenceSyncRepository.applyPushFailure = IllegalStateException("apply sentinel")
        harness.api.pushResultsQueue = mutableListOf(
            successResponse(),
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)
        assertEquals(listOf(ordinary.id), harness.syncRepository.updateSessionTimestampCalls)
        assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
    }

    @Test
    fun `duplicate response cardinality invalidates only that ledger key`() {
        val coreKey = coreSection(generation = 11).key
        val workoutKey = workoutSection(generation = 12).key
        val ledger = mapOf(coreKey to 11L, workoutKey to 12L)
        val coreRejection = ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 7,
            reason = "REVISION_CONFLICT",
            canonicalSection = coreCanonical(revision = 7),
        )
        val cases = listOf(
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(
                    coreCanonical(7),
                    workoutCanonical(4),
                ),
                profilePreferenceRejections = listOf(coreRejection),
            ),
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(
                    coreCanonical(7),
                    coreCanonical(8),
                    workoutCanonical(4),
                ),
            ),
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(workoutCanonical(4)),
                profilePreferenceRejections = listOf(coreRejection, coreRejection),
            ),
        )

        cases.forEach { response ->
            val outcomes = buildProfilePreferencePushOutcomes(response, ledger)
            assertEquals(listOf(workoutKey), outcomes.map { it.key })
            assertEquals(12L, outcomes.single().sentLocalGeneration)
            assertEquals(4L, outcomes.single().serverRevision)
        }
    }

    @Test
    fun `rejection canonical key or revision mismatch invalidates only that ledger key`() {
        val coreKey = coreSection(generation = 11).key
        val workoutKey = workoutSection(generation = 12).key
        val mismatchedRejections = listOf(
            ProfilePreferenceSectionRejectionDto(
                localProfileId = "profile-a",
                section = "CORE",
                serverRevision = 8,
                reason = "REVISION_CONFLICT",
                canonicalSection = coreCanonical(revision = 7),
            ),
            ProfilePreferenceSectionRejectionDto(
                localProfileId = "profile-a",
                section = "CORE",
                serverRevision = 8,
                reason = "REVISION_CONFLICT",
                canonicalSection = workoutCanonical(revision = 8),
            ),
        )

        mismatchedRejections.forEach { rejection ->
            val outcomes = buildProfilePreferencePushOutcomes(
                response(
                    profilePreferencesAccepted = true,
                    canonicalProfilePreferenceSections = listOf(workoutCanonical(4)),
                    profilePreferenceRejections = listOf(rejection),
                ),
                ledger = mapOf(coreKey to 11L, workoutKey to 12L),
            )

            assertEquals(listOf(workoutKey), outcomes.map { it.key })
            assertEquals(12L, outcomes.single().sentLocalGeneration)
            assertEquals(4L, outcomes.single().serverRevision)
        }
    }

    @Test
    fun `missing unknown and malformed response entries leave only their ledger keys dirty`() {
        val coreKey = coreSection(generation = 11).key
        val workoutKey = workoutSection(generation = 12).key
        val ledger = mapOf(coreKey to 11L, workoutKey to 12L)
        val workout = workoutCanonical(revision = 4)
        val cases = listOf(
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(
                    workout,
                    coreCanonical(9).copy(localProfileId = "remote-only"),
                ),
            ),
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonical(7), workout),
                profilePreferenceRejections = listOf(
                    ProfilePreferenceSectionRejectionDto(
                        localProfileId = "profile-a",
                        section = "CORE",
                        serverRevision = 7,
                        reason = "UNSAFE_REMOTE_REASON_SENTINEL",
                        canonicalSection = coreCanonical(7),
                    ),
                ),
            ),
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(workout),
                profilePreferenceRejections = listOf(
                    ProfilePreferenceSectionRejectionDto(
                        localProfileId = "profile-a",
                        section = "CORE",
                        serverRevision = 7,
                        reason = "VALIDATION_FAILED",
                        canonicalSection = coreCanonical(7),
                    ),
                ),
            ),
            response(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(workout),
                profilePreferenceRejections = listOf(
                    ProfilePreferenceSectionRejectionDto(
                        localProfileId = "profile-a",
                        section = "CORE",
                        serverRevision = 7,
                        reason = "REVISION_CONFLICT",
                        canonicalSection = null,
                    ),
                ),
            ),
        )

        cases.forEach { response ->
            val outcomes = buildProfilePreferencePushOutcomes(response, ledger)
            assertEquals(listOf(workoutKey), outcomes.map { it.key })
            assertEquals(12L, outcomes.single().sentLocalGeneration)
        }
    }

    @Test
    fun `profile preference diagnostics never expose raw identities sections or messages`() {
        val sentinel = "SECRET_PROFILE_DIAGNOSTIC_SENTINEL"
        val key = ProfilePreferenceSectionKey(
            localProfileId = "$sentinel\u0000",
            section = ProfilePreferenceSectionName.CORE,
        )
        val lines = listOf(
            profilePreferenceIssueLogLine(
                ProfilePreferenceSyncIssue(
                    key = key,
                    localGeneration = 9,
                    reason = ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID.name,
                ),
            ),
            profilePreferenceIssueLogLine(
                ProfilePreferenceSyncIssue(
                    key = key,
                    localGeneration = 9,
                    reason = sentinel,
                ),
            ),
            profilePreferenceMetadataDeferredLogLine(key),
            profilePreferenceDuplicateResultLogLine(key),
            profilePreferenceInvalidCanonicalLogLine(
                ProfilePreferenceCanonicalDecodeResult.Invalid(
                    localProfileId = sentinel,
                    section = sentinel,
                    reason = sentinel,
                ),
            ),
            profilePreferenceChunkFailureLogLine(
                PortalApiException(sentinel, statusCode = 503),
            ),
            profilePreferenceLocalFailureLogLine(
                ProfilePreferenceLocalFailureStage.RESPONSE_MAPPING,
            ),
        )

        assertEquals(
            listOf(
                "PROFILE_PREFERENCE_NOT_SENT section=CORE reason=INVALID_PROFILE_ID",
                "PROFILE_PREFERENCE_NOT_SENT section=CORE reason=INVALID_PROFILE_PREFERENCE_DIAGNOSTIC",
                "PROFILE_PREFERENCE_NOT_SENT section=CORE reason=PROFILE_METADATA_NOT_SENT",
                "PROFILE_PREFERENCE_DUPLICATE_RESULT section=CORE",
                "PROFILE_PREFERENCE_INVALID_CANONICAL reason=INVALID_PROFILE_PREFERENCE_DIAGNOSTIC",
                "PROFILE_PREFERENCE_CHUNK_FAILED status=503",
                "PROFILE_PREFERENCE_LOCAL_FAILURE stage=RESPONSE_MAPPING",
            ),
            lines,
        )
        lines.forEach { line ->
            assertFalse(sentinel in line)
            assertFalse('\u0000' in line)
        }
    }

    @Test
    fun `local preference isolation sanitizes every stage and rethrows cancellation`() = runTest {
        val sentinel = "SECRET_LOCAL_FAILURE_SENTINEL"
        ProfilePreferenceLocalFailureStage.entries.forEach { stage ->
            val emitted = mutableListOf<String>()
            val result = isolateProfilePreferenceFailure(
                stage = stage,
                onFailure = emitted::add,
            ) {
                throw IllegalStateException(sentinel)
            }

            assertNull(result)
            assertEquals(
                listOf("PROFILE_PREFERENCE_LOCAL_FAILURE stage=${stage.name}"),
                emitted,
            )
            assertTrue(emitted.none { sentinel in it })
        }

        val emitted = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            isolateProfilePreferenceFailure(
                stage = ProfilePreferenceLocalFailureStage.SNAPSHOT,
                onFailure = emitted::add,
            ) {
                throw CancellationException(sentinel)
            }
        }
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `pull planner pre-counts duplicate keys before validation and keeps valid siblings`() {
        val sentinel = "SECRET_PULL_PROFILE_SENTINEL"
        val plan = planProfilePreferencePullSections(
            listOf(
                coreCanonical(revision = 3),
                coreCanonical(revision = 4).copy(documentVersion = 2),
                workoutCanonical(revision = 5),
                coreCanonical(revision = 6).copy(localProfileId = "$sentinel\u0000"),
            ),
        )

        assertEquals(
            listOf(ProfilePreferenceSectionName.WORKOUT),
            plan.valid.map { it.key.section },
        )
        assertEquals(1, plan.duplicateKeyCount)
        assertEquals(2, plan.invalidCanonicalCount)
    }

    @Test
    fun `pull diagnostics contain only fixed categories and counts`() {
        val sentinel = "SECRET_PULL_DIAGNOSTIC_SENTINEL"
        val lines = ProfilePreferencePullDiagnosticCategory.entries.map { category ->
            profilePreferencePullCountLogLine(category, 7)
        } + profilePreferenceInvalidCanonicalLogLine(
            ProfilePreferenceCanonicalDecodeResult.Invalid(
                localProfileId = sentinel,
                section = sentinel,
                reason = sentinel,
            ),
        )

        assertEquals(
            listOf(
                "PROFILE_PREFERENCE_PULL category=INVALID_CANONICAL count=7",
                "PROFILE_PREFERENCE_PULL category=DUPLICATE_KEY count=7",
                "PROFILE_PREFERENCE_PULL category=LATER_PAGE_IGNORED count=7",
                "PROFILE_PREFERENCE_PULL category=UNKNOWN_PROFILE count=7",
                "PROFILE_PREFERENCE_PULL category=REPOSITORY_INVALID count=7",
                "PROFILE_PREFERENCE_INVALID_CANONICAL reason=INVALID_PROFILE_PREFERENCE_DIAGNOSTIC",
            ),
            lines,
        )
        assertTrue(lines.none { sentinel in it })
    }

    @Test
    fun `pull applies known preference section before existing entities`() = runTest {
        val mergeEvents = mutableListOf<String>()
        harness.preferenceSyncRepository.onApplyPulledSections = { mergeEvents += "preferences" }
        harness.syncRepository.onMergeAllPullData = { mergeEvents += "entities" }
        harness.api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                profilePreferenceSections = listOf(coreCanonical(revision = 3)),
                routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(1, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(
            3,
            harness.preferenceSyncRepository.appliedPulledSections.single().single().serverRevision,
        )
        assertEquals(1, harness.syncRepository.atomicMergeCallCount)
        assertEquals(listOf("preferences", "entities"), mergeEvents)
    }

    @Test
    fun `pull reports unknown preference without creating a profile or preference row`() = runTest {
        harness.api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                profilePreferenceSections = listOf(
                    coreCanonical(revision = 3).copy(localProfileId = "remote-only-profile"),
                ),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(1, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(
            "remote-only-profile",
            harness.preferenceSyncRepository.pulledSectionCalls.single().single()
                .key.localProfileId,
        )
        assertTrue(harness.preferenceSyncRepository.appliedPulledSections.single().isEmpty())
        assertTrue(harness.profileRepository.allProfiles.value.none { it.id == "remote-only-profile" })
    }

    @Test
    fun `localProfiles metadata still does not create mobile profiles`() = runTest {
        harness.api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                localProfiles = listOf(LocalProfileDto("remote-only-profile", "Remote", 2)),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(0, harness.preferenceSyncRepository.pullApplyCallCount)
        assertTrue(harness.profileRepository.allProfiles.value.none { it.id == "remote-only-profile" })
    }

    @Test
    fun `pull readiness is dynamic while not Ready still merges ordinary pages`() = runTest {
        var migrationState: RequiredMigrationState = RequiredMigrationState.NotStarted
        val manager = harness.manager(
            migrationReady = { migrationState is RequiredMigrationState.Ready },
        )
        harness.api.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_200_000L,
                    hasMore = true,
                    nextCursor = "page-2",
                    profilePreferenceSections = listOf(coreCanonical(revision = 2)),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_201_000L,
                    routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
                ),
            ),
        )

        assertTrue(manager.sync().isSuccess)
        assertEquals(2, harness.api.pullCallCount)
        assertEquals(0, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(2, harness.syncRepository.atomicMergeCallCount)

        migrationState = RequiredMigrationState.Ready
        harness.api.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_202_000L,
                    profilePreferenceSections = listOf(coreCanonical(revision = 3)),
                    routines = listOf(PullRoutineDto(id = "routine-2", name = "Routine 2")),
                ),
            ),
        )

        assertTrue(manager.sync().isSuccess)
        assertEquals(1, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(
            3,
            harness.preferenceSyncRepository.appliedPulledSections.single().single().serverRevision,
        )
        assertEquals(3, harness.syncRepository.atomicMergeCallCount)
    }

    @Test
    fun `preference apply failure is local and ordinary entities still merge`() = runTest {
        harness.preferenceSyncRepository.applyPullFailure =
            IllegalStateException("SECRET_PULL_APPLY_FAILURE")
        harness.api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                profilePreferenceSections = listOf(coreCanonical(revision = 3)),
                routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
            ),
        )

        assertTrue(harness.manager(migrationReady = { true }).sync().isSuccess)

        assertEquals(1, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(1, harness.preferenceSyncRepository.pulledSectionCalls.size)
        assertTrue(harness.preferenceSyncRepository.appliedPulledSections.isEmpty())
        assertEquals(1, harness.syncRepository.atomicMergeCallCount)
    }

    @Test
    fun `pull preference cancellation escapes before ordinary merge`() = runTest {
        harness.preferenceSyncRepository.applyPullFailure =
            CancellationException("SECRET_PULL_CANCELLATION")
        harness.api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                profilePreferenceSections = listOf(coreCanonical(revision = 3)),
                routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
            ),
        )

        assertFailsWith<CancellationException> {
            harness.manager(migrationReady = { true }).sync()
        }
        assertEquals(1, harness.preferenceSyncRepository.pullApplyCallCount)
        assertEquals(0, harness.syncRepository.atomicMergeCallCount)
    }

    @Test
    fun `ordinary merge failure keeps earlier preference commit and checkpoint for retry`() =
        runTest {
            val initialLastSync = 5_000L
            harness.tokenStorage.setLastSyncTimestamp(initialLastSync)
            harness.syncRepository.atomicMergeShouldFail = true
            harness.api.pullResult = Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_200_000L,
                    profilePreferenceSections = listOf(coreCanonical(revision = 3)),
                    routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
                ),
            )
            val manager = harness.manager(migrationReady = { true })

            assertTrue(manager.retryPull().isFailure)
            assertIs<SyncState.PartialSuccess>(manager.syncState.value)
            assertEquals(initialLastSync, harness.tokenStorage.getLastSyncTimestamp())
            assertEquals(1, harness.preferenceSyncRepository.appliedPulledSections.size)

            harness.syncRepository.atomicMergeShouldFail = false
            assertTrue(manager.retryPull().isSuccess)
            assertEquals(2, harness.preferenceSyncRepository.pullApplyCallCount)
            assertEquals(
                1_783_771_200_000L,
                harness.tokenStorage.getLastSyncTimestamp(),
            )
            assertEquals(1, harness.syncRepository.atomicMergeCallCount)
        }

    @Test
    fun `fake pull seam captures raw calls separately from known profile applications`() = runTest {
        val repository = FakeProfilePreferenceSyncRepository()
        val known = CanonicalProfilePreferenceSection(
            key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
            documentVersion = 1,
            serverRevision = 1,
            serverUpdatedAtEpochMs = 1_783_771_200_000L,
            payload = coreSection(generation = 1).payload,
        )
        val unknown = known.copy(
            key = ProfilePreferenceSectionKey("remote-only", ProfilePreferenceSectionName.CORE),
        )

        val report = repository.applyPulledSections(listOf(known, unknown))

        assertEquals(1, repository.pullApplyCallCount)
        assertEquals(listOf(listOf(known, unknown)), repository.pulledSectionCalls)
        assertEquals(listOf(listOf(known)), repository.appliedPulledSections)
        assertEquals(1, report.applied)
        assertEquals(1, report.ignoredUnknownProfile)

        repository.applyPullFailure = IllegalStateException("pull apply sentinel")
        assertFailsWith<IllegalStateException> {
            repository.applyPulledSections(listOf(known))
        }
        assertEquals(2, repository.pullApplyCallCount)
        assertEquals(listOf(known), repository.pulledSectionCalls.last())
        assertEquals(1, repository.appliedPulledSections.size)
    }

    private class Harness {
        val tokenStorage = PortalTokenStorage(MapSettings())
        val api = FakePortalApiClient()
        val syncRepository = FakeSyncRepository()
        val gamificationRepository = FakeGamificationRepository()
        val repMetricRepository = FakeRepMetricRepository()
        val profileRepository = FakeUserProfileRepository()
        val preferenceSyncRepository = FakeProfilePreferenceSyncRepository()
        val externalActivityRepository = FakeExternalActivityRepository()
        val velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository()

        init {
            profileRepository.setActiveProfileForTest("profile-a")
            tokenStorage.saveAuth(
                PortalAuthResponse(
                    token = "token",
                    user = PortalUser("user", "u@example.com", null, false),
                ),
            )
        }

        fun manager(
            migrationReady: () -> Boolean = { true },
            rateLimiter: ClientRateLimiter = ClientRateLimiter(),
        ) = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = syncRepository,
            gamificationRepository = gamificationRepository,
            repMetricRepository = repMetricRepository,
            userProfileRepository = profileRepository,
            profilePreferenceSyncRepository = preferenceSyncRepository,
            externalActivityRepository = externalActivityRepository,
            velocityOneRepMaxRepository = velocityOneRepMaxRepository,
            rateLimiter = rateLimiter,
            isProfilePreferenceMigrationReady = migrationReady,
        )
    }

    companion object {
        private fun coreSection(generation: Long) = ProfilePreferenceSectionSyncDto(
            key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
            documentVersion = 1,
            baseRevision = 0,
            clientModifiedAtEpochMs = 1_783_771_200_000L,
            localGeneration = generation,
            payload = buildJsonObject {
                put("bodyWeightKg", 80.0)
                put("weightUnit", "KG")
                put("weightIncrement", 0.5)
            },
        )

        private fun workoutSection(generation: Long) = ProfilePreferenceSectionSyncDto(
            key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.WORKOUT),
            documentVersion = 1,
            baseRevision = 0,
            clientModifiedAtEpochMs = 1_783_771_200_000L,
            localGeneration = generation,
            payload = ProfilePreferenceSyncCodec().workoutPayload(WorkoutPreferences()),
        )

        private fun coreCanonical(revision: Long) = PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "CORE",
            documentVersion = 1,
            serverRevision = revision,
            serverUpdatedAt = "2026-07-11T12:00:00Z",
            payload = coreSection(generation = 1).payload,
        )

        private fun workoutCanonical(revision: Long) = PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "WORKOUT",
            documentVersion = 1,
            serverRevision = revision,
            serverUpdatedAt = "2026-07-11T12:00:00Z",
            payload = workoutSection(generation = 1).payload,
        )

        private fun successResponse(
            profilePreferencesAccepted: Boolean? = null,
            canonicalProfilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto> = emptyList(),
            profilePreferenceRejections: List<ProfilePreferenceSectionRejectionDto> = emptyList(),
        ): Result<PortalSyncPushResponse> = Result.success(
            response(
                profilePreferencesAccepted = profilePreferencesAccepted,
                canonicalProfilePreferenceSections = canonicalProfilePreferenceSections,
                profilePreferenceRejections = profilePreferenceRejections,
            ),
        )

        private fun response(
            profilePreferencesAccepted: Boolean? = null,
            canonicalProfilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto> = emptyList(),
            profilePreferenceRejections: List<ProfilePreferenceSectionRejectionDto> = emptyList(),
        ) = PortalSyncPushResponse(
            syncTime = "2026-07-11T12:00:00Z",
            profilePreferencesAccepted = profilePreferencesAccepted,
            canonicalProfilePreferenceSections = canonicalProfilePreferenceSections,
            profilePreferenceRejections = profilePreferenceRejections,
        )

        private fun ordinarySession() = WorkoutSession(
            id = "ordinary-session",
            timestamp = 1_783_771_200_000L,
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 20f,
            totalReps = 10,
            exerciseId = "ordinary-exercise",
            exerciseName = "Ordinary Exercise",
            routineSessionId = null,
            profileId = "profile-a",
        )

        private fun multiChunkSnapshot(): ProfilePreferenceDirtySnapshot {
            fun largeBoundarySection(
                section: ProfilePreferenceSectionName,
                generation: Long,
            ) = ProfilePreferenceSectionSyncDto(
                key = ProfilePreferenceSectionKey("profile-a", section),
                documentVersion = 1,
                baseRevision = 0,
                clientModifiedAtEpochMs = 1_783_771_200_000L,
                localGeneration = generation,
                payload = buildJsonObject { put("padding", "x".repeat(180_000)) },
            )

            return ProfilePreferenceDirtySnapshot(
                valid = listOf(
                    coreSection(generation = 1),
                    largeBoundarySection(ProfilePreferenceSectionName.RACK, generation = 2),
                    largeBoundarySection(ProfilePreferenceSectionName.WORKOUT, generation = 3),
                    largeBoundarySection(ProfilePreferenceSectionName.LED, generation = 4),
                ),
                unsyncable = emptyList(),
            )
        }
    }
}
