package com.devil.phoenixproject.app.phoenix

import com.devil.phoenixproject.framework.core.WorkoutLifecycleDelegate
import com.devil.phoenixproject.framework.core.WorkoutStateProvider
import com.devil.phoenixproject.framework.protocol.BleRepository

/**
 * Compatibility aliases for the current Phoenix app wiring.
 *
 * This keeps app behavior intact while framework contracts migrate to
 * `framework/*` namespaces.
 */
typealias PhoenixBleRepository = BleRepository
typealias PhoenixWorkoutStateProvider = WorkoutStateProvider
typealias PhoenixWorkoutLifecycleDelegate = WorkoutLifecycleDelegate
