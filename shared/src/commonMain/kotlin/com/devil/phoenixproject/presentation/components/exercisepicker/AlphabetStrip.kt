package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Vertical alphabet strip for quick navigation to exercise sections.
 * Only shows letters that have exercises.
 *
 * The ENTIRE strip is a single 48dp-wide gesture target (exercise-library-2).
 * Tap or drag maps pointer Y position to a letter index and fires [onLetterTap].
 * Screen-reader users navigate via the underlying list; the strip is a sighted shortcut.
 *
 * Boundary math:
 *   index = (y / stripHeight * letters.size).toInt().coerceIn(0, letters.lastIndex)
 *   - y = 0     → index 0 (first letter, touch area extends into top padding) ✓
 *   - y → H     → index approaches letters.size-1 (last letter) ✓
 *   - y = H     → N.toInt() clamped to lastIndex (last letter) ✓
 *   All letters reachable; padding extends first/last letter touch area outward.
 */
@Composable
fun AlphabetStrip(letters: List<Char>, onLetterTap: (Char) -> Unit, modifier: Modifier = Modifier) {
    val alphabetNavDesc = stringResource(Res.string.cd_alphabet_nav)
    var stripHeightPx by remember { mutableStateOf(0f) }

    fun letterAt(yPx: Float): Char? {
        if (letters.isEmpty() || stripHeightPx <= 0f) return null
        val index = (yPx / stripHeightPx * letters.size).toInt().coerceIn(0, letters.lastIndex)
        return letters[index]
    }

    Box(
        modifier = modifier
            .width(48.dp) // gesture surface ≥48dp wide (a11y minimum)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .onSizeChanged { size -> stripHeightPx = size.height.toFloat() }
            .pointerInput(letters) {
                awaitEachGesture {
                    // Initial press: fire letter immediately so tap-without-drag works
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    letterAt(down.position.y)?.let { onLetterTap(it) }
                    // Continuous drag: fire on every position change (contacts-app behavior)
                    drag(down.id) { change ->
                        change.consume()
                        letterAt(change.position.y)?.let { onLetterTap(it) }
                    }
                }
            }
            .semantics { contentDescription = alphabetNavDesc },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                Text(
                    text = letter.toString(),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
