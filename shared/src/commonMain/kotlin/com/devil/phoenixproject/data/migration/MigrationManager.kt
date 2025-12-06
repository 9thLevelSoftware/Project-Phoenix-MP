package com.devil.phoenixproject.data.migration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 */
class MigrationManager(
    private val workoutRepository: WorkoutRepository,
    private val trainingCycleRepository: TrainingCycleRepository
) {
    private val log = Logger.withTag("MigrationManager")
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            try {
                runMigrations()
            } catch (e: Exception) {
                log.e(e) { "Migration failed" }
            }
        }
    }

    private suspend fun runMigrations() {
        // Migration 1: WeeklyProgram -> TrainingCycle
        runWeeklyProgramMigration()
    }

    /**
     * Migrate WeeklyPrograms to TrainingCycles if needed.
     */
    private suspend fun runWeeklyProgramMigration() {
        val migration = TrainingCycleMigration(workoutRepository, trainingCycleRepository)

        if (migration.needsMigration()) {
            log.i { "Starting WeeklyProgram to TrainingCycle migration" }
            val count = migration.migrateAll()
            log.i { "Migration complete: $count programs migrated" }
        } else {
            log.d { "No WeeklyProgram migration needed" }
        }
    }
}
