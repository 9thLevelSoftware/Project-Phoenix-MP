package com.devil.phoenixproject.presentation.components

import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import co.touchlab.kermit.Logger

/**
 * Android video player implementation using VideoView.
 * Plays video in a loop without controls (like a GIF preview).
 */
@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier
) {
    Logger.d("VideoPlayer") { "VideoPlayer called with URL: $videoUrl" }

    if (videoUrl.isNullOrBlank()) {
        Logger.d("VideoPlayer") { "URL is null or blank, showing NoVideoAvailable" }
        NoVideoAvailable(modifier)
        return
    }

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Only show VideoView if no error
        if (!hasError) {
            AndroidView(
                factory = { ctx ->
                    Logger.d("VideoPlayer") { "Creating VideoView for: $videoUrl" }
                    VideoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        try {
                            setVideoURI(videoUrl.toUri())

                            setOnPreparedListener { mp ->
                                Logger.d("VideoPlayer") { "Video prepared, starting playback" }
                                isLoading = false
                                mp.isLooping = true
                                // Mute the video to prevent audio focus theft from other apps (e.g., Spotify)
                                // These are silent preview videos that play like GIFs
                                mp.setVolume(0f, 0f)
                                start()
                            }

                            setOnErrorListener { _, what, extra ->
                                Logger.e("VideoPlayer") { "Video error: what=$what, extra=$extra" }
                                isLoading = false
                                hasError = true
                                errorMessage = "Error: $what/$extra"
                                true
                            }

                            setOnCompletionListener {
                                Logger.d("VideoPlayer") { "Video completed, restarting" }
                                start()
                            }
                        } catch (e: Exception) {
                            Logger.e("VideoPlayer", e) { "Exception setting up VideoView" }
                            isLoading = false
                            hasError = true
                            errorMessage = e.message ?: "Unknown error"
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading indicator with background for visibility
        if (isLoading && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // Error state with background
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Failed to load video",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (errorMessage.isNotBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "URL: ${videoUrl.take(50)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NoVideoAvailable(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No video available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
