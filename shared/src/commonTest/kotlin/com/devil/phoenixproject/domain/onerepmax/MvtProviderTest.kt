package com.devil.phoenixproject.domain.onerepmax

import kotlin.test.Test
import kotlin.test.assertEquals

class MvtProviderTest {
    private val provider = MvtProvider()

    @Test fun `falls back to pattern default when no personal or override`() =
        assertEquals(0.30f, provider.resolve("Back Squat", "Legs", null, null, 0))

    @Test fun `personal mvt overrides pattern once threshold met`() =
        assertEquals(0.22f, provider.resolve("Back Squat", "Legs", null, 0.22f, 3))

    @Test fun `personal mvt ignored below sample threshold`() =
        assertEquals(0.30f, provider.resolve("Back Squat", "Legs", null, 0.22f, 2))

    @Test fun `user override beats personal and pattern`() =
        assertEquals(0.18f, provider.resolve("Back Squat", "Legs", 0.18f, 0.22f, 5))

    @Test fun `non-positive override is ignored`() =
        assertEquals(0.20f, provider.resolve("Cable Fly", "Chest", 0f, null, 0))
}
