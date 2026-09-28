package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CycleShellTitleTest {
    @Test
    fun editorShowsTrimmedCycleName() {
        assertEquals(
            "Push Pull",
            cycleShellTitle(NavigationRoutes.CycleEditor.createRoute("new"), "  Push Pull  "),
        )
    }

    @Test
    fun reviewShowsCycleName() {
        assertEquals(
            "5/3/1",
            cycleShellTitle(NavigationRoutes.CycleReview.createRoute("cycle-1"), "5/3/1"),
        )
    }

    @Test
    fun blankEditorNameUsesTrainingCycleFallback() {
        assertEquals("Training Cycle", cycleShellTitle("cycle_editor/new", "   "))
    }

    @Test
    fun blankReviewNameUsesCycleReviewFallback() {
        assertEquals("Cycle Review", cycleShellTitle("cycleReview/cycle-1", ""))
    }

    @Test
    fun otherRoutesDoNotConsumeThePublishedTitle() {
        assertNull(cycleShellTitle(NavigationRoutes.TrainingCycles.route, "Push Pull"))
    }

    @Test
    fun compactEditorKeepsRealNameAndShortensFallback() {
        assertEquals("Push Pull", compactCycleShellTitle("cycle_editor/new", "Push Pull"))
        assertEquals("Cycle", compactCycleShellTitle("cycle_editor/new", "Training Cycle"))
    }

    @Test
    fun compactReviewKeepsRealNameAndShortensFallback() {
        assertEquals("5/3/1", compactCycleShellTitle("cycleReview/cycle-1", "5/3/1"))
        assertEquals("Review", compactCycleShellTitle("cycleReview/cycle-1", "Cycle Review"))
    }
}
