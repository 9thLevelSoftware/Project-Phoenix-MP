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
    android {
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
                implementation(libs.androidx.lifecycle.runtime.compose)

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
                // Pinned to alpha11: stable 1.1.0 made record constructors and Metadata internal.
                // The alpha SDK still uses public constructors. The permission strings are identical
                // across alpha11 and stable — the real fix for permissions was the manifest
                // intent-filter (VIEW_PERMISSION_USAGE + HEALTH_PERMISSIONS category).
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
            // Version 26 = initial schema (1) + 25 migrations (1.sqm through 25.sqm)
            version = 26
        }
    }
}

// ============================================================
// Schema Manifest Validator
//
// Fails the build if any column in VitruvianDatabase.sq lacks provenance
// (i.e., is not covered by a migration ALTER TABLE, a migration CREATE TABLE,
// a SchemaManifest SchemaHealOperation, a SchemaManifest SchemaTableOperation,
// or grandfathered as a v1 original table).
// ============================================================

tasks.register("validateSchemaManifest") {
    group = "verification"
    description = "Fails build if any column in VitruvianDatabase.sq lacks provenance"

    val sqFile = file("src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq")
    val manifestFile = file("src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt")
    val migrationsDir = file("src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations")

    inputs.file(sqFile)
    inputs.file(manifestFile)
    inputs.dir(migrationsDir)

    doLast {
        // V1 tables are grandfathered -- their original columns existed before
        // any migration system. Only flag columns on non-v1 tables that lack provenance.
        val v1Tables = setOf(
            "Exercise", "ExerciseVideo", "WorkoutSession", "MetricSample",
            "PersonalRecord", "Routine", "RoutineExercise",
        )

        // ── 1. Parse CREATE TABLE blocks from VitruvianDatabase.sq ──────────
        val sqText = sqFile.readText()
        val createTableRegex = Regex(
            """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.*?)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        // Map<TableName, Set<ColumnName>>
        val sqColumns = mutableMapOf<String, MutableSet<String>>()
        for (match in createTableRegex.findAll(sqText)) {
            val table = match.groupValues[1]
            // Skip temp/rebuild tables (used in migrations, not in final schema)
            if (table.contains("_temp") || table.contains("_rebuild") || table.contains("_new") || table.contains("_v")) continue
            val body = match.groupValues[2]
            val cols = mutableSetOf<String>()
            for (line in body.split(",").map { it.trim() }) {
                if (line.isBlank()) continue
                if (line.startsWith("--")) continue
                val upper = line.uppercase()
                if (upper.startsWith("FOREIGN KEY")) continue
                if (upper.startsWith("PRIMARY KEY") && !upper.startsWith("PRIMARY KEY(")) continue
                if (upper.startsWith("UNIQUE(") || upper.startsWith("UNIQUE (")) continue
                if (upper.startsWith("CHECK(") || upper.startsWith("CHECK (")) continue
                // Column name is the first word
                val colName = line.split("\\s+".toRegex()).firstOrNull()?.trim()
                if (!colName.isNullOrBlank() && colName != "--") {
                    cols.add(colName)
                }
            }
            sqColumns[table] = cols
        }

        // ── 2. Parse ALTER TABLE ADD COLUMN from .sqm files ─────────────────
        val alterRegex = Regex(
            """ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)""",
            RegexOption.IGNORE_CASE,
        )
        // Set of "Table.Column" pairs covered by migrations
        val migrationCovered = mutableSetOf<String>()
        // Also track tables fully created in migrations (all their columns are covered)
        val migrationCreatedTables = mutableMapOf<String, MutableSet<String>>()

        val sqmFiles = migrationsDir.listFiles()?.filter { it.extension == "sqm" } ?: emptyList()
        for (sqmFile in sqmFiles) {
            val sqmText = sqmFile.readText()

            // ALTER TABLE ADD COLUMN
            for (m in alterRegex.findAll(sqmText)) {
                migrationCovered.add("${m.groupValues[1]}.${m.groupValues[2]}")
            }

            // CREATE TABLE in migrations (covers all columns of the created table)
            for (ctMatch in createTableRegex.findAll(sqmText)) {
                val table = ctMatch.groupValues[1]
                // Skip temp/rebuild/versioned tables
                if (table.contains("_temp") || table.contains("_rebuild") || table.contains("_new") || table.contains("_v")) continue
                // Only track if this is a "real" table that also exists in the .sq
                if (!sqColumns.containsKey(table)) continue
                val body = ctMatch.groupValues[2]
                val cols = mutableSetOf<String>()
                for (line in body.split(",").map { it.trim() }) {
                    if (line.isBlank()) continue
                    if (line.startsWith("--")) continue
                    val upper = line.uppercase()
                    if (upper.startsWith("FOREIGN KEY")) continue
                    if (upper.startsWith("PRIMARY KEY") && !upper.startsWith("PRIMARY KEY(")) continue
                    if (upper.startsWith("UNIQUE(") || upper.startsWith("UNIQUE (")) continue
                    if (upper.startsWith("CHECK(") || upper.startsWith("CHECK (")) continue
                    val colName = line.split("\\s+".toRegex()).firstOrNull()?.trim()
                    if (!colName.isNullOrBlank() && colName != "--") {
                        cols.add(colName)
                    }
                }
                migrationCreatedTables.getOrPut(table) { mutableSetOf() }.addAll(cols)
            }
        }

        // ── 3. Parse SchemaHealOperation entries from SchemaManifest.kt ─────
        val manifestText = manifestFile.readText()
        val healRegex = Regex("""SchemaHealOperation\(\s*"(\w+)"\s*,\s*"(\w+)"""")
        val manifestCovered = mutableSetOf<String>()
        for (m in healRegex.findAll(manifestText)) {
            manifestCovered.add("${m.groupValues[1]}.${m.groupValues[2]}")
        }

        // ── 4. Parse SchemaTableOperation base columns from SchemaManifest.kt ──
        // These are tables created by the manifest (no migration). Their base shape
        // columns are covered.
        val tableOpRegex = Regex(
            """SchemaTableOperation\(\s*table\s*=\s*"(\w+)"\s*,\s*createSql\s*=\s*"""
                    + """["$]{3}(.*?)["$]{3}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val manifestTableColumns = mutableMapOf<String, MutableSet<String>>()
        for (m in tableOpRegex.findAll(manifestText)) {
            val table = m.groupValues[1]
            val createBody = m.groupValues[2]
            // Extract columns from the CREATE TABLE body inside the raw string
            val innerMatch = createTableRegex.find(createBody)
            if (innerMatch != null) {
                val body = innerMatch.groupValues[2]
                val cols = mutableSetOf<String>()
                for (line in body.split(",").map { it.trim() }) {
                    if (line.isBlank()) continue
                    if (line.startsWith("--")) continue
                    val upper = line.uppercase()
                    if (upper.startsWith("FOREIGN KEY")) continue
                    if (upper.startsWith("PRIMARY KEY") && !upper.startsWith("PRIMARY KEY(")) continue
                    if (upper.startsWith("UNIQUE(") || upper.startsWith("UNIQUE (")) continue
                    if (upper.startsWith("CHECK(") || upper.startsWith("CHECK (")) continue
                    val colName = line.split("\\s+".toRegex()).firstOrNull()?.trim()
                    if (!colName.isNullOrBlank() && colName != "--") {
                        cols.add(colName)
                    }
                }
                manifestTableColumns[table] = cols
            }
        }

        // ── 5. Verify every .sq column has provenance ───────────────────────
        val uncovered = mutableListOf<String>()
        var totalColumns = 0

        for ((table, columns) in sqColumns) {
            for (col in columns) {
                totalColumns++
                val key = "$table.$col"

                // V1 tables: all original columns are grandfathered
                if (table in v1Tables) continue

                // Covered by migration ALTER TABLE ADD COLUMN?
                if (key in migrationCovered) continue

                // Covered by migration CREATE TABLE?
                if (migrationCreatedTables[table]?.contains(col) == true) continue

                // Covered by SchemaManifest SchemaHealOperation?
                if (key in manifestCovered) continue

                // Covered by SchemaManifest SchemaTableOperation base columns?
                if (manifestTableColumns[table]?.contains(col) == true) continue

                uncovered.add(key)
            }
        }

        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "Schema manifest validation FAILED: ${uncovered.size} column(s) lack provenance:\n" +
                        uncovered.sorted().joinToString("\n") { "  - $it" } +
                        "\n\nFix: add a SchemaHealOperation, SchemaTableOperation, or migration for each."
            )
        }

        println("Schema manifest validated: $totalColumns columns across ${sqColumns.size} tables, all covered.")
    }
}

// Wire validator into both the aggregation task (direct invocation) and the
// per-database task (transitive via compile chain). afterEvaluate is required
// because SQLDelight registers its per-database tasks lazily.
tasks.named("generateSqlDelightInterface") { dependsOn("validateSchemaManifest") }
afterEvaluate {
    tasks.findByName("generateCommonMainVitruvianDatabaseInterface")
        ?.dependsOn("validateSchemaManifest")
}
