package com.devil.phoenixproject.config

interface BrandingConfig {
    val appDisplayName: String
    val appTagline: String
    val trainerDisplayName: String
    val blePermissionTitle: String
    val blePermissionBody: String
    val blePermissionDeniedBody: String
    val blePermissionSettingsHint: String
}

interface LegalConfig {
    val eulaVersion: String
    val privacyPolicyUrl: String
    val termsOfServiceUrl: String
}

interface SupportConfig {
    val supportEmail: String
    val supportUrl: String
    val feedbackUrl: String
}

data class AssetOverrideConfig(
    val overrideLogoAssets: Boolean,
    val overrideThemeColors: Boolean,
    val overrideSoundPack: Boolean,
    val logoAssetPath: String? = null,
    val primaryColorHex: String? = null,
    val secondaryColorHex: String? = null,
    val soundPackId: String? = null
)

interface VendorPackageConfig {
    val branding: BrandingConfig
    val legal: LegalConfig
    val support: SupportConfig
    val assets: AssetOverrideConfig
}
