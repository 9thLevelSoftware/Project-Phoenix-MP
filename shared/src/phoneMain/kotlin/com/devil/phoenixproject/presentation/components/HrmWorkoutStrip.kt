package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.ble.HrmBleManager
import com.devil.phoenixproject.data.ble.HrmCapabilityState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.RrBeat
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.util.HrmTelemetryUtils
import com.devil.phoenixproject.util.HrmTelemetryUtils.computeRollingRmssd
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

private const val MAX_ECG_DISPLAY_SAMPLES = 260 // ~2 seconds at 130 Hz

@Composable
fun HrmWorkoutStrip(
    modifier: Modifier = Modifier,
    hrmBleManager: HrmBleManager? = null,
) {
    if (hrmBleManager == null) return
    val connectionState by hrmBleManager.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    AnimatedVisibility(
        visible = isConnected,
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        HrmWorkoutStripContent(
            modifier = modifier,
            hrmBleManager = hrmBleManager,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HrmWorkoutStripContent(
    modifier: Modifier = Modifier,
    hrmBleManager: HrmBleManager,
) {
    val hrMeasurement by hrmBleManager.hrMeasurement.collectAsState()
    val isContactDetected by hrmBleManager.isContactDetected.collectAsState()
    val capabilities by hrmBleManager.capabilities.collectAsState()
    val hasContact = hrMeasurement?.contactDetected == true && isContactDetected

    // Sliding window of RR beats for RMSSD calculation
    val recentBeats = remember { mutableStateListOf<RrBeat>() }
    var rollingRmssd by remember { mutableStateOf<Double?>(null) }
    var latestRrMs by remember { mutableStateOf<Double?>(null) }

    // Rolling ECG samples (µV)
    val ecgSamples = remember { mutableStateListOf<Int>() }

    // Wipe display buffers cleanly when skin contact is lost
    LaunchedEffect(hasContact) {
        if (!hasContact) {
            ecgSamples.clear()
            recentBeats.clear()
            latestRrMs = null
            rollingRmssd = null
        }
    }

    // Latest accelerometer sample and filtered posture StateFlows
    val latestAcc by hrmBleManager.latestAcc.collectAsState()
    val latestAccSample = latestAcc?.samples?.lastOrNull()
    val torsoPitchDeg by hrmBleManager.torsoPitchDeg.collectAsState()
    val torsoRollDeg by hrmBleManager.torsoRollDeg.collectAsState()
    val dynamicAccMg by hrmBleManager.dynamicAccMg.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            hrmBleManager.releaseTelemetryPreviewAsync()
        }
    }

    // Stream collectors
    LaunchedEffect(Unit) {
        hrmBleManager.rrBeats.collect { beat ->
            val now = currentTimeMillis()
            latestRrMs = beat.rrIntervalMs
            recentBeats.add(beat)
            // Retain beats within sliding 60s window
            while (recentBeats.isNotEmpty() && recentBeats.first().timestampMs < now - 60_000L) {
                recentBeats.removeAt(0)
            }
            rollingRmssd = computeRollingRmssd(recentBeats, windowMs = 60_000L, currentTimeMs = now)
        }
    }

    LaunchedEffect(Unit) {
        hrmBleManager.ecgStream.collect { ecg ->
            ecgSamples.addAll(ecg.samplesMicrovolts)
            while (ecgSamples.size > MAX_ECG_DISPLAY_SAMPLES) {
                ecgSamples.removeAt(0)
            }
        }
    }

    val accessColors = AccessibilityTheme.colors
    val colorScheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        val heartRateSection: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = stringResource(Res.string.hrm_strip_heart_rate),
                    tint = if (hasContact) accessColors.error else accessColors.warning,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    val hr = hrMeasurement?.hr ?: 0
                    Text(
                        text = if (hr > 0) "$hr" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.hrm_strip_bpm_caps),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val hrvSection: @Composable () -> Unit = {
            if (capabilities.rrIntervals == HrmCapabilityState.CONFIRMED) {
                Column(horizontalAlignment = Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.hrm_strip_rr_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = HrmTelemetryUtils.formatMs(latestRrMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.hrm_strip_rmssd_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = HrmTelemetryUtils.formatMs(rollingRmssd),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = accessColors.success,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(Res.string.hrm_hrv_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }

        val ecgCanvasSlot: @Composable (Modifier) -> Unit = { slotModifier ->
            Surface(
                modifier = slotModifier
                    .height(38.dp)
                    .clip(MaterialTheme.shapes.small)
                    .border(0.5.dp, colorScheme.outlineVariant, MaterialTheme.shapes.small),
                color = colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small,
            ) {
                EcgWaveformCanvas(
                    samples = ecgSamples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    baselineColor = colorScheme.outlineVariant,
                    waveformColor = accessColors.info,
                )
            }
        }

        val postureAndTareSection: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.hrm_strip_hinge_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = torsoPitchDeg?.let { "${(it * 10).roundToInt() / 10.0}°" } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (torsoPitchDeg != null) accessColors.warning else colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.hrm_strip_tilt_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = torsoRollDeg?.let { "${(it * 10).roundToInt() / 10.0}°" } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (torsoRollDeg != null) accessColors.info else colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = dynamicAccMg?.let { "$it mG" } ?: (latestAccSample?.let { acc ->
                            val magnitudeG = sqrt(
                                (acc.xMg.toDouble() * acc.xMg + acc.yMg.toDouble() * acc.yMg + acc.zMg.toDouble() * acc.zMg),
                            ) / 1000.0
                            "${(magnitudeG * 100).roundToInt() / 100.0} G"
                        } ?: "—"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant,
                    )
                }

                Surface(
                    onClick = { hrmBleManager.tareCurrentPosture() },
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                    color = colorScheme.secondaryContainer,
                    border = BorderStroke(0.5.dp, colorScheme.secondary.copy(alpha = 0.5f)),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.hrm_strip_tare_zero),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val stripWidth = maxWidth
            val showSparkline = stripWidth >= 280.dp
            val isCompactStrip = stripWidth < 360.dp || fontScale >= 1.3f
            if (!isCompactStrip) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    heartRateSection()
                    hrvSection()
                    if (capabilities.polarPmd == HrmCapabilityState.CONFIRMED) {
                        ecgCanvasSlot(Modifier.weight(1f))
                        postureAndTareSection()
                    }
                }
            } else {
                // D-9: Stack gracefully on narrow widths or large font scale so nothing crushes 0° Tare
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        heartRateSection()
                        hrvSection()
                    }
                    if (capabilities.polarPmd == HrmCapabilityState.CONFIRMED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (showSparkline) Arrangement.spacedBy(8.dp) else Arrangement.End,
                        ) {
                            if (showSparkline) {
                                ecgCanvasSlot(Modifier.weight(1f))
                            }
                            postureAndTareSection()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Real-time hardware-accelerated Canvas waveform plotting 130 Hz ECG samples (µV).
 */
@Composable
internal fun EcgWaveformCanvas(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
    waveformColor: Color = AccessibilityTheme.colors.info,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Draw center baseline grid line
        drawLine(
            color = baselineColor,
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1f,
        )

        if (samples.size < 2) return@Canvas

        // Dynamic autoscale: nominal range ±1500 µV
        val maxAbsVal = samples.takeLast(130).maxOfOrNull { kotlin.math.abs(it) }?.toFloat()?.coerceAtLeast(800f) ?: 1500f
        val scaleY = (height * 0.42f) / maxAbsVal

        val stepX = width / (MAX_ECG_DISPLAY_SAMPLES - 1).toFloat()
        val path = Path()

        val startIndex = (MAX_ECG_DISPLAY_SAMPLES - samples.size).coerceAtLeast(0)
        samples.forEachIndexed { i, sampleUv ->
            val x = (startIndex + i) * stepX
            val y = (midY - (sampleUv * scaleY)).coerceIn(1f, height - 1f)
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = waveformColor,
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
