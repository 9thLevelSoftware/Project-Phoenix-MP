// Top-level build file for Vitruvian Project Phoenix - Multiplatform
plugins {
    // Android plugins - apply false to configure in submodules
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false

    // Kotlin plugins
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Compose Multiplatform
    alias(libs.plugins.compose.multiplatform) apply false

    // SQLDelight
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    // Common configuration for all projects
}

tasks.matching { it.name == "clean" }.configureEach {
    doLast {
        delete(rootProject.layout.buildDirectory)
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
