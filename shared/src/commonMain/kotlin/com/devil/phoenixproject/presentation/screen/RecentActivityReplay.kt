package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession

internal fun WorkoutSession.replayExerciseId(): String? = exerciseId
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
