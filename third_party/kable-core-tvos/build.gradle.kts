plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "com.juul.kable"
version = "0.43.1-tvos"

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        languageSettings.optIn("com.juul.kable.ExperimentalApi")
        languageSettings.optIn("kotlinx.cinterop.UnsafeNumber")
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    applyDefaultHierarchyTemplate()

    tvosArm64()
    tvosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain")
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kable.kotlinx.io.core)
                api(libs.kable.atomicfu.runtime)
            }
        }

        val appleMain by getting {
            kotlin.srcDir("src/appleMain")
        }


    }
}
