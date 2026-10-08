package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.Routine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1223 FE-C behavioral tests for the one-shot in-memory AI draft holder
 * and its response scoping (binding amendment 4: session/profile binding).
 *
 * Non-composable by design — the repo has no Compose UI test harness in
 * commonTest, so every scoping rule lives in [AiRoutineDraftHolder] and is
 * exercised here directly.
 */
class AiRoutineDraftHolderTest {

    private fun draft(id: String = "draft-1", profileId: String = "profile-a") = Routine(
        id = id,
        name = "AI workout",
        profileId = profileId,
    )

    // === Acceptance scoping: account id + profile id + per-request token ===

    @Test
    fun `response is accepted only while the request token and identity still match`() {
        val holder = AiRoutineDraftHolder()
        val token = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")

        val acceptance = holder.acceptResponse(
            requestToken = token,
            routine = draft(),
            currentAccountId = "account-1",
            currentProfileId = "profile-a",
        )

        assertEquals(AiRoutineDraftAcceptance.ACCEPTED, acceptance)
        assertTrue(holder.hasStagedDraft())
    }

    @Test
    fun `late response after cancel is rejected and stages nothing`() {
        val holder = AiRoutineDraftHolder()
        val token = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")

        // Cancel (also covers navigation away): invalidates in-flight + staged.
        holder.invalidate()

        val acceptance = holder.acceptResponse(
            requestToken = token,
            routine = draft(),
            currentAccountId = "account-1",
            currentProfileId = "profile-a",
        )

        assertEquals(AiRoutineDraftAcceptance.STALE_REQUEST, acceptance)
        assertFalse(holder.hasStagedDraft())
        assertNull(holder.consumeGeneratedRoutine("draft-1"))
    }

    @Test
    fun `late response after logout is rejected and stages nothing`() {
        val holder = AiRoutineDraftHolder()
        val token = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")

        // Logout: account gone (and the session state is invalidated).
        holder.invalidate()

        val acceptance = holder.acceptResponse(
            requestToken = token,
            routine = draft(),
            currentAccountId = null,
            currentProfileId = "profile-a",
        )

        assertEquals(AiRoutineDraftAcceptance.STALE_REQUEST, acceptance)
        assertFalse(holder.hasStagedDraft())
    }

    @Test
    fun `account switch mid-flight is rejected and clears staged drafts`() {
        val holder = AiRoutineDraftHolder()
        val token = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")

        val acceptance = holder.acceptResponse(
            requestToken = token,
            routine = draft(),
            currentAccountId = "account-2", // switched while in flight
            currentProfileId = "profile-a",
        )

        assertEquals(AiRoutineDraftAcceptance.IDENTITY_CHANGED, acceptance)
        assertFalse(holder.hasStagedDraft())
    }

    @Test
    fun `profile switch mid-flight is rejected and stages nothing`() {
        val holder = AiRoutineDraftHolder()
        val token = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")

        val acceptance = holder.acceptResponse(
            requestToken = token,
            routine = draft(profileId = "profile-a"),
            currentAccountId = "account-1",
            currentProfileId = "profile-b", // switched while in flight
        )

        assertEquals(AiRoutineDraftAcceptance.IDENTITY_CHANGED, acceptance)
        assertFalse(holder.hasStagedDraft())
        assertNull(holder.consumeGeneratedRoutine("draft-1"))
    }

    @Test
    fun `a newer request supersedes the older request token`() {
        val holder = AiRoutineDraftHolder()
        holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t1")
        val token2 = holder.beginRequest(accountId = "account-1", profileId = "profile-a", requestToken = "t2")

        // The t1 response arrives late; only t2 may stage.
        val stale = holder.acceptResponse("t1", draft(), "account-1", "profile-a")
        assertEquals(AiRoutineDraftAcceptance.STALE_REQUEST, stale)
        assertFalse(holder.hasStagedDraft())

        val fresh = holder.acceptResponse(token2, draft(), "account-1", "profile-a")
        assertEquals(AiRoutineDraftAcceptance.ACCEPTED, fresh)
        assertTrue(holder.hasStagedDraft())
    }

    // === One-shot holder semantics ===

    @Test
    fun `consume is one-shot and clears the holder`() {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())

        val first = holder.consumeGeneratedRoutine("draft-1")
        assertEquals("draft-1", first?.id)
        assertNull(holder.consumeGeneratedRoutine("draft-1"), "second consume must find nothing")
        assertFalse(holder.hasStagedDraft())
    }

    @Test
    fun `consume with a non-matching id clears the holder and returns null`() {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())

        assertNull(holder.consumeGeneratedRoutine("some-other-id"))
        assertFalse(holder.hasStagedDraft(), "id mismatch must clear the staged draft")
        assertNull(holder.consumeGeneratedRoutine("draft-1"))
    }

    @Test
    fun `starting a new request drops any previously staged draft`() {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())
        holder.beginRequest(accountId = "account-1", profileId = "profile-a")

        assertFalse(holder.hasStagedDraft())
        assertNull(holder.consumeGeneratedRoutine("draft-1"))
    }

    // === Profile re-check before Edit and before Save ===

    @Test
    fun `profile switch blocks Edit and Save for a staged draft`() {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft(profileId = "profile-a"))

        assertTrue(holder.stagedProfileMatches("profile-a"), "same profile: Edit allowed")

        // Profile switched after staging: Edit is blocked (offer regenerate) and
        // Save must not move prior-profile loads into the new profile.
        assertFalse(holder.stagedProfileMatches("profile-b"))
        assertFalse(
            generatedDraftSaveEnabled(
                expiredDraft = false,
                stagedDraftProfileId = "profile-a",
                currentProfileId = "profile-b",
            ),
        )
    }

    @Test
    fun `save stays enabled for a staged draft whose profile is still active`() {
        assertTrue(
            generatedDraftSaveEnabled(
                expiredDraft = false,
                stagedDraftProfileId = "profile-a",
                currentProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `expired draft recovery state always disables Save`() {
        assertFalse(
            generatedDraftSaveEnabled(
                expiredDraft = true,
                stagedDraftProfileId = null,
                currentProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `routines without a staged generated draft keep the existing save semantics`() {
        assertTrue(
            generatedDraftSaveEnabled(
                expiredDraft = false,
                stagedDraftProfileId = null,
                currentProfileId = "any-profile",
            ),
        )
    }
}
