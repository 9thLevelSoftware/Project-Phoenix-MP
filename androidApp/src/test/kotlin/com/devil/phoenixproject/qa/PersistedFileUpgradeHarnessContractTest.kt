package com.devil.phoenixproject.qa

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedFileUpgradeHarnessContractTest {
    @Test
    fun harnessPinsTheDestructiveBoundaryAndUpgradeAssertions() {
        val script = File(
            findRepoRoot(),
            "scripts/qa/Invoke-PhoenixPersistedFileUpgrade.ps1",
        )
        assertTrue("Missing persisted-file upgrade harness", script.isFile)

        val text = script.readText().replace("\r\n", "\n")
        listOf(
            "ValidateSet('Fresh', 'Upgrade', 'InterruptedStaging', 'CorruptSource', 'DualDatabase', 'LowStorage')",
            "ro.kernel.qemu",
            "adb install -r",
            "PROFILE_QA_SEED_OK",
            "INSERT OR IGNORE INTO Exercise",
            "PRAGMA quick_check",
            "PRAGMA wal_autocheckpoint=0",
            "committed-wal-survived",
            "vitruvian.db-wal",
            "phoenix-recovery.db",
            "DB_DUAL_DATABASES",
            "Get-ApkCertificateDigest",
            "Assert-SecondLaunchCleanup",
            "svc wifi disable",
            "svc data disable",
            "fallocate",
            "Invoke-RetryAction",
        ).forEach { required ->
            assertTrue("Harness is missing contract marker: $required", text.contains(required))
        }
        assertFalse("Harness must never clear app data mid-upgrade", text.contains("pm clear"))
        assertFalse("Harness must never use a broad recursive delete", text.contains("Remove-Item -Recurse"))
    }

    @Test
    fun guideSeparatesAutomatedDebugEvidenceFromProductionAndPhysicalDeviceGates() {
        val guide = File(findRepoRoot(), "docs/qa/persisted-file-upgrade.md")
        assertTrue("Missing persisted-file upgrade guide", guide.isFile)

        val text = guide.readText()
        listOf(
            "v0.9.6",
            "production-signed",
            "low-storage",
            "physical iOS device",
            "second launch",
            "PR 709",
        ).forEach { required ->
            assertTrue("Guide is missing release-gate marker: $required", text.contains(required))
        }
    }

    private fun findRepoRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var current = File(workingDirectory).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
                ?: error("Could not locate repo root from $workingDirectory")
        }
    }
}
