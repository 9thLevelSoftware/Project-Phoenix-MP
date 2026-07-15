package com.devil.phoenixproject.presentation.components

/**
 * Presentation policy for the adult-mode dialogs. This stays Compose-free so the
 * visual contract can be covered by fast common tests while SettingsTab renders
 * the concrete Material 3 components.
 */
data class AdultModePresentation(
    val containerTone: AdultModeDialogTone,
    val actionLayout: AdultModeActionLayout,
    val confirmTone: AdultModeActionTone,
    val declineTone: AdultModeActionTone? = null,
    val usesBrandAccent: Boolean,
    val usesBespokePinkAccent: Boolean,
) {
    companion object {
        fun adultsOnlyConfirmation(): AdultModePresentation = AdultModePresentation(
            containerTone = AdultModeDialogTone.ThemeSurface,
            actionLayout = AdultModeActionLayout.StackedFullWidth,
            confirmTone = AdultModeActionTone.Primary,
            declineTone = AdultModeActionTone.Outline,
            usesBrandAccent = true,
            usesBespokePinkAccent = false,
        )

        fun dominatrixUnlock(): AdultModePresentation = AdultModePresentation(
            containerTone = AdultModeDialogTone.ThemeSurface,
            actionLayout = AdultModeActionLayout.SinglePrimary,
            confirmTone = AdultModeActionTone.Primary,
            declineTone = null,
            usesBrandAccent = true,
            usesBespokePinkAccent = false,
        )

    }
}

enum class AdultModeDialogTone {
    ThemeSurface,
}

enum class AdultModeActionLayout {
    StackedFullWidth,
    SinglePrimary,
}

enum class AdultModeActionTone {
    Primary,
    Outline,
}
