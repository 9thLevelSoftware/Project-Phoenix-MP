package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.RackItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val rackItemsJson = Json { encodeDefaults = true }

fun encodeRackItemsJson(items: List<RackItem>): String = rackItemsJson.encodeToString(items)
