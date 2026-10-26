@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package com.devil.phoenixproject.data.local

import projectphoenix.shared.generated.resources.Res

internal actual suspend fun readBundledExerciseCatalogue(): ByteArray =
    Res.readBytes("files/exercises.json")
