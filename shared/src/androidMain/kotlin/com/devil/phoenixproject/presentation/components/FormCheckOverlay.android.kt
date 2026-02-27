package com.devil.phoenixproject.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.devil.phoenixproject.cv.LandmarkAngleCalculator
import com.devil.phoenixproject.cv.PoseLandmarkerHelper
import com.devil.phoenixproject.domain.model.ExerciseFormType
import com.devil.phoenixproject.domain.model.FormAssessment
import com.devil.phoenixproject.domain.premium.FormRulesEngine
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.Executors

/**
 * Android implementation of FormCheckOverlay.
 *
 * Renders a PiP camera preview with real-time skeleton overlay (CV-02, CV-03).
 * Uses CameraX for camera management and MediaPipe PoseLandmarker for pose detection.
 *
 * Key features:
 * - 160x120dp PiP size (small enough to not block workout metrics)
 * - Skeleton overlay using Compose Canvas
 * - Adaptive frame throttling via PoseLandmarkerHelper (CV-09)
 * - Camera permission handling
 */
@Composable
actual fun FormCheckOverlay(
    isEnabled: Boolean,
    exerciseType: ExerciseFormType?,
    onFormAssessment: (FormAssessment) -> Unit,
    modifier: Modifier
) {
    if (!isEnabled) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Show rationale before requesting camera permission (BOARD-05)
    var showRationale by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            // Always show rationale first - builds user trust by explaining on-device processing
            showRationale = true
        }
    }

    // Landmark state for skeleton drawing
    var currentLandmarks by remember { mutableStateOf<List<NormalizedLandmark>?>(null) }

    // Error state for user-facing error display (BOARD-03)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // PoseLandmarkerHelper instance
    val poseLandmarkerHelper = remember {
        PoseLandmarkerHelper(
            context = context,
            listener = object : PoseLandmarkerHelper.PoseLandmarkerListener {
                override fun onResults(
                    normalizedLandmarks: List<NormalizedLandmark>,
                    worldLandmarks: List<Landmark>?,
                    timestampMs: Long
                ) {
                    // Update skeleton overlay
                    currentLandmarks = normalizedLandmarks

                    // Convert to JointAngles and evaluate form if exercise type is set
                    if (exerciseType != null) {
                        val jointAngles = LandmarkAngleCalculator.convert(
                            normalizedLandmarks = normalizedLandmarks,
                            worldLandmarks = worldLandmarks,
                            timestampMs = timestampMs
                        )

                        val assessment = FormRulesEngine.evaluate(jointAngles, exerciseType)
                        onFormAssessment(assessment)
                    }
                }

                override fun onEmpty() {
                    currentLandmarks = null
                }

                override fun onError(error: String) {
                    co.touchlab.kermit.Logger.w("FormCheckOverlay") { "Pose detection error: $error" }
                    // Only set user-facing error for initialization failures (BOARD-03)
                    if (error.contains("not available") || error.contains("model")) {
                        errorMessage = error
                    }
                    currentLandmarks = null
                }
            }
        )
    }

    // Initialize MediaPipe on composition (errors handled internally via listener.onError)
    LaunchedEffect(Unit) {
        poseLandmarkerHelper.setupPoseLandmarker()
    }

    // Cleanup on disposal
    DisposableEffect(Unit) {
        onDispose {
            poseLandmarkerHelper.close()
        }
    }

    // Error state: show user-facing error when PoseLandmarker init fails (BOARD-03)
    if (errorMessage != null) {
        Box(
            modifier = modifier
                .size(160.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
        return
    }

    // Render based on permission state
    if (!hasCameraPermission) {
        // Camera permission rationale with on-device processing guarantee (BOARD-05)
        Box(
            modifier = modifier
                .size(160.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Camera needed for Form Check.\nAll processing stays on your device.",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow Camera")
                }
            }
        }
    } else {
        // Camera preview with skeleton overlay
        Box(
            modifier = modifier
                .size(160.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            // Camera preview layer
            CameraPreviewWithAnalysis(
                lifecycleOwner = lifecycleOwner,
                poseLandmarkerHelper = poseLandmarkerHelper,
                modifier = Modifier.fillMaxSize()
            )

            // Skeleton overlay layer
            SkeletonOverlay(
                landmarks = currentLandmarks,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * CameraX preview with ImageAnalysis for pose detection.
 */
@Composable
private fun CameraPreviewWithAnalysis(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    poseLandmarkerHelper: PoseLandmarkerHelper,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview use case
                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                // ImageAnalysis use case with backpressure (CV-09)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (poseLandmarkerHelper.isReady()) {
                        poseLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera = true)
                    } else {
                        imageProxy.close()
                    }
                }

                // Use front camera for self-view during workout
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // Unbind all and rebind with both use cases
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                co.touchlab.kermit.Logger.e("FormCheckOverlay") { "Camera init failed: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (_: Exception) {
                // Ignore errors during cleanup
            }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

/**
 * Skeleton overlay rendering body landmarks and connections.
 */
@Composable
private fun SkeletonOverlay(
    landmarks: List<NormalizedLandmark>?,
    modifier: Modifier = Modifier
) {
    if (landmarks == null || landmarks.size < 33) return

    // MediaPipe pose landmark connections for skeleton rendering
    // Pairs of landmark indices to connect with lines
    val connections = listOf(
        // Face
        // (skipped - not useful for form check)

        // Torso
        11 to 12, // Left shoulder to right shoulder
        11 to 23, // Left shoulder to left hip
        12 to 24, // Right shoulder to right hip
        23 to 24, // Left hip to right hip

        // Left arm
        11 to 13, // Left shoulder to left elbow
        13 to 15, // Left elbow to left wrist

        // Right arm
        12 to 14, // Right shoulder to right elbow
        14 to 16, // Right elbow to right wrist

        // Left leg
        23 to 25, // Left hip to left knee
        25 to 27, // Left knee to left ankle

        // Right leg
        24 to 26, // Right hip to right knee
        26 to 28  // Right knee to right ankle
    )

    // Key landmark indices for joint points
    val jointIndices = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Draw skeleton connections (lines)
        connections.forEach { (startIdx, endIdx) ->
            val start = landmarks[startIdx]
            val end = landmarks[endIdx]

            drawLine(
                color = Color.Green.copy(alpha = 0.8f),
                start = Offset(start.x() * w, start.y() * h),
                end = Offset(end.x() * w, end.y() * h),
                strokeWidth = 4f
            )
        }

        // Draw joint points
        jointIndices.forEach { idx ->
            val landmark = landmarks[idx]
            drawCircle(
                color = Color.Yellow,
                radius = 6f,
                center = Offset(landmark.x() * w, landmark.y() * h)
            )
        }
    }
}
