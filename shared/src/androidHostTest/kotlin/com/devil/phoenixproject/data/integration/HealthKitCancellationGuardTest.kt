package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.testutil.extractFunctionBody
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HealthKit callbacks and the health toggle run inside broad `catch (Exception)`
 * blocks. [CancellationException] extends [Exception], so those catches must
 * rethrow it, and a HealthKit completion must not resume a cancelled continuation.
 *
 * The iOS HealthKit types are not available to androidHostTest, so this guard
 * reads the source the same way the other Health Connect contract tests do.
 */
class HealthKitCancellationGuardTest {

    @Test
    fun readLatestScaleBodyWeight_rethrowsCancellationAndResumesOnlyWhenActive() {
        val body = extractFunctionBody(
            source = read("shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt"),
            signature = "actual suspend fun readLatestScaleBodyWeight",
        )
        assertRethrowsCancellation(body, "readLatestScaleBodyWeight")
        assertResumesOnlyWhenActive(body, "readLatestScaleBodyWeight")
    }

    @Test
    fun requestPermissions_rethrowsCancellationAndResumesOnlyWhenActive() {
        val body = extractFunctionBody(
            source = read("shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt"),
            signature = "actual suspend fun requestPermissions",
        )
        assertRethrowsCancellation(body, "requestPermissions")
        assertResumesOnlyWhenActive(body, "requestPermissions")
    }

    @Test
    fun writeHealthWorkout_rethrowsCancellationAndResumesOnlyWhenActive() {
        val body = extractFunctionBody(
            source = read("shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt"),
            signature = "actual override suspend fun writeHealthWorkout",
        )
        assertRethrowsCancellation(body, "writeHealthWorkout")
        assertResumesOnlyWhenActive(body, "writeHealthWorkout")
    }

    @Test
    fun toggleHealthIntegration_rethrowsCancellation() {
        val body = extractFunctionBody(
            source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt"),
            signature = "fun toggleHealthIntegration",
        )
        assertRethrowsCancellation(body, "toggleHealthIntegration")
    }

    private fun assertRethrowsCancellation(functionBody: String, name: String) {
        val cancellationCatch = functionBody.indexOf("catch (e: CancellationException)")
        val exceptionCatch = functionBody.indexOf("catch (e: Exception)")
        assertTrue(cancellationCatch >= 0, "$name must catch CancellationException before Exception")
        assertTrue(
            exceptionCatch > cancellationCatch,
            "$name must catch CancellationException before the broad Exception handler",
        )
        val between = functionBody.substring(cancellationCatch, exceptionCatch)
        assertTrue(
            between.contains("throw e"),
            "$name must rethrow CancellationException instead of reporting it as a failure",
        )
    }

    private fun assertResumesOnlyWhenActive(functionBody: String, name: String) {
        val resumes = Regex("continuation\\.resume").findAll(functionBody).toList()
        assertTrue(resumes.isNotEmpty(), "$name must resume its suspended continuation")
        resumes.forEach { resume ->
            val beforeResume = functionBody.substring(0, resume.range.first)
            val guard = beforeResume.lastIndexOf("continuation.isActive")
            assertTrue(
                guard >= 0,
                "$name resumes a continuation without checking continuation.isActive",
            )
        }
    }

    private fun read(relativePath: String): String =
        requireNotNull(readProjectFile(relativePath)) { "Missing $relativePath" }
}
