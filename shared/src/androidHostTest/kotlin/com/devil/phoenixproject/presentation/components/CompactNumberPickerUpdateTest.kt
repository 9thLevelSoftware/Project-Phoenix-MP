package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NumberPicker keeps the labels, max, and listener installed by AndroidView's factory.
 * These tests drive [applyCompactNumberPickerState] through a wheel that fails the same
 * way the platform widget does: the displayed-value length must match the range, a value
 * past max is rejected, and growing max while a short label array is still installed
 * throws when the selector window walks off the end of that array.
 */
class CompactNumberPickerUpdateTest {
    @Test
    fun finerStep_refreshesLabelsWithoutIndexingPastOldMax() {
        val wheel = StrictNumberPicker()
        val coarse = listOf(10f, 15f, 20f)
        applyCompactNumberPickerState(
            picker = wheel,
            values = coarse,
            step = 5f,
            suffix = "kg",
            selectedIndex = coarse.lastIndex,
            onValueChange = {},
        )

        val fine = (0..10).map { 10f + it }
        var selected: Float? = null
        var callbacks = 0
        applyCompactNumberPickerState(
            picker = wheel,
            values = fine,
            step = 1f,
            suffix = "kg",
            selectedIndex = fine.lastIndex,
            onValueChange = {
                selected = it
                callbacks += 1
            },
        )

        assertEquals(1, wheel.displayedValuesCleared)
        assertContentEquals(
            fine.map { "${it.toInt()} kg" }.toTypedArray(),
            wheel.displayedValues,
        )
        assertEquals(0, wheel.minValue)
        assertEquals(fine.lastIndex, wheel.maxValue)
        assertEquals(fine.lastIndex, wheel.value)
        assertEquals(0, callbacks)
        wheel.selectIndexFromUser(fine.lastIndex)
        assertEquals(20f, selected)
        assertEquals(1, callbacks)
    }

    @Test
    fun coarserStep_shrinksRangeBeforeInstallingShorterLabels() {
        val wheel = StrictNumberPicker()
        val fine = (0..10).map { it.toFloat() }
        applyCompactNumberPickerState(
            picker = wheel,
            values = fine,
            step = 1f,
            suffix = "lbs",
            selectedIndex = fine.lastIndex,
            onValueChange = {},
        )

        val coarse = listOf(0f, 5f, 10f)
        applyCompactNumberPickerState(
            picker = wheel,
            values = coarse,
            step = 5f,
            suffix = "lbs",
            selectedIndex = coarse.lastIndex,
            onValueChange = {},
        )

        assertEquals(1, wheel.displayedValuesCleared)
        assertContentEquals(arrayOf("0 lbs", "5 lbs", "10 lbs"), wheel.displayedValues)
        assertEquals(coarse.lastIndex, wheel.maxValue)
        assertEquals(coarse.lastIndex, wheel.value)
    }

    @Test
    fun suffixChange_replacesLabelsWhenRangeLengthStaysTheSame() {
        val wheel = StrictNumberPicker()
        val values = listOf(10f, 10.5f, 11f)
        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 0.5f,
            suffix = "kg",
            selectedIndex = 1,
            onValueChange = {},
        )
        val assignedAfterCreate = wheel.displayedValuesAssigned

        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 0.5f,
            suffix = "lbs",
            selectedIndex = 1,
            onValueChange = {},
        )

        assertEquals(0, wheel.displayedValuesCleared)
        assertEquals(assignedAfterCreate + 1, wheel.displayedValuesAssigned)
        assertContentEquals(arrayOf("10 lbs", "10.5 lbs", "11 lbs"), wheel.displayedValues)
        assertEquals(1, wheel.value)
    }

    @Test
    fun selectedValueChange_keepsLabelsAndUsesTheLatestListener() {
        val wheel = StrictNumberPicker()
        val values = listOf(2.5f, 5f, 7.5f)
        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 2.5f,
            suffix = "kg",
            selectedIndex = 0,
            onValueChange = {},
        )
        val assignedAfterCreate = wheel.displayedValuesAssigned
        var seen: Float? = null
        var callbacks = 0

        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 2.5f,
            suffix = "kg",
            selectedIndex = 2,
            onValueChange = {
                seen = it
                callbacks += 1
            },
        )

        assertEquals(0, wheel.displayedValuesCleared)
        assertEquals(assignedAfterCreate, wheel.displayedValuesAssigned)
        assertContentEquals(arrayOf("2.5 kg", "5 kg", "7.5 kg"), wheel.displayedValues)
        assertEquals(2, wheel.value)
        assertEquals(0, callbacks)
        wheel.selectIndexFromUser(1)
        assertEquals(5f, seen)
        assertEquals(1, callbacks)
    }

    @Test
    fun outOfRangeIndex_isCoercedOntoTheNewWheel() {
        val wheel = StrictNumberPicker()
        val values = listOf(1f, 2f, 3f)
        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 1f,
            suffix = "",
            selectedIndex = 99,
            onValueChange = {},
        )
        assertEquals(2, wheel.value)
        assertContentEquals(arrayOf("1", "2", "3"), wheel.displayedValues)

        applyCompactNumberPickerState(
            picker = wheel,
            values = values,
            step = 1f,
            suffix = "",
            selectedIndex = -1,
            onValueChange = {},
        )
        assertEquals(0, wheel.value)
    }

    @Test
    fun settingValueAboveMaxBeforeRangeGrows_isRejected() {
        val wheel = StrictNumberPicker()
        applyCompactNumberPickerState(
            picker = wheel,
            values = listOf(10f, 20f),
            step = 10f,
            suffix = "kg",
            selectedIndex = 0,
            onValueChange = {},
        )

        assertFailsWith<IllegalArgumentException> {
            wheel.value = 5
        }
    }

    @Test
    fun rangeChanges_leaveWrappingOffAfterLegacyPickerReenablesIt() {
        val wheel = StrictNumberPicker()
        val coarse = (0..10).map { it * 5f }
        applyCompactNumberPickerState(
            picker = wheel,
            values = coarse,
            step = 5f,
            suffix = "kg",
            selectedIndex = 0,
            onValueChange = {},
        )
        assertFalse(wheel.wrapSelectorWheel)
        assertTrue(wheel.wrapReenabledByRange > 0, "fake must model the API 26-28 range reset")

        val fine = (0..50).map { it.toFloat() }
        applyCompactNumberPickerState(
            picker = wheel,
            values = fine,
            step = 1f,
            suffix = "kg",
            selectedIndex = 0,
            onValueChange = {},
        )
        assertFalse(wheel.wrapSelectorWheel)

        applyCompactNumberPickerState(
            picker = wheel,
            values = coarse,
            step = 5f,
            suffix = "lbs",
            selectedIndex = 0,
            onValueChange = {},
        )
        assertFalse(wheel.wrapSelectorWheel)
    }
}

/**
 * Stand-in for [android.widget.NumberPicker]'s update rules. Growing [maxValue] while a
 * shorter [displayedValues] array is installed crashes the real selector cache; setting
 * [value] above max is rejected here so a finer step cannot silently clamp.
 * Like the API 26-28 widget, changing [minValue] or [maxValue] recomputes
 * [wrapSelectorWheel] from the range and discards an earlier `false`.
 */
private class StrictNumberPicker : CompactWheelPicker {
    private var labels: Array<String>? = null
    private var indexListener: ((Int) -> Unit)? = null
    private var valueField: Int = 0

    var displayedValuesCleared: Int = 0
        private set
    var displayedValuesAssigned: Int = 0
        private set
    var wrapReenabledByRange: Int = 0
        private set

    override var wrapSelectorWheel: Boolean = false

    private fun recomputeWrapLikeLegacyPicker() {
        val wrap = maxValue - minValue > LEGACY_SELECTOR_WHEEL_ITEM_COUNT
        if (wrap && !wrapSelectorWheel) wrapReenabledByRange += 1
        wrapSelectorWheel = wrap
    }

    override var minValue: Int = 0
        set(newMin) {
            if (newMin < 0) throw IllegalArgumentException("minValue must be >= 0")
            if (field == newMin) return
            field = newMin
            if (valueField < field) {
                valueField = field
            }
            recomputeWrapLikeLegacyPicker()
            validateCoverage()
        }

    override var maxValue: Int = 0
        set(newMax) {
            if (newMax < 0) throw IllegalArgumentException("maxValue must be >= 0")
            if (field == newMax) return
            field = newMax
            if (valueField > field) {
                valueField = field
            }
            recomputeWrapLikeLegacyPicker()
            validateCoverage()
        }

    override var value: Int
        get() = valueField
        set(newValue) {
            if (newValue < minValue || newValue > maxValue) {
                throw IllegalArgumentException(
                    "value $newValue must be between $minValue and $maxValue",
                )
            }
            if (valueField == newValue) return
            valueField = newValue
            validateCoverage()
        }

    override var displayedValues: Array<String>?
        get() = labels
        set(newLabels) {
            if (newLabels == null) {
                if (labels != null) displayedValuesCleared += 1
                labels = null
                return
            }
            val expectedLength = maxValue - minValue + 1
            if (newLabels.size != expectedLength) {
                throw IllegalArgumentException(
                    "displayed values length ${newLabels.size} != range $expectedLength",
                )
            }
            displayedValuesAssigned += 1
            labels = newLabels
            validateCoverage()
        }

    override fun setOnIndexSelected(listener: (Int) -> Unit) {
        indexListener = listener
    }

    fun selectIndexFromUser(index: Int) {
        value = index
        indexListener?.invoke(index)
    }

    private fun validateCoverage() {
        val installed = labels ?: return
        val selectorWindow = intArrayOf(valueField - 1, valueField, valueField + 1)
        for (index in selectorWindow) {
            if (index < minValue || index > maxValue) continue
            val displayedIndex = index - minValue
            if (displayedIndex !in installed.indices) {
                throw ArrayIndexOutOfBoundsException(
                    "displayedValues length ${installed.size} cannot cover index $index " +
                        "(min=$minValue max=$maxValue value=$valueField)",
                )
            }
        }
    }
}

/** NumberPicker.SELECTOR_WHEEL_ITEM_COUNT on API 26-28. */
private const val LEGACY_SELECTOR_WHEEL_ITEM_COUNT = 3
