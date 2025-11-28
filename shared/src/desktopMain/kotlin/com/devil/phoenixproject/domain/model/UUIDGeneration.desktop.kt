package com.devil.phoenixproject.domain.model

import java.util.UUID

actual fun generateUUID(): String = UUID.randomUUID().toString()
