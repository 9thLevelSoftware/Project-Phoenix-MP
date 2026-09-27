package com.devil.phoenixproject.di

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CycleEditorViewModel and GamificationViewModel extend AndroidX ViewModel and must
 * be resolved through Koin's ViewModel store. A factory plus koinInject() is remembered
 * only for the current composition, so rotation drops the instance and onCleared()
 * never runs.
 */
class ViewModelStoreWiringTest {

    @Test
    fun presentationModule_registersCycleAndGamificationViewModelsWithViewModelDsl() {
        val source = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt",
        )

        assertTrue(
            source.contains("viewModel { CycleEditorViewModel(get(), get()) }"),
            "CycleEditorViewModel must be declared with Koin's viewModel DSL.",
        )
        assertTrue(
            source.contains("viewModel { GamificationViewModel(get(), get()) }"),
            "GamificationViewModel must be declared with Koin's viewModel DSL.",
        )
        assertFalse(
            source.contains("factory { CycleEditorViewModel"),
            "CycleEditorViewModel must not stay a Koin factory.",
        )
        assertFalse(
            source.contains("factory { GamificationViewModel"),
            "GamificationViewModel must not stay a Koin factory.",
        )
    }

    @Test
    fun cycleEditorScreen_resolvesViewModelFromTheViewModelStore() {
        val source = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt",
        )

        assertTrue(source.contains("import org.koin.compose.viewmodel.koinViewModel"))
        assertTrue(source.contains("cycleEditorViewModel: CycleEditorViewModel = koinViewModel()"))
        assertFalse(
            source.contains("koinInject"),
            "CycleEditorScreen must not resolve CycleEditorViewModel with koinInject().",
        )
    }

    @Test
    fun badgesScreen_resolvesGamificationViewModelFromTheViewModelStore() {
        val source = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt",
        )

        assertTrue(source.contains("import org.koin.compose.viewmodel.koinViewModel"))
        assertTrue(source.contains("viewModel: GamificationViewModel = koinViewModel()"))
        assertFalse(
            source.contains("koinInject"),
            "BadgesScreen must not resolve GamificationViewModel with koinInject().",
        )
    }

    @Test
    fun linkAccountViewModel_documentsItsOwnScopeInsteadOfAnAndroidWrapper() {
        val source = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/ui/sync/LinkAccountViewModel.kt",
        )

        assertFalse(
            source.contains("AndroidLinkAccountViewModel"),
            "LinkAccountViewModel KDoc must not describe a wrapper that does not exist.",
        )
        assertFalse(
            source.contains("onCleared()"),
            "LinkAccountViewModel is not an AndroidX ViewModel and has no onCleared().",
        )
        assertTrue(source.contains("fun clear()"))
        assertTrue(
            source.contains("Koin registers this type as a `factory`"),
            "KDoc should describe the factory + koinInject() lifecycle the screen actually uses.",
        )
    }

    private fun readRequired(relativePath: String): String {
        val source = readProjectFile(relativePath)
        assertNotNull(source, "Could not read $relativePath")
        return source
    }
}
