import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // Global opt-ins for experimental APIs
    sourceSets.all {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    // Suppress expect/actual classes Beta warning
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Android target (AGP 9.0 new DSL)
    androidLibrary {
        namespace = "com.devil.phoenixproject.shared"
        compileSdk = 36
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }

        withHostTest {}
    }

    // iOS target (iosArm64 only - physical devices for distribution)
    val xcf = XCFramework()
    iosArm64 {
        binaries.framework {
            baseName = "shared"
            isStatic = true
            xcf.add(this)
            // Link system frameworks required by shared module
            linkerOpts("-framework", "HealthKit")
            linkerOpts("-framework", "Speech")
        }
        binaries.all {
            freeCompilerArgs += listOf("-Xadd-light-debug=enable")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose Multiplatform
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.material3)
                implementation(libs.cmp.material.icons.extended)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.components.resources)

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

        getByName("androidHostTest") {
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
                implementation(libs.cmp.ui.tooling)

                // Activity Compose (for file picker Activity Result APIs)
                implementation(libs.androidx.activity.compose)

                // Encrypted SharedPreferences for secure token storage
                implementation(libs.androidx.security.crypto)

                // Health Connect (Google Health)
                // Pinned to alpha11: stable 1.1.0 made Metadata constructor internal,
                // breaking ExerciseSessionRecord/TotalCaloriesBurnedRecord creation.
                // TODO: Migrate to stable API when Google provides a public record builder.
                implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

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

sqldelight {
    databases {
        create("VitruvianDatabase") {
            packageName.set("com.devil.phoenixproject.database")
            // Version 25 = initial schema (1) + 24 migrations (1.sqm through 24.sqm)
            version = 25
        }
    }
}
