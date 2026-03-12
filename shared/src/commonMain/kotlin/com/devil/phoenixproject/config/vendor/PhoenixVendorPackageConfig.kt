package com.devil.phoenixproject.config.vendor

import com.devil.phoenixproject.config.AssetOverrideConfig
import com.devil.phoenixproject.config.BrandingConfig
import com.devil.phoenixproject.config.LegalConfig
import com.devil.phoenixproject.config.SupportConfig
import com.devil.phoenixproject.config.VendorPackageConfig

private data object PhoenixBrandingConfig : BrandingConfig {
    override val appDisplayName: String = "Project Phoenix"
    override val appTagline: String = "Train smarter. Lift stronger."
    override val trainerDisplayName: String = "Vitruvian Trainer"
    override val blePermissionTitle: String = "Bluetooth Permission Required"
    override val blePermissionBody: String =
        "$appDisplayName needs Bluetooth permission to scan for and connect to your $trainerDisplayName machine."
    override val blePermissionDeniedBody: String =
        "Bluetooth permission is required to connect to your $trainerDisplayName. Please grant the permission to continue, or enable it in your device's Settings app."
    override val blePermissionSettingsHint: String =
        "If the permission dialog doesn't appear, you may need to enable Bluetooth permissions in your device's Settings > Apps > $appDisplayName > Permissions."
}

private data object PhoenixLegalConfig : LegalConfig {
    override val eulaVersion: String = "1.0"
    override val privacyPolicyUrl: String = "https://project-phoenix.app/privacy-policy"
    override val termsOfServiceUrl: String = "https://project-phoenix.app/terms"
}

private data object PhoenixSupportConfig : SupportConfig {
    override val supportEmail: String = "support@project-phoenix.app"
    override val supportUrl: String = "https://project-phoenix.app/support"
    override val feedbackUrl: String = "https://project-phoenix.app/feedback"
}

private val PhoenixAssetOverrideConfig = AssetOverrideConfig(
    overrideLogoAssets = false,
    overrideThemeColors = false,
    overrideSoundPack = false,
    logoAssetPath = null,
    primaryColorHex = null,
    secondaryColorHex = null,
    soundPackId = null
)

data object PhoenixVendorPackageConfig : VendorPackageConfig {
    override val branding: BrandingConfig = PhoenixBrandingConfig
    override val legal: LegalConfig = PhoenixLegalConfig
    override val support: SupportConfig = PhoenixSupportConfig
    override val assets: AssetOverrideConfig = PhoenixAssetOverrideConfig
}
