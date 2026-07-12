package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileResourceContractTest {
    private val selectableLocales = linkedMapOf(
        "en" to "values",
        "nl" to "values-nl",
        "de" to "values-de",
        "es" to "values-es",
        "fr" to "values-fr",
    )

    private val files = selectableLocales.mapValues { (_, directory) ->
        "src/commonMain/composeResources/$directory/strings.xml"
    }

    private val newKeys = listOf(
        "nav_profile",
        "cd_profile",
        "cd_open_profile_switcher",
        "profiles_title",
        "switch_profile",
        "profile_exercise_insights",
        "profile_choose_exercise",
        "profile_no_exercise_history",
        "profile_current_one_rep_max",
        "profile_one_rep_max_source_velocity",
        "profile_one_rep_max_source_assessment",
        "profile_one_rep_max_source_session",
        "profile_one_rep_max_source_none",
        "profile_pr_highlights",
        "profile_pr_max_weight",
        "profile_pr_estimated_one_rep_max",
        "profile_pr_max_volume",
        "profile_recent_history",
        "profile_view_full_history",
        "profile_preferences_title",
        "profile_measurements",
        "profile_workout_behavior",
        "profile_led",
        "profile_vbt",
        "profile_safety",
        "profile_vbt_enabled",
        "profile_switching",
        "profile_missing_exercise",
        "profile_insights_load_failed",
        "profile_update_failed",
        "profile_switch_failed",
        "profile_create_failed",
        "profile_recovery_title",
        "profile_recovery_message",
        "profile_recovery_retry_failed",
        "profile_delete_reassign_message",
        "settings_video_behavior",
        "settings_show_exercise_videos",
        "settings_show_exercise_videos_description",
        "profile_weight_increment",
        "profile_body_weight",
        "profile_set_summary",
        "profile_autostart_countdown",
        "profile_auto_start_routine",
        "profile_audio_rep_counter",
        "profile_countdown_beeps",
        "profile_rep_completion_sound",
        "profile_motion_start",
        "profile_gamification",
        "profile_default_scaling_basis",
        "profile_routine_starting_weights",
        "profile_stop_at_top",
        "profile_stall_detection",
        "profile_velocity_loss_threshold",
        "profile_auto_end_velocity_loss",
        "profile_vbt_history_note",
    )

    private val reusedKeys = listOf(
        "settings_weight_unit",
        "equipment_rack_title",
        "equipment_rack_manage",
        "cd_led_scheme",
    )

    private val expectedLedSchemeLabels = mapOf(
        "en" to "LED color scheme",
        "nl" to "LED-kleurenschema",
        "de" to "LED-Farbschema",
        "es" to "Esquema de color LED",
        "fr" to "Palette de couleurs LED",
    )

    private val expectedPlaceholders = mapOf(
        "profile_delete_reassign_message" to listOf("%1${'$'}s"),
    )

    private val placeholderPattern = Regex("""%\d+\${'$'}[A-Za-z]""")
    private val invalidAmpersandPattern =
        Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9A-Fa-f]+;)""")

    @Test
    fun contractInventoryIsUniqueAndTracksExactlyFiveSelectableLocales() {
        assertEquals(56, newKeys.size)
        assertEquals(newKeys.size, newKeys.toSet().size)
        assertTrue(newKeys.intersect(reusedKeys.toSet()).isEmpty())

        val settings = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt",
            ),
        )
        selectableLocales.keys.forEach { languageCode ->
            assertContains(settings, "\"$languageCode\" to stringResource")
        }
        assertFalse(settings.contains("\"it\" to stringResource"))
    }

    @Test
    fun selectableLocalesContainOneWellFormedDeclarationPerContractKey() {
        files.forEach { (languageCode, path) ->
            val source = assertNotNull(readProjectFile(path), "Missing $path")

            val names = Regex("""<string\s+name="([^"]+)"""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
            val duplicateNames = names.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
            assertTrue(duplicateNames.isEmpty(), "$path duplicates $duplicateNames")
            assertFalse(
                invalidAmpersandPattern.containsMatchIn(source),
                "$path contains an unescaped XML ampersand",
            )

            (newKeys + reusedKeys).forEach { key ->
                val declarationCount = Regex("""<string\s+name="$key"(?:\s|>)""")
                    .findAll(source)
                    .count()
                assertEquals(1, declarationCount, "$path declaration count for $key")
            }
            assertEquals(
                expectedLedSchemeLabels.getValue(languageCode),
                resourceValue(source, "cd_led_scheme", path),
                "$path translated LED scheme label",
            )

            newKeys.forEach { key ->
                val value = resourceValue(source, key, path)
                val placeholders = placeholderPattern.findAll(value)
                    .map { it.value }
                    .toList()
                assertEquals(
                    expectedPlaceholders[key].orEmpty(),
                    placeholders,
                    "$path placeholder contract for $key",
                )
            }
        }
    }

    @Test
    fun sourceReaderActualsSupportSharedRelativeContractPaths() {
        val androidReader = assertNotNull(
            readProjectFile(
                "src/androidHostTest/kotlin/com/devil/phoenixproject/testutil/SourceFileReader.android.kt",
            ),
        )
        val iosReader = assertNotNull(
            readProjectFile(
                "src/iosTest/kotlin/com/devil/phoenixproject/testutil/SourceFileReader.ios.kt",
            ),
        )

        assertContains(androidReader, """File(dir, "shared/${'$'}relativePath")""")
        assertContains(iosReader, """candidates.add("${'$'}dir/shared/${'$'}relativePath")""")
    }

    private fun resourceValue(source: String, key: String, path: String): String =
        assertNotNull(
            Regex(
                """<string\s+name="$key"[^>]*>(.*?)</string>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(source)?.groupValues?.get(1),
            "$path is missing the value for $key",
        )
}
