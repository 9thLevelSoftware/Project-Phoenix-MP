package com.devil.phoenixproject.presentation.screen

/**
 * A composed routine editor's registration with the leave gate (#1242). Lifetime is exactly
 * the editor's composition (registered and cleared in a `DisposableEffect`), so the gate never
 * holds a stale composable reference.
 */
interface RoutineEditorLeaveRegistration {
    /** The routine id the editor edits ("new" for a draft). */
    val routineId: String

    /** True when the editor holds unsaved content changes. */
    fun isDirty(): Boolean

    /**
     * Shows the editor's existing discard-changes dialog. [onConfirmed] runs only on Discard,
     * [onCancelled] on Cancel/dismiss. The dialog is the same one the back path uses; the two
     * confirm targets are distinct (back pops, the gate navigates forward).
     */
    fun requestDiscard(onConfirmed: () -> Unit, onCancelled: () -> Unit)
}

/**
 * Lifecycle-scoped registry of the currently composed routine editor, so an intent import can
 * resolve the dirty-editor leave gate BEFORE an overwrite commit (B2). Not composed or clean:
 * leaving runs at once. Dirty: the existing discard dialog decides — confirm leaves forward
 * (never `popBackStack`), cancel navigates nowhere and preserves the draft.
 */
object RoutineEditorLeaveGate {
    private var registration: RoutineEditorLeaveRegistration? = null

    fun register(registration: RoutineEditorLeaveRegistration) {
        this.registration = registration
    }

    fun unregister(registration: RoutineEditorLeaveRegistration) {
        if (this.registration === registration) this.registration = null
    }

    /** The composed editor's routine id, or null when no editor is composed. */
    fun registeredRoutineId(): String? = registration?.routineId

    /** True when a composed editor holds unsaved changes. */
    fun isDirty(): Boolean = registration?.isDirty() == true

    /**
     * Requests permission to leave the editor toward [onLeft]. Clean or not composed runs
     * [onLeft] immediately with no dialog; dirty asks with the existing discard dialog and
     * runs [onCancelled] (no navigation, draft intact) instead when the user cancels.
     */
    fun requestLeave(onLeft: () -> Unit, onCancelled: () -> Unit = {}) {
        val current = registration
        if (current == null || !current.isDirty()) {
            onLeft()
            return
        }
        current.requestDiscard(onConfirmed = onLeft, onCancelled = onCancelled)
    }
}
