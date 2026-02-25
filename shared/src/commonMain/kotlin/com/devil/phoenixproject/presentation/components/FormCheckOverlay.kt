package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.domain.model.ExerciseFormType
import com.devil.phoenixproject.domain.model.FormAssessment

/**
 * Camera preview with skeleton overlay for form checking.
 *
 * Platform-specific implementation:
 * - Android: CameraX preview + MediaPipe pose estimation + Canvas skeleton
 * - iOS: Stub returning empty content (CV-10 implementation deferred)
 *
 * @param isEnabled Whether form check is enabled (camera only activated when true)
 * @param exerciseType Current exercise being performed (for form evaluation)
 * @param onFormAssessment Callback when a new form assessment is available
 * @param modifier Modifier for the overlay container (typically positioned in corner)
 */
@Composable
expect fun FormCheckOverlay(
    isEnabled: Boolean,
    exerciseType: ExerciseFormType?,
    onFormAssessment: (FormAssessment) -> Unit,
    modifier: Modifier = Modifier
)
