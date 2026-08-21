package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DropSetResourceContractTest {
    private val locales = linkedMapOf(
        "en" to "values",
        "nl" to "values-nl",
        "de" to "values-de",
        "es" to "values-es",
        "fr" to "values-fr",
        "it" to "values-it",
    )

    private val requiredKeys = listOf(
        "drop_set_title",
        "drop_set_offer_toggle",
        "drop_set_min_weight",
        "drop_set_min_weight_supporting",
        "drop_set_min_weight_error",
        "drop_set_offer_question",
        "drop_set_retry_set",
        "drop_set_skip",
        "drop_set_candidate_unavailable",
        "drop_set_remaining_one",
        "drop_set_remaining_many",
        "drop_set_accepted_waiting",
        "drop_set_saving",
        "drop_set_preparing",
        "drop_set_ready",
        "drop_set_recovery",
        "drop_set_skip_rest_blocked",
        "drop_set_waiting_timer",
        "cd_drop_set_candidate",
        "cd_drop_set_candidate_unavailable",
        "cd_drop_set_retry_disabled",
    )

    @Test
    fun everyLocaleHasReviewedDropSetKeys() {
        val english = requireNotNull(
            readProjectFile("src/commonMain/composeResources/values/strings.xml"),
        )
        assertContains(english, "Offer drop set after failure.")
        assertContains(english, "Retry this set with a drop set?")

        locales.forEach { (locale, directory) ->
            val contents = readProjectFile("src/commonMain/composeResources/$directory/strings.xml")
            assertNotNull(contents, "Missing strings.xml for $locale")
            requiredKeys.forEach { key ->
                assertTrue(
                    contents.contains("<string name=\"$key\">"),
                    "$locale is missing $key",
                )
            }
        }
    }
}
