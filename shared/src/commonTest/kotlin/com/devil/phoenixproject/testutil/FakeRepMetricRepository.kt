package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.domain.model.RepMetricData

class FakeRepMetricRepository : RepMetricRepository {
    val savedMetrics = mutableMapOf<String, List<RepMetricData>>()

    override suspend fun saveRepMetrics(sessionId: String, metrics: List<RepMetricData>) {
        savedMetrics[sessionId] = (savedMetrics[sessionId] ?: emptyList()) + metrics
    }

    override suspend fun getRepMetrics(sessionId: String): List<RepMetricData> {
        return savedMetrics[sessionId] ?: emptyList()
    }

    override suspend fun deleteRepMetrics(sessionId: String) {
        savedMetrics.remove(sessionId)
    }

    override suspend fun getRepMetricCount(sessionId: String): Long {
        return (savedMetrics[sessionId]?.size ?: 0).toLong()
    }
}
