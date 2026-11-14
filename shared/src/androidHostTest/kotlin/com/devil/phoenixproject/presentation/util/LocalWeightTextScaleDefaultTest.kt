package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.devil.phoenixproject.domain.model.WeightTextScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1367 T12: `LocalWeightTextScale` defaults to STANDARD outside the app shell
 * (previews and tests render unchanged base typography) and honors a provided value.
 * Asserted through a real composition because `CompositionLocal.current` is @Composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalWeightTextScaleDefaultTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaultsToStandardOutsideProvider() {
        var seen: WeightTextScale? = null
        compose.setContent { seen = LocalWeightTextScale.current }
        assertEquals(WeightTextScale.STANDARD, seen)
    }

    @Test
    fun providerOverrideIsVisibleToTheShell() {
        var seen: WeightTextScale? = null
        compose.setContent {
            CompositionLocalProvider(LocalWeightTextScale provides WeightTextScale.EXTRA_LARGE) {
                seen = LocalWeightTextScale.current
            }
        }
        assertEquals(WeightTextScale.EXTRA_LARGE, seen)
    }
}
