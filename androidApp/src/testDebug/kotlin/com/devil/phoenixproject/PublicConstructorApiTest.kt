package com.devil.phoenixproject

import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.presentation.manager.ActiveSessionEngine
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlin.test.assertNotNull
import org.junit.Test

class PublicConstructorApiTest {
    @Test
    fun `shared constructors remain callable from a non-friend consumer module`() {
        assertNotNull(::ActiveSessionEngine)
        assertNotNull(::DefaultWorkoutSessionManager)
        assertNotNull(::MainViewModel)
        assertNotNull(::DropSetEligibilityPolicy)
    }
}
