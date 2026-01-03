package com.devil.phoenixproject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit Rule for testing coroutines with ViewModels.
 *
 * Replaces the Main dispatcher with a TestDispatcher for predictable testing.
 * Use this in any test class that tests ViewModels or code that uses Dispatchers.Main.
 *
 * Usage:
 * ```
 * class MyViewModelTest {
 *     @get:Rule
 *     val coroutineRule = TestCoroutineRule()
 *
 *     @Test
 *     fun `test something`() = runTest {
 *         // Your test code here
 *         // Use coroutineRule.dispatcher.scheduler.advanceTimeBy() for time control
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestCoroutineRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
