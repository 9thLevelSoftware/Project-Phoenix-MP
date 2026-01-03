package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for RGBColor and ColorScheme classes.
 */
class ColorSchemeTest {

    // ========== RGBColor Tests ==========

    @Test
    fun `RGBColor accepts valid values`() {
        val color = RGBColor(128, 64, 255)

        assertEquals(128, color.r)
        assertEquals(64, color.g)
        assertEquals(255, color.b)
    }

    @Test
    fun `RGBColor accepts boundary values`() {
        val black = RGBColor(0, 0, 0)
        val white = RGBColor(255, 255, 255)

        assertEquals(0, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)

        assertEquals(255, white.r)
        assertEquals(255, white.g)
        assertEquals(255, white.b)
    }

    @Test
    fun `RGBColor rejects negative red value`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(-1, 0, 0)
        }
    }

    @Test
    fun `RGBColor rejects negative green value`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(0, -1, 0)
        }
    }

    @Test
    fun `RGBColor rejects negative blue value`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(0, 0, -1)
        }
    }

    @Test
    fun `RGBColor rejects red value over 255`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(256, 0, 0)
        }
    }

    @Test
    fun `RGBColor rejects green value over 255`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(0, 256, 0)
        }
    }

    @Test
    fun `RGBColor rejects blue value over 255`() {
        assertFailsWith<IllegalArgumentException> {
            RGBColor(0, 0, 256)
        }
    }

    @Test
    fun `RGBColor supports hexadecimal notation`() {
        val color = RGBColor(0xFF, 0x80, 0x00)

        assertEquals(255, color.r)
        assertEquals(128, color.g)
        assertEquals(0, color.b)
    }

    // ========== ColorScheme Tests ==========

    @Test
    fun `ColorScheme has correct structure`() {
        val scheme = ColorScheme(
            name = "Test",
            brightness = 0.5f,
            colors = listOf(
                RGBColor(255, 0, 0),
                RGBColor(0, 255, 0),
                RGBColor(0, 0, 255)
            )
        )

        assertEquals("Test", scheme.name)
        assertEquals(0.5f, scheme.brightness)
        assertEquals(3, scheme.colors.size)
    }

    // ========== Predefined Schemes Tests ==========

    @Test
    fun `ColorSchemes_ALL contains 8 schemes`() {
        assertEquals(8, ColorSchemes.ALL.size)
    }

    @Test
    fun `ColorSchemes_ALL contains BLUE scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.BLUE))
        assertEquals("Blue", ColorSchemes.BLUE.name)
    }

    @Test
    fun `ColorSchemes_ALL contains GREEN scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.GREEN))
        assertEquals("Green", ColorSchemes.GREEN.name)
    }

    @Test
    fun `ColorSchemes_ALL contains TEAL scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.TEAL))
        assertEquals("Teal", ColorSchemes.TEAL.name)
    }

    @Test
    fun `ColorSchemes_ALL contains YELLOW scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.YELLOW))
        assertEquals("Yellow", ColorSchemes.YELLOW.name)
    }

    @Test
    fun `ColorSchemes_ALL contains PINK scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.PINK))
        assertEquals("Pink", ColorSchemes.PINK.name)
    }

    @Test
    fun `ColorSchemes_ALL contains RED scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.RED))
        assertEquals("Red", ColorSchemes.RED.name)
    }

    @Test
    fun `ColorSchemes_ALL contains PURPLE scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.PURPLE))
        assertEquals("Purple", ColorSchemes.PURPLE.name)
    }

    @Test
    fun `ColorSchemes_ALL contains NONE scheme`() {
        assertTrue(ColorSchemes.ALL.contains(ColorSchemes.NONE))
        assertEquals("None", ColorSchemes.NONE.name)
    }

    @Test
    fun `all predefined schemes have 3 colors`() {
        for (scheme in ColorSchemes.ALL) {
            assertEquals(3, scheme.colors.size, "Scheme ${scheme.name} should have 3 colors")
        }
    }

    @Test
    fun `all predefined schemes have 0_4 brightness`() {
        for (scheme in ColorSchemes.ALL) {
            assertEquals(0.4f, scheme.brightness, "Scheme ${scheme.name} should have 0.4 brightness")
        }
    }

    @Test
    fun `NONE scheme has black colors for LED off`() {
        for (color in ColorSchemes.NONE.colors) {
            assertEquals(0, color.r, "NONE scheme should have r=0")
            assertEquals(0, color.g, "NONE scheme should have g=0")
            assertEquals(0, color.b, "NONE scheme should have b=0")
        }
    }

    @Test
    fun `BLUE scheme first color is correct`() {
        val firstColor = ColorSchemes.BLUE.colors[0]

        assertEquals(0x00, firstColor.r)
        assertEquals(0xA8, firstColor.g)
        assertEquals(0xDD, firstColor.b)
    }

    @Test
    fun `RED scheme first color is pure red`() {
        val firstColor = ColorSchemes.RED.colors[0]

        assertEquals(0xFF, firstColor.r)
        assertEquals(0x00, firstColor.g)
        assertEquals(0x00, firstColor.b)
    }
}
