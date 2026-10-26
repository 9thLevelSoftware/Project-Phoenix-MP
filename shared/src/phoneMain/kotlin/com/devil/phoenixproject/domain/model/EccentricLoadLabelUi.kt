package com.devil.phoenixproject.domain.model

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.echo_level_epic
import projectphoenix.shared.generated.resources.echo_level_hard
import projectphoenix.shared.generated.resources.echo_level_harder
import projectphoenix.shared.generated.resources.echo_level_hardest

@Composable
fun percentLabel(value: Number): String = formatPercent(value, currentLanguageCode())

@Composable
fun eccentricLoadLabel(load: EccentricLoad): String = percentLabel(load.percentage)

@Composable
fun echoLevelLabel(level: EchoLevel): String = when (level) {
    EchoLevel.HARD -> stringResource(Res.string.echo_level_hard)
    EchoLevel.HARDER -> stringResource(Res.string.echo_level_harder)
    EchoLevel.HARDEST -> stringResource(Res.string.echo_level_hardest)
    EchoLevel.EPIC -> stringResource(Res.string.echo_level_epic)
}
