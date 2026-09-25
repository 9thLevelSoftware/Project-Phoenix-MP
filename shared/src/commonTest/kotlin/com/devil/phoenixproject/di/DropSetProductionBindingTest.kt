package com.devil.phoenixproject.di

import com.devil.phoenixproject.domain.model.DisabledDropSetFeatureGate
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.DropSetIneligibleReason
import com.devil.phoenixproject.domain.model.EnabledDropSetFeatureGate
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityRequest
import com.devil.phoenixproject.presentation.manager.ExecutionLease
import com.devil.phoenixproject.presentation.manager.completionFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

class DropSetProductionBindingTest {
    @Test
    fun productionPolicyResolvesWithEnabledGate() {
        val application = koinApplication { modules(domainModule) }
        try {
            assertEquals(EnabledDropSetFeatureGate, application.koin.get<DropSetFeatureGate>())
            assertTrue(application.koin.get<DropSetFeatureGate>().isEnabled())
        } finally {
            application.close()
        }
    }

    @Test
    fun disabledGateStillFailsClosedWithoutDeletingConfiguration() {
        val policy = DropSetEligibilityPolicy(DisabledDropSetFeatureGate, DropSetCandidateResolver())
        val lease = ExecutionLease(1, "session", "profile", true, 8, false, false, false, false)
        val configuration = DropSetConfiguration(true, 1f)
        val result = policy.evaluate(
            DropSetEligibilityRequest(
                offerId = "offer",
                completion = completionFixture(lease, SetEndReason.STALL_FAILURE),
                configuration = configuration,
                expectedLiveIdentity = null,
                commandTemplate = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 20f),
            ),
        )
        assertEquals(
            DropSetIneligibleReason.FEATURE_GATED,
            assertIs<DropSetEligibilityResult.Ineligible>(result).reason,
        )
        assertTrue(configuration.enabled)
        assertEquals(1f, configuration.minimumWeightPerCableKg)
    }
}
