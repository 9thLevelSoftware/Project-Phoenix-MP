package com.devil.phoenixproject.presentation.components

import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import co.touchlab.kermit.Logger

/**
 * Android video player implementation using Media3 ExoPlayer.
 *
 * Supports:
 * - Progressive MP4 downloads (stream.mux.com)
 * - HLS streaming (cdn.jwplayer.com .m3u8)
 *
 * Plays video in a loop without controls (like a GIF preview).
 */
@AndroidOptIn(markerClass = [UnstableApi::class])
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

    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Create ExoPlayer instance with proper lifecycle management
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // Mute - these are silent preview videos
            playWhenReady = true
        }
    }

    // Update media item when URL changes
    LaunchedEffect(videoUrl) {
        Logger.d("VideoPlayer") { "Setting media item: $videoUrl" }
        isLoading = true
        hasError = false
        errorMessage = ""

        val mediaItem = MediaItem.fromUri(videoUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    // Add player listener for state changes
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        Logger.d("VideoPlayer") { "Video ready, playing" }
                        isLoading = false
                    }
                    Player.STATE_BUFFERING -> {
                        Logger.d("VideoPlayer") { "Video buffering" }
                        isLoading = true
                    }
                    Player.STATE_ENDED -> {
                        Logger.d("VideoPlayer") { "Video ended (should loop)" }
                    }
                    Player.STATE_IDLE -> {
                        Logger.d("VideoPlayer") { "Player idle" }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.e("VideoPlayer") { "Player error: ${error.errorCodeName} - ${error.message}" }
                isLoading = false
                hasError = true
                errorMessage = error.message ?: "Unknown error"
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            Logger.d("VideoPlayer") { "ExoPlayer released" }
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Only show PlayerView if no error
        if (!hasError) {
            AndroidView(
                factory = { ctx ->
                    Logger.d("VideoPlayer") { "Creating PlayerView" }
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                        useController = false // No playback controls
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
