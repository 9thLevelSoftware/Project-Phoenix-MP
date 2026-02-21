package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult

class FakeBiomechanicsRepository : BiomechanicsRepository {
    val savedBiomechanics = mutableMapOf<String, List<BiomechanicsRepResult>>()

    override suspend fun saveRepBiomechanics(sessionId: String, results: List<BiomechanicsRepResult>) {
        savedBiomechanics[sessionId] = (savedBiomechanics[sessionId] ?: emptyList()) + results
    }

    override suspend fun getRepBiomechanics(sessionId: String): List<BiomechanicsRepResult> {
        return savedBiomechanics[sessionId] ?: emptyList()
    }

    override suspend fun deleteRepBiomechanics(sessionId: String) {
        savedBiomechanics.remove(sessionId)
    }
}
