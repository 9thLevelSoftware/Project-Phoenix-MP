package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalMvtRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.onerepmax.MvtProvider
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.CountVelocityOneRepMaxImprovementsUseCase
import com.devil.phoenixproject.domain.usecase.MvtExerciseView
import com.devil.phoenixproject.domain.usecase.ProgressionUseCase
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.domain.voice.SafeWordDetectionManager
import org.koin.dsl.module

val domainModule = module {
    // Preferences
    // Settings is provided by platformModule
    single { SettingsPreferencesManager(get()) }
    single<PreferencesManager> { get<SettingsPreferencesManager>() }

    // Use Cases
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }
    single { RecommendWeightAdjustmentUseCase() }
    single { ApplyEquipmentRackLoadUseCase() }
    factory { ResolveRoutineWeightsUseCase(get(), get(), get()) }
    factory { ApplyRoutineModifierUseCase(get(), get()) }
    factory { RoutineTimeEstimator(get()) }
    single { TemplateConverter(get()) }

    // Assessment
    single { AssessmentEngine() }

    // Velocity-based 1RM (issue #517)
    single { MvtProvider() }
    single { VelocityOneRepMaxEstimator(get()) } // get() = AssessmentEngine
    single {
        val workoutRepo = get<WorkoutRepository>()
        val exerciseRepo = get<ExerciseRepository>()
        val personalRepo = get<PersonalMvtRepository>()
        val velRepo = get<VelocityOneRepMaxRepository>()
        ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { id, profile, since -> workoutRepo.getVelocityPointsForExercise(id, profile, since) },
            exerciseLookup = { id ->
                exerciseRepo.getExerciseById(id)?.let { ex ->
                    object : MvtExerciseView {
                        override val name = ex.name
                        override val muscleGroups = ex.muscleGroups
                        override val mvtOverrideMs = ex.mvtOverrideMs
                    }
                }
            },
            personalMvtLookup = { id, profile -> personalRepo.get(id, profile)?.let { it.personalMvtMs to it.sampleCount } },
            mvtProvider = get(),
            estimator = get(),
            persist = { result, id, computedAt, profile -> velRepo.insert(result, id, computedAt, profile) },
        )
    }
    single { RecordPersonalMvtSampleUseCase(get()) }
    single { CountVelocityOneRepMaxImprovementsUseCase() }

    // Migration
    single { MigrationManager(get(), get<UserProfileRepository>(), get<GamificationRepository>()) }

    // Voice / Safe Word (Issue #141)
    // SafeWordListenerFactory is provided by platformModule
    single { SafeWordDetectionManager(get(), get()) }
}
