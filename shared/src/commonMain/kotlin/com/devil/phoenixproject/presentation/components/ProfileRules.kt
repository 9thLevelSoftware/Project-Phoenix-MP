package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.repository.UserProfile

const val PROFILE_COLOR_COUNT = 8

internal fun suggestedProfileColorIndex(profileCount: Int): Int =
    profileCount.coerceAtLeast(0) % PROFILE_COLOR_COUNT

internal fun normalizedProfileColorIndex(colorIndex: Int): Int =
    colorIndex.takeIf { it in 0 until PROFILE_COLOR_COUNT } ?: 0

internal fun canDeleteProfile(profile: UserProfile): Boolean =
    profile.id != "default" && profile.isActive
