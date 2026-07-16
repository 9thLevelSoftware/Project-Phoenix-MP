package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSettingsSeparationContractTest {
    private val settingsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt"
    private val profilePath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt"
    private val dialogsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt"
    private val navGraphPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt"
    private val mainViewModelPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"

    private val settingsForbiddenSymbols = listOf(
        "weightUnit",
        "onWeightUnitChange",
        "weightIncrement",
        "onWeightIncrementChange",
        "bodyWeightKg",
        "onBodyWeightKgChange",
        "onNavigateToEquipmentRack",
        "audioRepCountEnabled",
        "onAudioRepCountChange",
        "summaryCountdownSeconds",
        "onSummaryCountdownChange",
        "autoStartCountdownSeconds",
        "onAutoStartCountdownChange",
        "countdownBeepsEnabled",
        "onCountdownBeepsChange",
        "repSoundEnabled",
        "onRepSoundChange",
        "motionStartEnabled",
        "onMotionStartChange",
        "autoStartRoutine",
        "onAutoStartRoutineChange",
        "weightSuggestionsEnabled",
        "onWeightSuggestionsEnabledChange",
        "selectedColorSchemeIndex",
        "onColorSchemeChange",
        "discoModeUnlocked",
        "discoModeActive",
        "isConnected",
        "onDiscoModeUnlocked",
        "onDiscoModeToggle",
        "onPlayDiscoSound",
        "gamificationEnabled",
        "onGamificationEnabledChange",
        "voiceStopEnabled",
        "onVoiceStopEnabledChange",
        "safeWord",
        "onSafeWordChange",
        "safeWordCalibrated",
        "onSafeWordCalibratedChange",
        "velocityLossThresholdPercent",
        "onVelocityLossThresholdChange",
        "autoEndOnVelocityLoss",
        "onAutoEndOnVelocityLossChange",
        "stallDetectionEnabled",
        "defaultScalingBasis",
        "onDefaultScalingBasisChange",
        "defaultRoutineExerciseUsePercentOfPR",
        "onDefaultRoutineExerciseUsePercentOfPRChange",
        "defaultRoutineExerciseWeightPercentOfPR",
        "onDefaultRoutineExerciseWeightPercentOfPRChange",
        "verbalEncouragementEnabled",
        "onVerbalEncouragementEnabledChange",
        "vulgarModeEnabled",
        "onVulgarModeEnabledChange",
        "vulgarTier",
        "onVulgarTierChange",
        "dominatrixModeUnlocked",
        "onDominatrixModeUnlockedChange",
        "dominatrixModeActive",
        "onDominatrixModeActiveChange",
        "adultsOnlyConfirmed",
        "onConfirmAdultsAndEnableVulgar",
        "adultsOnlyPrompted",
        "onAdultsOnlyPromptedChange",
        "onPlayDominatrixUnlockSound",
        "onNavigateToBadges",
        "UserProfileRepository",
        "ExternalMeasurementRepository",
        "SafeWordCalibrationDialog",
        "AdultsOnlyConfirmDialog",
        "DominatrixUnlockDialog",
        "DiscoModeUnlockDialog",
        "activeProfileId",
        "healthBodyWeightMeasurements",
        "latestMatchingHealthBodyWeight",
        "localWeightUnit",
        "showWeightIncrementDialog",
        "showBodyWeightDialog",
        "bodyWeightInput",
        "localSafeWord",
        "showCalibrationDialog",
        "easterEggTapCount",
        "lastTapTime",
        "showDiscoUnlockDialog",
        "dominatrixEasterEggTapCount",
        "lastDominatrixTapTime",
        "showDominatrixUnlockDialog",
        "showAdultsOnlyDialog",
        "onCancelAutoConnecting",
    )

    private val mainViewModelProfileWriteRemovals = listOf(
        "setWeightUnit",
        "setStopAtTop",
        "setStallDetectionEnabled",
        "setAudioRepCountEnabled",
        "setRepCountTiming",
        "setSummaryCountdownSeconds",
        "setAutoStartCountdownSeconds",
        "setColorScheme",
        "setWeightIncrement",
        "setAutoStartRoutine",
        "setBodyWeightKg",
        "setGamificationEnabled",
        "setCountdownBeepsEnabled",
        "setRepSoundEnabled",
        "setMotionStartEnabled",
        "setVoiceStopEnabled",
        "setSafeWord",
        "setSafeWordCalibrated",
        "setVelocityLossThreshold",
        "setAutoEndOnVelocityLoss",
        "setWeightSuggestionsEnabled",
        "setDefaultScalingBasis",
        "setDefaultRoutineExerciseUsePercentOfPR",
        "setDefaultRoutineExerciseWeightPercentOfPR",
        "setVerbalEncouragementEnabled",
        "setVulgarModeEnabled",
        "setVulgarTier",
        "setDominatrixModeUnlocked",
        "setDominatrixModeActive",
        "setAdultsOnlyConfirmed",
        "confirmAdultsAndEnableVulgar",
        "setAdultsOnlyPrompted",
        "isAdultsOnlyPrompted",
        "unlockDiscoMode",
        "cancelAutoConnecting",
    )

    @Test
    fun settingsTabExposesExactGlobalOnlySignature() {
        val signature = functionSignature(source(settingsPath), "SettingsTab")
        val expected = """
            fun SettingsTab(
                enableVideoPlayback: Boolean,
                themeMode: ThemeMode,
                dynamicColorAvailable: Boolean,
                dynamicColorEnabled: Boolean,
                onEnableVideoPlaybackChange: (Boolean) -> Unit,
                onThemeModeChange: (ThemeMode) -> Unit,
                onDynamicColorEnabledChange: (Boolean) -> Unit,
                onDeleteAllWorkouts: () -> Unit,
                onNavigateToConnectionLogs: () -> Unit,
                onNavigateToDiagnostics: () -> Unit,
                onNavigateToLinkAccount: () -> Unit,
                onNavigateToIntegrations: () -> Unit,
                connectionError: String?,
                onClearConnectionError: () -> Unit,
                onSetTitle: (String) -> Unit,
                onTestSounds: () -> Unit,
                bleCompatibilityMode: BleCompatibilitySetting,
                onBleCompatibilityModeChange: (BleCompatibilitySetting) -> Unit,
                autoBackupEnabled: Boolean,
                onAutoBackupEnabledChange: (Boolean) -> Unit,
                backupStats: BackupStats?,
                onOpenBackupFolder: () -> Unit,
                backupDestination: BackupDestination,
                onBackupDestinationChange: (BackupDestination) -> Unit,
                selectedLanguage: String,
                onLanguageChange: (String) -> Unit,
                modifier: Modifier = Modifier,
            )
        """.trimIndent()

        assertEquals(compactKotlin(expected), compactKotlin(signature))
    }

    @Test
    fun settingsContainsNoCanonicalProfileOwnedSymbolOrModalState() {
        assertNoCanonicalSymbols(
            functionSource(source(settingsPath), "SettingsTab"),
            settingsForbiddenSymbols,
            "SettingsTab",
        )
    }

    @Test
    fun settingsRemovesOnlyTask8DialogCallSitesAndKeepsSharedDialogOwnership() {
        val settings = source(settingsPath)
        val settingsTab = functionSource(settings, "SettingsTab")
        val dialogs = source(dialogsPath)
        val sharedDialogs = listOf(
            "SafeWordCalibrationDialog",
            "AdultsOnlyConfirmDialog",
            "DominatrixUnlockDialog",
            "DiscoModeUnlockDialog",
        )

        sharedDialogs.forEach { name ->
            assertFalse(
                Regex("(?m)^\\s*import\\s+[^\\n]*\\.$name\\s*$").containsMatchIn(settings),
                "Settings must not import $name",
            )
            assertFalse(
                Regex("\\b$name\\s*\\(").containsMatchIn(settingsTab),
                "Settings must not call $name",
            )
            assertEquals(
                1,
                Regex("(?m)^fun\\s+$name\\s*\\(").findAll(dialogs).count(),
                "$name must remain shared exactly once",
            )
        }
        assertContains(dialogs, "DisposableEffect(safeWord)")
    }

    @Test
    fun globalSectionsStayInRequiredOrderAndVideoUsesLocalizedResources() {
        val settings = source(settingsPath)
        val body = functionBody(settings, "SettingsTab")
        assertInOrder(
            body,
            listOf(
                "Res.string.cd_support_developer",
                "Res.string.settings_cloud_sync",
                "Res.string.settings_appearance",
                "Res.string.settings_language",
                "Res.string.settings_video_behavior",
                "onDeleteAllWorkouts",
                "onNavigateToDiagnostics",
                "Res.string.settings_version",
            ),
        )
        listOf(
            "onNavigateToLinkAccount",
            "onNavigateToIntegrations",
            "autoBackupEnabled",
            "backupDestination",
            "backupStats",
            "onOpenBackupFolder",
            "showBackupDialog = true",
            "showRestoreDialog = true",
            "backupManager.shareBackup()",
            "backupManager.importFromFile(",
            "bleCompatibilityMode",
            "onBleCompatibilityModeChange",
            "onNavigateToConnectionLogs",
            "onNavigateToDiagnostics",
            "onTestSounds",
        ).forEach { retained -> assertContains(body, retained) }

        val videoKeys = listOf(
            "settings_video_behavior",
            "settings_show_exercise_videos",
            "settings_show_exercise_videos_description",
        )
        videoKeys.forEach { key -> assertContains(body, "Res.string.$key") }
        listOf("values", "values-de", "values-es", "values-fr", "values-nl")
            .forEach { locale ->
                val xml = source("src/commonMain/composeResources/$locale/strings.xml")
                videoKeys.forEach { key ->
                    assertEquals(
                        1,
                        Regex("""<string\s+name="$key"(?:\s|>)""").findAll(xml).count(),
                        "$locale:$key",
                    )
                }
            }

        assertContains(settings, "private fun GlobalSettingsSectionCard(")
        val switchRow = functionSource(settings, "GlobalSettingsSwitchRow")
        listOf("defaultMinSize(minHeight = 48.dp)", ".toggleable(", "role = Role.Switch")
            .forEach { contract -> assertContains(switchRow, contract) }
        assertTrue(
            Regex("""Switch\s*\([^)]*onCheckedChange\s*=\s*null""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(switchRow),
            "the child Switch must not install a second callback",
        )
    }

    @Test
    fun profileOwnsAchievementsRackAndPreferenceSectionsInRequiredOrder() {
        val profile = source(profilePath)
        val settings = functionSource(source(settingsPath), "SettingsTab")
        val itemAnchors = listOf(
            "item(key = \"profile-header\")",
            "item(key = \"exercise-insights\")",
            "item(key = \"achievements\")",
            "item(key = \"preferences-heading\")",
            "item(key = \"profile-preferences\")",
        )
        assertInOrder(profile, itemAnchors)
        listOf(
            "ProfilePreferenceSections(",
            "onNavigateToEquipmentRack",
            "onNavigateToBadges",
            "TestTags.ACTION_BADGES",
        ).forEach { contract -> assertContains(profile, contract) }

        val achievements = profile.substring(
            profile.indexOf(itemAnchors[2]),
            profile.indexOf(itemAnchors[3]),
        )
        assertFalse(achievements.contains("gamificationEnabled"))
        listOf(
            "ProfilePreferenceSections(",
            "onNavigateToEquipmentRack",
            "onNavigateToBadges",
            "equipment_rack_title",
            "TestTags.ACTION_BADGES",
        ).forEach { forbidden ->
            assertFalse(settings.contains(forbidden), "Settings retained $forbidden")
        }
    }

    @Test
    fun settingsDestinationCollectsOnlyGlobalSettingsConnectionErrorAndBackupStats() {
        val settingsDestination = destinationSource(source(navGraphPath), "Settings")
        listOf(
            "val globalSettings by viewModel.globalSettings.collectAsState()",
            "val connectionError by viewModel.connectionError.collectAsState()",
            "val backupStats by viewModel.backupStats.collectAsState()",
            "viewModel.refreshBackupStats()",
        ).forEach { contract -> assertContains(settingsDestination, contract) }
        assertEquals(
            3,
            Regex("\\.collectAsState\\s*\\(").findAll(settingsDestination).count(),
            "Settings must collect exactly three flows",
        )
        assertNoCanonicalSymbols(settingsDestination, settingsForbiddenSymbols, "Settings destination")

        val call = parenthesizedCall(settingsDestination, "SettingsTab(")
        listOf(
            "enableVideoPlayback = globalSettings.enableVideoPlayback",
            "themeMode = themeMode",
            "dynamicColorAvailable = dynamicColorAvailable",
            "dynamicColorEnabled = dynamicColorEnabled",
            "bleCompatibilityMode = globalSettings.bleCompatibilityMode",
            "autoBackupEnabled = globalSettings.autoBackupEnabled",
            "backupStats = backupStats",
            "backupDestination = globalSettings.backupDestination",
            "selectedLanguage = globalSettings.language",
            "connectionError = connectionError",
        ).forEach { assignment ->
            assertTrue(
                compactKotlin(call).contains(compactKotlin(assignment)),
                "missing Settings wiring: $assignment",
            )
        }
        listOf(
            "onEnableVideoPlaybackChange" to "setEnableVideoPlayback",
            "onDeleteAllWorkouts" to "deleteAllWorkouts",
            "onClearConnectionError" to "clearConnectionError",
            "onSetTitle" to "updateTopBarTitle",
            "onTestSounds" to "testSounds",
            "onBleCompatibilityModeChange" to "setBleCompatibilityMode",
            "onAutoBackupEnabledChange" to "setAutoBackupEnabled",
            "onOpenBackupFolder" to "openBackupFolder",
            "onBackupDestinationChange" to "setBackupDestination",
            "onLanguageChange" to "setLanguage",
        ).forEach { (parameter, method) -> assertViewModelCallback(call, parameter, method) }
        listOf(
            "onThemeModeChange = onThemeModeChange",
            "onDynamicColorEnabledChange = onDynamicColorEnabledChange",
            "onNavigateToConnectionLogs" to "NavigationRoutes.ConnectionLogs.route",
            "onNavigateToDiagnostics" to "NavigationRoutes.Diagnostics.route",
            "onNavigateToLinkAccount" to "NavigationRoutes.LinkAccount.route",
            "onNavigateToIntegrations" to "NavigationRoutes.Integrations.route",
        ).forEach { contract ->
            when (contract) {
                is String -> assertTrue(compactKotlin(call).contains(compactKotlin(contract)))
                is Pair<*, *> -> assertNavigationCallback(
                    call,
                    contract.first as String,
                    contract.second as String,
                )
            }
        }
    }

    @Test
    fun mainViewModelExposesNoProfileWriteWrappersAndKeepsRequiredReadAndTransientApis() {
        val main = source(mainViewModelPath)
        val forbidden = mainViewModelProfileWriteRemovals.filter { name ->
            Regex("(?m)^\\s*(?:(?:public|internal|private|protected)\\s+)?fun\\s+$name\\s*\\(")
                .containsMatchIn(main) || Regex("::\\s*$name\\b").containsMatchIn(main)
        }
        assertTrue(forbidden.isEmpty(), "MainViewModel retained profile writes: $forbidden")
        assertTrue(
            Regex("""\bprivate\s+val\s+settingsManager\b""").containsMatchIn(main),
            "settingsManager must be private",
        )
        assertFalse(
            Regex("(?m)^\\s*(?:public\\s+)?val\\s+settingsManager\\b").containsMatchIn(main),
            "settingsManager must not be public",
        )

        listOf("userPreferences", "weightUnit", "enableVideoPlayback", "autoplayEnabled", "globalSettings")
            .forEach { property ->
                assertTrue(
                    Regex("(?m)^\\s*(?:public\\s+)?val\\s+$property\\b").containsMatchIn(main),
                    "missing retained read: $property",
                )
            }
        listOf(
            "setEnableVideoPlayback",
            "setBleCompatibilityMode",
            "setAutoBackupEnabled",
            "setBackupDestination",
            "setLanguage",
            "deleteAllWorkouts",
            "clearConnectionError",
            "updateTopBarTitle",
            "testSounds",
            "refreshBackupStats",
            "openBackupFolder",
            "toggleDiscoMode",
            "emitDiscoSound",
            "emitDominatrixUnlockSound",
        ).forEach { function ->
            assertTrue(
                Regex("(?m)^\\s*(?:public\\s+)?fun\\s+$function\\s*\\(").containsMatchIn(main),
                "missing retained API: $function",
            )
        }
        val mapper = settingsGlobalMapperSource(main)
        val expectedMapper = """
            private fun UserPreferences.toSettingsGlobalUiState() = SettingsGlobalUiState(
                enableVideoPlayback = enableVideoPlayback,
                bleCompatibilityMode = bleCompatibilityMode,
                autoBackupEnabled = autoBackupEnabled,
                backupDestination = backupDestination,
                language = language,
            )
        """.trimIndent()
        assertEquals(compactKotlin(expectedMapper), compactKotlin(mapper))

        val initializer = globalSettingsInitializer(main)
        assertTrue(
            compactKotlin(initializer).startsWith(
                "preferencesManager.preferencesFlow" +
                    ".map(UserPreferences::toSettingsGlobalUiState).stateIn(",
            ),
            "globalSettings must map raw global preferences directly before stateIn",
        )
        assertFalse(initializer.contains("settingsManager"))
        assertFalse(initializer.contains("userPreferences"))
    }

    @Test
    fun profileOwnsRackAndBadgesNavigationWhileDestinationsRemainUnique() {
        val graph = source(navGraphPath)
        val profile = destinationSource(graph, "Profile")
        val settings = destinationSource(graph, "Settings")
        listOf("EquipmentRack", "Badges").forEach { destination ->
            val navigation = "navController.navigate(NavigationRoutes.$destination.route)"
            assertContains(profile, navigation)
            assertFalse(settings.contains(navigation), "Settings must not navigate to $destination")
            assertEquals(1, Regex(Regex.escape(navigation)).findAll(graph).count(), navigation)
            assertEquals(
                1,
                Regex("route\\s*=\\s*NavigationRoutes\\.$destination\\.route")
                    .findAll(graph)
                    .count(),
                "$destination destination registration",
            )
        }
    }

    @Test
    fun profileSwitcherRemainsTheOnlyTaskOwnedPresentationRepositoryCaller() {
        fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

        val switcherPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ProfileSwitcherViewModel.kt"
        val mainPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt"
        val justLiftPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt"
        val graphPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt"
        val profilePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt"
        val settingsPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt"
        val mainViewModelPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"
        val sheetPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt"
        val dialogsPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt"
        val listItemPath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt"

        val switcher = source(switcherPath)
        val main = source(mainPath)
        val graph = source(graphPath)
        val profile = source(profilePath)
        val sheet = source(sheetPath)
        val dialogs = source(dialogsPath)
        val listItem = source(listItemPath)

        listOf(
            "profiles.setActiveProfile(",
            "profiles.createAndActivateProfile(",
            "profiles.reconcileActiveProfileContext(",
        ).forEach { call ->
            assertEquals(1, Regex(Regex.escape(call)).findAll(switcher).count(), call)
        }

        val directRepositoryCall = Regex(
            """\b(?:profiles|profileRepository|userProfileRepository)\s*\.\s*""" +
                """(?:setActiveProfile|createAndActivateProfile|reconcileActiveProfileContext)\s*\(""",
        )
        mapOf(
            mainPath to main,
            justLiftPath to source(justLiftPath),
            graphPath to graph,
            profilePath to profile,
            settingsPath to source(settingsPath),
            mainViewModelPath to source(mainViewModelPath),
            sheetPath to sheet,
            dialogsPath to dialogs,
            listItemPath to listItem,
        ).forEach { (path, text) ->
            assertFalse(directRepositoryCall.containsMatchIn(text), path)
        }

        assertEquals(1, Regex("""\bProfileSwitcherSheet\s*\(""").findAll(main).count())
        assertContains(main, "profileSwitcherViewModel.openSwitcher()")
        assertContains(graph, "onOpenProfileSwitcher = onOpenProfileSwitcher")
        assertContains(profile, "onOpenProfileSwitcher")
        assertFalse(sheet.contains("UserProfileRepository"))
        assertFalse(dialogs.contains("UserProfileRepository"))
        assertFalse(listItem.contains("UserProfileRepository"))
        assertFalse(listItem.contains("onLongClick"))
        assertFalse(listItem.contains("combinedClickable"))
    }

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    private fun functionSignature(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue(start >= 0, "missing function $name")
        val open = source.indexOf('(', start)
        val close = matchingClose(source, open, '(', ')')
        return source.substring(start, close + 1)
    }

    private fun functionSource(source: String, name: String): String {
        val signature = functionSignature(source, name)
        val start = source.indexOf(signature)
        val open = source.indexOf('{', start + signature.length)
        assertTrue(open >= 0, "$name body")
        val close = matchingClose(source, open, '{', '}')
        return source.substring(start, close + 1)
    }

    private fun functionBody(source: String, name: String): String {
        val function = functionSource(source, name)
        return function.substring(function.indexOf('{') + 1, function.lastIndexOf('}'))
    }

    private fun settingsGlobalMapperSource(source: String): String {
        val marker = "private fun UserPreferences.toSettingsGlobalUiState()"
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "missing private raw-global mapper")
        val equals = source.indexOf('=', start + marker.length)
        assertTrue(equals >= 0, "raw-global mapper initializer")
        val expressionStart = source.indexOfFirstNonWhitespace(equals + 1)
        assertTrue(
            source.startsWith("SettingsGlobalUiState(", expressionStart),
            "raw-global mapper must directly construct SettingsGlobalUiState",
        )
        val open = source.indexOf('(', expressionStart)
        val close = matchingClose(source, open, '(', ')')
        return source.substring(start, close + 1)
    }

    private fun globalSettingsInitializer(source: String): String {
        val declaration = Regex(
            """(?m)^\s*val\s+globalSettings\s*:\s*StateFlow<SettingsGlobalUiState>\s*=""",
        ).find(source)
        assertTrue(declaration != null, "missing typed globalSettings declaration")
        val expressionStart = source.indexOfFirstNonWhitespace(declaration.range.last + 1)
        assertTrue(
            source.startsWith("preferencesManager.preferencesFlow", expressionStart),
            "globalSettings must start from preferencesManager.preferencesFlow",
        )
        val stateIn = source.indexOf(".stateIn(", expressionStart)
        assertTrue(
            stateIn in expressionStart..(expressionStart + 500),
            "globalSettings initializer must terminate in a nearby stateIn call",
        )
        val open = source.indexOf('(', stateIn)
        val close = matchingClose(source, open, '(', ')')
        return source.substring(expressionStart, close + 1)
    }

    private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
        for (index in startIndex until length) {
            if (!this[index].isWhitespace()) return index
        }
        error("missing initializer expression")
    }

    private fun destinationSource(source: String, route: String): String {
        val marker = "route = NavigationRoutes.$route.route"
        val markerIndex = source.indexOf(marker)
        assertTrue(markerIndex >= 0, marker)
        val callStart = source.lastIndexOf("composable(", markerIndex)
        assertTrue(callStart >= 0, "$route composable")
        val openParenthesis = source.indexOf('(', callStart)
        val closeParenthesis = matchingClose(source, openParenthesis, '(', ')')
        val openBody = source.indexOf('{', closeParenthesis)
        assertTrue(openBody >= 0, "$route destination body")
        val closeBody = matchingClose(source, openBody, '{', '}')
        return source.substring(callStart, closeBody + 1)
    }

    private fun parenthesizedCall(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue(start >= 0, marker)
        val open = source.indexOf('(', start)
        val close = matchingClose(source, open, '(', ')')
        return source.substring(start, close + 1)
    }

    private fun matchingClose(source: String, open: Int, opener: Char, closer: Char): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                opener -> depth += 1
                closer -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("Unclosed $opener at $open")
    }

    private fun compactKotlin(source: String): String = source
        .replace(Regex("""//[^\n]*|/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""\s+"""), "")
        .replace(",)", ")")

    private fun assertNoCanonicalSymbols(source: String, symbols: List<String>, label: String) {
        val hits = symbols.filter { symbol ->
            Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])")
                .containsMatchIn(source)
        }
        assertTrue(hits.isEmpty(), "$label retained forbidden symbols: $hits")
    }

    private fun assertInOrder(source: String, anchors: List<String>) {
        var previous = -1
        anchors.forEach { anchor ->
            val index = source.indexOf(anchor)
            assertTrue(index > previous, "$anchor is missing or out of order")
            previous = index
        }
    }

    private fun assertViewModelCallback(call: String, parameter: String, method: String) {
        assertTrue(
            Regex(
                """\b${Regex.escape(parameter)}\s*=\s*(?:viewModel::${Regex.escape(method)}|\{[\s\S]{0,100}?viewModel\.${Regex.escape(method)}\s*\([^}]*\)\s*})""",
            ).containsMatchIn(call),
            "$parameter must call MainViewModel.$method",
        )
    }

    private fun assertNavigationCallback(call: String, parameter: String, route: String) {
        assertTrue(
            Regex(
                """\b${Regex.escape(parameter)}\s*=\s*\{[\s\S]{0,140}?navController\.navigate\s*\(\s*${Regex.escape(route)}\s*\)\s*}""",
            ).containsMatchIn(call),
            "$parameter must navigate to $route",
        )
    }
}
