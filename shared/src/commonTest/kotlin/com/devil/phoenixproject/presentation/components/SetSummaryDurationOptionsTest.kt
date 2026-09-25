package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #849: Manual Set Summary option. */
class SetSummaryDurationOptionsTest {

    @Test
    fun manualIsTheStoredZeroValueListedAfterThirtySeconds() {
        assertEquals(listOf(-1, 5, 10, 15, 20, 25, 30, 0), SET_SUMMARY_DURATION_OPTIONS)
        assertEquals(0, SET_SUMMARY_MANUAL)
        assertEquals(SET_SUMMARY_MANUAL, SET_SUMMARY_DURATION_OPTIONS.last())
        assertEquals(SET_SUMMARY_SKIP, SET_SUMMARY_DURATION_OPTIONS.first())
    }

    @Test
    fun everyOfferedDurationIsAcceptedByTheValidatorAndNoneIsMissing() {
        SET_SUMMARY_DURATION_OPTIONS.forEach { seconds ->
            val errors = ProfilePreferencesValidator.workout(WorkoutPreferences(summaryCountdownSeconds = seconds))
            assertTrue("summaryCountdownSeconds" !in errors, "validator rejects offered value $seconds")
        }
        assertEquals(8, SET_SUMMARY_DURATION_OPTIONS.toSet().size)
    }

    @Test
    fun manualLabelIsLocalisedInEverySelectableLanguage() {
        val components = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt",
            ),
        )
        assertContains(components, "SET_SUMMARY_MANUAL -> stringResource(Res.string.profile_manual)")

        listOf("values", "values-de", "values-es", "values-fr", "values-nl").forEach { locale ->
            val strings = requireNotNull(readProjectFile("src/commonMain/composeResources/$locale/strings.xml"))
            assertTrue(
                Regex("""<string name="profile_manual">[^<]+</string>""").containsMatchIn(strings),
                "$locale is missing profile_manual",
            )
        }
    }
}
