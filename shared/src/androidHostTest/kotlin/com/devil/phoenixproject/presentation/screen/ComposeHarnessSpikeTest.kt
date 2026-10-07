package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.label_done

/**
 * Harness spike (issue #1164): prove the Robolectric + Compose runtime harness can
 * execute real Compose layout/semantics on this module's host tests before building
 * the full measurement matrix on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeHarnessSpikeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun composeLayoutAndSemantics_executeForReal() {
        var clicked = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                Box(Modifier.systemBarsPadding()) {
                    Button(onClick = { clicked = true }) {
                        Text("DONE")
                    }
                }
            }
        }
        val node = rule.onNodeWithText("DONE").assertIsDisplayed()
        node.performClick()
        assertTrue("click callback must fire through real semantics", clicked)

        val position = node.fetchSemanticsNode().positionInRoot
        val size = node.fetchSemanticsNode().size
        println("EVIDENCE|spike|density=3.0 fontScale=1.3 posPx=$position sizePx=$size")
    }

    @Test
    fun cmpStringResource_resolvesOnHostTests() {
        rule.setContent {
            Text(stringResource(Res.string.label_done))
        }
        rule.onNodeWithText("DONE").assertIsDisplayed()
    }

    @Test
    fun mergedSemantics_areMeasurable() {
        rule.setContent {
            Box(Modifier.semantics(mergeDescendants = true) {}) {
                Text("12")
                Text("Sets")
            }
        }
        // One merged node carries both the value and its label.
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText("Sets").assertIsDisplayed()
    }
}
