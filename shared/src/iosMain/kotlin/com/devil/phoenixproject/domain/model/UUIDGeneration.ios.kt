package com.devil.phoenixproject.domain.model

import platform.Foundation.NSUUID

actual fun generateUUID(): String = NSUUID().UUIDString()
