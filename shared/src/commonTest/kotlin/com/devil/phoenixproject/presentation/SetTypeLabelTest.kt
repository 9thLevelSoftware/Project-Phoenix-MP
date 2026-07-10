package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.presentation.util.SetTypeLabel
import com.devil.phoenixproject.presentation.util.setTypeLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #646: pin the helper that drives the active-workout set-type badge.
 * Pure Kotlin, no Compose runtime.
 */
class SetTypeLabelTest {

    @Test
    fun calibration_when_buffer_incomplete() {
        val rc = RepCount(warmupReps = 0, isWarmupComplete = false)
        val label = setTypeLabel(rc, currentWarmupSetIndex = -1, showCalibrationLabel = true)
        assertEquals(SetTypeLabel.Calibration, label)
    }

    @Test
    fun working_when_buffer_complete() {
        val rc = RepCount(warmupReps = 3, isWarmupComplete = true)
        val label = setTypeLabel(rc, currentWarmupSetIndex = -1, showCalibrationLabel = true)
        assertEquals(SetTypeLabel.Working, label)
    }

    @Test
    fun warmup_during_variable_warmup_set_1() {
        val rc = RepCount(warmupReps = 0, isWarmupComplete = false)
        val label = setTypeLabel(rc, currentWarmupSetIndex = 0, showCalibrationLabel = false)
        assertEquals(SetTypeLabel.Warmup(setNumber = 1), label)
    }

    @Test
    fun working_when_no_warmup_index_at_setready() {
        val rc = RepCount(warmupReps = 0, isWarmupComplete = false)
        val label = setTypeLabel(rc, currentWarmupSetIndex = -1, showCalibrationLabel = false)
        assertEquals(SetTypeLabel.Working, label)
    }
}
