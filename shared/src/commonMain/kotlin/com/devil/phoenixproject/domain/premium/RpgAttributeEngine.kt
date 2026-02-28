package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile

/**
 * Pure stateless computation engine for RPG attributes.
 * Follows the ReadinessEngine pattern: stateless object with pure functions,
 * no DB or DI dependencies.
 *
 * STUB: Returns EMPTY for all inputs. Will be implemented in Task 2 (GREEN).
 */
object RpgAttributeEngine {

    fun computeProfile(input: RpgInput): RpgProfile = RpgProfile.EMPTY
}
