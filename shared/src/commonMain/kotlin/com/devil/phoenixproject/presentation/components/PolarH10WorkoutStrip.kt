package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.ble.PolarH10BleManager
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.presentation.util.computeRollingRmssd
import com.devil.phoenixproject.presentation.util.formatRmssdMs
import com.devil.phoenixproject.presentation.util.formatRrMs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ECG_DISPLAY_SAMPLES = 260 // ~2 seconds at 130 Hz

@Composable
fun PolarH10WorkoutStrip(
    modifier: Modifier = Modifier,
    polarBleManager: PolarH10BleManager = koinInject(),
) {
    val connectionState by polarBleManager.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected

    AnimatedVisibility(
        visible = isConnected,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        PolarH10WorkoutStripContent(
            modifier = modifier,
            polarBleManager = polarBleManager,
        )
    }
}

@Composable
private fun PolarH10WorkoutStripContent(
    modifier: Modifier = Modifier,
    polarBleManager: PolarH10BleManager,
) {
    val scope = rememberCoroutineScope()
    val hrMeasurement by polarBleManager.hrMeasurement.collectAsState()

    // Sliding window of RR beats for RMSSD calculation
    val recentBeats = remember { mutableStateListOf<PolarRrBeat>() }
    var rollingRmssd by remember { mutableStateOf<Double?>(null) }
    var latestRrMs by remember { mutableStateOf<Double?>(null) }

    // Rolling ECG samples (µV)
    val ecgSamples = remember { mutableStateListOf<Int>() }

    // Latest accelerometer sample
    var latestAccSample by remember { mutableStateOf<PolarAccSample?>(null) }

    // 1. Auto-start ECG & ACC on enter, cleanly stop on dispose
    LaunchedEffect(Unit) {
        polarBleManager.startEcgStream()
        polarBleManager.startAccStream()
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                polarBleManager.stopEcgStream()
                polarBleManager.stopAccStream()
            }
        }
    }

    // 2. Stream collectors
    LaunchedEffect(Unit) {
        polarBleManager.rrBeats.collect { beat ->
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
        polarBleManager.ecgStream.collectLatest { ecg ->
            ecgSamples.addAll(ecg.samplesMicrovolts)
            while (ecgSamples.size > MAX_ECG_DISPLAY_SAMPLES) {
                ecgSamples.removeAt(0)
            }
        }
    }

    LaunchedEffect(Unit) {
        polarBleManager.accStream.collectLatest { acc ->
            latestAccSample = acc.samples.lastOrNull()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Section 1: Heart Rate (BPM) + Contact Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                val hasContact = hrMeasurement?.contactDetected == true
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Polar Heart Rate",
                    tint = if (hasContact) Color(0xFFFF1744) else Color(0xFFFF9100),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    val hr = hrMeasurement?.hr ?: 0
                    Text(
                        text = if (hr > 0) "$hr" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Section 2: RR Interval & Rolling RMSSD
            Column(
                modifier = Modifier.padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RR: ",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRrMs(latestRrMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RMSSD: ",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRmssdMs(rollingRmssd),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00E676),
                    )
                }
            }

            // Section 3: 130 Hz Real-Time ECG Waveform Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D1B2A))
                    .border(0.5.dp, Color(0xFF1E3A5F), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                EcgWaveformCanvas(
                    samples = ecgSamples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }

            // Section 4: Live Chest Acceleration (mG)
            Column(
                modifier = Modifier.padding(start = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val acc = latestAccSample
                if (acc != null) {
                    val magnitudeG = sqrt(
                        (acc.xMg.toDouble() * acc.xMg + acc.yMg.toDouble() * acc.yMg + acc.zMg.toDouble() * acc.zMg),
                    ) / 1000.0
                    Text(
                        text = "${(magnitudeG * 100).roundToInt() / 100.0} G",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "ACC 50Hz",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "ACC",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Real-time hardware-accelerated Canvas waveform plotting 130 Hz ECG samples (µV).
 */
@Composable
private fun EcgWaveformCanvas(
    samples: List<Int>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Draw center baseline grid line
        drawLine(
            color = Color(0xFF1B2A4A),
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
            color = Color(0xFF00E5FF),
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
