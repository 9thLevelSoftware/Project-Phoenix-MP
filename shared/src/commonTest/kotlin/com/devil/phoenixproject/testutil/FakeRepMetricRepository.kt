package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepMetricSummary

class FakeRepMetricRepository : RepMetricRepository {
    val savedMetrics = mutableMapOf<String, List<RepMetricData>>()

    /** Call counters so tests can pin "non-INFERNO never loads the 50 Hz curves". */
    var getRepMetricsCalls = 0
    var getRepMetricSummariesCalls = 0

    override suspend fun saveRepMetrics(sessionId: String, metrics: List<RepMetricData>) {
        savedMetrics[sessionId] = (savedMetrics[sessionId] ?: emptyList()) + metrics
    }

    override suspend fun getRepMetrics(sessionId: String): List<RepMetricData> {
        getRepMetricsCalls++
        return savedMetrics[sessionId] ?: emptyList()
    }

    override suspend fun getRepMetricSummaries(sessionId: String): List<RepMetricSummary> {
        getRepMetricSummariesCalls++
        return savedMetrics[sessionId]?.map { it.toSummary() } ?: emptyList()
    }

    override suspend fun deleteRepMetrics(sessionId: String) {
        savedMetrics.remove(sessionId)
    }

    override suspend fun getRepMetricCount(sessionId: String): Long = (savedMetrics[sessionId]?.size ?: 0).toLong()
}
