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
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }

        withHostTest {
            // Issue #1164 runtime harness: Compose UI/Robolectric measurement and
            // semantics tests need merged Android resources (ui-test-manifest activity,
            // CMP string resources) on the host-test classpath.
            isIncludeAndroidResources = true
        }
    }

    // iosArm64 is the device framework the app links. Gradle writes it to
    // debugFramework/ or releaseFramework/; iosApp/install-xcode-framework.sh
    // copies the chosen bundle to xcodeFramework/, the only path in the Xcode
    // project. iosSimulatorArm64 is test-only and is not installed for the app.
    // xcf.add keeps :shared:assembleXCFramework; the app does not link it.
    // isStatic = true, so the framework binary is an archive and has no dSYM.
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
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "shared"
            isStatic = true
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
                implementation(libs.atomicfu)

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
                implementation(libs.ktor.client.mock)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockk)
                implementation(libs.truth)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.koin.test.junit4)
                implementation(libs.multiplatform.settings.test)
                // Issue #1164 runtime harness: real Compose layout/semantics measurement.
                implementation(libs.robolectric)
                implementation(libs.cmp.ui.test)
                implementation(libs.cmp.ui.test.junit4)
                implementation(libs.androidx.ui.test.manifest)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.junit)
            }
        }

        getByName("androidMain") {
            dependencies {
                // Android-specific Coroutines
                implementation(libs.kotlinx.coroutines.android)

                // SQLDelight Android Driver
                implementation(libs.sqldelight.android.driver)

                // Koin Android
                implementation(libs.koin.android)

                // Ktor OkHttp engine for Android
                implementation(libs.ktor.client.okhttp)

                // Preview annotations for @Preview in shared Android sources.
                implementation(libs.cmp.ui.tooling.preview)

                // Activity Compose (for file picker Activity Result APIs)
                implementation(libs.androidx.activity.compose)

                // Android browser integrations for Custom Tabs OAuth handoff
                implementation(libs.androidx.browser)

                // Encrypted SharedPreferences for secure token storage
                implementation(libs.androidx.security.crypto)

                // DocumentFile for directory picker display name extraction
                implementation(libs.androidx.documentfile)

                // Health Connect (Google Health)
                implementation(libs.androidx.health.connect)
            }
        }

        val iosArm64Main by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting

        @Suppress("UNUSED_VARIABLE")
        val iosMain by creating {
            dependsOn(commonMain)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)

            dependencies {
                // SQLDelight Native Driver
                implementation(libs.sqldelight.native.driver)

                // Ktor Darwin engine for iOS
                implementation(libs.ktor.client.darwin)
            }
        }

        @Suppress("UNUSED_VARIABLE")
        val iosTest by creating {
            dependsOn(commonTest)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)

            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("PhoenixDatabase") {
            packageName.set("com.devil.phoenixproject.database")
            // Schema.version is highest N.sqm + 1. This block has no version property;
            // assigning version here sets the Gradle project version.
        }
    }
}

// Windows workaround: SQLite JDBC's native loader uses java.io.tmpdir which may resolve
// to C:\Windows (access-denied). This has to live on :shared. VerifyMigrationTask is
// created here by the SQLDelight plugin; a root-project withType never sees it, so the
// hook never ran. Set the system property before the task executes so the
// classloader-isolated worker inherits it from the daemon JVM.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    doFirst {
        val userTemp =
            System.getenv("TEMP") ?: System.getenv("TMP") ?: System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", userTemp)
        System.setProperty("org.sqlite.tmpdir", userTemp)
    }
}

// ============================================================
// Schema Manifest Validator
//
// Fails the build when either of these drifts:
// 1. A column in PhoenixDatabase.sq lacks provenance (migration ALTER TABLE,
//    migration CREATE TABLE, SchemaHealOperation, SchemaTableOperation, or a
//    grandfathered v1 table).
// 2. A SchemaManifest CREATE TABLE does not list the same column names in the
//    same order as PhoenixDatabase.sq. iOS schema heal creates missing tables
//    from that manifest DDL, so order is part of the contract.
// ============================================================

tasks.register("validateSchemaManifest") {
    group = "verification"
    description =
        "Fails the build if a PhoenixDatabase.sq column lacks provenance or its SchemaManifest name/order drifts"

    val sqFile = file("src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq")
    val manifestFile = file("src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt")
    val migrationsDir = file("src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations")

    inputs.file(sqFile)
    inputs.file(manifestFile)
    inputs.dir(migrationsDir)

    doLast {
        val sqCreateTablePattern = Regex(
            """CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\((.*?)\);""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val manifestCreateTablePattern = Regex(
            "CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\((.*?)\\)\\s*\"\"\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        val sqlIdentifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        fun stripSqlLineComments(text: String): String =
            text.lineSequence().joinToString("\n") { line ->
                val comment = line.indexOf("--")
                if (comment >= 0) line.substring(0, comment) else line
            }

        /** Split on commas that are not inside (), '', or "". */
        fun splitTopLevelSql(body: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            var i = 0
            while (i < body.length) {
                val char = body[i]
                if (quote != null) {
                    current.append(char)
                    if (char == quote) {
                        if (quote == '\'' && i + 1 < body.length && body[i + 1] == '\'') {
                            current.append(body[i + 1])
                            i++
                        } else {
                            quote = null
                        }
                    }
                } else {
                    when (char) {
                        '\'', '"' -> {
                            quote = char
                            current.append(char)
                        }
                        '(' -> {
                            depth++
                            current.append(char)
                        }
                        ')' -> {
                            depth--
                            current.append(char)
                        }
                        ',' -> {
                            if (depth == 0) {
                                val part = current.toString().trim()
                                if (part.isNotEmpty()) parts.add(part)
                                current.clear()
                            } else {
                                current.append(char)
                            }
                        }
                        else -> current.append(char)
                    }
                }
                i++
            }
            val tail = current.toString().trim()
            if (tail.isNotEmpty()) parts.add(tail)
            return parts
        }

        fun sqlKeyword(token: String): String =
            token.trim('`', '"').substringBefore('(').uppercase()

        /**
         * Table-level constraints only. The keyword must be the whole first token, so a
         * column such as uniqueExercisesUsed is not treated as UNIQUE.
         */
        fun isSqlTableConstraint(part: String): Boolean {
            val tokens = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            val first = sqlKeyword(tokens[0])
            val second = if (tokens.size > 1) sqlKeyword(tokens[1]) else ""
            return when (first) {
                "FOREIGN" -> second == "KEY"
                "PRIMARY" -> second == "KEY"
                "UNIQUE", "CHECK", "CONSTRAINT" -> true
                else -> false
            }
        }

        fun extractSqlColumnNames(body: String): List<String> {
            val columns = mutableListOf<String>()
            for (part in splitTopLevelSql(body)) {
                val stripped = part.trim()
                if (stripped.isEmpty() || isSqlTableConstraint(stripped)) continue
                columns.add(stripped.split(Regex("\\s+")).first().trim('`', '"'))
            }
            return columns
        }

        fun parseCreateTableColumns(
            text: String,
            pattern: Regex,
            stripCommentsBeforeMatch: Boolean,
        ): Pair<Map<String, List<String>>, List<String>> {
            val errors = mutableListOf<String>()
            val source = if (stripCommentsBeforeMatch) stripSqlLineComments(text) else text
            val tables = linkedMapOf<String, List<String>>()
            var matches = 0
            for (match in pattern.findAll(source)) {
                matches++
                val table = match.groupValues[1]
                val rawBody = match.groupValues[2]
                if (rawBody.contains("CREATE TABLE")) {
                    errors.add(
                        "Parser could not isolate CREATE TABLE $table; the match spanned another CREATE TABLE",
                    )
                }
                val body = if (stripCommentsBeforeMatch) rawBody else stripSqlLineComments(rawBody)
                if (table in tables) {
                    errors.add("Duplicate CREATE TABLE '$table'")
                }
                tables[table] = extractSqlColumnNames(body)
            }
            if (matches == 0) {
                errors.add("Parser found no CREATE TABLE statements")
            }
            return tables to errors
        }

        class SchemaOrderParity(
            val tableCount: Int,
            val columnCount: Int,
            val errors: List<String>,
        )

        /**
         * Name-and-order parity between PhoenixDatabase.sq and the manifest DDL that
         * iOS heal executes. Equivalent to the retired validate-ios-schema.sh check,
         * except constraint keywords are whole tokens (so uniqueExercisesUsed counts).
         */
        fun schemaColumnOrderErrors(sqText: String, manifestText: String): SchemaOrderParity {
            val errors = mutableListOf<String>()
            val (sqlTables, sqlParseErrors) = parseCreateTableColumns(
                sqText,
                sqCreateTablePattern,
                stripCommentsBeforeMatch = true,
            )
            val (manifestTables, manifestParseErrors) = parseCreateTableColumns(
                manifestText,
                manifestCreateTablePattern,
                stripCommentsBeforeMatch = false,
            )
            errors += sqlParseErrors.map { "SQLDelight: $it" }
            errors += manifestParseErrors.map { "SchemaManifest: $it" }

            (sqlTables.keys - manifestTables.keys).sorted().forEach { table ->
                errors.add("Table '$table' exists in SQLDelight but is missing from SchemaManifest")
            }
            (manifestTables.keys - sqlTables.keys).sorted().forEach { table ->
                errors.add("Table '$table' exists in SchemaManifest but not in SQLDelight")
            }

            for (table in sqlTables.keys.sorted()) {
                val manifestColumns = manifestTables[table] ?: continue
                val sqlColumns = sqlTables.getValue(table)
                for ((source, columns) in listOf("SQLDelight" to sqlColumns, "SchemaManifest" to manifestColumns)) {
                    columns.filterNot { sqlIdentifier.matches(it) }.forEach { token ->
                        errors.add("Table '$table' has an unparsable $source column token '$token'")
                    }
                }

                val missingColumns = sqlColumns.filter { it !in manifestColumns }
                val extraColumns = manifestColumns.filter { it !in sqlColumns }
                if (missingColumns.isNotEmpty()) {
                    errors.add(
                        "Table '$table' is missing manifest columns: ${missingColumns.joinToString(", ")}",
                    )
                }
                if (extraColumns.isNotEmpty()) {
                    errors.add(
                        "Table '$table' has extra manifest columns: ${extraColumns.joinToString(", ")}",
                    )
                }
                if (missingColumns.isEmpty() && extraColumns.isEmpty() && sqlColumns != manifestColumns) {
                    val mismatches = mutableListOf<String>()
                    val compared = minOf(sqlColumns.size, manifestColumns.size)
                    for (index in 0 until compared) {
                        if (sqlColumns[index] != manifestColumns[index]) {
                            mismatches.add(
                                "${index + 1}: expected ${sqlColumns[index]}, found ${manifestColumns[index]}",
                            )
                        }
                    }
                    if (sqlColumns.size != manifestColumns.size) {
                        mismatches.add("length: expected ${sqlColumns.size}, found ${manifestColumns.size}")
                    }
                    errors.add(
                        "Table '$table' column order differs between SQLDelight and SchemaManifest: " +
                            mismatches.joinToString("; "),
                    )
                }
            }

            return SchemaOrderParity(
                tableCount = sqlTables.size,
                columnCount = sqlTables.values.sumOf { it.size },
                errors = errors,
            )
        }

        // V1 tables are grandfathered -- their original columns existed before
        // any migration system. Only flag columns on non-v1 tables that lack provenance.
        val v1Tables = setOf(
            "Exercise",
            "ExerciseVideo",
            "WorkoutSession",
            "MetricSample",
            "PersonalRecord",
            "Routine",
            "RoutineExercise",
        )

        // ── 1. Parse CREATE TABLE blocks from PhoenixDatabase.sq ──────────
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
            sqColumns[table] = extractSqlColumnNames(stripSqlLineComments(match.groupValues[2])).toMutableSet()
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
                val cols = extractSqlColumnNames(stripSqlLineComments(ctMatch.groupValues[2]))
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
            """SchemaTableOperation\(\s*table\s*=\s*"(\w+)"\s*,\s*createSql\s*=\s*""" +
                """["$]{3}(.*?)["$]{3}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val manifestTableColumns = mutableMapOf<String, MutableSet<String>>()
        for (m in tableOpRegex.findAll(manifestText)) {
            val table = m.groupValues[1]
            val createBody = m.groupValues[2]
            // Extract columns from the CREATE TABLE body inside the raw string
            val innerMatch = createTableRegex.find(createBody)
            if (innerMatch != null) {
                manifestTableColumns[table] =
                    extractSqlColumnNames(stripSqlLineComments(innerMatch.groupValues[2])).toMutableSet()
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

        val failures = mutableListOf<String>()
        if (uncovered.isNotEmpty()) {
            failures.add(
                "Schema manifest validation FAILED: ${uncovered.size} column(s) lack provenance:\n" +
                    uncovered.sorted().joinToString("\n") { "  - $it" } +
                    "\n\nFix: add a SchemaHealOperation, SchemaTableOperation, or migration for each.",
            )
        }

        val orderParity = schemaColumnOrderErrors(sqText, manifestText)
        if (orderParity.errors.isNotEmpty()) {
            failures.add(
                "Schema manifest name/order parity FAILED: ${orderParity.errors.size} mismatch(es):\n" +
                    orderParity.errors.joinToString("\n") { "  - $it" } +
                    "\n\nSchemaManifest CREATE TABLE statements must list the same columns in the same " +
                    "order as PhoenixDatabase.sq. iOS schema heal creates missing tables from that " +
                    "manifest DDL, so a different order breaks SQLDelight on fresh installs. " +
                    "Update SchemaManifest.kt to match PhoenixDatabase.sq.",
            )
        }

        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString("\n\n"))
        }

        println("Schema manifest validated: $totalColumns columns across ${sqColumns.size} tables, all covered.")
        println(
            "Schema column order validated: ${orderParity.columnCount} columns across " +
                "${orderParity.tableCount} tables match SchemaManifest.",
        )
    }
}

// Wire validator into both the aggregation task (direct invocation) and the
// per-database task (transitive via compile chain). afterEvaluate is required
// because SQLDelight registers its per-database tasks lazily.
tasks.named("generateSqlDelightInterface") { dependsOn("validateSchemaManifest") }
afterEvaluate {
    tasks.findByName("generateCommonMainPhoenixDatabaseInterface")
        ?.dependsOn("validateSchemaManifest")
}

// ============================================================
// iOS privacy manifest
//
// Required-reason calls (UserDefaults, file timestamps, system boot time)
// are compiled into the static shared framework and linked into the app.
// The framework is also embedded, so the same manifest has to be inside
// shared.framework for App Store Connect to attribute those symbols.
// The source file lives in the Xcode app target; link tasks copy it to
// shared.framework/PrivacyInfo.xcprivacy after they produce the bundle.
// ============================================================

val iosPrivacyManifest = rootProject.layout.projectDirectory.file(
    "iosApp/PhoenixApp/PhoenixApp/PrivacyInfo.xcprivacy",
)

val iosFrameworkLinkTask = Regex("^link(Debug|Release)Framework(IosArm64|IosSimulatorArm64)$")

fun privacyManifestDestination(linkTaskName: String): java.io.File? {
    val match = iosFrameworkLinkTask.matchEntire(linkTaskName) ?: return null
    val buildType = if (match.groupValues[1] == "Debug") "debug" else "release"
    val target = if (match.groupValues[2] == "IosArm64") "iosArm64" else "iosSimulatorArm64"
    return layout.buildDirectory.get().asFile.resolve(
        "bin/$target/${buildType}Framework/shared.framework/PrivacyInfo.xcprivacy",
    )
}

tasks.register("validateIosPrivacyManifest") {
    group = "verification"
    description = "Checks PrivacyInfo.xcprivacy declares UserDefaults, file timestamp, and system boot time."

    val manifestPath = iosPrivacyManifest.asFile.absolutePath
    inputs.file(iosPrivacyManifest)

    doLast {
        val manifest = File(manifestPath)

        fun org.w3c.dom.Node.elements(): List<org.w3c.dom.Element> {
            val elements = mutableListOf<org.w3c.dom.Element>()
            val children = childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is org.w3c.dom.Element) elements.add(child)
            }
            return elements
        }

        fun org.w3c.dom.Element.dict(): Map<String, org.w3c.dom.Element> {
            val children = elements()
            if (children.size % 2 != 0) {
                throw GradleException("plist dict in ${manifest.path} has an unpaired key")
            }
            return children.chunked(2).associate { (keyNode, valueNode) ->
                if (keyNode.nodeName != "key") {
                    throw GradleException("Expected plist key in ${manifest.path}, found ${keyNode.nodeName}")
                }
                keyNode.textContent.trim() to valueNode
            }
        }

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(manifest)
        val plist = document.documentElement
            ?: throw GradleException("Privacy manifest ${manifest.path} has no root element")
        if (plist.nodeName != "plist") {
            throw GradleException("Privacy manifest ${manifest.path} root is ${plist.nodeName}, expected plist")
        }
        val rootDict = plist.elements().singleOrNull { it.nodeName == "dict" }
            ?: throw GradleException("Privacy manifest ${manifest.path} is missing the root dict")
        val accessedApis = rootDict.dict()["NSPrivacyAccessedAPITypes"]
            ?: throw GradleException("Privacy manifest ${manifest.path} is missing NSPrivacyAccessedAPITypes")
        if (accessedApis.nodeName != "array") {
            throw GradleException("NSPrivacyAccessedAPITypes must be an array")
        }
        val reasons = accessedApis.elements().associate { entry ->
            if (entry.nodeName != "dict") {
                throw GradleException("NSPrivacyAccessedAPITypes entries must be dicts")
            }
            val fields = entry.dict()
            val category = fields["NSPrivacyAccessedAPIType"]
                ?.takeIf { it.nodeName == "string" }
                ?.textContent
                ?.trim()
                .orEmpty()
            val reasonNode = fields["NSPrivacyAccessedAPITypeReasons"]
                ?: throw GradleException("Missing reasons for $category")
            if (reasonNode.nodeName != "array") {
                throw GradleException("Reasons for $category must be an array")
            }
            val codes = reasonNode.elements().map { reason ->
                if (reason.nodeName != "string") {
                    throw GradleException("Reason codes for $category must be strings")
                }
                reason.textContent.trim()
            }.toSet()
            if (category.isEmpty() || codes.isEmpty()) {
                throw GradleException("Privacy manifest entry is missing a category or reason code")
            }
            category to codes
        }
        val expected = mapOf(
            "NSPrivacyAccessedAPICategoryUserDefaults" to setOf("CA92.1"),
            "NSPrivacyAccessedAPICategoryFileTimestamp" to setOf("C617.1"),
            "NSPrivacyAccessedAPICategorySystemBootTime" to setOf("35F9.1"),
        )
        if (reasons != expected) {
            throw GradleException(
                "Privacy manifest ${manifest.path} declared $reasons, expected $expected",
            )
        }
        println("iOS privacy manifest validated: ${expected.keys.joinToString()}")
    }
}

tasks.configureEach {
    val destination = privacyManifestDestination(name) ?: return@configureEach
    val manifestPath = iosPrivacyManifest.asFile.absolutePath
    val destinationPath = destination.absolutePath
    inputs.file(iosPrivacyManifest)
    dependsOn("validateIosPrivacyManifest")
    doLast {
        val frameworkDir = File(destinationPath).parentFile
        if (!frameworkDir.isDirectory) {
            throw GradleException(
                "shared.framework not found at ${frameworkDir.path} after $name; " +
                    "cannot embed PrivacyInfo.xcprivacy",
            )
        }
        File(manifestPath).copyTo(File(destinationPath), overwrite = true)
    }
}

// Issue #1164 runtime harness: forward the evidence directory to host-test JVMs so
// Compose measurement runs can persist their measurement records alongside the
// console/test-report output.
tasks.withType<Test>().configureEach {
    systemProperty(
        "phoenix.evidence.dir",
        providers.systemProperty("phoenix.evidence.dir").orElse("").get(),
    )
    testLogging {
        showStandardStreams = true
    }
}
