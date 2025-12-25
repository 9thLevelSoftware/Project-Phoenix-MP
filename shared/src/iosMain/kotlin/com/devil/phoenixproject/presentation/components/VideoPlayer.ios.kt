@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeMake
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

/**
 * iOS video player implementation backed by AVPlayer.
 * Matches the Android behavior: loops the clip, shows a loader, and surfaces errors.
 */
@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier
) {
    if (videoUrl.isNullOrBlank()) {
        NoVideoAvailable(modifier)
        return
    }

    var isLoading by remember(videoUrl) { mutableStateOf(true) }
    var hasError by remember(videoUrl) { mutableStateOf(false) }
    var errorMessage by remember(videoUrl) { mutableStateOf("") }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        UIKitView(
            factory = {
                LoopingPlayerView().apply {
                    load(
                        urlString = videoUrl,
                        onReady = {
                            isLoading = false
                            hasError = false
                        },
                        onError = { message ->
                            isLoading = false
                            hasError = true
                            errorMessage = message
                        }
                    )
                }
            },
            update = { view ->
                // Reset state while reloading a new URL
                isLoading = true
                hasError = false
                errorMessage = ""
                view.load(
                    urlString = videoUrl,
                    onReady = {
                        isLoading = false
                        hasError = false
                    },
                    onError = { message ->
                        isLoading = false
                        hasError = true
                        errorMessage = message
                    }
                )
            },
            modifier = Modifier.fillMaxSize()
        )

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
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    if (errorMessage.isNotBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private class LoopingPlayerView : UIView(frame = CGRectZero.readValue()) {
    private var player: AVPlayer? = null
    private var playerLayer: AVPlayerLayer? = null
    private val observers = mutableListOf<NSObjectProtocol>()
    private var currentUrl: String? = null
    private var hasFailure: Boolean = false

    fun load(
        urlString: String?,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (urlString.isNullOrBlank()) {
            cleanup()
            hasFailure = true
            onError("Invalid video URL")
            return
        }

        if (currentUrl == urlString && player != null && !hasFailure) {
            player?.play()
            onReady()
            return
        }

        cleanup()
        hasFailure = false

        val url = NSURL(string = urlString)
        if (url == null) {
            onError("Malformed video URL")
            return
        }

        currentUrl = urlString

        val item = AVPlayerItem(uRL = url)
        val avPlayer = AVPlayer(playerItem = item)
        // Mute the video to prevent audio focus theft from other apps (e.g., Spotify)
        // These are silent preview videos that play like GIFs
        avPlayer.volume = 0f
        player = avPlayer

        val layer = playerLayer ?: AVPlayerLayer().also { createdLayer ->
            playerLayer = createdLayer
            this.layer.addSublayer(createdLayer)
        }
        layer.player = avPlayer
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.frame = bounds

        // Loop the clip when it finishes
        val endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                // Restart playback by seeking to the beginning
                avPlayer.seekToTime(CMTimeMake(value = 0, timescale = 1))
                avPlayer.play()
            }
        )
        observers += endObserver

        // Surface playback failures to Compose
        val failureObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { notification ->
                hasFailure = true
                cleanup()
                val message = (notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError)
                    ?.localizedDescription ?: "Playback failed"
                onError(message)
            }
        )
        observers += failureObserver

        avPlayer.play()
        onReady()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer?.frame = bounds
    }

    override fun removeFromSuperview() {
        cleanup()
        super.removeFromSuperview()
    }

    private fun cleanup() {
        observers.forEach { observer ->
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
        observers.clear()

        player?.pause()
        player = null
        playerLayer?.removeFromSuperlayer()
        playerLayer = null
        currentUrl = null
        hasFailure = false
    }
}
