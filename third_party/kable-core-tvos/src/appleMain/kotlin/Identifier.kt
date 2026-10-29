package com.juul.kable

import kotlin.uuid.Uuid

actual typealias Identifier = Uuid

actual fun String.toIdentifier(): Identifier = Uuid.parse(this)
