package com.devil.phoenixproject.domain.model

data class DiagnosticDetails(
    val seconds: Int,
    val faults: List<Short>,
    val temps: List<Byte>,
    val containsFaults: Boolean,
    val timestamp: Long = currentTimeMillis()
)
