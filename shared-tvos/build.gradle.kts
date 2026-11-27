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
        // Native bridge composition tests use the real in-memory SQLDelight driver.
        // Unlike the Xcode app target, test executables must link system SQLite here.
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.multiplatform.settings.test)
                implementation(libs.ktor.client.mock)
            }
            // Execute the authoritative command/limit, TV controller, domain, workout-engine,
            // and sync tests on the tvOS runtime too, without pulling phone UI or source-grep tests.
            kotlin.srcDir("../shared/src/commonTest/kotlin")
            kotlin.include(
                "com/devil/phoenixproject/util/CommandLimitsTest.kt",
                "com/devil/phoenixproject/util/WorkoutCommandValidatorTest.kt",
                "com/devil/phoenixproject/data/ble/CompositionGattFake.kt",
                "com/devil/phoenixproject/data/repository/KableGattCompositionTest.kt",
                "com/devil/phoenixproject/data/sync/**",
                "com/devil/phoenixproject/presentation/tv/**",
                "com/devil/phoenixproject/presentation/manager/**",
                "com/devil/phoenixproject/domain/usecase/**",
                "com/devil/phoenixproject/domain/model/**",
                "com/devil/phoenixproject/testutil/**",
                "com/devil/phoenixproject/tvnative/**",
            )
            kotlin.exclude(
                "com/devil/phoenixproject/testutil/SourceFileReader.kt",
                "com/devil/phoenixproject/presentation/manager/DWSMLaunchOriginTest.kt",
                "com/devil/phoenixproject/presentation/manager/Issue673DropSetProductFlowTest.kt",
                "com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt",
                "com/devil/phoenixproject/presentation/manager/WorkoutMachineTeardownTest.kt",
                "com/devil/phoenixproject/domain/model/EssentialsExercisesTest.kt",
                "com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt",
                "com/devil/phoenixproject/data/sync/PortalApiClientProfilePreferenceLimitsTest.kt",
                "com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt",
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

        val tvosArm64Test by getting
        val tvosSimulatorArm64Test by getting {
            // Re-run the original phone Phantom contracts against the same native
            // fixture source, so TV's opt-in replay cannot silently change defaults.
            kotlin.srcDir("../shared/src/iosSimulatorArm64Test/kotlin")
        }
        val tvosTest by creating {
            dependsOn(commonTest)
            tvosArm64Test.dependsOn(this)
            tvosSimulatorArm64Test.dependsOn(this)
            kotlin.srcDir("../shared/src/tvosTest/kotlin")
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
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

    // CompositionGattFake uses the vendored Kable 0.43.1 marker on tvOS.
    // Apply it to the test compiler; commonTest opt-ins are not propagated here.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.named("test") {
            compileTaskProvider.configure {
                compilerOptions.optIn.add("com.juul.kable.ExperimentalApi")
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
