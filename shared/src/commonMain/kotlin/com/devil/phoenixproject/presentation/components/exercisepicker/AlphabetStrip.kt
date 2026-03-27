package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
 */
@Composable
fun AlphabetStrip(letters: List<Char>, onLetterTap: (Char) -> Unit, modifier: Modifier = Modifier) {
    val alphabetNavDesc = stringResource(Res.string.cd_alphabet_nav)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics {
                contentDescription = alphabetNavDesc
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                val letterDesc = stringResource(Res.string.cd_jump_to_letter, letter.toString())
                Text(
                    text = letter.toString(),
                    modifier = Modifier
                        .semantics {
                            contentDescription = letterDesc
                            role = Role.Button
                        }
                        .clickable { onLetterTap(letter) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
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
