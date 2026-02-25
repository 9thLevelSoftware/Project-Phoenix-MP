package com.devil.phoenixproject.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * MediaPipe PoseLandmarker lifecycle wrapper with adaptive frame throttling.
 *
 * Key features for CV-09 (BLE pipeline protection):
 * 1. LIVE_STREAM mode: detectAsync() returns immediately, result via callback
 * 2. Timestamp throttle: skips frames within minInferenceIntervalMs
 * 3. Single-threaded executor: isolates ML inference from main/BLE threads
 *
 * Usage:
 * - Call [setupPoseLandmarker] once to initialize
 * - Call [detectLiveStream] for each CameraX ImageProxy frame
 * - Call [close] when done
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: PoseLandmarkerListener,
    private val minDetectionConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    private val minPresenceConfidence: Float = 0.5f,
    private val modelName: String = "pose_landmarker_lite.task"
) {
    private var poseLandmarker: PoseLandmarker? = null

    // Adaptive throttle: minimum interval between inference calls (CV-09)
    // 100ms = ~10 FPS max, sufficient for form checking, protects BLE pipeline
    private var lastInferenceTimeMs = 0L
    private val minInferenceIntervalMs = 100L

    /**
     * Initialize MediaPipe PoseLandmarker with LIVE_STREAM mode.
     * Must be called before [detectLiveStream].
     *
     * @throws RuntimeException if model file not found in assets
     */
    fun setupPoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelName)
            .setDelegate(Delegate.CPU) // CPU-only for v1 (GPU has documented crashes)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinPoseDetectionConfidence(minDetectionConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setMinPosePresenceConfidence(minPresenceConfidence)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::handleResult)
            .setErrorListener(this::handleError)
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    /**
     * Process a CameraX frame for pose detection.
     *
     * Implements adaptive throttling (CV-09):
     * - Frames within [minInferenceIntervalMs] of last inference are skipped
     * - Uses LIVE_STREAM async detection (non-blocking)
     *
     * IMPORTANT: Always closes [imageProxy] - even when frame is skipped.
     *
     * @param imageProxy CameraX frame from ImageAnalysis
     * @param isFrontCamera true if front camera (applies horizontal flip)
     */
    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val currentTimeMs = SystemClock.uptimeMillis()

        // Adaptive throttle: skip frame if too recent (CV-09)
        if (currentTimeMs - lastInferenceTimeMs < minInferenceIntervalMs) {
            imageProxy.close()
            return
        }
        lastInferenceTimeMs = currentTimeMs

        try {
            // Convert ImageProxy to Bitmap with correct rotation
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()

            val matrix = Matrix().apply {
                postRotate(rotationDegrees)
                if (isFrontCamera) {
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            // Build MediaPipe image and run async detection
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            poseLandmarker?.detectAsync(mpImage, currentTimeMs)

        } catch (e: Exception) {
            listener.onError("Frame processing failed: ${e.message}")
        } finally {
            // CRITICAL: Always close ImageProxy to prevent camera pipeline stall
            imageProxy.close()
        }
    }

    /**
     * Check if the helper is ready for detection.
     */
    fun isReady(): Boolean = poseLandmarker != null

    /**
     * Release MediaPipe resources. Call when form check is disabled or screen disposed.
     */
    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        lastInferenceTimeMs = 0L
    }

    private fun handleResult(result: PoseLandmarkerResult, input: MPImage) {
        if (result.landmarks().isEmpty()) {
            listener.onEmpty()
            return
        }

        // Use first detected pose (single-person assumption for workout)
        val normalizedLandmarks = result.landmarks()[0]
        val worldLandmarks = result.worldLandmarks().getOrNull(0)

        // Extract timestamp from result (set during detectAsync call)
        val timestampMs = result.timestampMs()

        listener.onResults(
            normalizedLandmarks = normalizedLandmarks,
            worldLandmarks = worldLandmarks,
            timestampMs = timestampMs
        )
    }

    private fun handleError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown MediaPipe error")
    }

    /**
     * Listener for pose detection results.
     */
    interface PoseLandmarkerListener {
        /**
         * Called when pose landmarks are detected.
         *
         * @param normalizedLandmarks 2D landmarks normalized to image dimensions (0-1)
         * @param worldLandmarks 3D landmarks in meters (null if not available)
         * @param timestampMs Frame timestamp in milliseconds
         */
        fun onResults(
            normalizedLandmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
            worldLandmarks: List<com.google.mediapipe.tasks.components.containers.Landmark>?,
            timestampMs: Long
        )

        /**
         * Called when no pose is detected in the frame.
         */
        fun onEmpty()

        /**
         * Called when an error occurs during detection.
         */
        fun onError(error: String)
    }
}
