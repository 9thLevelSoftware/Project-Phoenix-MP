package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.util.DpadSliderCommand
import com.devil.phoenixproject.presentation.util.calculateDpadSliderKeyResult
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutSetupDialogRemoteInputTest {

    @Test
    fun targetRepsRemoteStepAdvancesToNextIntegerRep() {
        val result = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Right,
            value = 10f,
            valueRange = 1f..50f,
            steps = 49,
            explicitStep = WorkoutSetupTargetRepsRemoteStep,
            enabled = true,
        )

        assertEquals(11, result.newValue?.toInt())
    }
}
