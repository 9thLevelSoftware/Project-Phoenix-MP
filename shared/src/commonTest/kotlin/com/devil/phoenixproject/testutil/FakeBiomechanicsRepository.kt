package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult

class FakeBiomechanicsRepository : BiomechanicsRepository {
    val savedBiomechanics = mutableMapOf<String, List<BiomechanicsRepResult>>()

    override suspend fun getRepBiomechanics(sessionId: String): List<BiomechanicsRepResult> = savedBiomechanics[sessionId] ?: emptyList()

    /**
     * Stand-in for the workout transaction's delete-then-insert. History still
     * reads [getRepBiomechanics].
     */
    fun replaceForSession(sessionId: String, results: List<BiomechanicsRepResult>) {
        if (results.isEmpty()) {
            savedBiomechanics.remove(sessionId)
        } else {
            savedBiomechanics[sessionId] = results.toList()
        }
    }
}
