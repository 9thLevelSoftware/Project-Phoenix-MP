// Top-level build file for Phoenix Project Phoenix - Multiplatform
plugins {
    // Android plugins - apply false to configure in submodules
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false

    // Kotlin plugins
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false

    // Compose Multiplatform
    alias(libs.plugins.compose.multiplatform) apply false

    // SQLDelight
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    // Common configuration for all projects
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.juul.kable:kable-core-android"))
                .using(project(":third_party:kable-core-android-patched"))
                .because("Phoenix #333: keep Kable's Android queue/callback path but use the machine's legacy GATT write shape")
            substitute(module("com.juul.kable:kable-core-android-debug"))
                .using(project(":third_party:kable-core-android-patched"))
                .because("Phoenix #333: debug Android variant must use the same patched Kable write path")
        }
    }
}

tasks.matching { it.name == "clean" }.configureEach {
    doLast {
        delete(rootProject.layout.buildDirectory)
    }
}

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
