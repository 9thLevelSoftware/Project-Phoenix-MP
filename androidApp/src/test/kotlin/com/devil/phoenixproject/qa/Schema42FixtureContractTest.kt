package com.devil.phoenixproject.qa

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Schema42FixtureContractTest {
    @Test
    fun fixtureGuideWaitsForRequiredProfileMigrationBeforeInspection() {
        val guide = File(findRepoRoot(), "docs/qa/profile-schema42-fixture.md")
            .readText()
            .replace("\r\n", "\n")
        val readinessBlock = listOf(
            "\$migrationDeadline = [DateTime]::UtcNow.AddSeconds(60)",
            "\$migrationReady = \$false",
            "do {",
            "    \$migrationLine = & \$adb shell run-as \$package grep -F profile_preferences_legacy_migration_complete_v1 shared_prefs/phoenix_preferences.xml 2>\$null",
            "    \$migrationReady = \$LASTEXITCODE -eq 0 -and \$migrationLine -match 'value=\"true\"'",
            "    if (-not \$migrationReady) { Start-Sleep -Seconds 1 }",
            "} while (-not \$migrationReady -and [DateTime]::UtcNow -lt \$migrationDeadline)",
            "if (-not \$migrationReady) { throw 'Timed out after 60 seconds waiting for required profile preference migration' }",
        ).joinToString("\n")

        val readinessStart = guide.indexOf(readinessBlock)
        assertTrue(
            "Guide must contain the complete bounded migration-readiness polling block",
            readinessStart >= 0,
        )
        val readinessEnd = readinessStart + readinessBlock.length
        val forceStop = guide.indexOf("& \$adb shell am force-stop \$package", startIndex = readinessEnd)
        val hostSqlite = guide.indexOf("platform-tools\\sqlite3.exe", startIndex = forceStop)
        val sqlInspection = guide.indexOf(
            "exec-out run-as \$package cat \$RelativePath",
            startIndex = forceStop,
        )
        val databaseCopy = guide.indexOf(
            "Receive-SandboxBytes 'databases/phoenix.db'",
            startIndex = sqlInspection,
        )
        val userVersionQuery = guide.indexOf(
            "& \$hostSqlite -readonly -batch \$inspectDb 'PRAGMA user_version;'",
            startIndex = databaseCopy,
        )
        assertTrue(
            "Guide must wait for migration before force-stop, then pipe the database through adb exec-out into host sqlite3",
            readinessEnd <= forceStop &&
                forceStop < hostSqlite &&
                hostSqlite < sqlInspection &&
                sqlInspection < databaseCopy &&
                databaseCopy < userVersionQuery,
        )
        assertFalse(
            "Post-migration inspection still runs device-side sqlite3 against databases/phoenix.db",
            guide.substring(forceStop).contains("sqlite3 databases/phoenix.db"),
        )
    }

    @Test
    fun schema42FixturePinsTheLegacyUpgradeContract() {
        val repoRoot = findRepoRoot()
        val fixtureFile = File(
            repoRoot,
            "docs/qa/fixtures/profile-schema42/phoenix_preferences.xml",
        )
        val guideFile = File(repoRoot, "docs/qa/profile-schema42-fixture.md")

        assertTrue("Expected tracked fixture XML at ${fixtureFile.path}", fixtureFile.isFile)
        assertTrue("Expected fixture reproduction guide at ${guideFile.path}", guideFile.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fixtureFile)
        assertEquals("map", document.documentElement.tagName)

        assertEntry(document, "string", "weight_unit", text = "KG")
        assertEntry(document, "float", "body_weight_kg", value = "82.5")
        assertEntry(document, "float", "weight_increment", value = "2.5")
        assertEntry(document, "int", "color_scheme", value = "3")
        assertEntry(document, "int", "summary_countdown_seconds", value = "15")
        assertEntry(document, "int", "autostart_countdown_seconds", value = "7")
        assertEntry(document, "int", "velocity_loss_threshold_percent", value = "35")
        assertEntry(document, "string", "default_scaling_basis", text = "ESTIMATED_1RM")

        val fixtureText = fixtureFile.readText()
        assertFalse(
            "The pre-upgrade fixture must not claim profile preference migration is complete",
            fixtureText.contains("profile_preferences_legacy_migration_complete_v1"),
        )
        assertTrue(
            "Expected the rack fixture to contain fixture-vest",
            entryText(document, "string", "equipment_rack_items_v1").contains("fixture-vest"),
        )

        val guide = guideFile.readText().replace("\r\n", "\n")
        assertTrue(
            "Guide must pin the full pre-profile commit SHA",
            guide.contains("ac84d9bb8e156002833ad526bf324a8f12710da0"),
        )
        assertTrue(
            "Guide must pin the exact SQLDelight schema-version diff",
            guide.contains(
                """-            // Version 41 = initial schema (1) + 40 migrations (1.sqm through 40.sqm).
-            version = 41
+            // Version 42 = initial schema (1) + 41 migrations (1.sqm through 41.sqm).
+            version = 42""",
            ),
        )
    }

    private fun assertEntry(
        document: org.w3c.dom.Document,
        tag: String,
        name: String,
        value: String? = null,
        text: String? = null,
    ) {
        val nodes = document.getElementsByTagName(tag)
        val entry = (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .singleOrNull { it.getAttribute("name") == name }
        assertTrue("Expected exactly one <$tag> entry named $name", entry != null)
        value?.let { assertEquals("Unexpected value for $name", it, entry?.getAttribute("value")) }
        text?.let { assertEquals("Unexpected text for $name", it, entry?.textContent) }
    }

    private fun entryText(document: org.w3c.dom.Document, tag: String, name: String): String {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == name }
            .textContent
    }

    private fun findRepoRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is not available"
        }
        var current = File(workingDirectory).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
                ?: error("Could not locate repo root from $workingDirectory")
        }
    }
}
