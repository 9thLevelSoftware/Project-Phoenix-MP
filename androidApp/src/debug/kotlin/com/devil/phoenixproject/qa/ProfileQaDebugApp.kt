package com.devil.phoenixproject.qa

import com.devil.phoenixproject.VitruvianApp
import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.database.VitruvianDatabase
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

class ProfileQaDebugApp : VitruvianApp() {
    override fun onCreate() {
        super.onCreate()

        loadKoinModules(
            module {
                single { ProfileQaFixtureGate(this@ProfileQaDebugApp) }
                single {
                    ProfileQaSeeder(
                        userProfileRepository = get<UserProfileRepository>(),
                        exerciseRepository = get<ExerciseRepository>(),
                        workoutRepository = get<WorkoutRepository>(),
                        repMetricRepository = get<RepMetricRepository>(),
                        personalRecordRepository = get<PersonalRecordRepository>(),
                        assessmentRepository = get<AssessmentRepository>(),
                        velocityOneRepMaxRepository = get<VelocityOneRepMaxRepository>(),
                        database = get<VitruvianDatabase>(),
                    )
                }
                single<PortalApiClient> {
                    QaBlockingPortalApiClient(
                        supabaseConfig = get<SupabaseConfig>(),
                        tokenStorage = get<PortalTokenStorage>(),
                        fixtureGate = get<ProfileQaFixtureGate>(),
                    )
                }
            },
        )
    }
}
