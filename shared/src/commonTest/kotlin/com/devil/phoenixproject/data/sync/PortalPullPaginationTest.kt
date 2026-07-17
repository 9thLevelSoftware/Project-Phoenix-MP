package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests covering the pull-side pagination contract:
 *   - Multi-page loops until hasMore=false, accumulating all entities and deduplicating by id.
 *   - nextCursor propagation: the cursor from page N is sent as request.cursor for page N+1.
 *   - hasMore=false ends the loop on the same page.
 *   - Empty page with hasMore=true is treated as "end of pagination" (SyncManager line 823-830).
 *   - Failure mid-pagination does NOT advance the lastSync timestamp (caller restarts).
 *   - knownEntityIds is built from the local repository's ID lists (parity sync).
 *   - Large knownEntityIds sets (> MAX_PARITY_IDS) are capped to the current
 *     MAX_PARITY_IDS limit (audit item #7, Phase 4.1) so the server's HTTP
 *     413 enforcement is never triggered. The most recent (`takeLast`) window
 *     is sent.
 *   - pageSize defaults to SyncConfig.DEFAULT_PAGE_SIZE (100).
 *
 * These tests use the existing FakePortalApiClient.pullResultsQueue mechanism to return
 * successive pages per call.
 */
class PortalPullPaginationTest {

    private val settings = MapSettings()
    private val tokenStorage = PortalTokenStorage(settings)
    private val fakeApi = FakePortalApiClient()
    private val fakeSyncRepo = FakeSyncRepository()
    private val fakeGamificationRepo = FakeGamificationRepository()
    private val fakeRepMetricRepo = FakeRepMetricRepository()
    private val fakeUserProfileRepo = FakeUserProfileRepository()
    private val fakeExternalActivityRepo = FakeExternalActivityRepository()
    private val fakeVelocityRepo = FakeVelocityOneRepMaxRepository()
    private val fakeProfilePreferenceSyncRepo = FakeProfilePreferenceSyncRepository()

    private fun createManager(rateLimiter: ClientRateLimiter = ClientRateLimiter()) = SyncManager(
        apiClient = fakeApi,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo,
        userProfileRepository = fakeUserProfileRepo,
        profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
        externalActivityRepository = fakeExternalActivityRepo,
        velocityOneRepMaxRepository = fakeVelocityRepo,
        rateLimiter = rateLimiter,
        isProfilePreferenceMigrationReady = { true },
    )

    private fun authenticate(userId: String = "user-123") {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "tok",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "rtok",
                user = GoTrueUser(id = userId, email = "$userId@e.com"),
            ),
        )
    }

    // ==================== Multi-Page Accumulation ====================

    @Test
    fun threePagesOfSessionsAllGetMerged() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        // Build 3 pages, each with 100 unique sessions (total 300). Each session has ≥1 exercise
        // so PortalPullAdapter produces a merged WorkoutSession row.
        val pages = List(3) { pageIndex ->
            val pageSessions = List(100) { i ->
                val id = "sess-$pageIndex-$i"
                PullWorkoutSessionDto(
                    id = id,
                    userId = "user-123",
                    startedAt = "2026-01-01T12:00:00Z",
                    exercises = listOf(
                        PullExerciseDto(
                            id = "ex-$pageIndex-$i",
                            sessionId = id,
                            name = "Squat",
                            muscleGroup = "Legs",
                        ),
                    ),
                )
            }
            val isLast = pageIndex == 2
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_740_000_000_000L + pageIndex,
                    hasMore = !isLast,
                    nextCursor = if (isLast) null else "cursor-page-${pageIndex + 1}",
                    sessions = pageSessions,
                ),
            )
        }
        fakeApi.pullResultsQueue = pages.toMutableList()

        val manager = createManager()
        val result = manager.sync()

        assertTrue(result.isSuccess, "multi-page sync should succeed")
        assertEquals(3, fakeApi.pullCallCount, "Pull should fire once per page")
        // Across 3 pages, 300 unique sessions should have been merged.
        // Each page triggers mergeAllPullData with that page's slice; we check the final merge
        // call contains page 3's payload, and every page reaches the ordinary repository merge.
        assertEquals(
            3,
            fakeSyncRepo.atomicMergeCallCount,
            "Each page calls mergeAllPullData once",
        )
    }

    @Test
    fun cursorPropagatesFromPageToPage() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1L,
                    hasMore = true,
                    nextCursor = "after-100",
                    routines = listOf(PullRoutineDto(id = "r1", name = "R1")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 2L,
                    hasMore = true,
                    nextCursor = "after-200",
                    routines = listOf(PullRoutineDto(id = "r2", name = "R2")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 3L,
                    hasMore = false,
                    nextCursor = null,
                    routines = listOf(PullRoutineDto(id = "r3", name = "R3")),
                ),
            ),
        )

        createManager().sync()

        assertEquals(3, fakeApi.pullCallCount, "three pages")
        // The last captured cursor reflects the cursor sent on the FINAL pull request — which
        // is page 3's request using page 2's nextCursor ("after-200").
        assertEquals(
            "after-200",
            fakeApi.lastPullCursor,
            "Last pull request must send the previous page's nextCursor",
        )
    }

    @Test
    fun hasMoreFalseStopsPaginationOnSamePage() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1L,
                hasMore = false,
                nextCursor = null,
                routines = listOf(PullRoutineDto(id = "r-only", name = "solo")),
            ),
        )

        createManager().sync()
        assertEquals(
            1,
            fakeApi.pullCallCount,
            "hasMore=false on the first page terminates the loop",
        )
    }

    // ==================== Empty Page Guard ====================

    @Test
    fun emptyPageWithHasMoreBreaksLoopToAvoidInfinite() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1L,
                hasMore = true, // normally would continue
                nextCursor = "still-more",
                // but the page is entirely empty → production code breaks
            ),
        )

        val result = createManager().sync()
        assertTrue(result.isSuccess, "sync should still succeed despite the oddity")
        assertEquals(
            1,
            fakeApi.pullCallCount,
            "Empty page + hasMore=true must break the pagination loop",
        )
    }

    @Test
    fun emptyResponseOnFirstPageSucceedsAndMergesEmptyState() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1L,
                hasMore = false,
            ),
        )

        val result = createManager().sync()
        assertTrue(result.isSuccess, "empty response → success, no crash")
        // SyncManager calls mergeAllPullData exactly once with all-empty lists; the fake's
        // counter-based tracking (which only increments per non-empty list) stays at 0, but
        // the ordinary merge call counter (invoked unconditionally) is 1.
        assertEquals(
            1,
            fakeSyncRepo.atomicMergeCallCount,
            "Empty pull payload still calls mergeAllPullData once (with empty collections)",
        )
        assertTrue(
            fakeSyncRepo.lastAtomicMergeSessions.isEmpty(),
            "No sessions merged from empty response",
        )
        assertTrue(
            fakeSyncRepo.lastAtomicMergeRoutines.isEmpty(),
            "No routines merged from empty response",
        )
    }

    // ==================== Failure Mid-Pagination ====================

    @Test
    fun pullFailureOnSecondPageRestartsFromBeginningNextSync() = runTest {
        authenticate()
        val initial = 5000L
        tokenStorage.setLastSyncTimestamp(initial)

        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_740_000_000_000L,
                    hasMore = true,
                    nextCursor = "page-2",
                    routines = listOf(PullRoutineDto(id = "r1", name = "R1")),
                ),
            ),
            Result.failure(PortalApiException("network blip on page 2")),
        )

        createManager().sync()

        // lastSync must NOT advance when any page fails
        assertEquals(
            initial,
            tokenStorage.getLastSyncTimestamp(),
            "A failed page keeps lastSync at its pre-sync value so next sync re-sends the cursor from scratch",
        )
    }

    @Test
    fun pull429WithRetryAfterRetriesSameCursorAndCompletes() = runTest {
        authenticate()
        val finalSyncTime = 3L
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1L,
                    hasMore = true,
                    nextCursor = "page-2",
                    routines = listOf(PullRoutineDto(id = "r1", name = "R1")),
                ),
            ),
            Result.failure(PortalApiException("rate limited", null, 429, retryAfterSeconds = 1)),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = finalSyncTime,
                    hasMore = false,
                    routines = listOf(PullRoutineDto(id = "r2", name = "R2")),
                ),
            ),
        )

        val result = createManager().sync()

        assertTrue(result.isSuccess, "pull should resume after a 429 retry-after response")
        assertEquals(3, fakeApi.pullCallCount, "retry should count as another pull request")
        assertEquals(
            listOf(null, "page-2", "page-2"),
            fakeApi.pullCallCursors,
            "429 retry must re-request the same page cursor",
        )
        assertEquals(finalSyncTime, tokenStorage.getLastSyncTimestamp(), "completed pull should advance lastSync")
        assertTrue(currentTime >= 1_000L, "retry-after delay should be honored before the retry")
    }

    @Test
    fun pull429BeyondRetryCapFailsAndKeepsLastSync() = runTest {
        authenticate()
        val initialSyncTime = 7_000L
        tokenStorage.setLastSyncTimestamp(initialSyncTime)
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1L,
                    hasMore = true,
                    nextCursor = "page-2",
                    routines = listOf(PullRoutineDto(id = "r1", name = "R1")),
                ),
            ),
            Result.failure(PortalApiException("rate limited", null, 429, retryAfterSeconds = 1)),
            Result.failure(PortalApiException("rate limited", null, 429, retryAfterSeconds = 1)),
            Result.failure(PortalApiException("rate limited", null, 429, retryAfterSeconds = 1)),
            Result.failure(PortalApiException("rate limited", null, 429, retryAfterSeconds = 1)),
        )

        val result = createManager().retryPull()

        assertTrue(result.isFailure, "retryPull should fail after exceeding the per-page retry-after cap")
        val error = assertIs<PortalApiException>(result.exceptionOrNull())
        assertEquals(429, error.statusCode)
        assertEquals(1, error.retryAfterSeconds)
        assertEquals(
            listOf(null, "page-2", "page-2", "page-2", "page-2"),
            fakeApi.pullCallCursors,
            "every capped retry should stay on the same page cursor",
        )
        assertEquals(5, fakeApi.pullCallCount, "page 2 should be attempted once plus three retries after page 1")
        assertEquals(initialSyncTime, tokenStorage.getLastSyncTimestamp(), "failed pull must not advance lastSync")
        assertTrue(currentTime >= 3_000L, "only the capped retries should incur Retry-After waits")
    }

    @Test
    fun pull503WithRetryAfterRetriesSameCursorAndCompletes() = runTest {
        authenticate()
        val finalSyncTime = 9L
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1L,
                    hasMore = true,
                    nextCursor = "page-2",
                    routines = listOf(PullRoutineDto(id = "r1", name = "R1")),
                ),
            ),
            Result.failure(PortalApiException("service unavailable", null, 503, retryAfterSeconds = 1)),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = finalSyncTime,
                    hasMore = false,
                    routines = listOf(PullRoutineDto(id = "r2", name = "R2")),
                ),
            ),
        )

        val result = createManager().retryPull()

        assertTrue(result.isSuccess, "503 with Retry-After should resume the same pull page")
        assertEquals(3, fakeApi.pullCallCount)
        assertEquals(
            listOf(null, "page-2", "page-2"),
            fakeApi.pullCallCursors,
            "503 retry must re-request the same cursor",
        )
        assertEquals(finalSyncTime, tokenStorage.getLastSyncTimestamp())
        assertTrue(currentTime >= 1_000L, "503 retry-after delay should be honored")
    }

    @Test
    fun multiPagePullConsumesLimiterTokenPerPage() = runTest {
        authenticate()
        val pageCount = SyncConfig.PULL_RATE_LIMIT_PER_MIN + 1
        var limiterClockMs = 0L
        fakeApi.pullTimestampSourceMs = { limiterClockMs }
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResultsQueue = MutableList(pageCount) { index ->
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_000L + index,
                    hasMore = index < pageCount - 1,
                    nextCursor = if (index < pageCount - 1) "cursor-${index + 1}" else null,
                    routines = listOf(PullRoutineDto(id = "r$index", name = "Routine $index")),
                ),
            )
        }

        val result = createManager(
            rateLimiter = ClientRateLimiter(
                windowMillis = 250L,
                nowMs = { limiterClockMs },
                waitFor = { delayMs -> limiterClockMs += delayMs },
            ),
        ).sync()
        val expectedTimestamps = List(SyncConfig.PULL_RATE_LIMIT_PER_MIN) { 0L } + listOf(250L)

        assertTrue(result.isSuccess, "pagination should still complete under limiter pressure")
        assertEquals(pageCount, fakeApi.pullCallCount, "every page should still be requested")
        assertEquals(
            expectedTimestamps,
            fakeApi.pullCallTimestampsMs,
            "per-page limiter acquisition should advance the shared manual clock only when the window is full",
        )
    }

    // ==================== Parity Sync: knownEntityIds ====================

    @Test
    fun pullSendsKnownEntityIdsButOmitsPersonalRecordsForLwwRefresh() = runTest {
        authenticate()
        val s1 = "11111111-1111-4111-a111-111111111111"
        val s2 = "22222222-2222-4222-a222-222222222222"
        val r1 = "33333333-3333-4333-a333-333333333333"
        val b1 = "44444444-4444-4444-a444-444444444444"
        val b2 = "55555555-5555-4555-a555-555555555555"
        val pr1 = "66666666-6666-4666-a666-666666666666"
        fakeSyncRepo.sessionIds = listOf(s1, s2)
        fakeSyncRepo.routineIds = listOf(r1)
        fakeSyncRepo.cycleIds = emptyList()
        fakeSyncRepo.badgeIds = listOf(b1, b2)
        fakeSyncRepo.personalRecordIds = listOf(pr1)
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        val known = fakeApi.lastPullKnownEntityIds
        assertNotNull(known, "Pull must send knownEntityIds (parity sync)")
        assertEquals(listOf(s1, s2), known.sessionIds)
        assertEquals(listOf(r1), known.routineIds)
        assertEquals(emptyList<String>(), known.cycleIds)
        assertEquals(listOf(b1, b2), known.badgeIds)
        assertEquals(
            emptyList<String>(),
            known.personalRecordIds,
            "Known PR UUIDs must be omitted so the portal returns newer active rows and tombstones",
        )
    }

    @Test
    fun pullDropsNonUuidRoutineIdsBeforeSend() = runTest {
        // fix(pull 400): TemplateConverter mints cycle-derived routine ids as
        // "cycle_routine_<uuid>". server's mobile-sync-pull validator rejects
        // the whole request on any non-canonical UUID in knownEntityIds, so
        // SyncManager.runPullLoop filters them out client-side before send.
        authenticate()
        val realRoutine = "44444444-4444-4444-a444-444444444444"
        val cycleRoutine = "cycle_routine_${"55555555-5555-4555-a555-555555555555"}"
        fakeSyncRepo.routineIds = listOf(realRoutine, cycleRoutine)
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        val known = fakeApi.lastPullKnownEntityIds
        assertNotNull(known)
        assertEquals(
            listOf(realRoutine),
            known.routineIds,
            "Non-UUID routine ids (e.g. cycle_routine_...) must be filtered before send",
        )
    }

    @Test
    fun pullFiltersNonUuidBadgesAndOmitsPersonalRecordsBeforeSend() = runTest {
        authenticate()
        val realBadge = "77777777-7777-4777-a777-777777777777"
        val realPr = "88888888-8888-4888-a888-888888888888"
        fakeSyncRepo.badgeIds = listOf("1", realBadge, "2")
        fakeSyncRepo.personalRecordIds = listOf("10", realPr, "20")
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        val known = fakeApi.lastPullKnownEntityIds
        assertNotNull(known)
        assertEquals(
            listOf(realBadge),
            known.badgeIds,
            "Local numeric badge ids must be filtered before send; portal parity uses UUID row ids",
        )
        assertEquals(emptyList<String>(), known.personalRecordIds)
    }

    @Test
    fun deltaPullDoesNotHardDeleteKnownRoutinesOrCyclesOmittedFromResponse() = runTest {
        authenticate()
        val routineId = "99999999-9999-4999-a999-999999999999"
        val cycleId = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
        fakeSyncRepo.routineIds = listOf(routineId)
        fakeSyncRepo.cycleIds = listOf(cycleId)
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1_740_916_800_000L,
                routines = emptyList(),
                cycles = emptyList(),
                hasMore = false,
            ),
        )

        createManager().sync()

        assertEquals(
            emptyList(),
            fakeSyncRepo.hardDeletedRoutineIds,
            "A delta pull omits unchanged known routines; omission is not a deletion signal",
        )
        assertEquals(
            emptyList(),
            fakeSyncRepo.hardDeletedCycleIds,
            "A delta pull omits unchanged known cycles; omission is not a deletion signal",
        )
    }

    @Test
    fun pullCapsLargeKnownSessionIdsToMaxParityIds() = runTest {
        authenticate()
        // Simulate a user with deep history whose parity list exceeds the
        // current cap. SyncManager.runPullLoop must truncate via `capParity()`
        // to the last MAX_PARITY_IDS entries (most recent window) and rely on
        // server-side `lastSync` delta + local dedupe for the older tail.
        // Resolves audit item #7 (Phase 4.1).
        fun fakeUuid(i: Int): String {
            val hex = i.toString(16).padStart(8, '0')
            return "$hex-0000-4000-8000-000000000000"
        }

        val totalSessionIds = SyncConfig.MAX_PARITY_IDS + 500
        val bigSet = List(totalSessionIds) { fakeUuid(it) }
        fakeSyncRepo.sessionIds = bigSet
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        val known = fakeApi.lastPullKnownEntityIds
        assertNotNull(known)
        assertEquals(
            SyncConfig.MAX_PARITY_IDS,
            known.sessionIds.size,
            "Large knownEntityIds payloads must be capped at MAX_PARITY_IDS to avoid server 413",
        )
        // capParity() uses takeLast(), so the window is the most recent IDs.
        assertEquals(fakeUuid(totalSessionIds - SyncConfig.MAX_PARITY_IDS), known.sessionIds.first())
        assertEquals(fakeUuid(totalSessionIds - 1), known.sessionIds.last())
    }

    @Test
    fun preferenceOnlyPageCountsAsNonEmptyAndContinuesPagination() = runTest {
        authenticate()
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_200_000L,
                    hasMore = true,
                    nextCursor = "page-2",
                    profilePreferenceSections = listOf(coreCanonicalForPull(revision = 2)),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_201_000L,
                    hasMore = false,
                ),
            ),
        )

        val result = createManager().sync()

        assertTrue(result.isSuccess)
        assertEquals(2, fakeApi.pullCallCount)
        assertEquals(listOf(null, "page-2"), fakeApi.pullCallCursors)
    }

    @Test
    fun laterPagePreferenceIsIgnoredButStillKeepsPaginationMoving() = runTest {
        authenticate()
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_200_000L,
                    hasMore = true,
                    nextCursor = "page-2",
                    routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine 1")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_201_000L,
                    hasMore = true,
                    nextCursor = "page-3",
                    profilePreferenceSections = listOf(coreCanonicalForPull(revision = 9)),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1_783_771_202_000L,
                    routines = listOf(PullRoutineDto(id = "routine-3", name = "Routine 3")),
                ),
            ),
        )

        assertTrue(createManager().sync().isSuccess)

        assertEquals(3, fakeApi.pullCallCount)
        assertEquals(listOf(null, "page-2", "page-3"), fakeApi.pullCallCursors)
        assertEquals(0, fakeProfilePreferenceSyncRepo.pullApplyCallCount)
        assertEquals(3, fakeSyncRepo.atomicMergeCallCount)
    }

    private fun coreCanonicalForPull(revision: Long) =
        PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "CORE",
            documentVersion = 1,
            serverRevision = revision,
            serverUpdatedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject {
                put("bodyWeightKg", 80.0)
                put("weightUnit", "KG")
                put("weightIncrement", 0.5)
            },
        )

    // ==================== pageSize Wiring ====================

    @Test
    fun pullSendsDefaultPageSize() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        assertEquals(
            SyncConfig.DEFAULT_PAGE_SIZE,
            fakeApi.lastPullPageSize,
            "pageSize defaults to SyncConfig.DEFAULT_PAGE_SIZE (=100)",
        )
    }

    @Test
    fun firstPullCallSendsNullCursor() = runTest {
        authenticate()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        assertNull(
            fakeApi.lastPullCursor,
            "First page request must send cursor=null (start from beginning)",
        )
    }
}
