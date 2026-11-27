package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_close

private val MODAL_DIALOG_MAX_WIDTH = 560.dp

/**
 * Standard Phoenix elevated section card using [MaterialTheme.colorScheme.surfaceContainerHighest],
 * [MaterialTheme.shapes.medium], 2dp card elevation, and a subtle outline border without any
 * stacked [Modifier.shadow].
 *
 * When [maxWidth] is provided, the card is centered horizontally and bounded to [maxWidth].
 */
@Composable
fun PhoenixSectionCard(
    modifier: Modifier = Modifier,
    maxWidth: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.medium),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
        Card(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }

    if (maxWidth != null) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            cardContent(
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .then(modifier),
            )
        }
    } else {
        cardContent(Modifier.fillMaxWidth().then(modifier))
    }
}

/**
 * 48dp rounded-medium icon badge for section headers with accessible M3 container/content
 * color contrast across Dark, Light, and Dynamic Color themes.
 */
@Composable
fun SectionHeaderIconBadge(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Settings action block that places optional [title] and [description] above a custom [cta]
 * slot so explanatory context always precedes the action control.
 */
@Composable
fun SettingsActionRow(
    description: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    cta: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        cta()
    }
}

/**
 * Settings action row with a standard full-width outlined action button (`heightIn(min = 48.dp)`)
 * rendered below its [description].
 */
@Composable
fun SettingsActionRow(
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val borderColor = if (enabled) {
        contentColor
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
    }
    SettingsActionRow(
        description = description,
        modifier = modifier,
        title = title,
    ) {
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = contentColor,
            ),
            border = BorderStroke(1.5.dp, borderColor),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.small))
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Responsive single-choice selector that uses [SingleChoiceSegmentedButtonRow] when there are
 * at most 3 options and accessibility layout pressure is low, and wraps cleanly into a [FlowRow]
 * of [FilterChip]s when compact/scaled or when there are more than 3 options so labels never
 * clip or break mid-word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ResponsiveSegmentedSelector(
    options: List<T>,
    selectedOption: T,
    onSelect: (T) -> Unit,
    labelForOption: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    val useSegmentedRow = options.size <= 3 && !isCompactAccessibilityLayout()
    if (useSegmentedRow) {
        SingleChoiceSegmentedButtonRow(
            modifier = modifier.fillMaxWidth(),
        ) {
            options.forEachIndexed { index, option ->
                val label = labelForOption(option)
                SegmentedButton(
                    selected = option == selectedOption,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            text = label,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            options.forEach { option ->
                val label = labelForOption(option)
                FilterChip(
                    selected = option == selectedOption,
                    onClick = { onSelect(option) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            text = label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Standard pinned header for bottom sheets and modal dialogs, featuring a [title],
 * optional [subtitle], and a 48dp minimum touch-target close button.
 */
@Composable
fun PhoenixSheetHeader(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.medium, end = Spacing.small, top = Spacing.small, bottom = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Responsive modal dialog shell (`usePlatformDefaultWidth = false`, `widthIn(max = 560.dp)`)
 * with a pinned [PhoenixSheetHeader], scrollable body, and a pinned equal-height action row
 * (`heightIn(min = 48.dp)`).
 */
@Composable
fun PhoenixModalDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissOnClickOutside: Boolean = true,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val windowSizeClass = LocalWindowSizeClass.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.large),
            contentAlignment = Alignment.Center,
        ) {
            // On iOS, Dialog(usePlatformDefaultWidth = false) measures with Infinity width;
            // fall back to the captured WindowSizeClass width so children get finite bounds.
            val fallbackWidth = (windowSizeClass.widthDp - Spacing.medium * 2).coerceAtLeast(280.dp)
            val widthModifier = if (constraints.hasBoundedWidth) {
                Modifier
                    .widthIn(max = MODAL_DIALOG_MAX_WIDTH)
                    .fillMaxWidth()
            } else {
                Modifier.width(minOf(MODAL_DIALOG_MAX_WIDTH, fallbackWidth))
            }
            val maxDialogHeight = if (constraints.hasBoundedHeight) {
                maxHeight
            } else {
                (windowSizeClass.heightDp * 0.9f).coerceAtLeast(280.dp)
            }

            Surface(
                modifier = widthModifier
                    .then(modifier)
                    .heightIn(max = maxDialogHeight),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PhoenixSheetHeader(
                        title = title,
                        onClose = onDismiss,
                        subtitle = subtitle,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        content = content,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/**
 * Convenience overload of [PhoenixModalDialogFrame] rendering equal-height primary confirm and
 * optional secondary dismiss buttons (`heightIn(min = 48.dp)`).
 */
@Composable
fun PhoenixModalDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissLabel: String? = null,
    confirmEnabled: Boolean = true,
    isDestructive: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    PhoenixModalDialogFrame(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = subtitle,
        dismissOnClickOutside = dismissOnClickOutside,
        actions = {
            if (!dismissLabel.isNullOrBlank()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        text = dismissLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = confirmLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        content = content,
    )
}
