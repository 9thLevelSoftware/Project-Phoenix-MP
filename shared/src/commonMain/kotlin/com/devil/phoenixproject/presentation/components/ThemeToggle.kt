package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import com.devil.phoenixproject.ui.theme.ThemeMode

/**
 * Compact icon-only theme toggle.
 * Toggles between Light and Dark modes only.
 */
@Composable
fun ThemeToggle(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = {
            // Toggle between Light and Dark only
            val nextMode = when (mode) {
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.LIGHT
                ThemeMode.SYSTEM -> ThemeMode.LIGHT // If somehow in SYSTEM, go to LIGHT
            }
            onModeChange(nextMode)
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = when (mode) {
                ThemeMode.LIGHT -> Icons.Default.LightMode
                ThemeMode.DARK -> Icons.Default.DarkMode
                ThemeMode.SYSTEM -> Icons.Default.LightMode // Fallback
            },
            contentDescription = "Toggle theme (current: ${mode.name.lowercase()})",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
