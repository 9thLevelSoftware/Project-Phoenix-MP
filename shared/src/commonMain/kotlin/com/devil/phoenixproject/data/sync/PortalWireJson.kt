package com.devil.phoenixproject.data.sync

import kotlinx.serialization.json.Json

internal val PortalWireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
