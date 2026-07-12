package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.buildProfileRecentHistory
import com.devil.phoenixproject.presentation.viewmodel.ProfileIdentityMutationKind
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileScreenContractTest {
    private val screenPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt"
    private val insightsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt"
    private val preferenceComponentsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfilePreferenceComponents.kt"
    private val safetyDialogsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt"
    private val settingsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt"
    private val navGraphPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt"

    @Test
    fun screenTagAndNonReadyLoaderUseTheRealLoadingIndicatorApi() {
        val screen = source(screenPath)
        val tags = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt",
        )

        assertContains(tags, "const val SCREEN_PROFILE")
        assertContains(screen, "TestTags.SCREEN_PROFILE")
        assertContains(screen, "LoadingIndicator(LoadingIndicatorSize.Large)")
        assertContains(screen, "Res.string.profile_switching")
        assertFalse(screen.contains("message ="))
    }

    @Test
    fun selectionFailurePrecedesMissingAndEmptyWhilePickerRemainsAvailable() {
        val insights = source(insightsPath)
        val selector = insights.indexOf("onChooseExercise")
        val failure = insights.indexOf("selectionFailure != null")
        val missing = insights.indexOf("missingExerciseId != null")
        val empty = insights.indexOf("Res.string.profile_no_exercise_history")

        assertTrue(selector >= 0)
        assertTrue(failure in 0 until missing, "selection failure must precede missing")
        assertTrue(missing in 0 until empty, "missing must precede ordinary empty")
        assertContains(insights, "Res.string.profile_insights_load_failed")
    }

    @Test
    fun pickerAndInsightsUseTheCompleteResourceAndSourceInventory() {
        val screen = source(screenPath)
        val insights = source(insightsPath)

        assertContains(screen, "ExercisePickerDialog(")
        assertContains(screen, "enableCustomExercises = false")
        assertFalse(insights.contains("oneRepMaxKg"))
        listOf(
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
            "profile_missing_exercise",
            "profile_insights_load_failed",
        ).forEach { key -> assertContains(insights, "Res.string.$key", message = key) }
    }

    @Test
    fun recentHistoryIsDeterministicBoundedChronologicalAndFinite() {
        val result = buildProfileRecentHistory(
            sessions = listOf(
                session("old", 1, 10f),
                session("outside", 2, 20f),
                session("zero", 3, 0f),
                session("middle", 4, 40f),
                session("nan", 5, Float.NaN),
                session("tie-a", 6, 50f),
                session("tie-b", 6, 60f),
            ),
            labelForTimestamp = Long::toString,
        )

        assertEquals(
            listOf("tie-b", "tie-a", "nan", "middle", "zero"),
            result.sessionsNewestFirst.map(WorkoutSession::id),
        )
        assertEquals(listOf("4", "6", "6"), result.chartPointsOldestFirst.map { it.label })
        assertEquals(listOf(40f, 50f, 60f), result.chartPointsOldestFirst.map { it.volume })
        assertTrue(result.chartPointsOldestFirst.all { it.volume.isFinite() && it.volume > 0f })
    }

    @Test
    fun prVolumeIsExplicitlyPerCableAndNeverUsesCableMultiplication() {
        val insights = source(insightsPath)
        val start = insights.indexOf("fun ProfilePrHighlightsCard(")
        val end = insights.indexOf("fun ProfileRecentHistoryCard(")
        assertTrue(start >= 0 && end > start)
        val prCard = insights.substring(start, end)

        assertContains(prCard, "maxVolumeKg")
        assertContains(prCard, "formatPerCableWeight")
        assertContains(prCard, "per-cable kg x reps")
        assertFalse(prCard.contains("cableCount"))
        assertFalse(prCard.contains("effectiveTotalVolumeKg"))
    }

    @Test
    fun overlaysEventsActionsAndTagsAreProfileScopedAndAccessible() {
        val screen = source(screenPath)
        val dialogs = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
        )

        listOf("pickerProfileId", "editTargetProfileId", "deleteTargetProfileId").forEach {
            assertContains(screen, it)
        }
        assertContains(screen, "event.profileId")
        assertContains(screen, "event.kind")
        assertContains(screen, "ProfileUiEvent.ProfileRecoveryRequired")
        assertContains(screen, "heightIn(min = 48.dp)")
        assertContains(screen, "Role.Button")
        assertContains(screen, "contentDescription")
        assertEquals(1, Regex("TestTags\\.ACTION_EDIT_PROFILE").findAll(screen).count())
        assertEquals(1, Regex("TestTags\\.ACTION_DELETE_PROFILE").findAll(screen).count())
        assertFalse(dialogs.contains("TestTags.ACTION_EDIT_PROFILE"))
        assertFalse(dialogs.contains("TestTags.ACTION_DELETE_PROFILE"))
    }

    @Test
    fun deletionOverlayOwnershipSurvivesSwitchingRollbackAndScopedFailure() {
        val initial = ProfileIdentityOverlayOwnership(
            deleteTargetProfileId = "a",
            pendingIdentityProfileId = "a",
        )

        val switching = retainProfileIdentityOverlayOwnership(
            ownership = initial,
            readyProfileId = null,
        )
        assertEquals(initial, switching)

        val rolledBack = retainProfileIdentityOverlayOwnership(
            ownership = switching,
            readyProfileId = "a",
        )
        assertEquals(initial, rolledBack)

        val failure = applyProfileIdentityFailure(
            ownership = rolledBack,
            profileId = "a",
            kind = ProfileIdentityMutationKind.DELETE,
        )
        assertTrue(failure.showError)
        assertEquals(
            ProfileIdentityOverlayOwnership(deleteTargetProfileId = "a"),
            failure.ownership,
        )

        assertEquals(
            ProfileIdentityOverlayOwnership(),
            retainProfileIdentityOverlayOwnership(
                ownership = rolledBack,
                readyProfileId = "default",
            ),
        )
    }

    @Test
    fun preferenceResourceInventoryOccursOnceInEveryLocaleAndVisibleCopyIsNotHardcoded() {
        val english = linkedMapOf(
            "profile_automatic" to "Automatic",
            "profile_default_rest" to "Default rest",
            "profile_master_beeps" to "Workout beeps",
            "profile_rep_count_timing" to "Rep count timing",
            "profile_body_weight_unset" to "Not set",
            "profile_body_weight_invalid" to "Enter a body weight from 20 to 300 kg",
            "profile_body_weight_imported" to "Matches an imported health measurement",
            "profile_led_scheme_blue" to "Blue",
            "profile_led_scheme_green" to "Green",
            "profile_led_scheme_teal" to "Teal",
            "profile_led_scheme_yellow" to "Yellow",
            "profile_led_scheme_pink" to "Pink",
            "profile_led_scheme_red" to "Red",
            "profile_led_scheme_purple" to "Purple",
            "profile_led_scheme_none" to "None",
            "cd_select_led_scheme" to "Select LED scheme: %1\$s",
            "profile_disco_mode" to "Disco Mode",
            "profile_disco_requires_connection" to "Connect to your trainer to use Disco Mode",
            "profile_disco_unlocked_title" to "Disco Mode unlocked",
            "profile_disco_unlocked_body" to
                "Turn on Disco Mode in LED preferences to make your trainer party.",
            "profile_disco_unlocked_action" to "Let's party",
            "profile_adult_enable_partial_failure" to
                "Age confirmation was saved, but Vulgar Mode could not be enabled. Try again.",
        )
        val placeholderPattern = Regex("""%\d+\${'$'}[A-Za-z]""")
        fun placeholderMultiset(value: String): Map<String, Int> = placeholderPattern
            .findAll(value)
            .map { it.value }
            .groupingBy { it }
            .eachCount()

        val localeValues = linkedMapOf<String, Map<String, String>>()
        listOf("values", "values-de", "values-es", "values-fr", "values-nl").forEach { locale ->
            val xml = source("src/commonMain/composeResources/$locale/strings.xml")
            english.keys.forEach { key ->
                assertEquals(
                    1,
                    Regex("""name\s*=\s*\"${Regex.escape(key)}\"""").findAll(xml).count(),
                    "$locale:$key",
                )
            }
            localeValues[locale] = english.keys.associateWith { key -> resourceValue(xml, key) }
        }
        assertEquals(english, localeValues.getValue("values"))
        localeValues.forEach { (locale, localized) ->
            english.forEach { (key, value) ->
                assertEquals(
                    placeholderMultiset(value),
                    placeholderMultiset(localized.getValue(key)),
                    "$locale:$key placeholder multiset",
                )
            }
        }

        val proseThatMustBeTranslated = listOf(
            "profile_automatic",
            "profile_default_rest",
            "profile_master_beeps",
            "profile_rep_count_timing",
            "profile_body_weight_unset",
            "profile_body_weight_invalid",
            "profile_body_weight_imported",
            "cd_select_led_scheme",
            "profile_disco_requires_connection",
            "profile_disco_unlocked_title",
            "profile_disco_unlocked_body",
            "profile_disco_unlocked_action",
            "profile_adult_enable_partial_failure",
        )
        listOf("values-de", "values-es", "values-fr", "values-nl").forEach { locale ->
            val localized = localeValues.getValue(locale)
            proseThatMustBeTranslated.forEach { key ->
                assertFalse(
                    localized.getValue(key).equals(english.getValue(key), ignoreCase = true),
                    "$locale must translate $key idiomatically",
                )
            }
            assertTrue(
                english.keys.count { key -> localized.getValue(key) != english.getValue(key) } >= 18,
                "$locale contains too many copied English preference strings",
            )
        }

        val visibleSource =
            source(screenPath) + source(preferenceComponentsPath) + source(safetyDialogsPath)
        english.forEach { (key, value) ->
            assertContains(visibleSource, "Res.string.$key", message = key)
            assertFalse(visibleSource.contains("\"$value\""), "hardcoded visible copy: $value")
        }
        assertFalse(visibleSource.contains("scheme.name"))
        assertFalse(visibleSource.contains("ColorScheme.name"))
    }

    @Test
    fun profileScreenKeepsOneEventCollectorAndFiltersCurrentProfileAndTrackedToken() {
        val screen = source(screenPath)
        assertEquals(1, Regex("viewModel\\.events\\.collect").findAll(screen).count())
        val collector = bracedBlock(screen, "viewModel.events.collect")
        val success = bracedBlock(
            collector,
            "is ProfileUiEvent.PreferenceMutationSucceeded",
        )
        val failure = bracedBlock(
            collector,
            "is ProfileUiEvent.PreferenceUpdateFailed",
        )
        assertCurrentProfileAndTrackedTokenBeforeKind(success, "success")
        assertCurrentProfileAndTrackedTokenBeforeKind(failure, "failure")
    }

    @Test
    fun profileRouteUsesTransientMainActionsAndPassesAllNavigationCallbacks() {
        val graph = source(navGraphPath)
        val route = graph.substring(
            graph.indexOf("route = NavigationRoutes.Profile.route").also { assertTrue(it >= 0) },
            graph.indexOf("// Exercise Detail screen").also { assertTrue(it >= 0) },
        )

        listOf(
            "onOpenProfileSwitcher = onOpenProfileSwitcher",
            "onNavigateToExerciseDetail",
            "onNavigateToEquipmentRack",
            "NavigationRoutes.EquipmentRack.route",
            "onNavigateToBadges",
            "NavigationRoutes.Badges.route",
            "onProfileRecoveryRequired = onProfileRecoveryRequired",
            "viewModel::toggleDiscoMode",
            "viewModel::emitDiscoSound",
            "viewModel::emitDominatrixUnlockSound",
        ).forEach { contract -> assertContains(route, contract, message = contract) }
        val persistedMainViewModelDenylist = listOf(
            "setBodyWeightKg",
            "setWeightUnit",
            "setWeightIncrement",
            "setEnableVideoPlayback",
            "setStopAtTop",
            "setStallDetectionEnabled",
            "setAudioRepCountEnabled",
            "setRepCountTiming",
            "setColorScheme",
            "setSummaryCountdownSeconds",
            "setAutoStartCountdownSeconds",
            "setAutoStartRoutine",
            "setGamificationEnabled",
            "setCountdownBeepsEnabled",
            "setRepSoundEnabled",
            "setMotionStartEnabled",
            "setWeightSuggestionsEnabled",
            "setBleCompatibilityMode",
            "setAutoBackupEnabled",
            "setBackupDestination",
            "setLanguage",
            "setVelocityLossThreshold",
            "setAutoEndOnVelocityLoss",
            "setDefaultScalingBasis",
            "setDefaultRoutineExerciseUsePercentOfPR",
            "setDefaultRoutineExerciseWeightPercentOfPR",
            "setVerbalEncouragementEnabled",
            "setVulgarModeEnabled",
            "setVulgarTier",
            "setDominatrixModeUnlocked",
            "setDominatrixModeActive",
            "setVoiceStopEnabled",
            "setSafeWord",
            "setSafeWordCalibrated",
            "setAdultsOnlyConfirmed",
            "confirmAdultsAndEnableVulgar",
            "setAdultsOnlyPrompted",
            "unlockDiscoMode",
            "saveRackItem",
            "deleteRackItem",
            "saveRackBehaviorOverridesForExercise",
            "updateActiveRackSelection",
            "updateActiveRackBehaviorOverrides",
            "clearActiveRackSelection",
        )
        persistedMainViewModelDenylist.forEach { forbidden ->
            assertFalse(route.contains("viewModel.$forbidden"), forbidden)
            assertFalse(route.contains("viewModel::$forbidden"), forbidden)
            assertFalse(route.contains("$forbidden("), forbidden)
        }
        assertFalse(Regex("""viewModel(?:::|\.)set[A-Z]\w*""").containsMatchIn(route))
        assertFalse(Regex("""viewModel(?:::|\.)unlock[A-Z]\w*""").containsMatchIn(route))
        assertFalse(Regex("""viewModel(?:::|\.)confirmAdults\w*""").containsMatchIn(route))
    }

    @Test
    fun adultAndUnlockDialogsReactOnlyToMatchingPostCommitOutcomes() {
        val screen = source(screenPath)
        listOf(
            "ProfileUiEvent.PreferenceMutationSucceeded",
            "ProfileUiEvent.PreferenceUpdateFailed",
            "ProfilePreferenceMutationKind.ADULT_ENABLE",
            "ProfilePreferenceMutationKind.ADULT_DECLINE",
            "ProfilePreferenceMutationKind.DISCO_UNLOCK",
            "ProfilePreferenceMutationKind.DOMINATRIX_UNLOCK",
            "AdultsOnlyConfirmDialog(",
            "DiscoModeUnlockDialog(",
            "DominatrixUnlockDialog(",
            "onPlayDiscoUnlockSound",
            "onPlayDominatrixUnlockSound",
        ).forEach { contract -> assertContains(screen, contract, message = contract) }
        val collector = bracedBlock(screen, "viewModel.events.collect")
        val success = bracedBlock(
            collector,
            "is ProfileUiEvent.PreferenceMutationSucceeded",
        )
        val failure = bracedBlock(
            collector,
            "is ProfileUiEvent.PreferenceUpdateFailed",
        )
        val adultBranch = mutationKindBranch(success, "ADULT_ENABLE")
        val adultFailureBranch = mutationKindBranch(failure, "ADULT_ENABLE")
        val ordinaryFailureBranch = mutationKindBranch(failure, "UPDATE")
        val discoBranch = mutationKindBranch(success, "DISCO_UNLOCK")
        val dominatrixBranch = mutationKindBranch(success, "DOMINATRIX_UNLOCK")

        assertTrue(
            adultBranch.contains("showAdultsOnlyDialog = false") ||
                Regex("""adult\w*(?:Target|Dialog)\w*\s*=\s*null""").containsMatchIn(adultBranch),
            "adult dialog may close only in the matching committed-success branch",
        )
        assertContains(discoBranch, "onPlayDiscoUnlockSound()")
        assertContains(discoBranch, "showDiscoUnlockDialog = true")
        assertContains(dominatrixBranch, "onPlayDominatrixUnlockSound()")
        assertContains(dominatrixBranch, "showDominatrixUnlockDialog = true")
        assertTrue(
            Regex("""adult\w*[Ee]rror\w*\s*=""").containsMatchIn(adultFailureBranch),
            "ADULT_ENABLE failure must update inline adult modal error state",
        )
        assertContains(adultFailureBranch, "event.committedSections")
        assertContains(adultFailureBranch, "ProfilePreferenceSection.LOCAL_SAFETY")
        assertFalse(adultFailureBranch.contains("snackbarHostState.showSnackbar"))
        assertFalse(adultFailureBranch.contains("showAdultsOnlyDialog = false"))
        assertFalse(adultFailureBranch.contains("onPlayDiscoUnlockSound()"))
        assertFalse(adultFailureBranch.contains("onPlayDominatrixUnlockSound()"))
        assertContains(ordinaryFailureBranch, "snackbarHostState.showSnackbar")
        assertEquals(1, Regex("onPlayDiscoUnlockSound\\(\\)").findAll(screen).count())
        assertEquals(1, Regex("onPlayDominatrixUnlockSound\\(\\)").findAll(screen).count())
        assertEquals(1, Regex("showDiscoUnlockDialog\\s*=\\s*true").findAll(screen).count())
        assertEquals(1, Regex("showDominatrixUnlockDialog\\s*=\\s*true").findAll(screen).count())

        val preferenceCall = parenthesizedCall(screen, "ProfilePreferenceSections(")
        assertFalse(preferenceCall.contains("onPlayDiscoUnlockSound()"))
        assertFalse(preferenceCall.contains("onPlayDominatrixUnlockSound()"))
        assertFalse(preferenceCall.contains("showDiscoUnlockDialog = true"))
        assertFalse(preferenceCall.contains("showDominatrixUnlockDialog = true"))
    }

    @Test
    fun achievementsPrecedesPreferencesAndIsUnconditional() {
        val screen = source(screenPath)
        val insights = screen.indexOf("item(key = \"exercise-insights\")")
        val achievements = screen.indexOf("item(key = \"achievements\")")
        val heading = screen.indexOf("item(key = \"preferences-heading\")")
        val preferences = screen.indexOf("item(key = \"profile-preferences\")")

        assertTrue(insights in 0 until achievements)
        assertTrue(achievements in 0 until heading)
        assertTrue(heading in 0 until preferences)
        assertContains(screen.substring(achievements, heading), "TestTags.ACTION_BADGES")
        assertFalse(screen.substring(achievements, heading).contains("gamificationEnabled"))
    }

    @Test
    fun continuousSlidersDraftLocallyAndCommitOnlyWhenFinished() {
        val preferences = source(preferenceComponentsPath)
        assertEquals(2, Regex("\\bSlider\\(").findAll(preferences).count())
        assertEquals(2, Regex("onValueChangeFinished\\s*=").findAll(preferences).count())
        assertEquals(2, Regex("onValueChange\\s*=").findAll(preferences).count())
        assertSliderContract(
            source = preferences,
            authoritativeField = "defaultRoutineExerciseWeightPercentOfPR",
            section = "WORKOUT",
            finalCallback = "onWorkoutChange",
        )
        assertSliderContract(
            source = preferences,
            authoritativeField = "velocityLossThresholdPercent",
            section = "VBT",
            finalCallback = "onVbtChange",
        )
        assertFalse(
            Regex("""onValueChange\s*=\s*\{[^}]*on(?:Workout|Vbt)Change""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(preferences),
            "continuous onValueChange must update only a local draft",
        )
    }

    @Test
    fun ledOptionsUseStableLocalizedIndicesAndRadioSemantics() {
        val preferences = source(preferenceComponentsPath)
        val labels = listOf(
            "profile_led_scheme_blue",
            "profile_led_scheme_green",
            "profile_led_scheme_teal",
            "profile_led_scheme_yellow",
            "profile_led_scheme_pink",
            "profile_led_scheme_red",
            "profile_led_scheme_purple",
            "profile_led_scheme_none",
        )
        labels.forEachIndexed { index, label ->
            assertTrue(
                Regex(
                    """\b$index\s+to\s+Res\.string\.$label|index\s*=\s*$index[\s\S]{0,100}Res\.string\.$label""",
                ).containsMatchIn(preferences),
                "LED index $index must map explicitly to $label",
            )
        }
        listOf(
            "normalizedLedSchemeIndex(",
            "selectableGroup",
            "Role.RadioButton",
            "selected =",
            "contentDescription",
            "Res.string.cd_select_led_scheme",
        )
            .forEach { contract -> assertContains(preferences, contract, message = contract) }
        val ledWrites = Regex("onLedChange\\s*\\(").findAll(preferences).toList()
        assertEquals(1, ledWrites.size, "normalization must not write a fallback LED section")
        val ledWriteIndex = ledWrites.single().range.first
        val clickWindow = preferences.substring((ledWriteIndex - 400).coerceAtLeast(0), ledWriteIndex)
        assertContains(clickWindow, "onClick")
        assertFalse(
            Regex(
                """(?:LaunchedEffect|SideEffect)\s*\([^)]*\)\s*\{[^}]*onLedChange""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(preferences),
            "display normalization must never auto-write index zero",
        )
        assertFalse(preferences.contains("scheme.name"))
        assertFalse(preferences.contains("ColorScheme.name"))
    }

    @Test
    fun settingsDelegatesSharedDialogsAndMicrophoneDisposalIsRetained() {
        val settings = source(settingsPath)
        val dialogs = source(safetyDialogsPath)
        listOf(
            "SafeWordCalibrationDialog(",
            "AdultsOnlyConfirmDialog(",
            "DominatrixUnlockDialog(",
            "DiscoModeUnlockDialog(",
        ).forEach { call ->
            assertContains(settings, call, message = call)
            assertEquals(1, Regex("fun\\s+${call.removeSuffix("(")}\\(").findAll(dialogs).count())
            assertFalse(settings.contains("private fun ${call.removeSuffix("(")}"))
        }
        listOf(
            "DisposableEffect(safeWord)",
            "startListening()",
            "onDispose",
            "stopListening()",
            "listener = null",
        ).forEach { contract -> assertContains(dialogs, contract, message = contract) }

        val settingsAdultCall = parenthesizedCall(settings, "AdultsOnlyConfirmDialog(")
        assertContains(settingsAdultCall, "isSubmitting = false")
        assertContains(settingsAdultCall, "errorMessage = null")

        val adultDialog = bracedBlock(dialogs, "fun AdultsOnlyConfirmDialog(")
        assertContains(adultDialog, "errorMessage?.let")
        assertTrue(
            Regex("""errorMessage\?\.let\s*\{[\s\S]*?Text\s*\(""")
                .containsMatchIn(adultDialog),
            "adult error must render inline",
        )
        assertTrue(
            Regex("""enabled\s*=\s*!isSubmitting""").findAll(adultDialog).count() >= 2,
            "confirm and decline must both disable while submitting",
        )
        listOf("onConfirm", "onDecline").forEach { callback ->
            val click = "onClick\\s*=\\s*(?:$callback|\\{\\s*$callback\\(\\)\\s*\\})"
            assertTrue(
                Regex(
                    "(?:$click[\\s\\S]{0,180}enabled\\s*=\\s*!isSubmitting|" +
                        "enabled\\s*=\\s*!isSubmitting[\\s\\S]{0,180}$click)",
                ).containsMatchIn(adultDialog),
                "$callback must be attached to a submitting-disabled action",
            )
        }
        assertContains(adultDialog, "dismissOnBackPress = !isSubmitting")
        assertContains(adultDialog, "dismissOnClickOutside = !isSubmitting")
        assertTrue(
            Regex(
                """onDismissRequest\s*=\s*\{[\s\S]{0,160}!isSubmitting[\s\S]{0,160}onDismiss\s*\(""",
            ).containsMatchIn(adultDialog),
            "scrim, back, and onDismiss must be ignored while submitting",
        )
    }

    private fun session(id: String, timestamp: Long, totalVolumeKg: Float) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        totalVolumeKg = totalVolumeKg,
        totalReps = 5,
    )

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    private fun resourceValue(xml: String, key: String): String = requireNotNull(
        Regex(
            """<string\b[^>]*\bname\s*=\s*\"${Regex.escape(key)}\"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml),
    ) { key }.groupValues[1]
        .trim()
        .replace("&apos;", "'")
        .replace("\\'", "'")

    private fun bracedBlock(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue(markerIndex >= 0, marker)
        val open = source.indexOf('{', markerIndex)
        assertTrue(open >= 0, "$marker opening brace")
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("Unclosed block: $marker")
    }

    private fun parenthesizedCall(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue(markerIndex >= 0, marker)
        val open = source.indexOf('(', markerIndex)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("Unclosed call: $marker")
    }

    private fun mutationKindBranch(successBlock: String, kind: String): String {
        val marker = "ProfilePreferenceMutationKind.$kind"
        val start = successBlock.indexOf(marker)
        assertTrue(start >= 0, marker)
        val arrow = successBlock.indexOf("->", start)
        assertTrue(arrow >= 0, "$marker branch")
        val open = successBlock.indexOf('{', arrow)
        return if (open >= 0 && open - arrow < 16) {
            bracedBlock(successBlock.substring(arrow), "->")
        } else {
            successBlock.substring(arrow, successBlock.indexOf('\n', arrow).takeIf { it >= 0 }
                ?: successBlock.length)
        }
    }

    private fun assertCurrentProfileAndTrackedTokenBeforeKind(
        branch: String,
        label: String,
    ) {
        assertTrue(
            Regex("""event\.profileId\s*==\s*readyProfileId|readyProfileId\s*==\s*event\.profileId""")
                .containsMatchIn(branch),
            "the $label branch itself must reject a non-current profile",
        )
        assertTrue(
            Regex(
                """(?:containsKey|contains|remove|get|\[)\s*\(?\s*event\.token|event\.token\s+in\s+""",
            ).containsMatchIn(branch),
            "the $label branch itself must consume or verify the tracked token",
        )
        val kindDispatch = branch.indexOf("event.kind")
        assertTrue(kindDispatch >= 0, "$label kind dispatch")
        assertTrue(branch.indexOf("event.profileId") in 0 until kindDispatch)
        assertTrue(branch.indexOf("event.token") in 0 until kindDispatch)
    }

    private fun assertSliderContract(
        source: String,
        authoritativeField: String,
        section: String,
        finalCallback: String,
    ) {
        val authoritative = requireNotNull(
            Regex(
                """val\s+(\w+)\s*=\s*[^\n]*\b$authoritativeField\b""",
            ).find(source),
        ) { "$authoritativeField needs an explicit authoritative value" }.groupValues[1]
        val draft = requireNotNull(
            Regex(
                """var\s+(\w*[Dd]raft\w*)\s+by\s+rememberSaveable\s*\(\s*profileId\s*,\s*${Regex.escape(authoritative)}\s*\)""",
            ).find(source),
        ) { "$authoritativeField needs a profile and authoritative-value keyed local draft" }
            .groupValues[1]
        assertTrue(
            Regex(
                """LaunchedEffect\s*\(\s*profileId\s*,\s*${Regex.escape(authoritative)}\s*\)\s*\{[\s\S]{0,180}${Regex.escape(draft)}\s*=\s*${Regex.escape(authoritative)}""",
            ).containsMatchIn(source),
            "$authoritativeField draft must resynchronize from authoritative Ready",
        )
        assertTrue(
            Regex(
                """enabled\s*=\s*[^\n]{0,180}ProfilePreferenceSection\.$section[^\n]{0,100}busySections""",
            ).containsMatchIn(source),
            "$section slider must disable while its section is busy",
        )
        assertTrue(
            Regex(
                """onValueChangeFinished\s*=\s*\{[\s\S]{0,700}$finalCallback\s*\([\s\S]{0,300}\.copy\s*\([\s\S]{0,220}$authoritativeField\s*=\s*${Regex.escape(draft)}""",
            ).containsMatchIn(source),
            "$authoritativeField must commit one final whole-section copy",
        )
    }
}
