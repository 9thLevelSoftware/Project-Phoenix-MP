package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Video player component for exercise demonstrations.
 *
 * Platform-specific implementations provide actual video playback:
 * - Android: Uses VideoView wrapped in AndroidView
 * - iOS: AVPlayer implementation
 */
@Composable
expect fun VideoPlayer(videoUrl: String?, modifier: Modifier = Modifier)
