package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Calculate responsive dimension based on window size class.
 * Scales the base dimension up for larger screens.
 */
@Composable
private fun responsiveDimension(base: Dp): Dp {
    val windowSizeClass = LocalWindowSizeClass.current
    return when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> base * 1.4f
        WindowWidthSizeClass.Medium -> base * 1.2f
        WindowWidthSizeClass.Compact -> base
    }
}

// Extension for Dp multiplication
private operator fun Dp.times(factor: Float): Dp = (this.value * factor).dp

/**
 * Shimmer effect for skeleton loading screens.
 * Creates an animated gradient that sweeps across placeholder content.
 */
@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    showShimmer: Boolean = true
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            Color.LightGray.copy(alpha = 0.6f),
            Color.LightGray.copy(alpha = 0.2f),
            Color.LightGray.copy(alpha = 0.6f),
        )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1000,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_translate"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation.value, y = translateAnimation.value)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent),
            start = Offset.Zero,
            end = Offset.Zero
        )
    }
}

/**
 * Shimmer box placeholder - generic rectangular shimmer element.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    showShimmer: Boolean = true
) {
    Box(
        modifier = modifier
            .background(
                brush = shimmerBrush(showShimmer = showShimmer),
                shape = RoundedCornerShape(8.dp)
            )
    )
}

/**
 * Skeleton card for workout history loading state.
 * Mimics the structure of WorkoutHistoryCard with shimmer placeholders.
 */
@Composable
fun WorkoutHistoryCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(responsiveDimension(160.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Material 3 Expressive: Higher elevation (was 4dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.medium)
        ) {
            // Header section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Icon placeholder
                    ShimmerBox(
                        modifier = Modifier.size(responsiveDimension(48.dp))
                    )

                    Spacer(modifier = Modifier.width(Spacing.medium))

                    Column {
                        // Exercise name placeholder
                        ShimmerBox(
                            modifier = Modifier
                                .width(responsiveDimension(120.dp))
                                .height(responsiveDimension(20.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // Mode placeholder
                        ShimmerBox(
                            modifier = Modifier
                                .width(responsiveDimension(80.dp))
                                .height(responsiveDimension(16.dp))
                        )
                    }
                }

                // Date placeholder
                ShimmerBox(
                    modifier = Modifier
                        .width(responsiveDimension(60.dp))
                        .height(responsiveDimension(16.dp))
                )
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Stats placeholders
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ShimmerBox(
                            modifier = Modifier
                                .width(responsiveDimension(40.dp))
                                .height(responsiveDimension(24.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ShimmerBox(
                            modifier = Modifier
                                .width(responsiveDimension(50.dp))
                                .height(responsiveDimension(14.dp))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Skeleton card for personal record loading state.
 */
@Composable
fun PersonalRecordCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(responsiveDimension(100.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Material 3 Expressive: Higher elevation (was 4dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Rank placeholder
                ShimmerBox(
                    modifier = Modifier.size(responsiveDimension(40.dp))
                )

                Spacer(modifier = Modifier.width(Spacing.medium))

                Column {
                    // Exercise name placeholder
                    ShimmerBox(
                        modifier = Modifier
                            .width(responsiveDimension(140.dp))
                            .height(responsiveDimension(20.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Weight placeholder
                    ShimmerBox(
                        modifier = Modifier
                            .width(responsiveDimension(100.dp))
                            .height(responsiveDimension(18.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Details placeholder
                    ShimmerBox(
                        modifier = Modifier
                            .width(responsiveDimension(120.dp))
                            .height(responsiveDimension(14.dp))
                    )
                }
            }
        }
    }
}

/**
 * Skeleton for routine card loading state.
 */
@Composable
fun RoutineCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(responsiveDimension(140.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Material 3 Expressive: Higher elevation (was 4dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.medium)
        ) {
            // Title placeholder
            ShimmerBox(
                modifier = Modifier
                    .width(responsiveDimension(160.dp))
                    .height(responsiveDimension(24.dp))
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Description placeholders
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(responsiveDimension(16.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(responsiveDimension(16.dp))
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Exercise count placeholder
            ShimmerBox(
                modifier = Modifier
                    .width(responsiveDimension(100.dp))
                    .height(responsiveDimension(14.dp))
            )
        }
    }
}
