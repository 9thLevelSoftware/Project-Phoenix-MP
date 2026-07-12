package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.devil.phoenixproject.domain.voice.SafeWordListener
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.ForgeGreen
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.openAppSettings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.*

/** Calibrates a profile's safe word with three successful local detections. */
@Composable
fun SafeWordCalibrationDialog(
    safeWord: String,
    onCalibrated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listenerFactory: SafeWordListenerFactory = koinInject()
    var detectionCount by remember { mutableStateOf(0) }
    var micError by remember { mutableStateOf(false) }
    var listener by remember { mutableStateOf<SafeWordListener?>(null) }

    DisposableEffect(safeWord) {
        val newListener = try {
            listenerFactory.create(safeWord)
        } catch (_: Exception) {
            micError = true
            null
        }
        listener = newListener
        newListener?.startListening()

        onDispose {
            newListener?.stopListening()
            listener = null
        }
    }

    val currentListener = listener
    if (currentListener != null) {
        val isListening by currentListener.isListening.collectAsState()

        LaunchedEffect(currentListener) {
            currentListener.detectedWord.collect {
                detectionCount += 1
                if (detectionCount >= 3) {
                    currentListener.stopListening()
                    onCalibrated()
                }
            }
        }

        LaunchedEffect(isListening) {
            if (!isListening && detectionCount == 0) {
                kotlinx.coroutines.delay(3000)
                if (!currentListener.isListening.value && detectionCount == 0) micError = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.medium,
        title = {
            Text(
                stringResource(Res.string.settings_calibration_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                if (micError) {
                    Text(
                        stringResource(Res.string.settings_calibration_mic_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { openAppSettings() },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(Res.string.settings_calibration_open_settings))
                    }
                } else {
                    Text(
                        stringResource(Res.string.settings_calibration_prompt),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        safeWord,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    CalibrationProgress(detectionCount)
                    Text(
                        stringResource(Res.string.settings_calibration_listening),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CalibrationProgress(detectionCount: Int) {
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        modifier = Modifier.padding(vertical = Spacing.small),
    ) {
        repeat(3) { index ->
            val filled = index < detectionCount
            val circleColor by animateColorAsState(
                targetValue = if (filled) ForgeGreen else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncyColor,
                label = "circleColor$index",
            )
            val circleScale by animateFloatAsState(
                targetValue = if (filled || reduceMotion) 1f else 0.8f,
                animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncy,
                label = "circleScale$index",
            )
            val checkScale by animateFloatAsState(
                targetValue = if (filled) 1f else 0f,
                animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncy,
                label = "checkScale$index",
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .scale(circleScale)
                    .background(circleColor, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = if (filled) {
                        stringResource(Res.string.cd_calibration_check)
                    } else {
                        null
                    },
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).scale(checkScale).alpha(checkScale),
                )
            }
        }
    }
    Text(
        stringResource(Res.string.settings_calibration_progress, detectionCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AdultModeDialogCard(
    presentation: AdultModePresentation,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = adultModeContainerColor(presentation.containerTone),
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, adultModeBorderColor(presentation)),
    ) {
        content()
    }
}

@Composable
private fun adultModeContainerColor(tone: AdultModeDialogTone): Color = when (tone) {
    AdultModeDialogTone.ThemeSurface -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun adultModeBorderColor(presentation: AdultModePresentation): Color = when {
    presentation.usesBespokePinkAccent -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
    presentation.usesBrandAccent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    else -> MaterialTheme.colorScheme.outlineVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdultsOnlyConfirmDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = AdultModePresentation.adultsOnlyConfirmation()

    BasicAlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting,
        ),
    ) {
        AdultModeDialogCard(presentation = presentation) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_adults_only_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Text(
                    stringResource(Res.string.settings_adults_only_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        stringResource(Res.string.settings_adults_only_compliance_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.medium),
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        stringResource(Res.string.settings_adults_only_confirm),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        stringResource(Res.string.settings_adults_only_decline),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DominatrixUnlockDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dominatrix_dialog_scale",
    )
    val presentation = AdultModePresentation.dominatrixUnlock()

    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.scale(scale)) {
        AdultModeDialogCard(presentation) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("👑", style = MaterialTheme.typography.displayMedium)
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    stringResource(Res.string.settings_dominatrix_unlock_toast),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    stringResource(Res.string.settings_dominatrix_mode_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.large))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.action_ok))
                }
            }
        }
    }
}

@Composable
fun DiscoModeUnlockDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "disco_dialog_scale",
    )
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val transition = rememberInfiniteTransition(label = "disco")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1920, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "disco_rotation",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (reduceMotion) 0.3f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "disco_glow",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(80.dp).rotate(rotation).background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = glowAlpha), Color.Transparent),
                        ),
                        RoundedCornerShape(40.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🪩", style = MaterialTheme.typography.displayLarge)
                }
                Text(
                    stringResource(Res.string.profile_disco_unlocked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Text(
                stringResource(Res.string.profile_disco_unlocked_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(Res.string.profile_disco_unlocked_action),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}
