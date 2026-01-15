import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // Global opt-ins for experimental APIs
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    // Suppress expect/actual classes Beta warning
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Android target
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iOS target (iosArm64 only - physical devices for distribution)
    val xcf = XCFramework()
    iosArm64 {
        binaries.framework {
            baseName = "shared"
            isStatic = true
            xcf.add(this)
        }
        binaries.all {
            freeCompilerArgs += listOf("-Xadd-light-debug=enable")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose Multiplatform
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)

                // Lifecycle ViewModel for Compose
                implementation(libs.androidx.lifecycle.viewmodel.compose)

                // Navigation Compose (Multiplatform)
                implementation(libs.androidx.navigation.compose)

                // SavedState
                implementation(libs.androidx.savedstate)

                // Kotlinx
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                // DI - Koin
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                // Database - SQLDelight
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)

                // Settings/Preferences
                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.coroutines)

                // Logging
                implementation(libs.kermit)

                // Image Loading - Coil 3 (Multiplatform)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)

                // Ktor Client (for Coil network and HTTP API)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                // BLE - Kable (Multiplatform)
                implementation(libs.kable.core)

                // Drag and Drop
                api(libs.reorderable)

                // Lottie Animations (Compose Multiplatform)
                implementation(libs.compottie)
                implementation(libs.compottie.resources)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.koin.test)
                implementation(libs.multiplatform.settings.test)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.truth)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.koin.test.junit4)
                implementation(libs.multiplatform.settings.test)
            }
        }
        
        val androidMain by getting {
            dependencies {
                // Android-specific Coroutines
                implementation(libs.kotlinx.coroutines.android)

                // SQLDelight Android Driver
                implementation(libs.sqldelight.android.driver)

                // Koin Android
                implementation(libs.koin.android)

                // Ktor OkHttp engine for Android
                implementation(libs.ktor.client.okhttp)

                // Charts - Vico (Android only)
                implementation(libs.vico.charts)

                // Media3 ExoPlayer (for HLS video playback)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.exoplayer.hls)
                implementation(libs.media3.ui)

                // Compose Preview Tooling (for @Preview in shared module)
                implementation(compose.uiTooling)

                // Activity Compose (for file picker Activity Result APIs)
                implementation(libs.androidx.activity.compose)
            }
        }
        
        val iosArm64Main by getting
        val iosArm64Test by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosArm64Main.dependsOn(this)

            dependencies {
                // SQLDelight Native Driver
                implementation(libs.sqldelight.native.driver)

                // Ktor Darwin engine for iOS
                implementation(libs.ktor.client.darwin)
            }
        }

        val iosTest by creating {
            dependsOn(commonTest)
            iosArm64Test.dependsOn(this)

            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }

    }
}

android {
    namespace = "com.devil.phoenixproject.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("VitruvianDatabase") {
            packageName.set("com.devil.phoenixproject.database")
            // Version 11 = initial schema (1) + 10 migrations (1.sqm through 10.sqm)
            // Explicit version ensures migration 10 runs on devices stuck at version 10
            version = 11
        }
    }
}
