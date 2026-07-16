package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile

val ProfileColors = listOf(
    Color(0xFF3B82F6),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFF06B6D4),
    Color(0xFFF97316),
)

const val PROFILE_COLOR_COUNT = 8

internal fun suggestedProfileColorIndex(profileCount: Int): Int =
    profileCount.coerceAtLeast(0) % PROFILE_COLOR_COUNT

internal fun normalizedProfileColorIndex(colorIndex: Int): Int =
    colorIndex.takeIf(ProfileColors.indices::contains) ?: 0

internal fun canDeleteProfile(profile: UserProfile): Boolean =
    profile.id != "default" && profile.isActive

internal fun profileInitialsColor(background: Color): Color {
    val luminance = background.luminance()
    val blackContrast = (luminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (luminance + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

@Composable
fun ProfileAvatar(
    profile: UserProfile,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val accessibleName = profile.name.trim().ifEmpty { "?" }
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = accessibleName }
    }
    val background = ProfileColors[normalizedProfileColorIndex(profile.colorIndex)]

    Box(
        modifier = modifier.then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(size)
                .shadow(if (isActive) 8.dp else 0.dp, CircleShape)
                .clearAndSetSemantics { },
            shape = CircleShape,
            color = background,
        ) {
            Text(
                text = accessibleName.take(1).uppercase(),
                color = profileInitialsColor(background),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.wrapContentSize(Alignment.Center),
            )
        }
    }
}
