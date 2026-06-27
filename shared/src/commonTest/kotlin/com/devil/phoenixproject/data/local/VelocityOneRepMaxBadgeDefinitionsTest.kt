package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VelocityOneRepMaxBadgeDefinitionsTest {
    @Test fun `three tiered velocity 1RM badges exist with STRENGTH category`() {
        val ids = listOf("velocity_1rm_1", "velocity_1rm_5", "velocity_1rm_15")
        val badges = ids.map { id -> assertNotNull(BadgeDefinitions.getBadgeById(id), "missing $id") }
        badges.forEach { assertEquals(BadgeCategory.STRENGTH, it.category) }
        assertEquals(
            listOf(1, 5, 15),
            badges.map { (it.requirement as BadgeRequirement.VelocityOneRepMaxImprovements).count },
        )
    }

    @Test fun `velocity 1RM badges are distinct from PR badges`() {
        val velocity = BadgeDefinitions.allBadges.filter { it.requirement is BadgeRequirement.VelocityOneRepMaxImprovements }
        val pr = BadgeDefinitions.allBadges.filter { it.requirement is BadgeRequirement.PRsAchieved }
        assertTrue(velocity.isNotEmpty() && pr.isNotEmpty())
        assertTrue(velocity.none { v -> pr.any { it.id == v.id } })
    }
}
