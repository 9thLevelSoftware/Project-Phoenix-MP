package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.withPlatformLock
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authentication events that observers can subscribe to for user notifications.
 * These events are emitted when authentication state changes require user action.
 */
sealed class AuthEvent {
    /**
     * Session has expired and user needs to re-authenticate.
     * Emitted when refresh token fails or is rejected by the server.
     */
    data class SessionExpired(val reason: String) : AuthEvent()

    /**
     * Token refresh attempt failed.
     * May be recoverable (network issue) or require re-authentication (token revoked).
     */
    data class RefreshFailed(val reason: String, val isRecoverable: Boolean) : AuthEvent()

    /**
     * User was logged out explicitly (e.g., by calling logout).
     * Distinguished from session expiry for UI messaging purposes.
     */
    object LoggedOut : AuthEvent()
}

/**
 * Secure storage for Portal authentication tokens.
 *
 * SECURITY REQUIREMENTS:
 * - This class MUST be initialized with a Settings instance backed by secure storage
 *   (Android EncryptedSharedPreferences or iOS Keychain).
 * - Tokens stored include JWT access tokens and refresh tokens which grant account access.
 * - Never initialize with plain SharedPreferences or NSUserDefaults on production builds.
 *
 * @param settings A Settings instance backed by secure storage (encrypted at rest).
 * @throws IllegalStateException if secure storage verification fails during initialization.
 */
class PortalTokenStorage(private val settings: Settings) {

    internal data class AuthStateSnapshot(
        val generation: Long,
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAt: Long,
        val userId: String?,
        val userEmail: String?,
        val userName: String?,
        val isPremium: Boolean,
        val subscriptionTier: String?,
    )

    companion object {
        private const val KEY_TOKEN = "portal_auth_token"
        private const val KEY_USER_ID = "portal_user_id"
        private const val KEY_USER_EMAIL = "portal_user_email"
        private const val KEY_USER_NAME = "portal_user_display_name"
        private const val KEY_IS_PREMIUM = "portal_user_is_premium"
        private const val KEY_SUBSCRIPTION_TIER = "portal_user_subscription_tier"
        private const val KEY_REFRESH_TOKEN = "portal_refresh_token"
        private const val KEY_EXPIRES_AT = "portal_token_expires_at"

        /**
         * Legacy global cursor written by builds before PR 10. Read once by
         * [migrateLegacyCursors] to seed the per-profile cursors, then removed.
         */
        private const val KEY_LEGACY_LAST_SYNC = "portal_last_sync_timestamp"

        /**
         * Legacy "userId:profileId" marker of the completed pull that produced
         * [KEY_LEGACY_LAST_SYNC]. Absent meant the next pull had to be a full pull.
         * Read once by [migrateLegacyCursors], then removed.
         */
        private const val KEY_LEGACY_DELTA_PULL_KEY = "portal_delta_pull_key"

        /**
         * Device-clock watermark of the last completed push for one (portal userId, profileId).
         * Post-push stamps use it; push gather selects `updatedAt > pushWatermark`.
         */
        private const val KEY_PUSH_WATERMARK_PREFIX = "portal_push_watermark_"
        private const val KEY_PULL_MERGE_WATERMARK_PREFIX = "portal_pull_merge_watermark_"

        /**
         * Server-clock cursor of the last completed pull for one (portal userId, profileId).
         * Sent as `PortalSyncPullRequest.lastSync`. 0 / absent means "full pull".
         */
        private const val KEY_PULL_CURSOR_PREFIX = "portal_pull_cursor_"

        /**
         * One-time upgrade repair: routines, cycles and PRs for every profile re-push from 0.
         * Absent = still owed; present = done.
         */
        private const val KEY_ROUTINE_CYCLE_PR_REPAIR_DONE_PREFIX = "portal_rcp_repair_done_"

        /**
         * Content fingerprint of the session DTO last sent to the portal, keyed by portal
         * session id. Lets an LWW-rejected row with unchanged content be stamped instead
         * of re-pushed forever (PR 10 step 11).
         */
        private const val KEY_SESSION_SENT_HASH_PREFIX = "portal_session_sent_hash_"

        /**
         * Per-(userId, profileId) index of the portal session ids that currently hold a
         * sent hash, oldest first. Bounds the store (see [MAX_SESSION_SENT_HASHES]) and lets
         * garbage collection find entries without enumerating every settings key.
         */
        private const val KEY_SESSION_SENT_HASH_INDEX_PREFIX = "portal_session_sent_hash_index_"

        /**
         * Most sent hashes kept per (userId, profileId). Dropping an old one is always
         * safe: the LWW gate stamps only on `sentHash == currentHash`, so a missing hash
         * sends the row down the re-push/pending path, never to a wrong "synced" stamp.
         */
        internal const val MAX_SESSION_SENT_HASHES = 500

        private const val KEY_PHASE_PR_BACKFILL_CHECKPOINT_PREFIX = "portal_phase_pr_backfill_checkpoint_"
        private const val KEY_ROUTINE_GROUP_REPAIR_CURSOR_PREFIX = "portal_routine_group_repair_cursor_"
        private const val KEY_DEVICE_ID = "portal_device_id"
        private const val KEY_STORAGE_VERIFIED = "portal_storage_verified"

        /**
         * Portal user id of the account the last successful push landed in (PR 11).
         * Deliberately NOT cleared by [clearAuth]: sign-out must not forget which
         * account the local rows already live in, or the next sign-in cannot tell
         * "same account" from "different account" and would re-upload rows the new
         * account does not own.
         */
        private const val KEY_LAST_SYNCED_PORTAL_USER_ID = "portal_last_synced_user_id"
    private const val KEY_LAST_SYNCED_PORTAL_USER_LABEL = "portal_last_synced_user_label"
    }

    init {
        // Defensive check: verify that the storage is writable and can round-trip data.
        // This catches cases where the Settings instance is somehow compromised.
        verifyStorageIntegrity()
    }

    /**
     * Verifies that the secure storage can read/write data correctly.
     * This is a defensive check to catch initialization issues early.
     *
     * @throws IllegalStateException if storage verification fails.
     */
    private fun verifyStorageIntegrity() {
        try {
            // Write a test value and read it back
            val testValue = "storage_check_${currentTimeMillis()}"
            settings[KEY_STORAGE_VERIFIED] = testValue
            val readBack: String? = settings[KEY_STORAGE_VERIFIED]
            if (readBack != testValue) {
                throw IllegalStateException(
                    "Secure storage verification failed: written value '$testValue' " +
                        "but read back '$readBack'. Storage may be corrupted or unavailable.",
                )
            }
            // Clean up test value
            settings.remove(KEY_STORAGE_VERIFIED)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException(
                "Secure storage verification failed with exception: ${e.message}. " +
                    "Auth tokens cannot be safely stored.",
                e,
            )
        }
    }

    private val deviceIdLock = Any()

    // Serializes multi-key auth state mutations (save/clear). Without this,
    // a concurrent sign-in/refresh and sign-out can interleave their multi-key
    // writes, letting a stale completion re-populate tokens after sign-out or
    // letting observers see partial combinations (audit F004).
    private val authLock = Any()

    private val _isAuthenticated = MutableStateFlow(hasToken())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow(loadUser())
    val currentUser: StateFlow<PortalUser?> = _currentUser.asStateFlow()

    // Seeded from the stored per-profile cursors so a process restart does not report
    // "never synced" (or re-arm the first-sync trigger) until the next sync publishes.
    private val _lastSyncTimestamp = MutableStateFlow(
        settings.getStringOrNull(KEY_USER_ID)?.let { pullCursorFloor(it) } ?: 0L,
    )
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    /**
     * Flow of authentication events for UI notification.
     * Collectors will receive events when session expires, refresh fails, or user logs out.
     * Uses replay=0 so only active collectors receive events (no stale events on new subscriptions).
     */
    private val _authEvents = MutableSharedFlow<AuthEvent>(replay = 0, extraBufferCapacity = 1)
    val authEvents: SharedFlow<AuthEvent> = _authEvents.asSharedFlow()

    // Bumped (under authLock) by every clearAuth and every non-refresh save
    // (sign-in/sign-up). A refresh captures it before its network call and passes
    // it back to saveGoTrueAuth, so a refresh that was in flight across a sign-out
    // or account switch drops its write instead of resurrecting the old session.
    private var authGeneration = 0L

    /** Current auth generation; capture before a token refresh network call. */
    fun authGeneration(): Long = withPlatformLock(authLock) { authGeneration }

    /** Captures account-scoped state so a multi-store identity commit can roll back exactly. */
    internal fun snapshotAuthState(): AuthStateSnapshot = withPlatformLock(authLock) {
        AuthStateSnapshot(
            generation = authGeneration,
            accessToken = settings.getStringOrNull(KEY_TOKEN),
            refreshToken = settings.getStringOrNull(KEY_REFRESH_TOKEN),
            expiresAt = settings.getLong(KEY_EXPIRES_AT, 0L),
            userId = settings.getStringOrNull(KEY_USER_ID),
            userEmail = settings.getStringOrNull(KEY_USER_EMAIL),
            userName = settings.getStringOrNull(KEY_USER_NAME),
            isPremium = settings[KEY_IS_PREMIUM, false],
            subscriptionTier = settings.getStringOrNull(KEY_SUBSCRIPTION_TIER),
        )
    }

    /** Restores a snapshot after a failed profile/token identity commit. */
    internal fun restoreAuthState(snapshot: AuthStateSnapshot) = withPlatformLock(authLock) {
        // Never move the fence backwards: refreshes captured before the failed
        // transition must not become valid again when the prior account is restored.
        authGeneration = maxOf(authGeneration, snapshot.generation) + 1L
        restoreString(KEY_TOKEN, snapshot.accessToken)
        restoreString(KEY_REFRESH_TOKEN, snapshot.refreshToken)
        settings.putLong(KEY_EXPIRES_AT, snapshot.expiresAt)
        restoreString(KEY_USER_ID, snapshot.userId)
        restoreString(KEY_USER_EMAIL, snapshot.userEmail)
        restoreString(KEY_USER_NAME, snapshot.userName)
        settings[KEY_IS_PREMIUM] = snapshot.isPremium
        restoreString(KEY_SUBSCRIPTION_TIER, snapshot.subscriptionTier)
        // Per-(userId, profileId) cursors are not part of the identity snapshot: they are
        // written during sync, not during an identity commit, and are namespaced by user id
        // so a rolled-back account switch cannot corrupt them.
        _isAuthenticated.value = snapshot.accessToken != null
        _currentUser.value = loadUser()
    }

    /**
     * The stored refresh token and the generation it belongs to, read under one
     * lock so a sign-out/sign-in can't land between the two reads.
     */
    fun refreshTokenWithGeneration(): Pair<String?, Long> = withPlatformLock(authLock) {
        getRefreshToken() to authGeneration
    }

    /**
     * Persists a GoTrue session.
     *
     * @param expectedGeneration pass the [authGeneration] captured before a refresh
     *   request. If auth was cleared or replaced since, the write is dropped.
     *   Null (sign-in/sign-up) always writes and starts a new generation.
     * @return false if the write was dropped as stale.
     */
    fun saveGoTrueAuth(response: GoTrueAuthResponse, expectedGeneration: Long? = null): Boolean = withPlatformLock(authLock) {
        if (expectedGeneration == null) {
            authGeneration++
        } else if (expectedGeneration != authGeneration) {
            return@withPlatformLock false
        }
        // Preserve existing premium status — GoTrue auth response does not include it,
        // and overwriting would reset paid users to non-premium on every sign-in.
        //
        // F024: only preserve premium when the new auth response belongs to the
        // SAME user. Switching accounts (email/OAuth sign-in or a refresh that
        // resolves a different user) must NOT inherit the previous user's
        // entitlement; fail closed to non-premium and let a subsequent status
        // refresh set the correct value.
        // Only inherit premium when there was a real previous session for the SAME
        // user. A null previous id means no trusted prior session, so any leftover
        // premium flag is stale and must not be inherited (fail closed).
        val previousUserId: String? = settings.getStringOrNull(KEY_USER_ID)
        val sameUser = previousUserId != null && previousUserId == response.user.id
        val existingPremium: Boolean = if (sameUser) settings[KEY_IS_PREMIUM, false] else false

        settings[KEY_TOKEN] = response.accessToken
        settings[KEY_REFRESH_TOKEN] = response.refreshToken
        val expiresAt = response.expiresAt
            ?: (currentTimeMillis() / 1000 + response.expiresIn)
        settings.putLong(KEY_EXPIRES_AT, expiresAt)
        settings[KEY_USER_ID] = response.user.id
        settings[KEY_USER_EMAIL] = response.user.email ?: ""
        settings[KEY_USER_NAME] = response.user.displayName ?: ""
        settings[KEY_IS_PREMIUM] = existingPremium
        if (!sameUser) {
            // Account-scoped entitlement state must never cross an identity switch.
            // Sync cursors are namespaced by (userId, profileId) and survive the switch
            // so returning to the previous account resumes where it left off.
            settings.remove(KEY_SUBSCRIPTION_TIER)
            // The un-namespaced legacy cursor belongs to the previous account (the old
            // build zeroed it on every switch). It must never seed this account's cursors.
            settings.remove(KEY_LEGACY_LAST_SYNC)
            settings.remove(KEY_LEGACY_DELTA_PULL_KEY)
            // The UI's lastSyncTime is a cache of this user's min pull cursor; it must
            // never keep showing the previous account's "last successful pull".
            publishPullCursorFloor(response.user.id)
        }
        _isAuthenticated.value = true
        _currentUser.value = loadUser()
        true
    }

    fun getRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH_TOKEN)

    /**
     * Portal user id of the account the last successful push landed in (PR 11).
     * Survives [clearAuth] so a later sign-in can detect a different account.
     */
    fun getLastSyncedPortalUserId(): String? = settings.getStringOrNull(KEY_LAST_SYNCED_PORTAL_USER_ID)

    /**
     * Display label (email or id) for [getLastSyncedPortalUserId], so the account-switch
     * dialog can name both accounts. Never logged (F-076).
     */
    fun getLastSyncedPortalUserLabel(): String? = settings.getStringOrNull(KEY_LAST_SYNCED_PORTAL_USER_LABEL)

    /** Records which portal user the last successful push landed in. Not cleared by [clearAuth]. */
    fun setLastSyncedPortalUserId(userId: String) {
        settings[KEY_LAST_SYNCED_PORTAL_USER_ID] = userId
    }

    /** Records the display label for [setLastSyncedPortalUserId]. Not cleared by [clearAuth]. */
    fun setLastSyncedPortalUserLabel(label: String) {
        settings[KEY_LAST_SYNCED_PORTAL_USER_LABEL] = label
    }

    fun getExpiresAt(): Long = settings.getLong(KEY_EXPIRES_AT, 0L)

    fun isTokenExpired(): Boolean {
        val expiresAt = getExpiresAt()
        if (expiresAt == 0L) return true
        return currentTimeMillis() / 1000 >= (expiresAt - 60)
    }

    fun getToken(): String? = settings[KEY_TOKEN]

    fun hasToken(): Boolean = settings.getStringOrNull(KEY_TOKEN) != null

    fun getDeviceId(): String = withPlatformLock(deviceIdLock) {
        val existing: String? = settings[KEY_DEVICE_ID]
        if (existing != null) return@withPlatformLock existing

        val newId = generateDeviceId()
        settings[KEY_DEVICE_ID] = newId
        newId
    }

    // === Per-profile sync cursors (PR 10) ===
    //
    // Two cursors per (portal userId, profileId):
    //   pushWatermark — device clock, captured before gather; post-push stamps use it.
    //   pullCursor    — server clock, sent as PortalSyncPullRequest.lastSync.
    // Both are namespaced by user id so a sign-out / account switch cannot corrupt them,
    // and clearAuth deliberately leaves them in place.

    /** Device-clock watermark of the last completed push for this profile. 0 = never pushed. */
    fun getPushWatermark(userId: String, profileId: String): Long =
        settings[cursorKey(KEY_PUSH_WATERMARK_PREFIX, userId, profileId), 0L]

    fun setPushWatermark(userId: String, profileId: String, timestamp: Long) {
        withPlatformLock(authLock) {
            settings[cursorKey(KEY_PUSH_WATERMARK_PREFIX, userId, profileId)] = timestamp
        }
    }

    /**
     * Device-clock time at which a pull that followed a successful push last merged rows
     * into this profile. A pulled routine, cycle or custom exercise gets its local
     * `createdAt` at merge time, so this, together with [getPushWatermark], bounds
     * which rows reached this portal account (PR 11 account-switch classification).
     * Namespaced like the cursors and kept by [clearAuth]. 0 = no such pull yet.
     */
    fun getPullMergeWatermark(userId: String, profileId: String): Long =
        settings[cursorKey(KEY_PULL_MERGE_WATERMARK_PREFIX, userId, profileId), 0L]

    fun setPullMergeWatermark(userId: String, profileId: String, timestamp: Long) {
        withPlatformLock(authLock) {
            settings[cursorKey(KEY_PULL_MERGE_WATERMARK_PREFIX, userId, profileId)] = timestamp
        }
    }

    /**
     * The PR 11 "already reached this account" boundary for one profile: the later of the
     * last acknowledged push gather and the last post-push pull merge.
     */
    fun getAccountSyncBoundary(userId: String, profileId: String): Long =
        maxOf(getPushWatermark(userId, profileId), getPullMergeWatermark(userId, profileId))

    /** Server-clock cursor of the last completed pull. 0 / absent = next pull is a full pull. */
    fun getPullCursor(userId: String, profileId: String): Long =
        settings[cursorKey(KEY_PULL_CURSOR_PREFIX, userId, profileId), 0L]

    fun setPullCursor(userId: String, profileId: String, timestamp: Long) {
        withPlatformLock(authLock) {
            settings[cursorKey(KEY_PULL_CURSOR_PREFIX, userId, profileId)] = timestamp
        }
    }

    /**
     * Records a completed pull for one profile. The caller passes the first page's
     * `syncTime` minus the overlap (see SyncManager); this only persists it.
     */
    fun recordCompletedPull(userId: String, profileId: String, syncTime: Long) {
        setPullCursor(userId, profileId, syncTime)
    }

    /**
     * Resets one profile's pull cursor to 0 so its next pull is a full pull. Used by any
     * bulk removal of that profile's local rows (Delete All, backup restore, account-switch
     * choices) — otherwise the cursor would skip the rows the bulk removal just cleared.
     */
    fun resetPullCursor(userId: String, profileId: String) {
        setPullCursor(userId, profileId, 0L)
    }

    /** Resets every profile's pull cursor for [userId] (force-full-resync). */
    fun resetAllPullCursors(userId: String) {
        withPlatformLock(authLock) {
            val prefix = "$KEY_PULL_CURSOR_PREFIX$userId:"
            settings.keys
                .filter { it.startsWith(prefix) }
                .forEach { settings.remove(it) }
            _lastSyncTimestamp.value = 0L
        }
    }

    /**
     * Publishes the UI-facing "last successful pull" value (minimum across profiles).
     * Called by SyncManager after each profile loop.
     */
    fun publishMinPullCursor(minAcrossProfiles: Long) {
        _lastSyncTimestamp.value = minAcrossProfiles
    }

    /** Recomputes and publishes the UI cursor floor from this user's stored pull cursors. */
    private fun publishPullCursorFloor(userId: String) {
        _lastSyncTimestamp.value = pullCursorFloor(userId)
    }

    /**
     * Minimum non-zero stored pull cursor for [userId] (G-4: never-pulled profiles must not
     * report "never synced"). Before the one-time migration has run, the legacy global
     * value stands in, so an upgraded install does not flash "never synced" either.
     */
    private fun pullCursorFloor(userId: String): Long {
        val prefix = "$KEY_PULL_CURSOR_PREFIX$userId:"
        val floor = settings.keys
            .filter { it.startsWith(prefix) }
            .map { settings[it, 0L] }
            .filter { it > 0L }
            .minOrNull()
        return floor ?: settings[KEY_LEGACY_LAST_SYNC, 0L]
    }

    /**
     * One-time upgrade seeding (PR 10 step 8). Reads the legacy global cursor and writes:
     *  - every profile's `pushWatermark` = the legacy value (same sessions boundary as
     *    today, so pulled sessions are not re-selected);
     *  - `pullCursor` = the legacy value ONLY for the profile named by the legacy
     *    "userId:profileId" delta-pull marker, 0 for every other profile. The old build
     *    sent a full pull whenever that marker was absent or named another user/profile,
     *    so seeding any other profile with the value would skip server rows it never
     *    received (codex #856 P1).
     *
     * The legacy value belongs to [userId]: the old build zeroed it on every account
     * switch, and [saveGoTrueAuth]/[clearAuth] drop it on a switch or sign-out. A marker
     * that names a different user is the one remaining way to tell, so it also blocks
     * the push-watermark seed (a watermark inherited from another account would hide
     * this account's never-pushed routines/PRs).
     *
     * A legacy value of 0 seeds nothing (a restore must never zero cursors already
     * seeded). Both legacy keys are then removed. Returns true when seeding ran.
     */
    fun migrateLegacyCursors(
        userId: String,
        allProfileIds: List<String>,
    ): Boolean = withPlatformLock(authLock) {
        if (KEY_LEGACY_LAST_SYNC !in settings.keys) return@withPlatformLock false
        val legacy: Long = settings[KEY_LEGACY_LAST_SYNC, 0L]
        val marker: String? = settings.getStringOrNull(KEY_LEGACY_DELTA_PULL_KEY)
        settings.remove(KEY_LEGACY_LAST_SYNC)
        settings.remove(KEY_LEGACY_DELTA_PULL_KEY)
        if (legacy <= 0L) return@withPlatformLock false
        val markerUserId = marker?.substringBefore(':', missingDelimiterValue = "")
        if (markerUserId != null && markerUserId != userId) return@withPlatformLock false
        val markerProfileId = marker
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.trim()
            ?.ifBlank { "default" }

        for (profileId in allProfileIds) {
            val normalized = profileId.trim().ifBlank { "default" }
            settings[cursorKey(KEY_PUSH_WATERMARK_PREFIX, userId, normalized)] = legacy
            settings[cursorKey(KEY_PULL_CURSOR_PREFIX, userId, normalized)] =
                if (normalized == markerProfileId) legacy else 0L
        }
        true
    }

    /**
     * The legacy global cursor as evidence for [userId], without consuming it: the value
     * when it is present and not attributed (by the legacy delta-pull marker) to another
     * user, else 0. Used by the one-shot generation seeding that must run before
     * [migrateLegacyCursors] removes the key.
     */
    fun legacyLastSyncFor(userId: String): Long = withPlatformLock(authLock) {
        if (KEY_LEGACY_LAST_SYNC !in settings.keys) return@withPlatformLock 0L
        val markerUserId = settings.getStringOrNull(KEY_LEGACY_DELTA_PULL_KEY)
            ?.substringBefore(':', missingDelimiterValue = "")
        if (markerUserId != null && markerUserId != userId) 0L else settings[KEY_LEGACY_LAST_SYNC, 0L]
    }

    /**
     * True while the one-time routines/cycles/PRs repair push is still owed for this user.
     * That push re-sends routines, cycles, PRs and their tombstones for every profile from
     * timestamp 0, which is non-destructive under portal LWW and holds no session children.
     */
    fun needsRoutineCyclePrRepairPush(userId: String): Boolean =
        settings.getStringOrNull(routineCyclePrRepairKey(userId)) == null

    fun markRoutineCyclePrRepairPushDone(userId: String) {
        withPlatformLock(authLock) {
            settings[routineCyclePrRepairKey(userId)] = "1"
        }
    }

    /**
     * Content fingerprint of the session DTO this account last got accepted under
     * [portalSessionId]. Namespaced by `(userId, profileId)` exactly like the cursors
     * (S-1): account A's accept must never let account B's never-accepted row be
     * stamped as synced.
     */
    fun getSessionSentHash(userId: String, profileId: String, portalSessionId: String): String? =
        settings[sessionSentHashKey(userId, profileId, portalSessionId)]

    fun setSessionSentHash(userId: String, profileId: String, portalSessionId: String, hash: String?) {
        withPlatformLock(authLock) {
            val index = readSentHashIndex(userId, profileId)
            index.remove(portalSessionId)
            if (hash == null) {
                settings.remove(sessionSentHashKey(userId, profileId, portalSessionId))
            } else {
                settings[sessionSentHashKey(userId, profileId, portalSessionId)] = hash
                index.addLast(portalSessionId)
                while (index.size > MAX_SESSION_SENT_HASHES) {
                    settings.remove(sessionSentHashKey(userId, profileId, index.removeFirst()))
                }
            }
            writeSentHashIndex(userId, profileId, index)
        }
    }

    /** Portal session ids that currently hold a sent hash for (userId, profileId), oldest first. */
    fun sessionSentHashIds(userId: String, profileId: String): List<String> =
        withPlatformLock(authLock) { readSentHashIndex(userId, profileId).toList() }

    /**
     * Drops the sent hash of every indexed session not in [livePortalSessionIds] (deleted
     * locally or tombstoned by a pull). Returns how many were removed.
     */
    fun retainSessionSentHashes(userId: String, profileId: String, livePortalSessionIds: Set<String>): Int =
        withPlatformLock(authLock) {
            val index = readSentHashIndex(userId, profileId)
            val stale = index.filter { it !in livePortalSessionIds }
            if (stale.isEmpty()) return@withPlatformLock 0
            stale.forEach { settings.remove(sessionSentHashKey(userId, profileId, it)) }
            index.removeAll(stale.toSet())
            writeSentHashIndex(userId, profileId, index)
            stale.size
        }

    private fun readSentHashIndex(userId: String, profileId: String): ArrayDeque<String> {
        val raw = settings.getStringOrNull(cursorKey(KEY_SESSION_SENT_HASH_INDEX_PREFIX, userId, profileId))
        return ArrayDeque(raw?.split(',')?.filter { it.isNotEmpty() }.orEmpty())
    }

    private fun writeSentHashIndex(userId: String, profileId: String, index: Collection<String>) {
        val key = cursorKey(KEY_SESSION_SENT_HASH_INDEX_PREFIX, userId, profileId)
        if (index.isEmpty()) settings.remove(key) else settings[key] = index.joinToString(",")
    }

    fun getPhasePRBackfillCheckpoint(profileId: String): Long = settings[phasePRBackfillCheckpointKey(profileId), 0L]

    fun setPhasePRBackfillCheckpoint(profileId: String, timestamp: Long) {
        settings[phasePRBackfillCheckpointKey(profileId)] = timestamp
    }

    /**
     * Cursor for the one-time routine-group repair push: each sync re-sends a capped
     * batch of complete routine workouts older than this timestamp, so the portal
     * history the pre-fix per-set push truncated is rebuilt a batch at a time.
     *
     * `Long.MAX_VALUE` (the default) means "start at the newest workout"; `0` means the
     * repair has walked the whole history and is done.
     */
    fun getRoutineGroupRepairCursor(profileId: String): Long = settings[routineGroupRepairCursorKey(profileId), Long.MAX_VALUE]

    fun setRoutineGroupRepairCursor(profileId: String, cursor: Long) {
        settings[routineGroupRepairCursorKey(profileId)] = cursor
    }

    fun updatePremiumStatus(isPremium: Boolean) {
        settings[KEY_IS_PREMIUM] = isPremium
        _currentUser.value = _currentUser.value?.copy(isPremium = isPremium)
    }

    /**
     * Persists the active subscription tier string (EMBER, FLAME, INFERNO, or null).
     * Null clears the key so callers can distinguish "not yet resolved" from "no
     * active subscription." Used by [SyncManager] to gate features whose entitlement
     * depends on a specific tier rather than the broad `isPremium` flag (e.g., 50 Hz
     * telemetry sync is Inferno-only).
     */
    fun updateSubscriptionTier(tier: String?) {
        if (tier == null) {
            settings.remove(KEY_SUBSCRIPTION_TIER)
        } else {
            settings[KEY_SUBSCRIPTION_TIER] = tier
        }
    }

    fun getSubscriptionTier(): String? = settings[KEY_SUBSCRIPTION_TIER]

    fun clearAuth() {
        clearAuthInternal()
    }

    /**
     * Clears auth state and emits an authentication event to notify UI.
     * Use this when clearing auth due to session expiry or refresh failure
     * to allow the UI to show appropriate messaging to the user.
     *
     * @param event The authentication event describing why auth was cleared
     * @param expectedGeneration when set (refresh failures), clear and emit only if
     *   auth hasn't been cleared or replaced since that [authGeneration] was
     *   captured, so a stale failure can't wipe a newer session or report
     *   "session expired" after a deliberate sign-out.
     * @return false if skipped because the generation moved.
     */
    fun clearAuthWithEvent(event: AuthEvent, expectedGeneration: Long? = null): Boolean {
        val cleared = withPlatformLock(authLock) {
            if (expectedGeneration != null && expectedGeneration != authGeneration) {
                false
            } else {
                clearAuthInternal()
                true
            }
        }
        if (cleared) _authEvents.tryEmit(event)
        return cleared
    }

    /**
     * Emits a logout event for UI notification when user explicitly logs out.
     * Called by logout flows to inform UI of explicit user action.
     */
    fun emitLogoutEvent() {
        _authEvents.tryEmit(AuthEvent.LoggedOut)
    }

    /**
     * Emits an auth event WITHOUT clearing stored tokens. Use for recoverable
     * (transient/network) refresh failures so the stored refresh token survives
     * for a later retry instead of forcing the user to re-login (audit F020/F077).
     */
    fun emitAuthEvent(event: AuthEvent) {
        _authEvents.tryEmit(event)
    }

    private fun clearAuthInternal() = withPlatformLock(authLock) {
        authGeneration++
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
        settings.remove(KEY_EXPIRES_AT)
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_USER_NAME)
        settings.remove(KEY_IS_PREMIUM)
        settings.remove(KEY_SUBSCRIPTION_TIER)
        // Per-(userId, profileId) sync cursors are deliberately KEPT: they are namespaced
        // by user id, so re-linking the same account resumes where it left off and a
        // different account's cursors cannot be read through this one's keys.
        // The un-namespaced legacy cursor is NOT: its owner is unknown once the user id
        // is gone (the old build removed it on sign-out too).
        settings.remove(KEY_LEGACY_LAST_SYNC)
        settings.remove(KEY_LEGACY_DELTA_PULL_KEY)
        // Keep device ID for stable identity

        _isAuthenticated.value = false
        _currentUser.value = null
    }

    private fun loadUser(): PortalUser? {
        val id: String = settings[KEY_USER_ID] ?: return null
        val email: String = settings[KEY_USER_EMAIL] ?: return null
        val displayName: String? = settings[KEY_USER_NAME]
        val isPremium: Boolean = settings[KEY_IS_PREMIUM, false]

        return PortalUser(id, email, displayName, isPremium)
    }

    private fun restoreString(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings[key] = value
    }

    private fun generateDeviceId(): String {
        // Generate a stable device identifier using multiplatform UUID
        return generateUUID()
    }

    private fun phasePRBackfillCheckpointKey(profileId: String): String {
        val normalizedProfileId = profileId.trim().ifBlank { "default" }
        return "$KEY_PHASE_PR_BACKFILL_CHECKPOINT_PREFIX$normalizedProfileId"
    }

    private fun routineGroupRepairCursorKey(profileId: String): String {
        val normalizedProfileId = profileId.trim().ifBlank { "default" }
        return "$KEY_ROUTINE_GROUP_REPAIR_CURSOR_PREFIX$normalizedProfileId"
    }

    private fun cursorKey(prefix: String, userId: String, profileId: String): String {
        val normalizedProfileId = profileId.trim().ifBlank { "default" }
        return "$prefix$userId:$normalizedProfileId"
    }

    private fun routineCyclePrRepairKey(userId: String): String =
        "$KEY_ROUTINE_CYCLE_PR_REPAIR_DONE_PREFIX$userId"

    private fun sessionSentHashKey(userId: String, profileId: String, portalSessionId: String): String =
        cursorKey("$KEY_SESSION_SENT_HASH_PREFIX", userId, profileId) + ":$portalSessionId"
}

/**
 * Wipes secure auth storage on a fresh install whose secure store outlives the
 * app (the iOS Keychain survives uninstall; app preferences and the database
 * don't). Without this, a reinstall on a handed-down device is still signed in
 * as the previous owner.
 *
 * No install marker and no local database means a fresh install: clear. No
 * marker but a database present is an upgrade from a build that predates the
 * marker: keep the session. The marker is set in both cases.
 *
 * @return true if secure storage was cleared.
 */
internal fun resetSecureStorageOnFreshInstall(
    hasInstallMarker: () -> Boolean,
    localDatabaseExists: () -> Boolean,
    clearSecureStorage: () -> Unit,
    setInstallMarker: () -> Unit,
): Boolean {
    if (hasInstallMarker()) return false
    val freshInstall = !localDatabaseExists()
    if (freshInstall) clearSecureStorage()
    setInstallMarker()
    return freshInstall
}
