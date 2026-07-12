package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileIdentityPolicyTest {
    @Test
    fun `palette selection wraps and invalid stored indexes normalize`() {
        assertEquals(PROFILE_COLOR_COUNT, ProfileColors.size)
        assertEquals(0, suggestedProfileColorIndex(-1))
        assertEquals(0, suggestedProfileColorIndex(0))
        assertEquals(7, suggestedProfileColorIndex(7))
        assertEquals(0, suggestedProfileColorIndex(8))

        assertEquals(0, normalizedProfileColorIndex(-1))
        assertEquals(0, normalizedProfileColorIndex(0))
        assertEquals(7, normalizedProfileColorIndex(7))
        assertEquals(0, normalizedProfileColorIndex(8))
    }

    @Test
    fun `avatar initials meet text contrast across the shared palette`() {
        ProfileColors.forEach { background ->
            val foreground = profileInitialsColor(background)
            assertTrue(
                contrastRatio(foreground, background) >= 4.5f,
                "Insufficient initials contrast for $background",
            )
        }
    }

    @Test
    fun `only the active non-default profile may be deleted`() {
        assertFalse(canDeleteProfile(profile("default", isActive = true)))
        assertFalse(canDeleteProfile(profile("athlete-a", isActive = false)))
        assertTrue(canDeleteProfile(profile("athlete-a", isActive = true)))
    }

    @Test
    fun `switcher may dismiss only while no switch is in flight`() {
        assertTrue(canDismissProfileSwitcher(null))
        assertFalse(canDismissProfileSwitcher("athlete-a"))
    }

    @Test
    fun `identity dialogs are callback only guarded and responsive`() {
        val dialogs = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
            ),
        )

        assertFalse(dialogs.contains("UserProfileRepository"))
        assertFalse(dialogs.contains("CoroutineScope"))
        assertFalse(dialogs.contains("DestructiveConfirmDialog("))
        assertFalse(dialogs.contains("testTag(TestTags.ACTION_"))
        assertContains(dialogs, "fun ProfileAddDialog(")
        assertContains(dialogs, "fun ProfileEditDialog(")
        assertContains(dialogs, "fun ProfileDeleteDialog(")
        assertContains(dialogs, "require(canDeleteProfile(profile))")
        assertContains(dialogs, "AlertDialog(")
        assertContains(dialogs, "name.trim()")
        assertContains(dialogs, "normalizedProfileColorIndex(selectedColorIndex)")
        assertContains(dialogs, "ProfileColors.indices.chunked(4)")
        assertContains(dialogs, ".size(48.dp)")
        assertContains(dialogs, ".selectable(")
        assertContains(dialogs, "Role.RadioButton")
        assertContains(dialogs, ".selectableGroup()")
        assertContains(dialogs, "enabled = !isSubmitting")
        assertContains(dialogs, "if (!isSubmitting)")

        val deleteCopyOffset = dialogs.indexOf("Res.string.profile_delete_reassign_message")
        assertTrue(deleteCopyOffset >= 0)
        val deleteCopyCall = dialogs.substring(
            deleteCopyOffset,
            minOf(dialogs.length, deleteCopyOffset + 240),
        )
        assertContains(deleteCopyCall, "profile.name")
    }

    @Test
    fun `switcher is switch create only and cannot hide while switching`() {
        val switcher = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt",
            ),
        )

        assertContains(switcher, "Res.string.profiles_title")
        assertContains(switcher, "TestTags.PROFILE_SWITCHER_SHEET")
        assertContains(switcher, "TestTags.ACTION_ADD_PROFILE")
        assertFalse(switcher.contains("onEditProfile"))
        assertFalse(switcher.contains("onDeleteProfile"))
        assertFalse(switcher.contains("combinedClickable"))
        assertContains(switcher, "rememberUpdatedState")
        assertContains(switcher, "confirmValueChange")
        assertContains(switcher, "targetValue != SheetValue.Hidden")
        assertContains(switcher, "sheetGesturesEnabled = canDismiss")
        assertContains(switcher, "if (canDismissProfileSwitcher(")
        assertContains(switcher, ".selectableGroup()")
    }

    @Test
    fun `identity surfaces declare accessible semantics and all downstream tags`() {
        val identity = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileIdentityComponents.kt",
            ),
        )
        val row = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileListItem.kt",
            ),
        )
        val tags = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt",
            ),
        )

        assertContains(identity, "minimumInteractiveComponentSize")
        assertContains(identity, "Role.Button")
        assertContains(identity, "contentDescription = accessibleName")
        assertContains(identity, "clearAndSetSemantics")
        assertContains(row, "heightIn(min = 56.dp)")
        assertContains(row, "Modifier.selectable(")
        assertContains(row, "Role.RadioButton")
        assertContains(row, "selected = isActive")
        assertContains(row, "enabled && !switching")
        assertContains(row, "maxLines = 1")
        assertContains(row, "TextOverflow.Ellipsis")
        listOf(
            "PROFILE_SWITCHER_SHEET",
            "ACTION_ADD_PROFILE",
            "ACTION_EDIT_PROFILE",
            "ACTION_DELETE_PROFILE",
        ).forEach { tag -> assertContains(tags, "const val $tag") }
    }

    private fun profile(id: String, isActive: Boolean) = UserProfile(
        id = id,
        name = id,
        colorIndex = 0,
        createdAt = 1L,
        isActive = isActive,
    )

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
