package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import kotlinx.coroutines.delay
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Desktop video player implementation using JavaFX MediaPlayer.
 * Plays video in a loop without controls (like a GIF preview), matching Android behavior.
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
    var isJavaFxInitialized by remember { mutableStateOf(false) }

    // Initialize JavaFX toolkit
    LaunchedEffect(Unit) {
        try {
            // Initialize JavaFX Platform if not already done
            Platform.startup { }
        } catch (e: IllegalStateException) {
            // Already initialized, which is fine
        }
        // Small delay to ensure JavaFX is ready
        delay(100)
        isJavaFxInitialized = true
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!isJavaFxInitialized) {
            // Show loading while JavaFX initializes
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        } else if (hasError) {
            // Error state
            ErrorDisplay(errorMessage, videoUrl, modifier = Modifier.fillMaxSize())
        } else {
            // Video player
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    createVideoPanel(
                        videoUrl = videoUrl,
                        onReady = { isLoading = false },
                        onError = { error ->
                            isLoading = false
                            hasError = true
                            errorMessage = error
                        }
                    )
                }
            )

            // Loading overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

/**
 * Creates a JPanel containing a JFXPanel with the video player
 */
private fun createVideoPanel(
    videoUrl: String,
    onReady: () -> Unit,
    onError: (String) -> Unit
): JPanel {
    val panel = JPanel(BorderLayout())
    val jfxPanel = JFXPanel()
    panel.add(jfxPanel, BorderLayout.CENTER)

    Platform.runLater {
        try {
            Logger.d("VideoPlayer") { "Creating JavaFX Media for: $videoUrl" }

            val media = Media(videoUrl)
            val mediaPlayer = MediaPlayer(media)
            val mediaView = MediaView(mediaPlayer)

            // Configure media view to fill available space
            mediaView.isPreserveRatio = true

            // Create a StackPane to center the video
            val root = StackPane()
            root.children.add(mediaView)
            root.style = "-fx-background-color: #1a1a1a;"

            // Bind media view size to container
            mediaView.fitWidthProperty().bind(root.widthProperty())
            mediaView.fitHeightProperty().bind(root.heightProperty())

            val scene = Scene(root)
            jfxPanel.scene = scene

            // Handle media player events
            mediaPlayer.setOnReady {
                Logger.d("VideoPlayer") { "Video ready, starting playback" }
                onReady()
                mediaPlayer.play()
            }

            mediaPlayer.setOnError {
                val error = mediaPlayer.error?.message ?: "Unknown error"
                Logger.e("VideoPlayer") { "Video error: $error" }
                onError(error)
            }

            mediaPlayer.setOnEndOfMedia {
                Logger.d("VideoPlayer") { "Video ended, restarting for loop" }
                mediaPlayer.seek(Duration.ZERO)
                mediaPlayer.play()
            }

            // Set volume to 0 (like a GIF preview)
            mediaPlayer.volume = 0.0

            // Handle errors during media loading
            media.setOnError {
                val error = media.error?.message ?: "Failed to load media"
                Logger.e("VideoPlayer") { "Media error: $error" }
                onError(error)
            }

        } catch (e: Exception) {
            Logger.e("VideoPlayer", e) { "Exception creating video player" }
            onError(e.message ?: "Failed to initialize video player")
        }
    }

    return panel
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorDisplay(
    errorMessage: String,
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
