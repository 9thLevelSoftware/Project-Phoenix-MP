@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.devil.phoenixproject

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.di.SecureSettingsQualifier
import com.devil.phoenixproject.di.isTvosSimulatorFixtureEnabled
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlin.native.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

/**
 * Explicit simulator/debug fixture only. No screen replacement, no production DB deletion,
 * no Keychain tokens, and no reusable persistent data from earlier UI tests.
 * The device actual of isTvosSimulatorFixtureEnabled() is unconditionally false.
 */
internal fun installTvosIsolatedFixture(): Boolean {
    if (!Platform.isDebugBinary || !isTvosSimulatorFixtureEnabled() ||
        !NSProcessInfo.processInfo.arguments.contains("--phoenix-tv-isolated-data")) return false
    val koin = KoinPlatform.getKoin()
    val backend = koin.get<BleRepository>()
    val arguments = NSProcessInfo.processInfo.arguments
    val fixtureBle = object : BleRepository by backend {
        override val scannedDevices = if (arguments.contains("--phoenix-tv-empty-scan")) {
            MutableStateFlow<List<ScannedDevice>>(emptyList())
        } else backend.scannedDevices

        override suspend fun connect(device: ScannedDevice): Result<Unit> =
            if (arguments.contains("--phoenix-tv-connect-failure")) {
                Result.failure(IllegalStateException("SIMULATOR FIXTURE: connection refused"))
            } else backend.connect(device)
    }
    val suite = "com.devil.phoenixproject.tv.fixture.${NSUUID().UUIDString}"
    val settings = NSUserDefaultsSettings(NSUserDefaults(suiteName = suite))
    koin.loadModules(listOf(module {
        single<SqlDriver> {
            NativeSqliteDriver(PhoenixDatabase.Schema, ":memory:", onConfiguration = { config ->
                // Native SQLDelight does not treat a ':memory:' name as the
                // in-memory flag; explicitly share its ephemeral connection pool.
                config.copy(name = null, inMemory = true,
                    extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
            })
        }
        single<Settings> { settings }
        single<Settings>(SecureSettingsQualifier) { settings }
        single<BleRepository> { fixtureBle }
        single { SupabaseConfig(url = "", anonKey = "") }
    }))
    return true
}

internal suspend fun seedTvosFixtureRoutine() {
    check(Platform.isDebugBinary && isTvosSimulatorFixtureEnabled())
    check(NSProcessInfo.processInfo.arguments.contains("--phoenix-tv-isolated-data"))
    if (NSProcessInfo.processInfo.arguments.contains("--phoenix-tv-empty-scan")) return
    val koin = KoinPlatform.getKoin()
    val bodyweight = NSProcessInfo.processInfo.arguments.contains("--phoenix-tv-bodyweight-routine")
    val exercise = koin.get<ExerciseRepository>().getAllExercises().first()
        .first { if (bodyweight) it.isBodyweight else it.id == "Ab_Crunch_Machine" && !it.isBodyweight }
    val repository = koin.get<WorkoutRepository>()
    val routine = if (bodyweight) Routine(
        id = "tv-fixture-bodyweight", name = "SIMULATOR FIXTURE · Bodyweight",
        description = "One bodyweight set · manual rep entry · no trainer load. Test data only.",
        exercises = listOf(RoutineExercise(
            id = "tv-fixture-bodyweight-exercise", exercise = exercise, orderIndex = 0,
            setReps = listOf(8), weightPerCableKg = 0f, setRestSeconds = listOf(0),
        )),
    ) else Routine(
        id = "tv-fixture-two-sets",
        name = "SIMULATOR FIXTURE · Cable strength",
        description = "Two cable sets · 8 reps · 20 kg per cable · 30s rest. Test data, not a live workout.",
        exercises = listOf(RoutineExercise(
            id = "tv-fixture-cable-exercise", exercise = exercise, orderIndex = 0,
            setReps = listOf(8, 8), weightPerCableKg = 20f,
            setRestSeconds = listOf(30, 30), stallDetectionEnabled = false,
        )),
    )
    // Use the real SQLDelight repository, and read back the exact row before exposing startup-ready.
    if (repository.getRoutineById(routine.id) == null) repository.saveRoutine(routine)
    val saved = repository.getRoutineById(routine.id)?.exercises?.single()
    check(saved?.setReps == routine.exercises.single().setReps)
    check(saved?.exercise?.isBodyweight == bodyweight)
}
