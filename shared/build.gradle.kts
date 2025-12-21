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

    // iOS targets
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
        // Generate dSYM for release framework
        iosTarget.binaries.all {
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
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

                // Navigation Compose (Multiplatform)
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")

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
                implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")

                // BLE - Kable (Multiplatform)
                implementation(libs.kable.core)

                // Drag and Drop
                api(libs.reorderable)

                // Lottie Animations (Compose Multiplatform)
                implementation("io.github.alexzhirkevich:compottie:2.0.0-rc01")
                implementation("io.github.alexzhirkevich:compottie-resources:2.0.0-rc01")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
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

                // Compose Preview Tooling (for @Preview in shared module)
                implementation(compose.uiTooling)
            }
        }
        
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)

            dependencies {
                // SQLDelight Native Driver
                implementation(libs.sqldelight.native.driver)

                // Ktor Darwin engine for iOS
                implementation(libs.ktor.client.darwin)
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
        }
    }
}
