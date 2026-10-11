package com.devil.phoenixproject.presentation.components.charts

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatVolumeLabelTest {

    @Test
    fun largeVolumes_roundToNearestThousand() {
        assertEquals("2k", formatVolumeLabel(1950f))
        assertEquals("1k", formatVolumeLabel(1499f))
        assertEquals("2k", formatVolumeLabel(1500f))
    }

    @Test
    fun belowOneThousand_staysTruncatedWholeNumber() {
        assertEquals("0", formatVolumeLabel(0f))
        assertEquals("999", formatVolumeLabel(999f))
        assertEquals("999", formatVolumeLabel(999.9f))
    }

    @Test
    fun atLeastOneThousand_keepsCompactKSuffix() {
        assertEquals("1k", formatVolumeLabel(1000f))
        assertEquals("1k", formatVolumeLabel(1000.4f))
    }
}
