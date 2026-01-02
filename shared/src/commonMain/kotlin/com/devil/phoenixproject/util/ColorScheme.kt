package com.devil.phoenixproject.util

/**
 * Color scheme data class
 */
data class ColorScheme(
    val name: String,
    val brightness: Float,
    val colors: List<RGBColor>
)

/**
 * Predefined color schemes
 */
object ColorSchemes {
    val BLUE = ColorScheme(
        name = "Blue",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0x00, 0xA8, 0xDD),
            RGBColor(0x00, 0xCF, 0xFC),
            RGBColor(0x5D, 0xDF, 0xFC)
        )
    )

    val GREEN = ColorScheme(
        name = "Green",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0x7D, 0xC1, 0x47),
            RGBColor(0xA1, 0xD8, 0x6A),
            RGBColor(0xBA, 0xE0, 0x94)
        )
    )

    val TEAL = ColorScheme(
        name = "Teal",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0x3E, 0x9A, 0xB7),
            RGBColor(0x83, 0xBE, 0xD1),
            RGBColor(0xC2, 0xDF, 0xE8)
        )
    )

    val YELLOW = ColorScheme(
        name = "Yellow",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0xFF, 0x90, 0x51),
            RGBColor(0xFF, 0xD6, 0x47),
            RGBColor(0xFF, 0xB7, 0x00)
        )
    )

    val PINK = ColorScheme(
        name = "Pink",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0xFF, 0x00, 0x4C),
            RGBColor(0xFF, 0x23, 0x8C),
            RGBColor(0xFF, 0x8C, 0x8C)
        )
    )

    val RED = ColorScheme(
        name = "Red",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0xFF, 0x00, 0x00),
            RGBColor(0xFF, 0x55, 0x55),
            RGBColor(0xFF, 0xAA, 0xAA)
        )
    )

    val PURPLE = ColorScheme(
        name = "Purple",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0x88, 0x00, 0xFF),
            RGBColor(0xAA, 0x55, 0xFF),
            RGBColor(0xDD, 0xAA, 0xFF)
        )
    )

    /**
     * "None" turns off the LED lights by sending black (0,0,0) colors.
     * This matches the official app's implementation.
     */
    val NONE = ColorScheme(
        name = "None",
        brightness = 0.4f,
        colors = listOf(
            RGBColor(0x00, 0x00, 0x00),
            RGBColor(0x00, 0x00, 0x00),
            RGBColor(0x00, 0x00, 0x00)
        )
    )

    val ALL = listOf(BLUE, GREEN, TEAL, YELLOW, PINK, RED, PURPLE, NONE)
}
