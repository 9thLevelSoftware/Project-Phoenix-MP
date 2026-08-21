package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DropSetRoutineConfigurationTest {
    @Test
    fun editorShowsApprovedCopyAndGatesSaveOnValidation() {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt",
        )
        assertNotNull(src, "Could not locate ExerciseEditBottomSheet.kt")
        assertTrue(src.contains("DropSetRoutineConfigurationCard"))
        assertTrue(src.contains("selectedMode is WorkoutMode.OldSchool"))
        assertTrue(src.contains("drop_set_offer_toggle"))
        assertTrue(src.contains("onDropSetEnabledChange"))
        assertTrue(src.contains("onDropSetMinWeightKgChange"))
        assertTrue(src.contains("displayToKg(it, weightUnit)"))
        assertTrue(src.contains("isDropSetMinWeightValid"))
        assertTrue(src.contains("sets.isNotEmpty() && primaryActionEnabled && isDropSetMinWeightValid"))
        assertFalse(src.contains("Automatically stop when next set"))
    }

    @Test
    fun viewModelValidatesOldSchoolEnabledFloorAndPreservesValues() {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt",
        )
        assertNotNull(src, "Could not locate ExerciseConfigViewModel.kt")
        assertTrue(src.contains("_selectedMode.value !is WorkoutMode.OldSchool"))
        assertTrue(src.contains("minimum.isFinite() && minimum > 0f"))
        assertTrue(src.contains("dropSetMinWeightKg = _dropSetMinWeightKg.value"))
        assertTrue(src.contains("if (_sets.value.isEmpty() || !_isDropSetMinWeightValid.value) return"))
    }
}
