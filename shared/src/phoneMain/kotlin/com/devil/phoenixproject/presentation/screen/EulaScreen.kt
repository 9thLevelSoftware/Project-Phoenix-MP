package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.eula_accept_button
import projectphoenix.shared.generated.resources.eula_age_certification
import projectphoenix.shared.generated.resources.eula_app_name_label
import projectphoenix.shared.generated.resources.eula_confirm_age_to_continue
import projectphoenix.shared.generated.resources.eula_developer_label
import projectphoenix.shared.generated.resources.eula_developer_name
import projectphoenix.shared.generated.resources.eula_end_of_agreement
import projectphoenix.shared.generated.resources.eula_must_accept
import projectphoenix.shared.generated.resources.eula_scroll_down_prompt
import projectphoenix.shared.generated.resources.eula_scroll_to_continue
import projectphoenix.shared.generated.resources.eula_section_affiliation_body
import projectphoenix.shared.generated.resources.eula_section_affiliation_bullet_1
import projectphoenix.shared.generated.resources.eula_section_affiliation_bullet_2
import projectphoenix.shared.generated.resources.eula_section_affiliation_title
import projectphoenix.shared.generated.resources.eula_section_arbitration_body
import projectphoenix.shared.generated.resources.eula_section_arbitration_title
import projectphoenix.shared.generated.resources.eula_section_arbitration_waiver
import projectphoenix.shared.generated.resources.eula_section_indemnification_body
import projectphoenix.shared.generated.resources.eula_section_indemnification_title
import projectphoenix.shared.generated.resources.eula_section_liability_body
import projectphoenix.shared.generated.resources.eula_section_liability_title
import projectphoenix.shared.generated.resources.eula_section_medical_body
import projectphoenix.shared.generated.resources.eula_section_medical_title
import projectphoenix.shared.generated.resources.eula_section_risk_assumption
import projectphoenix.shared.generated.resources.eula_section_risk_bullet_1
import projectphoenix.shared.generated.resources.eula_section_risk_bullet_2
import projectphoenix.shared.generated.resources.eula_section_risk_highlight
import projectphoenix.shared.generated.resources.eula_section_risk_intro
import projectphoenix.shared.generated.resources.eula_section_risk_title
import projectphoenix.shared.generated.resources.eula_section_warranty_body
import projectphoenix.shared.generated.resources.eula_section_warranty_title
import projectphoenix.shared.generated.resources.eula_title
import projectphoenix.shared.generated.resources.nav_app_fallback

/**
 * Full-screen EULA acceptance screen.
 *
 * Features:
 * - Scroll-to-accept: Accept button is disabled until user scrolls to bottom
 * - Age gate: Checkbox certifying user is 18+ years old
 * - Full EULA text with proper legal formatting
 *
 * @param onAccept Callback when user accepts the EULA
 */
@Composable
fun EulaScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ageConfirmed by rememberSaveable { mutableStateOf(false) }
    var hasScrolledToBottom by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Latch scroll completion once the user reaches the bottom so slight scroll-up
    // does not re-disable Continue.
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (!hasScrolledToBottom &&
            (scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue - 50)
        ) {
            hasScrolledToBottom = true
        }
    }

    val canAccept = ageConfirmed && hasScrolledToBottom

    val bgBrush = screenBackgroundBrush()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp),
            ) {
            // Title
            Text(
                text = stringResource(Res.string.eula_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )

            // Scrollable EULA content
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                ) {
                    EulaContent()

                    // Scroll indicator at bottom
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.eula_end_of_agreement),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Scroll hint (shown until user scrolls to bottom)
            if (!hasScrolledToBottom) {
                Text(
                    text = stringResource(Res.string.eula_scroll_down_prompt),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Age confirmation checkbox — full Row is clickable per M3 labeled-checkbox pattern
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = ageConfirmed,
                        role = Role.Checkbox,
                        onValueChange = { ageConfirmed = it },
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = ageConfirmed,
                    onCheckedChange = null, // Row's toggleable handles semantics and clicks
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.eula_age_certification),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accept button
            Button(
                onClick = onAccept,
                enabled = canAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (canAccept) {
                        stringResource(Res.string.eula_accept_button)
                    } else {
                        if (!hasScrolledToBottom) {
                            stringResource(Res.string.eula_scroll_to_continue)
                        } else {
                            stringResource(Res.string.eula_confirm_age_to_continue)
                        }
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cannot proceed without accepting
            Text(
                text = stringResource(Res.string.eula_must_accept),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            }
        }
    }
}

/**
 * The full EULA content formatted for display.
 */
@Composable
private fun EulaContent() {
    // Capture theme colors here — needed inside buildAnnotatedString lambdas (non-@Composable scope)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val errorColor = AccessibilityTheme.colors.error

    val appNameLabel = stringResource(Res.string.eula_app_name_label)
    val appTitle = stringResource(Res.string.nav_app_fallback)
    val developerLabel = stringResource(Res.string.eula_developer_label)
    val developerName = stringResource(Res.string.eula_developer_name)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // App info header
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = onSurfaceVariant)) {
                    append(appNameLabel)
                }
                withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.Bold)) {
                    append(appTitle)
                }
                append("\n")
                withStyle(SpanStyle(color = onSurfaceVariant)) {
                    append(developerLabel)
                }
                withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.Bold)) {
                    append(developerName)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider(color = onSurfaceVariant.copy(alpha = 0.3f))

        // Section 1: Medical Warning
        EulaSection(
            number = "1",
            title = stringResource(Res.string.eula_section_medical_title),
            content = stringResource(Res.string.eula_section_medical_body),
            isWarning = true,
        )

        // Section 2: Assumption of Risk
        EulaSection(
            number = "2",
            title = stringResource(Res.string.eula_section_risk_title),
            content = null,
            isWarning = true,
        )
        Text(
            text = stringResource(Res.string.eula_section_risk_highlight),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = errorColor,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = stringResource(Res.string.eula_section_risk_intro),
            style = MaterialTheme.typography.bodyMedium.copy(color = onSurface),
        )
        BulletPoint(stringResource(Res.string.eula_section_risk_bullet_1))
        BulletPoint(stringResource(Res.string.eula_section_risk_bullet_2))
        Text(
            text = stringResource(Res.string.eula_section_risk_assumption),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = errorColor,
                fontWeight = FontWeight.Bold,
            ),
        )

        // Section 3: No Affiliation
        EulaSection(
            number = "3",
            title = stringResource(Res.string.eula_section_affiliation_title),
            content = null,
        )
        Text(
            text = stringResource(Res.string.eula_section_affiliation_body),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = onSurface,
                fontWeight = FontWeight.Bold,
            ),
        )
        BulletPoint(stringResource(Res.string.eula_section_affiliation_bullet_1))
        BulletPoint(stringResource(Res.string.eula_section_affiliation_bullet_2))

        // Section 4: Disclaimer of Warranties
        EulaSection(
            number = "4",
            title = stringResource(Res.string.eula_section_warranty_title),
            content = stringResource(Res.string.eula_section_warranty_body),
            isAllCaps = true,
        )

        // Section 5: Limitation of Liability
        EulaSection(
            number = "5",
            title = stringResource(Res.string.eula_section_liability_title),
            content = stringResource(Res.string.eula_section_liability_body),
            isAllCaps = true,
        )

        // Section 6: Indemnification
        EulaSection(
            number = "6",
            title = stringResource(Res.string.eula_section_indemnification_title),
            content = stringResource(Res.string.eula_section_indemnification_body),
        )

        // Section 7: Arbitration
        EulaSection(
            number = "7",
            title = stringResource(Res.string.eula_section_arbitration_title),
            content = null,
        )
        Text(
            text = stringResource(Res.string.eula_section_arbitration_body),
            style = MaterialTheme.typography.bodyMedium.copy(color = onSurface),
        )
        Text(
            text = stringResource(Res.string.eula_section_arbitration_waiver),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = errorColor,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * A single EULA section with number, title, and content.
 */
@Composable
private fun EulaSection(
    number: String,
    title: String,
    content: String?,
    isWarning: Boolean = false,
    isAllCaps: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$number. $title",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isWarning) AccessibilityTheme.colors.error else MaterialTheme.colorScheme.primary,
            ),
        )
        if (content != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isAllCaps) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
    }
}

/**
 * A bullet point item.
 */
@Composable
private fun BulletPoint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        )
    }
}
