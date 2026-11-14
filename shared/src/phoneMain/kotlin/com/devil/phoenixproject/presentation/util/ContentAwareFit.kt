package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Issue #1385: content-aware fit decisions for responsive layouts.
 *
 * The set-summary metric grid and the RPE action row used to decide stacking from
 * fixed width/fontScale heuristics (`maxWidth/fontScale > 200f`, `< 180f`), which
 * know nothing about the rendered content: long or localized strings could still be
 * squeezed into a half-width cell and break mid-word. These helpers compare actually
 * measured text widths instead. Callers measure each control's required width with a
 * [TextMeasurer] at the current font scale and pass the resulting dp here, so the
 * rendered content — not a guess about it — drives the layout decision.
 */
object ContentAwareFit {

    /**
     * Characters after which a soft line break is legal, mirroring the #1345 layout
     * detector: a wrapped line may start after whitespace or one of these.
     */
    private val BREAK_AFTER = setOf('-', '/', '•', '—')

    /**
     * The longest run of [text] that soft wrapping cannot break: the minimum width
     * the text needs to render without a mid-word break. A line may start after
     * whitespace or one of [BREAK_AFTER], so runs split there. Leading/trailing
     * separator whitespace is trimmed from each run before comparing.
     */
    fun longestUnbreakableRun(text: String): String {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch.isWhitespace() || ch in BREAK_AFTER) {
                runs += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) runs += current.toString()
        return runs.maxByOrNull { it.trim().length }?.trim() ?: ""
    }

    /**
     * The width [text] needs to fit its layout slot.
     *
     * [singleLine] = true measures the whole string: tokens like `(lbs/cable)` or a
     * button label must stay on ONE line, so the slot has to fit the full text.
     * [singleLine] = false measures only [longestUnbreakableRun]: labels and values
     * may wrap at spaces or separators, they just must not break mid-word.
     */
    fun requiredTextWidthDp(
        text: String,
        style: TextStyle,
        singleLine: Boolean,
        measurer: TextMeasurer,
        density: Density,
    ): Dp {
        val measured = if (singleLine) text else longestUnbreakableRun(text)
        return with(density) {
            measurer.measure(AnnotatedString(measured), style = style).size.width.toDp()
        }
    }

    /**
     * A font-scale factor (≤ 1) that keeps [text] on ONE line inside [availableDp] at
      * the given [style] — content-aware shrink-to-fit for unbreakable number tokens
      * like the total-volume value, which must never wrap mid-token (#1385). The factor
      * is measured from the live [measurer] so long or localized content is handled,
      * and stays 1 whenever the text already fits. Text width scales linearly with font
      * size, so one measurement plus a 2% margin yields an exact fit.
      */
     fun singleLineFitFontScale(
         text: String,
         style: TextStyle,
         availableDp: Float,
         measurer: TextMeasurer,
         density: Density,
     ): Float {
         if (text.isEmpty() || availableDp <= 0f) return 1f
         val required = requiredTextWidthDp(text, style, singleLine = true, measurer, density).value
         if (required <= availableDp) return 1f
         return (availableDp / required * 0.98f).coerceIn(0.25f, 1f)
     }

     /** True when two equal-weight cells side by side each get at least its own
     * required content width. A [androidx.compose.foundation.layout.Row] with equal
     * `weight(1f)` children gives each half the available width minus [spacingDp],
     * so both cells must fit their half — summing the widths is not enough when one
     * cell needs much more room than the other.
     */
    fun equalColumnsFit(
        firstRequiredDp: Float,
        secondRequiredDp: Float,
        spacingDp: Float,
        availableDp: Float,
    ): Boolean {
        val half = (availableDp - spacingDp) / 2f
        return half >= firstRequiredDp && half >= secondRequiredDp
    }
}
