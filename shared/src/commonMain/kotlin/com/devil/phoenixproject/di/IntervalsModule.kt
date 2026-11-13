package com.devil.phoenixproject.di

import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.data.integration.intervals.CompletionEligibilityResolver
import com.devil.phoenixproject.data.integration.intervals.IntervalsApi
import com.devil.phoenixproject.data.integration.intervals.IntervalsActiveProfileSource
import com.devil.phoenixproject.data.integration.intervals.IntervalsApiClient
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionReader
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionStore
import com.devil.phoenixproject.data.integration.intervals.IntervalsCredentialSource
import com.devil.phoenixproject.data.integration.intervals.IntervalsDrainTrigger
import com.devil.phoenixproject.data.integration.intervals.IntervalsExportCoordinator
import com.devil.phoenixproject.data.integration.intervals.IntegrationStorageGate
import com.devil.phoenixproject.data.integration.intervals.IntervalsProfileLifecycle
import com.devil.phoenixproject.data.integration.intervals.PendingCompletionFinalizer
import com.devil.phoenixproject.data.integration.intervals.RoutineCompletionFinalizer
import com.devil.phoenixproject.data.integration.intervals.WorkoutCompletionFinalizer
import com.devil.phoenixproject.data.repository.IntervalsOutboxRepository
import com.devil.phoenixproject.data.repository.SqlDelightIntervalsOutboxRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.currentTimeMillis
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.random.Random

/**
 * Explicit singleton bindings for the Intervals.icu outbound export (issue #1384,
 * packet P3; spec §5 P3 and integration-model §6 'Secure DI and account lifecycle').
 *
 * Security invariants enforced at these bindings:
 * - The secret store is ONLY ever resolved through the named
 *   [SecureSettingsQualifier] binding (EncryptedSharedPreferences on Android, which
 *   throws rather than falling back; Keychain on iOS). There is no plaintext path.
 * - [IntervalsApiClient] owns its engine lifetime with the injectable
 *   [IntervalsHttpClientQualifier] seam (MockEngine in tests) and is never built on the
 *   portal client.
 * - The outbox repository gets the REAL [SqlDriver] so cross-restart UNCERTAIN jobs are
 *   visible to the bounded reconciliation listing.
 * - The export coordinator runs in a dedicated supervised application scope with
 *   injectable clock/jitter seams; it has no BLE dependency and never blocks workout
 *   completion.
 * - Active-profile observation is the dispatch suppression switch: a profile switch
 *   (`activeProfileContext` not `Ready`) yields a null active profile, which suppresses
 *   dispatch and cancels in-flight coordination work (integration-model §6).
 */
val intervalsModule = module {

    // ---- secure connection/consent store ------------------------------------
    single<IntervalsConnectionStore> {
        IntervalsConnectionStore(
            db = get(),
            // Named secure binding ONLY: never an unqualified Settings lookup.
            secureSettings = get(SecureSettingsQualifier),
            api = get(),
            nowEpochMs = ::currentTimeMillis,
            rawScanDriver = get<SqlDriver>(),
            // Platform fail-closed seam (integration-model §6): platforms that support
            // Intervals.icu export must explicitly bind IntegrationStorageGate (iOS binds
            // the live backup-exclusion gate; Android binds its explicit gate). Unbound
            // targets (e.g. tvOS) fail closed.
            integrationStoragePermitted = getOrNull<IntegrationStorageGate>()?.let { gate ->
                { gate.permitted() }
            } ?: { false },
        )
    }
    single<IntervalsConnectionReader> { get<IntervalsConnectionStore>() }
    single<IntervalsCredentialSource> { get<IntervalsConnectionStore>() }
    single<CompletionEligibilityResolver> { get<IntervalsConnectionStore>() }
    single<IntervalsProfileLifecycle> { get<IntervalsConnectionStore>() }

    // ---- outbox persistence -------------------------------------------------
    single<IntervalsOutboxRepository> {
        SqlDelightIntervalsOutboxRepository(
            db = get(),
            connectionReader = get(),
            // The same driver SQLDelight uses; unlocks the persisted UNCERTAIN scan.
            rawScanDriver = get<SqlDriver>(),
        )
    }

    // ---- isolated transport -------------------------------------------------
    single<IntervalsApi> {
        IntervalsApiClient(
            // Injectable engine seam for tests; the client builds its own otherwise.
            httpClientEngine = getOrNull<HttpClientEngine>(IntervalsHttpClientQualifier),
            nowEpochMs = ::currentTimeMillis,
        )
    } onClose { (it as? IntervalsApiClient)?.close() }

    // ---- completion finalization & drain trigger ----------------------------
    single { IntervalsDrainTrigger() }

    single {
        WorkoutCompletionFinalizer(
            db = get(),
            workoutRepository = get(),
            eligibilityResolver = get(),
            now = ::currentTimeMillis,
            // A durable admission signals the shared coalesced trigger; the coordinator's
            // drain finalizes pending journals first and then dispatches due outbox jobs.
            onDurableAdmission = get<IntervalsDrainTrigger>()::signal,
        )
    }
    // The narrow engine seam threaded through MainViewModel / DefaultWorkoutSessionManager
    // and both native factories.
    single<RoutineCompletionFinalizer> { get<WorkoutCompletionFinalizer>() }

    single<PendingCompletionFinalizer> {
        val finalizer = get<WorkoutCompletionFinalizer>()
        PendingCompletionFinalizer {
            withContext(Dispatchers.IO) {
                finalizer.finalizePending()
            }
        }
    }

    // ---- bounded dispatch ---------------------------------------------------
    // Explicit binding so module verification can resolve the coordinator's
    // active-profile dependency (KoinModuleVerifyTest).
    single<IntervalsActiveProfileSource> {
        IntervalsActiveProfileSource {
            // Profile switch (context not Ready) suppresses dispatch: null -> stop.
            (get<UserProfileRepository>().activeProfileContext.value
                as? com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready)?.profile?.id
        }
    }
    single {
        IntervalsExportCoordinator(
            // Dedicated supervised application scope (children fail independently).
            applicationScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("IntervalsApplication"),
            ),
            api = get(),
            outbox = get(),
            connectionReader = get(),
            credentials = get(),
            activeProfile = get(),
            pendingFinalizer = get(),
            drainTrigger = get(),
            // Injectable clock/jitter seams (deterministic in tests).
            nowEpochMs = ::currentTimeMillis,
            jitterUnit = { Random.nextDouble() },
        )
    } onClose { it?.shutdown() }
}
