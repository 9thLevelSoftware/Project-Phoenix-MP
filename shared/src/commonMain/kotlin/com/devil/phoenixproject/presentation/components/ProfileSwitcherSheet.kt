package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.presentation.util.TestTags
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.add_profile
import vitruvianprojectphoenix.shared.generated.resources.profiles_title

internal fun canDismissProfileSwitcher(switchingTargetProfileId: String?): Boolean =
    switchingTargetProfileId == null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    activeProfileId: String?,
    switchingTargetProfileId: String?,
    onSelectProfile: (UserProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentSwitchingTargetProfileId by rememberUpdatedState(switchingTargetProfileId)
    val canDismiss = canDismissProfileSwitcher(switchingTargetProfileId)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            targetValue != SheetValue.Hidden ||
                canDismissProfileSwitcher(currentSwitchingTargetProfileId)
        },
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (canDismissProfileSwitcher(currentSwitchingTargetProfileId)) onDismiss()
        },
        sheetState = sheetState,
        sheetGesturesEnabled = canDismiss,
        modifier = Modifier.testTag(TestTags.PROFILE_SWITCHER_SHEET),
    ) {
        Text(
            text = stringResource(Res.string.profiles_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(
                items = profiles,
                key = UserProfile::id,
            ) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    enabled = switchingTargetProfileId == null,
                    switching = profile.id == switchingTargetProfileId,
                    onClick = { onSelectProfile(profile) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.add_profile)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = switchingTargetProfileId == null,
                            role = Role.Button,
                            onClick = onAddProfile,
                        )
                        .testTag(TestTags.ACTION_ADD_PROFILE),
                )
            }
        }
    }
}
