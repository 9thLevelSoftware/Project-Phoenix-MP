package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Theme colors matching the app
private val DarkSlate = Color(0xFF0F172A)
private val DeepNavy = Color(0xFF1E293B)
private val FireOrange = Color(0xFFFF6B35)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val SurfaceColor = Color(0xFF1E293B)
private val WarningRed = Color(0xFFEF4444)

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
    modifier: Modifier = Modifier
) {
    var ageConfirmed by remember { mutableStateOf(false) }
    var hasScrolledToBottom by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Track when user has scrolled to the bottom
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            // Consider "scrolled to bottom" when within 50px of the end
            hasScrolledToBottom = scrollState.value >= (scrollState.maxValue - 50)
        }
    }

    val canAccept = ageConfirmed && hasScrolledToBottom

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkSlate, DeepNavy, DarkSlate)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "End User License Agreement (EULA),\nSafety Disclaimer, and Assumption of Risk",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = FireOrange,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            // Scrollable EULA content
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                ) {
                    EulaContent()

                    // Scroll indicator at bottom
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "— End of Agreement —",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Scroll hint (shown until user scrolls to bottom)
            if (!hasScrolledToBottom) {
                Text(
                    text = "↓ Scroll down to read the full agreement ↓",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = FireOrange,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Age confirmation checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = ageConfirmed,
                    onCheckedChange = { ageConfirmed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = FireOrange,
                        uncheckedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "I certify that I am at least 18 years of age.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accept button
            Button(
                onClick = onAccept,
                enabled = canAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FireOrange,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (canAccept) "I ACCEPT" else
                        if (!hasScrolledToBottom) "SCROLL TO BOTTOM TO CONTINUE"
                        else "CONFIRM AGE TO CONTINUE",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cannot proceed without accepting
            Text(
                text = "You must accept these terms to use Project Phoenix.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The full EULA content formatted for display.
 */
@Composable
private fun EulaContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App info header
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append("App Name: ")
                }
                withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold)) {
                    append("Project Phoenix")
                }
                append("\n")
                withStyle(SpanStyle(color = TextSecondary)) {
                    append("Developer: ")
                }
                withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold)) {
                    append("9th Level Software")
                }
            },
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider(color = TextSecondary.copy(alpha = 0.3f))

        // Section 1: Medical Warning
        EulaSection(
            number = "1",
            title = "MEDICAL WARNING AND DISCLAIMER",
            content = """CONSULT A PHYSICIAN BEFORE USE. The content and functionality provided by Project Phoenix are for informational and entertainment purposes only. You should consult your physician or other health care professional before starting this or any other fitness program to determine if it is right for your needs. Do not use Project Phoenix if your physician or health care provider advises against it. If you experience faintness, dizziness, pain, or shortness of breath at any time while exercising, you should stop immediately.""",
            isWarning = true
        )

        // Section 2: Assumption of Risk
        EulaSection(
            number = "2",
            title = "VOLUNTARY ASSUMPTION OF RISK",
            content = null,
            isWarning = true
        )
        Text(
            text = "READ CAREFULLY: PROJECT PHOENIX CONTROLS THIRD-PARTY HARDWARE CAPABLE OF GENERATING SIGNIFICANT PHYSICAL FORCE.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = WarningRed,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = """By using Project Phoenix, you acknowledge that resistance training involves inherent risks of serious injury, permanent disability, paralysis, and death. You explicitly acknowledge that software-controlled resistance equipment carries unique risks, including but not limited to:""",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
        )
        BulletPoint("Sudden Resistance Changes: Software bugs, Bluetooth latency, or connection drops may cause the equipment to apply sudden, unexpected force or fail to release force when required.")
        BulletPoint("Hardware Unresponsiveness: The \"safety features\" of the hardware (e.g., spotting) may fail to engage due to communication errors between this App and the hardware.")
        Text(
            text = "YOU KNOWINGLY AND FREELY ASSUME ALL SUCH RISKS, both known and unknown, even if arising from the negligence of the Developer, and assume full responsibility for your participation.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = WarningRed,
                fontWeight = FontWeight.Bold
            )
        )

        // Section 3: No Affiliation
        EulaSection(
            number = "3",
            title = "NO AFFILIATION (THIRD-PARTY HARDWARE)",
            content = null
        )
        Text(
            text = "Project Phoenix is an independent, community-developed project. IT IS NOT AFFILIATED WITH, ENDORSED BY, AUTHORIZED BY, OR SUPPORTED BY VITRUVIAN INVESTMENTS PTY LTD (IN LIQUIDATION), MANAGED BY MERCHANTS ADVISORY, OR ANY OTHER EQUIPMENT MANUFACTURER.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        )
        BulletPoint("You acknowledge that using this App may void your warranty with the equipment manufacturer.")
        BulletPoint("You acknowledge that the equipment manufacturer is not responsible for the performance of this App.")

        // Section 4: Disclaimer of Warranties
        EulaSection(
            number = "4",
            title = "DISCLAIMER OF WARRANTIES (\"AS IS\")",
            content = """TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE APPLICATION IS PROVIDED "AS IS" AND "AS AVAILABLE," WITH ALL FAULTS AND WITHOUT WARRANTY OF ANY KIND. 9TH LEVEL SOFTWARE HEREBY DISCLAIMS ALL WARRANTIES AND CONDITIONS WITH RESPECT TO THE APPLICATION, EITHER EXPRESS, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT.

WE DO NOT WARRANT THAT THE FUNCTIONS CONTAINED IN THE APPLICATION WILL MEET YOUR REQUIREMENTS, THAT THE OPERATION OF THE APPLICATION WILL BE UNINTERRUPTED OR ERROR-FREE, OR THAT DEFECTS IN THE APPLICATION WILL BE CORRECTED.""",
            isAllCaps = true
        )

        // Section 5: Limitation of Liability
        EulaSection(
            number = "5",
            title = "LIMITATION OF LIABILITY",
            content = """TO THE EXTENT NOT PROHIBITED BY LAW, IN NO EVENT SHALL 9TH LEVEL SOFTWARE BE LIABLE FOR PERSONAL INJURY, OR ANY INCIDENTAL, SPECIAL, INDIRECT, OR CONSEQUENTIAL DAMAGES WHATSOEVER, INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF DATA, BUSINESS INTERRUPTION, OR ANY OTHER COMMERCIAL DAMAGES OR LOSSES, ARISING OUT OF OR RELATED TO YOUR USE OR INABILITY TO USE THE APPLICATION, HOWEVER CAUSED, REGARDLESS OF THE THEORY OF LIABILITY (CONTRACT, TORT, OR OTHERWISE).""",
            isAllCaps = true
        )

        // Section 6: Indemnification
        EulaSection(
            number = "6",
            title = "INDEMNIFICATION",
            content = """You agree to indemnify, defend, and hold harmless 9th Level Software and its contributors from and against any and all claims, losses, liabilities, expenses, damages, and costs, including reasonable attorneys' fees, resulting from or arising out of your use of the App, your violation of these Terms, or any injury or damage caused to you or any third party during the use of the App."""
        )

        // Section 7: Arbitration
        EulaSection(
            number = "7",
            title = "BINDING ARBITRATION AND CLASS ACTION WAIVER",
            content = null
        )
        Text(
            text = """You and 9th Level Software agree that any dispute arising out of or relating to this Agreement or the App shall be legally settled by binding arbitration.""",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
        )
        Text(
            text = "YOU AGREE THAT YOU ARE WAIVING THE RIGHT TO A TRIAL BY JURY OR TO PARTICIPATE AS A PLAINTIFF OR CLASS MEMBER IN ANY CLASS ACTION PROCEEDING.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = WarningRed,
                fontWeight = FontWeight.Bold
            )
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
    isAllCaps: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$number. $title",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isWarning) WarningRed else FireOrange
            )
        )
        if (content != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary,
                    fontWeight = if (isAllCaps) FontWeight.Medium else FontWeight.Normal
                )
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
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = FireOrange,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
        )
    }
}
