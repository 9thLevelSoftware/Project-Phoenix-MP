plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    tvosArm64 {
        binaries.framework {
            baseName = "PhoenixTvCore"
            isStatic = true
        }
    }
    tvosSimulatorArm64 {
        binaries.framework {
            baseName = "PhoenixTvCore"
            isStatic = true
        }
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
            // Execute the authoritative command/limit tests on the tvOS runtime too,
            // without pulling phone UI tests or duplicating their implementations.
            kotlin.srcDir("../shared/src/commonTest/kotlin")
            kotlin.include(
                "com/devil/phoenixproject/util/CommandLimitsTest.kt",
                "com/devil/phoenixproject/util/WorkoutCommandValidatorTest.kt",
                "com/devil/phoenixproject/data/sync/PortalWirePlatformTest.kt",
                "com/devil/phoenixproject/data/ble/CompositionGattFake.kt",
                "com/devil/phoenixproject/data/repository/KableGattCompositionTest.kt",
                "com/devil/phoenixproject/presentation/tv/TvSessionExitPolicyTest.kt",
                "com/devil/phoenixproject/presentation/tv/TvRemoteControlPolicyTest.kt",
                "com/devil/phoenixproject/presentation/tv/TvTelemetryWatchdogTest.kt",
                "com/devil/phoenixproject/tvnative/**",
            )
        }
        val commonMain by getting {
            kotlin.srcDir("../shared/src/commonMain/kotlin")
            dependencies {
                implementation(libs.cmp.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kable.atomicfu.runtime)
                implementation(libs.koin.core)
                implementation(libs.koin.core.viewmodel)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.coroutines)
                implementation(libs.kermit)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(project(":third_party:kable-core-tvos"))
            }
        }

        val tvosSimulatorArm64Test by getting {
            // Re-run the original phone Phantom contracts against the same native
            // fixture source, so TV's opt-in replay cannot silently change defaults.
            kotlin.srcDir("../shared/src/iosSimulatorArm64Test/kotlin")
            dependencies { implementation(libs.turbine) }
        }
        val tvosArm64Main by getting
        val tvosSimulatorArm64Main by getting {
            // Reuse the deterministic Phantom BLE transport only in the simulator target.
            // It cannot enter the tvosArm64 device framework or a TestFlight archive.
            kotlin.srcDir("../shared/src/iosSimulatorArm64Main/kotlin")
        }
        val tvosMain by creating {
            dependsOn(commonMain)
            tvosArm64Main.dependsOn(this)
            tvosSimulatorArm64Main.dependsOn(this)
            kotlin.srcDir("../shared/src/tvosMain/kotlin")
            dependencies {
                implementation(libs.sqldelight.native.driver)
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

val phoneShared = project(":shared")
val generatedDatabaseCode = phoneShared.layout.buildDirectory.dir(
    "generated/sqldelight/code/PhoenixDatabase/commonMain",
)
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generatedDatabaseCode)
}

val generateDatabaseInterface = phoneShared.tasks.named("generateCommonMainPhoenixDatabaseInterface")
tasks.matching {
    it.name == "compileKotlinTvosArm64" || it.name == "compileKotlinTvosSimulatorArm64"
}.configureEach {
    dependsOn(generateDatabaseInterface)
}
