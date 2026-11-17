package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * T30 backup-compatibility regression (issue #1359, packet P2, SD9): the
 * identifier rename must not change the persisted backup wire keys
 * `polarBeatSamples` / `polarEcgSegments` or their section shapes. The existing
 * DataBackupCompletenessTest additionally stays green as the full export/restore
 * gate for the same contract.
 */
class HrmBackupWireKeysRegressionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `T30 backup wire keys polarBeatSamples and polarEcgSegments survive the rename unchanged`() {
        val original = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-11-14T12:00:00Z",
            appVersion = "test",
            data = BackupContent(
                polarBeatSamples = listOf(
                    PolarBeatSampleBackup(
                        sessionId = "s1",
                        timestampMs = 1L,
                        phase = "WORKING_SET",
                        hrBpm = 145,
                        rrMs = 800f,
                    ),
                ),
                polarEcgSegments = listOf(
                    PolarEcgSegmentBackup(
                        sessionId = "s1",
                        sensorTimestampNs = 2L,
                        wallClockTimestampMs = 3L,
                        phase = "WORKING_SET",
                        samplesUvCsv = "1,2,3",
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(original)
        assertTrue(encoded.contains("\"polarBeatSamples\""), "wire key polarBeatSamples must be preserved")
        assertTrue(encoded.contains("\"polarEcgSegments\""), "wire key polarEcgSegments must be preserved")

        val decoded = json.decodeFromString<BackupData>(encoded)
        assertEquals(1, decoded.data.polarBeatSamples.size)
        assertEquals(145, decoded.data.polarBeatSamples[0].hrBpm)
        assertEquals("s1", decoded.data.polarBeatSamples[0].sessionId)
        assertEquals(1, decoded.data.polarEcgSegments.size)
        assertEquals("1,2,3", decoded.data.polarEcgSegments[0].samplesUvCsv)
    }
}
