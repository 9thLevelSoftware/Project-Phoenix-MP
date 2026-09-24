package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineCopyNamingTest {
    @Test
    fun firstCopyIsPlainThenNumbered() {
        assertEquals("Push (Copy)", routineCopyName("Push", listOf("Push")))
        assertEquals("Push (Copy 2)", routineCopyName("Push", listOf("Push", "Push (Copy)")))
        assertEquals("Push (Copy 4)", routineCopyName("Push", listOf("Push", "Push (Copy)", "Push (Copy 3)")))
    }

    @Test
    fun copyingACopyNumbersAgainstTheBaseName() {
        assertEquals("Push (Copy 3)", routineCopyName("Push (Copy 2)", listOf("Push", "Push (Copy)", "Push (Copy 2)")))
    }

    @Test
    fun regexCharactersInNamesAreLiteral() {
        assertEquals("A+B (Copy 2)", routineCopyName("A+B", listOf("A+B", "A+B (Copy)", "AAB (Copy 7)")))
    }
}
