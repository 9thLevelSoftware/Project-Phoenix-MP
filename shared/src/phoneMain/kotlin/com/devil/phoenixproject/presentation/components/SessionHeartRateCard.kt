package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.ble.HrmBleManager
import com.devil.phoenixproject.data.ble.HrmSessionSummary
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.PolarMetricSample
import com.devil.phoenixproject.domain.model.HrmBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.hrm_card_analytics_header
import projectphoenix.shared.generated.resources.hrm_card_avg_hr
import projectphoenix.shared.generated.resources.hrm_card_avg_rr_beat
import projectphoenix.shared.generated.resources.hrm_card_cancel_cooldown
import projectphoenix.shared.generated.resources.hrm_card_cooldown_active_title
import projectphoenix.shared.generated.resources.hrm_card_cooldown_header
import projectphoenix.shared.generated.resources.hrm_card_coronal_asymmetry
import projectphoenix.shared.generated.resources.hrm_card_current_hr
import projectphoenix.shared.generated.resources.hrm_card_hrr_300_desc
import projectphoenix.shared.generated.resources.hrm_card_hrr_300_label
import projectphoenix.shared.generated.resources.hrm_card_hrr_60_desc
import projectphoenix.shared.generated.resources.hrm_card_hrr_60_label
import projectphoenix.shared.generated.resources.hrm_card_hrr_unavailable
import projectphoenix.shared.generated.resources.hrm_card_hrv_rmssd
import projectphoenix.shared.generated.resources.hrm_card_max_hip_hinge
import projectphoenix.shared.generated.resources.hrm_card_max_lateral_tilt
import projectphoenix.shared.generated.resources.hrm_card_min_hr
import projectphoenix.shared.generated.resources.hrm_card_peak_hr
import projectphoenix.shared.generated.resources.hrm_card_pitch_unit
import projectphoenix.shared.generated.resources.hrm_card_restart_cooldown
import projectphoenix.shared.generated.resources.hrm_card_roll_unit
import projectphoenix.shared.generated.resources.hrm_card_rr_variation
import projectphoenix.shared.generated.resources.hrm_card_sagittal_bend
import projectphoenix.shared.generated.resources.hrm_card_sensor_cd
import projectphoenix.shared.generated.resources.hrm_card_start_cooldown
import projectphoenix.shared.generated.resources.hrm_card_starting_hr
import projectphoenix.shared.generated.resources.hrm_card_status_logged
import projectphoenix.shared.generated.resources.hrm_card_summary_title
import projectphoenix.shared.generated.resources.hrm_card_track_prompt
import projectphoenix.shared.generated.resources.hrm_chart_deg_ref
import projectphoenix.shared.generated.resources.hrm_chart_duration_ms
import projectphoenix.shared.generated.resources.hrm_chart_ecg_header
import projectphoenix.shared.generated.resources.hrm_chart_ecg_samples
import projectphoenix.shared.generated.resources.hrm_chart_ecg_time
import projectphoenix.shared.generated.resources.hrm_chart_reps_pts
import projectphoenix.shared.generated.resources.hrm_chart_timeline_header
import projectphoenix.shared.generated.resources.hrm_chart_torso_header
import projectphoenix.shared.generated.resources.hrm_hrv_unavailable
import projectphoenix.shared.generated.resources.hrm_legend_cable_pos
import projectphoenix.shared.generated.resources.hrm_legend_hinge_pitch
import projectphoenix.shared.generated.resources.hrm_legend_hrv
import projectphoenix.shared.generated.resources.hrm_legend_tilt_roll
import projectphoenix.shared.generated.resources.hrm_phase_cooldown
import projectphoenix.shared.generated.resources.hrm_phase_rest
import projectphoenix.shared.generated.resources.hrm_phase_warmup
import projectphoenix.shared.generated.resources.hrm_phase_working
import projectphoenix.shared.generated.resources.hrm_scrub_cable_pct
import projectphoenix.shared.generated.resources.hrm_scrub_hinge
import projectphoenix.shared.generated.resources.hrm_scrub_hr
import projectphoenix.shared.generated.resources.hrm_scrub_rmssd
import projectphoenix.shared.generated.resources.hrm_scrub_tilt
import projectphoenix.shared.generated.resources.hrm_status_connected
import projectphoenix.shared.generated.resources.hrm_unit_bpm
import projectphoenix.shared.generated.resources.hrm_unit_ms

/**
 * Enhanced Session Heart Rate Analytics Card.
 *
 * Displays post-set and post-workout cardiac performance metrics (Avg/Max/Min HR, RMSSD, RR),
 * 3D Torso Biomechanics (max hip-hinge pitch & lateral roll asymmetry), the 5-minute
 * Heart Rate Recovery (HRR) cooldown protocol with real-time countdown, drop tracking, and control,
 * plus 3 rich interactive Canvas visualizations:
 * 1. Continuous Heart Rate & Recovery Timeline with color-coded phase bands
 * 2. 3D Torso Biomechanics vs. Rep Cable Position
 * 3. Saved 130 Hz ECG Waveform Scrubber
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionHeartRateCard(
    sessionId: String? = null,
    session: WorkoutSession? = null,
    showCooldown: Boolean = true,
    modifier: Modifier = Modifier,
    hrmBleManager: HrmBleManager? = null,
    workoutRepository: WorkoutRepository? = null,
) {
    val connectionState = hrmBleManager?.connectionState?.collectAsState()?.value ?: ConnectionState.Disconnected
    val managerCooldownActive = hrmBleManager?.isCooldownActive?.collectAsState()?.value ?: false
    val cooldownSecondsRemaining = hrmBleManager?.cooldownSecondsRemaining?.collectAsState()?.value ?: 0
    val cooldownStartHr = hrmBleManager?.cooldownStartHeartRate?.collectAsState()?.value
    val latestSummary = hrmBleManager?.latestSessionSummary?.collectAsState()?.value
    val hrMeasurement = hrmBleManager?.hrMeasurement?.collectAsState()?.value
    val activeCooldownSessionId = hrmBleManager?.activeCooldownSessionId?.collectAsState()?.value
    val lastRecordedSessionId = hrmBleManager?.lastRecordedSessionId?.collectAsState()?.value

    val resolvedSessionId = sessionId ?: session?.id ?: if (showCooldown) {
        activeCooldownSessionId
            ?: lastRecordedSessionId
            ?: hrmBleManager?.currentRecordingSessionId
            ?: latestSummary?.sessionId
    } else null
    val isCooldownActive = showCooldown && managerCooldownActive &&
        resolvedSessionId != null && resolvedSessionId == activeCooldownSessionId
    val isCurrentSession = showCooldown && resolvedSessionId != null &&
        (resolvedSessionId == lastRecordedSessionId || resolvedSessionId == hrmBleManager?.currentRecordingSessionId)
    val fallbackSummary = remember(session, resolvedSessionId) {
        session?.takeIf { it.id == resolvedSessionId }?.let { s ->
            if (s.avgHeartRateBpm != null || s.maxHeartRateBpm != null || s.minHeartRateBpm != null ||
                s.avgRrIntervalMs != null || s.rmssdMs != null || s.hrr60Bpm != null ||
                s.hrr300Bpm != null || s.maxTorsoPitchDeg != null || s.maxTorsoRollDeg != null) {
                HrmSessionSummary(
                    sessionId = s.id,
                    avgHeartRateBpm = s.avgHeartRateBpm,
                    maxHeartRateBpm = s.maxHeartRateBpm,
                    minHeartRateBpm = s.minHeartRateBpm,
                    avgRrIntervalMs = s.avgRrIntervalMs,
                    rmssdMs = s.rmssdMs,
                    hrr60Bpm = s.hrr60Bpm,
                    hrr300Bpm = s.hrr300Bpm,
                    maxTorsoPitchDeg = s.maxTorsoPitchDeg,
                    maxTorsoRollDeg = s.maxTorsoRollDeg,
                )
            } else null
        } ?: resolvedSessionId?.let { hrmBleManager?.getSessionSummary(it) }
    }

    var persistedSummary by remember(resolvedSessionId) { mutableStateOf<HrmSessionSummary?>(null) }
    val summary = if (resolvedSessionId != null && latestSummary?.sessionId == resolvedSessionId) {
        latestSummary
    } else {
        persistedSummary ?: fallbackSummary
    }

    // Historical samples queries for visualizations
    var beatSamples by remember(resolvedSessionId) { mutableStateOf<List<HrmBeatSample>>(emptyList()) }
    var ecgSegments by remember(resolvedSessionId) { mutableStateOf<List<PolarEcgSegment>>(emptyList()) }
    var metricSamples by remember(resolvedSessionId) { mutableStateOf<List<PolarMetricSample>>(emptyList()) }

    LaunchedEffect(resolvedSessionId, workoutRepository) {
        if (resolvedSessionId != null && workoutRepository != null) {
            val savedSession = workoutRepository.getSession(resolvedSessionId)
            persistedSummary = savedSession?.let {
                HrmSessionSummary(
                    sessionId = it.id,
                    avgHeartRateBpm = it.avgHeartRateBpm,
                    maxHeartRateBpm = it.maxHeartRateBpm,
                    minHeartRateBpm = it.minHeartRateBpm,
                    avgRrIntervalMs = it.avgRrIntervalMs,
                    rmssdMs = it.rmssdMs,
                    hrr60Bpm = it.hrr60Bpm,
                    hrr300Bpm = it.hrr300Bpm,
                    maxTorsoPitchDeg = it.maxTorsoPitchDeg,
                    maxTorsoRollDeg = it.maxTorsoRollDeg,
                )
            }
        }
    }

    val hasCardiacData = summary?.avgHeartRateBpm != null || summary?.maxHeartRateBpm != null ||
        summary?.minHeartRateBpm != null || summary?.avgRrIntervalMs != null || summary?.rmssdMs != null ||
        summary?.hrr60Bpm != null || summary?.hrr300Bpm != null || isCooldownActive
    val hasBiomechanicsData = summary?.maxTorsoPitchDeg != null || summary?.maxTorsoRollDeg != null

    LaunchedEffect(resolvedSessionId, workoutRepository, hasCardiacData, hasBiomechanicsData, latestSummary) {
        if (resolvedSessionId != null && workoutRepository != null && (hasCardiacData || hasBiomechanicsData)) {
            try {
                beatSamples = workoutRepository.getPolarBeatSamplesBySession(resolvedSessionId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.w { "Failed to load PolarBeatSamples: ${e.message}" }
            }
            try {
                ecgSegments = workoutRepository.getPolarEcgSegmentsBySession(resolvedSessionId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.w { "Failed to load PolarEcgSegments: ${e.message}" }
            }
            try {
                metricSamples = workoutRepository.getMetricSamplesBySession(resolvedSessionId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Logger.w { "Failed to load MetricSamples: ${e.message}" }
            }
        }
    }

    val isConnected = isCurrentSession && connectionState is ConnectionState.Connected

    // If no Polar data and not connected or in cooldown, hide the card
    if (!hasCardiacData && !hasBiomechanicsData && !isConnected && !isCooldownActive) {
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCooldownActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            val accessColors = AccessibilityTheme.colors
            val colorScheme = MaterialTheme.colorScheme

            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeaderIconBadge(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(Res.string.hrm_card_sensor_cd),
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer,
                    )
                    Column {
                        Text(
                            text = stringResource(Res.string.hrm_card_analytics_header),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = stringResource(
                                if (isCooldownActive) {
                                    Res.string.hrm_card_cooldown_active_title
                                } else {
                                    Res.string.hrm_card_summary_title
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isConnected) {
                        colorScheme.primaryContainer
                    } else {
                        colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (isConnected) Res.string.hrm_status_connected else Res.string.hrm_card_status_logged,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) {
                            colorScheme.onPrimaryContainer
                        } else {
                            colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Section 1: Heart Rate Stats
            val bpmUnit = stringResource(Res.string.hrm_unit_bpm)
            if (summary?.avgHeartRateBpm != null || summary?.maxHeartRateBpm != null || summary?.minHeartRateBpm != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.avgHeartRateBpm?.let { avg ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_avg_hr),
                            value = "$avg",
                            unit = bpmUnit,
                            icon = Icons.Default.Favorite,
                            tint = colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.maxHeartRateBpm?.let { max ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_peak_hr),
                            value = "$max",
                            unit = bpmUnit,
                            icon = Icons.Default.TrendingUp,
                            tint = accessColors.warning,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.minHeartRateBpm?.let { min ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_min_hr),
                            value = "$min",
                            unit = bpmUnit,
                            icon = Icons.Default.TrendingDown,
                            tint = accessColors.info,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 2: HRV & Recovery Metrics — HRV only when enough valid RR
            // samples produced an RMSSD; otherwise explicitly unavailable (SD1:
            // HRV is never promised).
            val msUnit = stringResource(Res.string.hrm_unit_ms)
            if (summary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.rmssdMs?.let { rmssd ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_hrv_rmssd),
                            value = "${rmssd.roundToInt()}",
                            unit = msUnit,
                            subtext = stringResource(Res.string.hrm_card_rr_variation),
                            icon = Icons.Default.Speed,
                            tint = accessColors.success,
                            modifier = Modifier.weight(1f),
                        )
                    } ?: run {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        ) {
                            Text(
                                text = stringResource(Res.string.hrm_hrv_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    summary.avgRrIntervalMs?.let { rr ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_avg_rr_beat),
                            value = "${rr.roundToInt()}",
                            unit = msUnit,
                            icon = Icons.Default.Timer,
                            tint = colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 3: Torso Biomechanics Posture & Asymmetry
            if (summary?.maxTorsoPitchDeg != null || summary?.maxTorsoRollDeg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.maxTorsoPitchDeg?.let { pitch ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_max_hip_hinge),
                            value = "${pitch.roundToInt()}°",
                            unit = stringResource(Res.string.hrm_card_pitch_unit),
                            subtext = stringResource(Res.string.hrm_card_sagittal_bend),
                            icon = Icons.Default.Accessibility,
                            tint = accessColors.warning,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.maxTorsoRollDeg?.let { roll ->
                        MetricTile(
                            label = stringResource(Res.string.hrm_card_max_lateral_tilt),
                            value = "${roll.roundToInt()}°",
                            unit = stringResource(Res.string.hrm_card_roll_unit),
                            subtext = stringResource(Res.string.hrm_card_coronal_asymmetry),
                            icon = Icons.Default.CompareArrows,
                            tint = accessColors.info,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 4: 5-Minute Heart Rate Recovery (HRR) Protocol Card
            val hasRecoveryData = summary?.hrr60Bpm != null || summary?.hrr300Bpm != null || isCooldownActive
            if (hasRecoveryData || isConnected || summary != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCooldownActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isCooldownActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.hrm_card_cooldown_header),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            if (isCooldownActive) {
                                val mins = cooldownSecondsRemaining / 60
                                val secs = cooldownSecondsRemaining % 60
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        text = "${mins}:${secs.toString().padStart(2, '0')}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        if (isCooldownActive) {
                            // Active Cooldown Display
                            val currentHr = hrMeasurement?.hr
                            val dropSoFar = if (currentHr != null && cooldownStartHr != null) {
                                (cooldownStartHr!! - currentHr).coerceAtLeast(0)
                            } else null

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(
                                            Res.string.hrm_card_starting_hr,
                                            cooldownStartHr?.toString() ?: "—",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(
                                            Res.string.hrm_card_current_hr,
                                            currentHr?.toString() ?: "—",
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                dropSoFar?.let { drop ->
                                    val tint = MaterialTheme.colorScheme.primary
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = tint.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = "-$drop $bpmUnit",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = tint,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }

                            // Active progress bar (300s total)
                            val progress = 1f - (cooldownSecondsRemaining / 300f).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(MaterialTheme.shapes.extraSmall),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )

                            // Cancel Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { hrmBleManager?.stopCooldown() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.hrm_card_cancel_cooldown))
                                }
                            }
                        } else {
                            // Historical or Post-Cooldown State
                            val hrr60 = summary?.hrr60Bpm
                            val hrr300 = summary?.hrr300Bpm

                            if (hrr60 != null || hrr300 != null) {
                                val stackChips = isCompactAccessibilityLayout() ||
                                    LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact
                                if (stackChips) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        hrr60?.let { drop60 ->
                                            RecoveryChip(
                                                label = stringResource(Res.string.hrm_card_hrr_60_label),
                                                drop = drop60,
                                                rating = stringResource(Res.string.hrm_card_hrr_60_desc),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        hrr300?.let { drop300 ->
                                            RecoveryChip(
                                                label = stringResource(Res.string.hrm_card_hrr_300_label),
                                                drop = drop300,
                                                rating = stringResource(Res.string.hrm_card_hrr_300_desc),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        hrr60?.let { drop60 ->
                                            RecoveryChip(
                                                label = stringResource(Res.string.hrm_card_hrr_60_label),
                                                drop = drop60,
                                                rating = stringResource(Res.string.hrm_card_hrr_60_desc),
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        hrr300?.let { drop300 ->
                                            RecoveryChip(
                                                label = stringResource(Res.string.hrm_card_hrr_300_label),
                                                drop = drop300,
                                                rating = stringResource(Res.string.hrm_card_hrr_300_desc),
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                if (isConnected) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                hrmBleManager?.triggerCooldown(
                                                    resolvedSessionId,
                                                    forceRestart = true,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                stringResource(Res.string.hrm_card_restart_cooldown),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            } else if (isConnected) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.hrm_card_track_prompt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            hrmBleManager?.triggerCooldown(
                                                resolvedSessionId,
                                                forceRestart = true,
                                            )
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.wrapContentWidth(),
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            stringResource(Res.string.hrm_card_start_cooldown),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            } else {
                                // SD1: HRR is computed from recovery HR, independently of
                                // RR intervals. Missing HRR is never blamed on RR data.
                                Text(
                                    text = stringResource(Res.string.hrm_card_hrr_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Section 5: Interactive Visualizations
            if (beatSamples.isNotEmpty()) {
                ContinuousHeartRateTimelineChart(samples = beatSamples)
            }

            if (metricSamples.any { it.torsoPitchDeg != null || it.torsoRollDeg != null }) {
                TorsoBiomechanicsVsCableChart(samples = metricSamples)
            }

            if (ecgSegments.isNotEmpty()) {
                SavedEcgWaveformScrubber(segments = ecgSegments)
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    tint: Color,
    subtext: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (subtext != null) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RecoveryChip(
    label: String,
    drop: Int,
    rating: String,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "-$drop ${stringResource(Res.string.hrm_unit_bpm)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
                Text(
                    text = "($rating)",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * Chart 1 — Continuous HR & Recovery Timeline (`HrmBeatSample`).
 * Plots BPM and RMSSD over time with color-coded phase bands and interactive scrubber.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuousHeartRateTimelineChart(
    samples: List<HrmBeatSample>,
    modifier: Modifier = Modifier,
) {
    val validSamples = remember(samples) {
        samples.filter { it.hrBpm > 0 }
    }
    if (validSamples.size < 2) return

    val accessColors = AccessibilityTheme.colors
    val colorScheme = MaterialTheme.colorScheme
    val hrColor = colorScheme.error
    val hrvColor = accessColors.connectedGreen
    val warmupColor = accessColors.warning
    val workingColor = colorScheme.error
    val restColor = accessColors.info
    val cooldownColor = accessColors.success
    val plotBackground = colorScheme.surfaceContainerLowest
    val gridLineColor = colorScheme.outlineVariant.copy(alpha = 0.45f)

    val minTs = validSamples.first().timestampMs
    val maxTs = validSamples.last().timestampMs
    val durationMs = (maxTs - minTs).coerceAtLeast(1000L)

    val minHr = (validSamples.minOfOrNull { it.hrBpm.toInt() } ?: 60).coerceAtMost(50)
    val maxHr = ((validSamples.maxOfOrNull { it.hrBpm.toInt() } ?: 180) + 10).coerceAtLeast(minHr + 40)
    val hrRange = (maxHr - minHr).toFloat()

    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    val selectedSample = remember(scrubFraction, validSamples) {
        scrubFraction?.let { fraction ->
            val targetTs = minTs + (fraction * durationMs).toLong()
            validSamples.minByOrNull { abs(it.timestampMs - targetTs) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = hrColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(Res.string.hrm_chart_timeline_header),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Text(
                    text = stringResource(
                        Res.string.hrm_chart_duration_ms,
                        durationMs / 60000,
                        (durationMs % 60000) / 1000,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            // Legend Row (wrapped cleanly in FlowRow for narrow screens & large fonts)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhaseLegendDot(color = warmupColor, label = stringResource(Res.string.hrm_phase_warmup))
                PhaseLegendDot(color = workingColor, label = stringResource(Res.string.hrm_phase_working))
                PhaseLegendDot(color = restColor, label = stringResource(Res.string.hrm_phase_rest))
                PhaseLegendDot(color = cooldownColor, label = stringResource(Res.string.hrm_phase_cooldown))
                PhaseLegendDot(color = hrvColor, label = stringResource(Res.string.hrm_legend_hrv), isLine = true)
            }

            // Scrub Info Bar
            if (selectedSample != null) {
                val elapsedSec = ((selectedSample.timestampMs - minTs) / 1000).coerceAtLeast(0)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "${elapsedSec / 60}m ${elapsedSec % 60}s [${selectedSample.phase}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(Res.string.hrm_scrub_hr, selectedSample.hrBpm),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = hrColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        selectedSample.rmssdMs?.let { rmssd ->
                            Text(
                                text = stringResource(Res.string.hrm_scrub_rmssd, rmssd.roundToInt()),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = hrvColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // Canvas Line Chart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(plotBackground)
                    .border(0.5.dp, colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                tryAwaitRelease()
                                scrubFraction = null
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { scrubFraction = null },
                            onDragCancel = { scrubFraction = null },
                            onHorizontalDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        )
                    },
            ) {
                val scrubberLineColor = colorScheme.onSurface.copy(alpha = 0.7f)
                val scrubberDotColor = colorScheme.surface
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. Draw Phase Bands
                    var currentPhase = validSamples.first().phase
                    var spanStartTs = validSamples.first().timestampMs
                    val spans = mutableListOf<Triple<String, Long, Long>>()
                    for (s in validSamples) {
                        if (s.phase != currentPhase) {
                            spans.add(Triple(currentPhase, spanStartTs, s.timestampMs))
                            currentPhase = s.phase
                            spanStartTs = s.timestampMs
                        }
                    }
                    spans.add(Triple(currentPhase, spanStartTs, maxTs))

                    for (span in spans) {
                        val x0 = ((span.second - minTs).toFloat() / durationMs) * w
                        val x1 = ((span.third - minTs).toFloat() / durationMs) * w
                        val bandColor = when (span.first) {
                            "WARMUP" -> warmupColor.copy(alpha = 0.14f)
                            "WORKING_SET" -> workingColor.copy(alpha = 0.18f)
                            "REST" -> restColor.copy(alpha = 0.14f)
                            "POST_WORKOUT_COOLDOWN" -> cooldownColor.copy(alpha = 0.20f)
                            else -> Color.Transparent
                        }
                        drawRect(color = bandColor, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), h))
                    }

                    // 2. Draw Horizontal Grid Baseline Lines
                    listOf(80, 120, 160).forEach { bpm ->
                        if (bpm in minHr..maxHr) {
                            val y = h - ((bpm - minHr) / hrRange) * h
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f,
                            )
                        }
                    }

                    // 3. RMSSD Curve (Secondary)
                    val rmssdPath = Path()
                    var rmssdStarted = false
                    val maxRmssd = 120f
                    for (s in validSamples) {
                        if (s.rmssdMs != null) {
                            val x = ((s.timestampMs - minTs).toFloat() / durationMs) * w
                            val y = h - (s.rmssdMs.toFloat().coerceIn(0f, maxRmssd) / maxRmssd) * (h * 0.7f)
                            if (!rmssdStarted) {
                                rmssdPath.moveTo(x, y)
                                rmssdStarted = true
                            } else {
                                rmssdPath.lineTo(x, y)
                            }
                        }
                    }
                    if (rmssdStarted) {
                        drawPath(
                            path = rmssdPath,
                            color = hrvColor.copy(alpha = 0.7f),
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    }

                    // 4. Heart Rate Curve (Primary)
                    val hrPath = Path()
                    val hrFillPath = Path()
                    var hrStarted = false
                    for (s in validSamples) {
                        val hr = s.hrBpm.toInt()
                        val x = ((s.timestampMs - minTs).toFloat() / durationMs) * w
                        val y = h - ((hr - minHr) / hrRange) * h
                        if (!hrStarted) {
                            hrPath.moveTo(x, y)
                            hrFillPath.moveTo(x, h)
                            hrFillPath.lineTo(x, y)
                            hrStarted = true
                        } else {
                            hrPath.lineTo(x, y)
                            hrFillPath.lineTo(x, y)
                        }
                    }
                    if (hrStarted) {
                        hrFillPath.lineTo(w, h)
                        hrFillPath.close()

                        drawPath(
                            path = hrFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(hrColor.copy(alpha = 0.25f), Color.Transparent),
                                startY = 0f,
                                endY = h,
                            ),
                        )

                        drawPath(
                            path = hrPath,
                            color = hrColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 5. Scrubber Indicator
                    scrubFraction?.let { fraction ->
                        val scrubX = fraction * w
                        drawLine(
                            color = scrubberLineColor,
                            start = Offset(scrubX, 0f),
                            end = Offset(scrubX, h),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        selectedSample?.hrBpm?.let { hr ->
                            val dotY = h - ((hr.toInt() - minHr) / hrRange) * h
                            drawCircle(color = scrubberDotColor, radius = 4.dp.toPx(), center = Offset(scrubX, dotY))
                            drawCircle(color = hrColor, radius = 2.5.dp.toPx(), center = Offset(scrubX, dotY))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chart 2 — 3D Torso Biomechanics vs. Rep Position (`PolarMetricSample`).
 * Plots intra-set torso pitch (hinge) and roll (tilt) synchronized with cable position/force.
 */
@Composable
private fun TorsoBiomechanicsVsCableChart(
    samples: List<PolarMetricSample>,
    modifier: Modifier = Modifier,
) {
    val biomechSamples = remember(samples) {
        samples.filter { it.torsoPitchDeg != null || it.torsoRollDeg != null }
    }
    if (biomechSamples.isEmpty()) return

    val sampleCount = biomechSamples.size
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    val selectedSample = remember(scrubFraction, biomechSamples) {
        scrubFraction?.let { fraction ->
            val index = (fraction * (sampleCount - 1)).roundToInt().coerceIn(0, sampleCount - 1)
            biomechSamples[index]
        }
    }

    // Dynamic Left Axis (Degrees):
    // Collect all angles present, ensuring 0° is always included with padding
    val pitchAngles = remember(biomechSamples) { biomechSamples.mapNotNull { it.torsoPitchDeg?.toFloat() } }
    val rollAngles = remember(biomechSamples) { biomechSamples.mapNotNull { it.torsoRollDeg?.toFloat() } }
    val allAngles = remember(pitchAngles, rollAngles) { pitchAngles + rollAngles }
    val rawMinAngle = (allAngles.minOrNull() ?: 0f).coerceAtMost(0f)
    val rawMaxAngle = (allAngles.maxOrNull() ?: 45f).coerceAtLeast(0f)
    val minAngle = rawMinAngle - 5f
    val maxAngle = (rawMaxAngle + 5f).coerceAtLeast(minAngle + 10f)
    val angleRange = (maxAngle - minAngle).coerceAtLeast(1f)

    // Dynamic Right Axis (Cable Position):
    // Independent normalization to session's min/max active cable position (position + positionB) / 2f
    val cablePositions = remember(biomechSamples) {
        biomechSamples.mapNotNull { s ->
            if (s.position != null && s.positionB != null) {
                (s.position.toFloat() + s.positionB.toFloat()) / 2f
            } else {
                s.position?.toFloat() ?: s.positionB?.toFloat()
            }
        }
    }
    val rawMinPos = cablePositions.minOrNull() ?: 0f
    val rawMaxPos = cablePositions.maxOrNull() ?: 1f
    val posSpan = (rawMaxPos - rawMinPos).coerceAtLeast(0.05f)
    val minPos = (rawMinPos - posSpan * 0.05f).coerceAtLeast(0f)
    val maxPos = rawMaxPos + posSpan * 0.05f
    val posRange = (maxPos - minPos).coerceAtLeast(0.01f)

    val accessColors = AccessibilityTheme.colors
    val colorScheme = MaterialTheme.colorScheme
    val pitchColor = accessColors.warning
    val rollColor = accessColors.info
    val cableColor = colorScheme.tertiary
    val plotBackground = colorScheme.surfaceContainerLowest
    val zeroLineColor = colorScheme.outlineVariant
    val tickColor = colorScheme.outline

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Accessibility,
                        contentDescription = null,
                        tint = pitchColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(Res.string.hrm_chart_torso_header),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Text(
                    text = stringResource(Res.string.hrm_chart_reps_pts, sampleCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            // Legend & Dynamic Axis Info Row (wrapped cleanly in FlowRow)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhaseLegendDot(color = pitchColor, label = stringResource(Res.string.hrm_legend_hinge_pitch))
                PhaseLegendDot(color = rollColor, label = stringResource(Res.string.hrm_legend_tilt_roll))
                PhaseLegendDot(color = cableColor, label = stringResource(Res.string.hrm_legend_cable_pos), isLine = true)
                Text(
                    text = stringResource(Res.string.hrm_chart_deg_ref, minAngle.roundToInt(), maxAngle.roundToInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            // Scrub Info Bar
            if (selectedSample != null) {
                val cablePos = if (selectedSample.position != null && selectedSample.positionB != null) {
                    (selectedSample.position.toFloat() + selectedSample.positionB.toFloat()) / 2f
                } else {
                    selectedSample.position?.toFloat() ?: selectedSample.positionB?.toFloat()
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.hrm_scrub_hinge,
                                selectedSample.torsoPitchDeg?.let { "${(it * 10).roundToInt() / 10.0}°" } ?: "—",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = pitchColor,
                        )
                        Text(
                            text = stringResource(
                                Res.string.hrm_scrub_tilt,
                                selectedSample.torsoRollDeg?.let { "${(it * 10).roundToInt() / 10.0}°" } ?: "—",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = rollColor,
                        )
                        cablePos?.let { pos ->
                            val romPercent = ((pos - minPos) / posRange * 100f).roundToInt().coerceIn(0, 100)
                            Text(
                                text = stringResource(Res.string.hrm_scrub_cable_pct, romPercent),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = cableColor,
                            )
                        }
                    }
                }
            }

            // Canvas Plot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(plotBackground)
                    .border(0.5.dp, colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                tryAwaitRelease()
                                scrubFraction = null
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { scrubFraction = null },
                            onDragCancel = { scrubFraction = null },
                            onHorizontalDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        )
                    },
            ) {
                val scrubberLineColor = colorScheme.onSurface.copy(alpha = 0.7f)
                val scrubberDotColor = colorScheme.surface
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val padTop = 14f
                    val padBottom = 14f
                    val plotH = h - padTop - padBottom

                    // Center 0° baseline for posture reference
                    val zeroY = padTop + (1f - (0f - minAngle) / angleRange) * plotH
                    drawLine(
                        color = zeroLineColor,
                        start = Offset(0f, zeroY),
                        end = Offset(w, zeroY),
                        strokeWidth = 1.2f,
                    )

                    // Degree tick marker on left edge
                    drawLine(
                        color = tickColor,
                        start = Offset(0f, zeroY),
                        end = Offset(10.dp.toPx(), zeroY),
                        strokeWidth = 2f,
                    )

                    val stepX = w / (sampleCount - 1).coerceAtLeast(1).toFloat()

                    // 1. Cable Position (Tertiary line scaled independently across full height)
                    val cablePath = Path()
                    var cableStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        val pos = if (s.position != null && s.positionB != null) {
                            (s.position.toFloat() + s.positionB.toFloat()) / 2f
                        } else {
                            s.position?.toFloat() ?: s.positionB?.toFloat()
                        }
                        if (pos != null) {
                            val x = i * stepX
                            val y = padTop + (1f - (pos - minPos) / posRange) * plotH
                            if (!cableStarted) {
                                cablePath.moveTo(x, y)
                                cableStarted = true
                            } else {
                                cablePath.lineTo(x, y)
                            }
                        }
                    }
                    if (cableStarted) {
                        drawPath(
                            path = cablePath,
                            color = cableColor.copy(alpha = 0.75f),
                            style = Stroke(
                                width = 1.8.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 2. Torso Pitch (Hip Hinge, warning line scaled across angle range)
                    val pitchPath = Path()
                    var pitchStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        s.torsoPitchDeg?.let { pitch ->
                            val x = i * stepX
                            val y = padTop + (1f - (pitch.toFloat() - minAngle) / angleRange) * plotH
                            if (!pitchStarted) {
                                pitchPath.moveTo(x, y)
                                pitchStarted = true
                            } else {
                                pitchPath.lineTo(x, y)
                            }
                        }
                    }
                    if (pitchStarted) {
                        drawPath(
                            path = pitchPath,
                            color = pitchColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 3. Torso Roll (Lateral Tilt, info line scaled across angle range)
                    val rollPath = Path()
                    var rollStarted = false
                    biomechSamples.forEachIndexed { i, s ->
                        s.torsoRollDeg?.let { roll ->
                            val x = i * stepX
                            val y = padTop + (1f - (roll.toFloat() - minAngle) / angleRange) * plotH
                            if (!rollStarted) {
                                rollPath.moveTo(x, y)
                                rollStarted = true
                            } else {
                                rollPath.lineTo(x, y)
                            }
                        }
                    }
                    if (rollStarted) {
                        drawPath(
                            path = rollPath,
                            color = rollColor,
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 4. Scrubber Line & Indicators
                    scrubFraction?.let { fraction ->
                        val scrubX = fraction * w
                        drawLine(
                            color = scrubberLineColor,
                            start = Offset(scrubX, 0f),
                            end = Offset(scrubX, h),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        selectedSample?.torsoPitchDeg?.let { pitch ->
                            val pitchY = padTop + (1f - (pitch.toFloat() - minAngle) / angleRange) * plotH
                            drawCircle(color = scrubberDotColor, radius = 4.dp.toPx(), center = Offset(scrubX, pitchY))
                            drawCircle(color = pitchColor, radius = 2.5.dp.toPx(), center = Offset(scrubX, pitchY))
                        }
                        val selCablePos = if (selectedSample?.position != null && selectedSample?.positionB != null) {
                            (selectedSample.position!!.toFloat() + selectedSample.positionB!!.toFloat()) / 2f
                        } else {
                            selectedSample?.position?.toFloat() ?: selectedSample?.positionB?.toFloat()
                        }
                        selCablePos?.let { pos ->
                            val cableY = padTop + (1f - (pos - minPos) / posRange) * plotH
                            drawCircle(color = scrubberDotColor, radius = 4.dp.toPx(), center = Offset(scrubX, cableY))
                            drawCircle(color = cableColor, radius = 2.5.dp.toPx(), center = Offset(scrubX, cableY))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chart 3 — Saved 130 Hz ECG Waveform Scrubber (`PolarEcgSegment`).
 * Renders parsed microvolt samples across clinical ECG grid with interactive window scrubber.
 */
@Composable
private fun SavedEcgWaveformScrubber(
    segments: List<PolarEcgSegment>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    val segmentCounts = remember(segments) {
        segments.map { seg -> if (seg.samplesUvCsv.isEmpty()) 0 else seg.samplesUvCsv.count { it == ',' } + 1 }
    }
    val totalSamples = remember(segmentCounts) { segmentCounts.sum() }
    if (totalSamples < 10) return

    val windowSize = 260 // 2.0 seconds at 130 Hz
    var windowStart by remember { mutableStateOf(0) }
    val maxStart = (totalSamples - windowSize).coerceAtLeast(0)

    val currentPhase = remember(windowStart, segments, segmentCounts) {
        var accum = 0
        var phase = segments.first().phase
        for (i in segments.indices) {
            val count = segmentCounts.getOrElse(i) { 0 }
            if (windowStart in accum until (accum + count)) {
                phase = segments[i].phase
                break
            }
            accum += count
        }
        phase
    }

    val colorScheme = MaterialTheme.colorScheme
    val ecgTraceColor = colorScheme.primary
    val ecgBgColor = colorScheme.surfaceContainerLowest
    val ecgBorderColor = colorScheme.outlineVariant
    val ecgMinorGridColor = colorScheme.outlineVariant.copy(alpha = 0.25f)
    val ecgMajorGridColor = colorScheme.outlineVariant.copy(alpha = 0.55f)
    val ecgBaselineColor = colorScheme.outline

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = ecgTraceColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(Res.string.hrm_chart_ecg_header),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = colorScheme.primaryContainer.copy(alpha = 0.4f),
                ) {
                    Text(
                        text = currentPhase.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Window details and slider
            val startSec = (windowStart / 130.0 * 10).roundToInt() / 10.0
            val endSec = (((windowStart + windowSize).coerceAtMost(totalSamples)) / 130.0 * 10).roundToInt() / 10.0
            val totalSec = (totalSamples / 130.0 * 10).roundToInt() / 10.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        Res.string.hrm_chart_ecg_time,
                        startSec.toString(),
                        endSec.toString(),
                        totalSec.toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    text = stringResource(Res.string.hrm_chart_ecg_samples, totalSamples),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            if (maxStart > 0) {
                Slider(
                    value = windowStart.toFloat(),
                    onValueChange = { windowStart = it.roundToInt().coerceIn(0, maxStart) },
                    valueRange = 0f..maxStart.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                )
            }

            // ECG Grid & Trace Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(ecgBgColor)
                    .border(0.5.dp, ecgBorderColor, MaterialTheme.shapes.small),
            ) {
                val windowSlice by produceState<List<Int>>(
                    initialValue = emptyList(),
                    key1 = segments,
                    key2 = windowStart,
                ) {
                    value = withContext(Dispatchers.Default) {
                        val result = mutableListOf<Int>()
                        var currentOffset = 0
                        val targetEnd = (windowStart + windowSize).coerceAtMost(totalSamples)
                        for (i in segments.indices) {
                            val seg = segments[i]
                            val count = segmentCounts.getOrElse(i) { 0 }
                            val segStart = currentOffset
                            val segEnd = currentOffset + count
                            if (segEnd > windowStart && segStart < targetEnd) {
                                val segSamples = seg.samplesUvCsv.split(',').mapNotNull { it.trim().toIntOrNull() }
                                val startInSeg = (windowStart - segStart).coerceAtLeast(0)
                                val endInSeg = (targetEnd - segStart).coerceAtMost(segSamples.size)
                                if (startInSeg < endInSeg && startInSeg < segSamples.size) {
                                    result.addAll(segSamples.subList(startInSeg, endInSeg))
                                }
                            }
                            currentOffset += count
                            if (currentOffset >= targetEnd) break
                        }
                        result
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val midY = h / 2f

                    // 1. Draw Clinical ECG Grid
                    val minorStep = 10.dp.toPx()
                    var gx = 0f
                    while (gx < w) {
                        drawLine(color = ecgMinorGridColor, start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 0.5f)
                        gx += minorStep
                    }
                    var gy = 0f
                    while (gy < h) {
                        drawLine(color = ecgMinorGridColor, start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 0.5f)
                        gy += minorStep
                    }
                    val majorStep = 40.dp.toPx()
                    gx = 0f
                    while (gx < w) {
                        drawLine(color = ecgMajorGridColor, start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 1f)
                        gx += majorStep
                    }
                    gy = 0f
                    while (gy < h) {
                        drawLine(color = ecgMajorGridColor, start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 1f)
                        gy += majorStep
                    }

                    // Zero baseline center line
                    drawLine(color = ecgBaselineColor, start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1.5f)

                    if (windowSlice.size < 2) return@Canvas

                    // 2. Autoscale µV
                    val maxAbsUv = windowSlice.maxOfOrNull { abs(it) }?.toFloat()?.coerceAtLeast(800f) ?: 1500f
                    val scaleY = (h * 0.42f) / maxAbsUv
                    val stepX = w / (windowSlice.size - 1).toFloat()

                    // 3. Draw ECG Trace
                    val path = Path()
                    windowSlice.forEachIndexed { i, uv ->
                        val x = i * stepX
                        val y = (midY - (uv * scaleY)).coerceIn(1f, h - 1f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = ecgTraceColor,
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseLegendDot(
    color: Color,
    label: String,
    isLine: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isLine) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(2.dp)
                    .background(color),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
