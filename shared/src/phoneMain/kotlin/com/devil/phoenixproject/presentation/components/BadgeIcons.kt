package com.devil.phoenixproject.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.devil.phoenixproject.domain.model.BadgeCategory

internal fun getCategoryIcon(category: BadgeCategory): ImageVector = when (category) {
    BadgeCategory.CONSISTENCY -> Icons.Default.LocalFireDepartment
    BadgeCategory.STRENGTH -> Icons.Default.EmojiEvents
    BadgeCategory.VOLUME -> Icons.Default.Repeat
    BadgeCategory.EXPLORER -> Icons.Default.Explore
    BadgeCategory.DEDICATION -> Icons.Default.FitnessCenter
}

internal fun getBadgeIcon(iconResource: String): ImageVector = when (iconResource) {
    "fire" -> Icons.Default.LocalFireDepartment
    "trophy" -> Icons.Default.EmojiEvents
    "dumbbell" -> Icons.Default.FitnessCenter
    "repeat" -> Icons.Default.Repeat
    "compass" -> Icons.Default.Explore
    "calendar" -> Icons.Default.CalendarMonth
    "sun" -> Icons.Default.WbSunny
    "moon" -> Icons.Default.NightsStay
    "weight" -> Icons.Default.FitnessCenter
    "lightning" -> Icons.Default.Bolt
    "body" -> Icons.Default.Accessibility
    "phoenix" -> Icons.Default.LocalFireDepartment // Phoenix uses fire icon
    "shield" -> Icons.Default.Shield
    "list" -> Icons.Default.Checklist
    else -> Icons.Default.Star
}
