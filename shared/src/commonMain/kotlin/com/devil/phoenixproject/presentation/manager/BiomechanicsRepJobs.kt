package com.devil.phoenixproject.presentation.manager

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Includes registered lazy jobs so completion snapshots wait for every captured rep. */
internal class BiomechanicsRepJobs {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())

    fun track(job: Job) {
        jobs.update { current -> current.filter { !it.isCompleted } + job }
        job.invokeOnCompletion {
            jobs.update { current -> current.filter { it !== job } }
        }
    }

    fun hasUnfinishedJobs(): Boolean = jobs.value.any { !it.isCompleted }

    suspend fun awaitCompletion() {
        while (true) {
            val unfinished = jobs.value.filter { !it.isCompleted }
            if (unfinished.isEmpty()) return
            unfinished.forEach { it.join() }
        }
    }
}
