package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.devil.phoenixproject.util.KmpUtils
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.velocityZoneColor
import com.devil.phoenixproject.ui.theme.velocityZoneLabel

// velocityZoneColor() now provided by shared utility from AccessibilityColors.kt

/**
 * Human-readable label for a [StrengthProfile].
 */
private fun strengthProfileLabel(profile: StrengthProfile): String {
    return when (profile) {
        StrengthProfile.ASCENDING -> "Ascending"
        StrengthProfile.DESCENDING -> "Descending"
        StrengthProfile.BELL_SHAPED -> "Bell-shaped"
        StrengthProfile.FLAT -> "Flat"
    }
}

// velocityZoneLabel() now provided by shared utility from AccessibilityColors.kt

// ──────────────────────────────────────────────────────────────────────────────
// 1. Set-level summary ("at a glance")
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Compact set-level biomechanics summary shown in the session history card.
 *
 * Displays average MCV with velocity zone color, strength profile,
 * average asymmetry with dominant side, and velocity loss trend.
 * All values are nullable to handle graceful degradation.
 *
 * @param avgMcvMmS Average mean concentric velocity in mm/s, or null
 * @param avgAsymmetryPercent Average asymmetry percentage, or null (hidden for non-Elite)
 * @param totalVelocityLossPercent Total velocity loss from first to last rep, or null
 * @param dominantSide Dominant cable side ("A", "B", "BALANCED"), or null
 * @param strengthProfile Strength profile label, or null
 * @param onExpandReps Callback to toggle per-rep detail expansion
 */
@Composable
fun BiomechanicsHistorySummary(
    avgMcvMmS: Float?,
    avgAsymmetryPercent: Float?,
    totalVelocityLossPercent: Float?,
    dominantSide: String?,
    strengthProfile: String?,
    onExpandReps: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small)
        ) {
            // Section header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.extraSmall))
                Text(
                    "Biomechanics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Row 1: Average MCV + Strength Profile
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // MCV with velocity zone color
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (avgMcvMmS != null) {
                        val zone = BiomechanicsVelocityZone.fromMcv(avgMcvMmS)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(velocityZoneColor(zone))
                        )
                        Spacer(modifier = Modifier.width(Spacing.extraSmall))
                        Text(
                            "Avg MCV: ${KmpUtils.formatFloat(avgMcvMmS, 1)} mm/s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(Spacing.extraSmall))
                        VelocityZoneChip(zone)
                    } else {
                        Text(
                            "Avg MCV: \u2014",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Strength profile
                val profileLabel = strengthProfile?.let { sp ->
                    try {
                        strengthProfileLabel(StrengthProfile.valueOf(sp))
                    } catch (_: IllegalArgumentException) {
                        sp
                    }
                }
                Text(
                    profileLabel ?: "\u2014",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.extraSmall))

            // Row 2: Asymmetry + Velocity Loss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Asymmetry (only shown if non-null, i.e. Elite tier)
                if (avgAsymmetryPercent != null) {
                    val sideLabel = when (dominantSide) {
                        "A" -> "Cable A"
                        "B" -> "Cable B"
                        "BALANCED" -> "Balanced"
                        else -> dominantSide ?: ""
                    }
                    Text(
                        "Asymmetry: ${KmpUtils.formatFloat(avgAsymmetryPercent, 1)}% ($sideLabel)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Velocity loss
                if (totalVelocityLossPercent != null) {
                    Text(
                        "Vel. Loss: ${KmpUtils.formatFloat(totalVelocityLossPercent, 1)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (totalVelocityLossPercent > 20f)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Vel. Loss: \u2014",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // "View Per-Rep" expand trigger
            Text(
                "View Per-Rep Details",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onExpandReps)
                    .padding(vertical = Spacing.extraSmall)
            )
        }
    }
}

/**
 * Small colored chip showing the velocity zone label.
 */
@Composable
private fun VelocityZoneChip(zone: BiomechanicsVelocityZone) {
    Surface(
        color = velocityZoneColor(zone).copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            velocityZoneLabel(zone),
            style = MaterialTheme.typography.labelSmall,
            color = velocityZoneColor(zone),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. Per-rep drill-down
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Per-rep biomechanics detail list, shown when the user expands from the summary.
 *
 * Each rep row shows MCV, velocity zone, velocity loss %, and optionally asymmetry.
 * Tapping a rep row expands to show its force curve sparkline with sticking point
 * and strength profile annotations.
 *
 * @param repResults List of per-rep biomechanics results (lazy-loaded from DB)
 * @param isLoading True while data is being fetched
 * @param showAsymmetry True for Elite tier users (controls asymmetry column visibility)
 * @param showForceCurves True for Phoenix+ tier users (controls force curve visibility)
 */
@Composable
fun RepBiomechanicsDetail(
    repResults: List<BiomechanicsRepResult>,
    isLoading: Boolean,
    showAsymmetry: Boolean = true,
    showForceCurves: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        } else if (repResults.isEmpty()) {
            Text(
                "No per-rep biomechanics data available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.small)
            )
        } else {
            repResults.forEach { rep ->
                RepBiomechanicsRow(
                    rep = rep,
                    showAsymmetry = showAsymmetry,
                    showForceCurves = showForceCurves
                )
            }
        }
    }
}

/**
 * Single rep row with expandable force curve sparkline.
 */
@Composable
private fun RepBiomechanicsRow(
    rep: BiomechanicsRepResult,
    showAsymmetry: Boolean,
    showForceCurves: Boolean
) {
    var isExpanded by remember { mutableStateOf(false) }
    val zone = rep.velocity.zone

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(Spacing.small)
        ) {
            // Main rep metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Rep number + MCV + zone chip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Rep ${rep.repNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "${KmpUtils.formatFloat(rep.velocity.meanConcentricVelocityMmS, 1)} mm/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(Spacing.extraSmall))
                    VelocityZoneChip(zone)
                }

                // Right: Velocity loss + expand indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rep.velocity.velocityLossPercent?.let { loss ->
                        Text(
                            "-${KmpUtils.formatFloat(loss, 1)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (loss > 20f)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(Spacing.extraSmall))
                    }

                    if (showForceCurves) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (isExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Asymmetry row (Elite only)
            if (showAsymmetry) {
                Spacer(modifier = Modifier.height(2.dp))
                val sideLabel = when (rep.asymmetry.dominantSide) {
                    "A" -> "Cable A"
                    "B" -> "Cable B"
                    "BALANCED" -> "Balanced"
                    else -> rep.asymmetry.dominantSide
                }
                Text(
                    "Asymmetry: ${KmpUtils.formatFloat(rep.asymmetry.asymmetryPercent, 1)}% ($sideLabel)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable force curve sparkline (Phoenix+ with FORCE_CURVES)
            if (showForceCurves) {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = Spacing.small)) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Force curve sparkline (reuse existing ForceSparkline)
                        if (rep.forceCurve.normalizedForceN.isNotEmpty()) {
                            ForceSparkline(
                                forceData = rep.forceCurve.normalizedForceN,
                                peakIndex = null, // Normalized curve, no single peak index
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(Spacing.extraSmall))

                        // Annotations: sticking point + strength profile
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            rep.forceCurve.stickingPointPct?.let { sp ->
                                Text(
                                    "Sticking point: ${KmpUtils.formatFloat(sp, 0)}% ROM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } ?: Spacer(modifier = Modifier.width(1.dp))

                            Text(
                                strengthProfileLabel(rep.forceCurve.strengthProfile),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. Premium upsell card (FREE tier)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Upsell card shown to FREE tier users instead of biomechanics data.
 *
 * Follows the same visual pattern as [LockedFeatureOverlay] in PremiumFeatureGate.kt.
 */
@Composable
fun PremiumBiomechanicsUpsell(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(Spacing.medium),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Premium feature",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Biomechanics Analysis",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Upgrade to Phoenix+ to view detailed biomechanics data for every rep",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
