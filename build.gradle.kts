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

    // Code formatting
    alias(libs.plugins.spotless)
}

allprojects {
    // Common configuration for all projects
}

spotless {
    kotlin {
        target("shared/src/**/*.kt", "androidApp/src/**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint().editorConfigOverride(
            mapOf(
                "max_line_length" to "off",
                "ktlint_code_style" to "android_studio",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_package-name" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_multiline-expression-wrapping" to "disabled",
                "ktlint_standard_string-template-indent" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_kdoc" to "disabled",
                "ktlint_standard_value-parameter-comment" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_class-naming" to "disabled",
                "ktlint_standard_comment-wrapping" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "shared/*.gradle.kts", "androidApp/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                "max_line_length" to "140",
                "ktlint_code_style" to "android_studio",
            ),
        )
    }
}

tasks.matching { it.name == "clean" }.configureEach {
    doLast {
        delete(rootProject.layout.buildDirectory)
    }
}

// Migration verifier re-enabled after fixing migration gaps (MVP audit, Batch B).
// Previously disabled to work around duplicate columns and missing migrations.
//
// Windows workaround: SQLite JDBC's native loader uses java.io.tmpdir which may resolve
// to C:\Windows (access-denied). Set the system property before the task executes so the
// classloader-isolated worker inherits it from the daemon JVM.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    doFirst {
        val userTemp =
            System.getenv("TEMP") ?: System.getenv("TMP") ?: System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", userTemp)
        System.setProperty("org.sqlite.tmpdir", userTemp)
    }
}
