package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.FormViolation
import com.devil.phoenixproject.domain.model.FormViolationSeverity

/**
 * Displays the highest-severity form violation as a corrective cue banner.
 * Shows during active sets when form check is enabled and violations are detected.
 *
 * Follows WCAG AA 1.4.1: Uses icon + text alongside color (BOARD-02 pattern).
 */
@Composable
fun FormWarningBanner(
    violations: List<FormViolation>,
    modifier: Modifier = Modifier
) {
    val topViolation = violations.maxByOrNull { it.severity.ordinal }

    AnimatedVisibility(
        visible = topViolation != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        topViolation?.let { violation ->
            val backgroundColor = when (violation.severity) {
                FormViolationSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
                FormViolationSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                FormViolationSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when (violation.severity) {
                FormViolationSeverity.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
                FormViolationSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
                FormViolationSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = backgroundColor,
                    contentColor = contentColor
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = when (violation.severity) {
                            FormViolationSeverity.CRITICAL -> "Critical form warning"
                            FormViolationSeverity.WARNING -> "Form warning"
                            FormViolationSeverity.INFO -> "Form info"
                        },
                        modifier = Modifier.size(20.dp),
                        tint = contentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = violation.correctiveCue,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}
