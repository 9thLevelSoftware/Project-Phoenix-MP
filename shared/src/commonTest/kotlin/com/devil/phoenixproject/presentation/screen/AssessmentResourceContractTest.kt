package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class AssessmentResourceContractTest {
    @Test
    fun `save failure copy exists in every selectable locale`() {
        listOf(
            "values",
            "values-nl",
            "values-de",
            "values-es",
            "values-fr",
        ).forEach { directory ->
            val path = "src/commonMain/composeResources/$directory/strings.xml"
            val source = readProjectFile(path)
            assertNotNull(source, "Could not read $path")
            assertContains(
                charSequence = source,
                other = """name="assessment_save_failed"""",
                message = path,
            )
        }
    }
}
