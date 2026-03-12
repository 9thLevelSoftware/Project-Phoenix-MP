package com.devil.phoenixproject.di

import com.devil.phoenixproject.config.AssetOverrideConfig
import com.devil.phoenixproject.config.BrandingConfig
import com.devil.phoenixproject.config.LegalConfig
import com.devil.phoenixproject.config.SupportConfig
import com.devil.phoenixproject.config.VendorPackageConfig
import com.devil.phoenixproject.config.vendor.PhoenixVendorPackageConfig
import org.koin.core.module.Module
import org.koin.dsl.module

expect val platformModule: Module

val appModule = module {
    single<VendorPackageConfig> { PhoenixVendorPackageConfig }
    single<BrandingConfig> { get<VendorPackageConfig>().branding }
    single<LegalConfig> { get<VendorPackageConfig>().legal }
    single<SupportConfig> { get<VendorPackageConfig>().support }
    single<AssetOverrideConfig> { get<VendorPackageConfig>().assets }
    includes(dataModule, syncModule, domainModule, presentationModule)
}
