package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.ThemeMode
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Compact icon-only theme toggle.
 * Cycles through System, Light, and Dark modes.
 */
@Composable
fun ThemeToggle(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val nextMode = nextThemeModeAfterToggle(mode)
    IconButton(
        onClick = {
            onModeChange(nextMode)
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = when (mode) {
                ThemeMode.LIGHT -> Icons.Default.LightMode
                ThemeMode.DARK -> Icons.Default.DarkMode
                ThemeMode.SYSTEM -> Icons.Default.Settings
            },
            contentDescription = stringResource(
                Res.string.cd_toggle_theme_cycle,
                mode.name.lowercase(),
                nextMode.name.lowercase(),
            ),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun nextThemeModeAfterToggle(mode: ThemeMode): ThemeMode = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}
