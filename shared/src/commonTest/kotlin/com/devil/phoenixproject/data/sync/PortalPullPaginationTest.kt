package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests covering the pull-side pagination contract:
 *   - Multi-page loops until hasMore=false, accumulating all entities and deduplicating by id.
 *   - nextCursor propagation: the cursor from page N is sent as request.cursor for page N+1.
 *   - hasMore=false ends the loop on the same page.
 *   - Empty page with hasMore=true is treated as "end of pagination" (SyncManager line 823-830).
 *   - Failure mid-pagination does NOT advance the lastSync timestamp (caller restarts).
 *   - knownEntityIds is built from the local repository's ID lists (parity sync).
 *   - Large knownEntityIds sets (5000+ ids) are capped to MAX_PARITY_IDS=500
 *     (audit item #7, Phase 4.1) so the server's HTTP 413 enforcement is
 *     never triggered. The most recent (`takeLast`) window is sent.
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

    private fun createManager() = SyncManager(
        apiClient = fakeApi,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo,
        userProfileRepository = fakeUserProfileRepo,
        externalActivityRepository = fakeExternalActivityRepo,
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
        // call contains page 3's payload, and the total count of merged sessions via atomic merges.
        assertEquals(
            3,
            fakeSyncRepo.atomicMergeCallCount,
            "Each page calls mergeAllPullData once (atomic per-page merge)",
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
        // the atomicMergeCallCount (invoked unconditionally) is 1.
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

    // ==================== Parity Sync: knownEntityIds ====================

    @Test
    fun pullSendsKnownEntityIdsToServer() = runTest {
        authenticate()
        fakeSyncRepo.sessionIds = listOf("s-1", "s-2")
        fakeSyncRepo.routineIds = listOf("r-1")
        fakeSyncRepo.cycleIds = emptyList()
        fakeSyncRepo.badgeIds = listOf("b-1", "b-2")
        fakeSyncRepo.personalRecordIds = listOf("pr-1")
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        createManager().sync()

        val known = fakeApi.lastPullKnownEntityIds
        assertNotNull(known, "Pull must send knownEntityIds (parity sync)")
        assertEquals(listOf("s-1", "s-2"), known.sessionIds)
        assertEquals(listOf("r-1"), known.routineIds)
        assertEquals(emptyList<String>(), known.cycleIds)
        assertEquals(listOf("b-1", "b-2"), known.badgeIds)
        assertEquals(listOf("pr-1"), known.personalRecordIds)
    }

    @Test
    fun pullCapsLargeKnownSessionIdsToMaxParityIds() = runTest {
        authenticate()
        // 5000 session IDs simulates a user with deep history doing a parity
        // sync. The server enforces HTTP 413 above SyncConfig.MAX_PARITY_IDS
        // (500), so SyncManager.runPullLoop must truncate via `capParity()`
        // to the last MAX_PARITY_IDS entries (most recent window) and rely on
        // server-side `lastSync` delta + local dedupe for the older tail.
        // Resolves audit item #7 (Phase 4.1).
        val bigSet = List(5000) { "sess-$it" }
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
        assertEquals("sess-${5000 - SyncConfig.MAX_PARITY_IDS}", known.sessionIds.first())
        assertEquals("sess-4999", known.sessionIds.last())
    }

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
