package com.devil.phoenixproject.util

/**
 * RGB Color data class
 */
data class RGBColor(
    val r: Int,
    val g: Int,
    val b: Int
) {
    init {
        require(r in 0..255) { "Red value must be 0-255" }
        require(g in 0..255) { "Green value must be 0-255" }
        require(b in 0..255) { "Blue value must be 0-255" }
    }
}
