package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.domain.model.ExerciseFormType
import com.devil.phoenixproject.domain.model.FormAssessment

/**
 * iOS stub for FormCheckOverlay.
 *
 * Form Check on iOS is deferred to v0.6.0+ (requirement IOS-CV-01/02).
 * This stub provides the actual implementation to satisfy KMP expect/actual,
 * but renders no content.
 *
 * Phase 16 (CV-10) will add UI to show "Form Check coming soon" message
 * when iOS users try to enable the feature.
 */
@Composable
actual fun FormCheckOverlay(
    isEnabled: Boolean,
    exerciseType: ExerciseFormType?,
    onFormAssessment: (FormAssessment) -> Unit,
    modifier: Modifier
) {
    // iOS: No-op stub. Form Check requires Apple Vision or MediaPipe iOS SDK
    // which is deferred to v0.6.0+ (IOS-CV-01, IOS-CV-02 requirements).
    // The UI layer (Phase 16) will gate access and show "coming soon" message.
}
