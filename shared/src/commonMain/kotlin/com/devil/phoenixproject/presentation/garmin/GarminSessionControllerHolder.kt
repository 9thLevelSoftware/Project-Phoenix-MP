package com.devil.phoenixproject.presentation.garmin

/**
 * Koin-visible holder for the composed [GarminSessionController] (issue #1391, packet B2).
 *
 * The single [com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager]
 * lives inside MainViewModel, so the controller is composed there (integration-model.md §5:
 * "existing V owns composition") and published here for the phone UI to resolve via Koin.
 * This keeps exactly one controller bound to exactly one session manager — never a second
 * session manager in the DI graph.
 */
class GarminSessionControllerHolder {
    /**
     * Set once by MainViewModel at composition time. Access from the main thread only
     * (the same owner-dispatcher discipline as the controller itself).
     */
    var controller: GarminSessionController? = null

    fun requireController(): GarminSessionController =
        controller ?: error("GarminSessionController is not composed yet (MainViewModel owns composition)")
}
