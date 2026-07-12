package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import com.devil.phoenixproject.presentation.components.ProfileAvatar
import com.devil.phoenixproject.presentation.components.ProfileDeleteDialog
import com.devil.phoenixproject.presentation.components.ProfileEditDialog
import com.devil.phoenixproject.presentation.components.ProfileExerciseInsights
import com.devil.phoenixproject.presentation.components.canDeleteProfile
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.viewmodel.ProfileIdentityMutationKind
import com.devil.phoenixproject.presentation.viewmodel.ProfileUiEvent
import com.devil.phoenixproject.presentation.viewmodel.ProfileViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import vitruvianprojectphoenix.shared.generated.resources.*

internal data class ProfileIdentityOverlayOwnership(
    val editTargetProfileId: String? = null,
    val deleteTargetProfileId: String? = null,
    val pendingIdentityProfileId: String? = null,
)

internal fun retainProfileIdentityOverlayOwnership(
    ownership: ProfileIdentityOverlayOwnership,
    readyProfileId: String?,
): ProfileIdentityOverlayOwnership {
    if (readyProfileId == null) return ownership
    return ProfileIdentityOverlayOwnership(
        editTargetProfileId = ownership.editTargetProfileId.takeIf { it == readyProfileId },
        deleteTargetProfileId = ownership.deleteTargetProfileId.takeIf { it == readyProfileId },
        pendingIdentityProfileId = ownership.pendingIdentityProfileId.takeIf {
            it == readyProfileId
        },
    )
}

internal data class ProfileIdentityFailureDisposition(
    val ownership: ProfileIdentityOverlayOwnership,
    val showError: Boolean,
)

internal fun applyProfileIdentityFailure(
    ownership: ProfileIdentityOverlayOwnership,
    profileId: String,
    kind: ProfileIdentityMutationKind,
): ProfileIdentityFailureDisposition {
    val showError = when (kind) {
        ProfileIdentityMutationKind.UPDATE -> ownership.editTargetProfileId == profileId
        ProfileIdentityMutationKind.DELETE -> ownership.deleteTargetProfileId == profileId
    }
    return ProfileIdentityFailureDisposition(
        ownership = ownership.copy(
            pendingIdentityProfileId = ownership.pendingIdentityProfileId.takeUnless {
                it == profileId
            },
        ),
        showError = showError,
    )
}

@Composable
fun ProfileScreen(
    onOpenProfileSwitcher: () -> Unit,
    onNavigateToExerciseDetail: (String) -> Unit,
    onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit,
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    exerciseRepository: ExerciseRepository = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val ready = state.context as? ActiveProfileContext.Ready
    val readyProfileId = ready?.profile?.id
    var pickerProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingIdentityProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnProfileRecoveryRequired by rememberUpdatedState(onProfileRecoveryRequired)
    val updateFailedMessage = stringResource(Res.string.profile_update_failed)

    LaunchedEffect(readyProfileId) {
        if (pickerProfileId != readyProfileId) pickerProfileId = null
        val retained = retainProfileIdentityOverlayOwnership(
            ownership = ProfileIdentityOverlayOwnership(
                editTargetProfileId = editTargetProfileId,
                deleteTargetProfileId = deleteTargetProfileId,
                pendingIdentityProfileId = pendingIdentityProfileId,
            ),
            readyProfileId = readyProfileId,
        )
        editTargetProfileId = retained.editTargetProfileId
        deleteTargetProfileId = retained.deleteTargetProfileId
        pendingIdentityProfileId = retained.pendingIdentityProfileId
    }

    LaunchedEffect(viewModel, snackbarHostState, updateFailedMessage) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileUiEvent.IdentityUpdated -> {
                    if (editTargetProfileId == event.profileId) editTargetProfileId = null
                    if (pendingIdentityProfileId == event.profileId) pendingIdentityProfileId = null
                }
                is ProfileUiEvent.IdentityUpdateFailed -> {
                    val failure = applyProfileIdentityFailure(
                        ownership = ProfileIdentityOverlayOwnership(
                            editTargetProfileId = editTargetProfileId,
                            deleteTargetProfileId = deleteTargetProfileId,
                            pendingIdentityProfileId = pendingIdentityProfileId,
                        ),
                        profileId = event.profileId,
                        kind = event.kind,
                    )
                    editTargetProfileId = failure.ownership.editTargetProfileId
                    deleteTargetProfileId = failure.ownership.deleteTargetProfileId
                    pendingIdentityProfileId = failure.ownership.pendingIdentityProfileId
                    if (failure.showError) snackbarHostState.showSnackbar(updateFailedMessage)
                }
                is ProfileUiEvent.ProfileDeleted -> {
                    if (deleteTargetProfileId == event.profileId) deleteTargetProfileId = null
                    if (pendingIdentityProfileId == event.profileId) pendingIdentityProfileId = null
                }
                is ProfileUiEvent.ProfileRecoveryRequired -> {
                    if (pendingIdentityProfileId == event.profileId) {
                        editTargetProfileId = null
                        deleteTargetProfileId = null
                        pendingIdentityProfileId = null
                        currentOnProfileRecoveryRequired(event.cause)
                    }
                }
                ProfileUiEvent.PreferenceUpdateFailed -> {
                    snackbarHostState.showSnackbar(updateFailedMessage)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.testTag(TestTags.SCREEN_PROFILE),
    ) { padding ->
        if (ready == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LoadingIndicator(LoadingIndicatorSize.Large)
                Text(
                    text = stringResource(Res.string.profile_switching),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "profile-header") {
                    ProfileHeaderCard(
                        profile = ready.profile,
                        identityMutationInFlight = state.identityMutationInFlight,
                        onSwitchProfile = onOpenProfileSwitcher,
                        onEdit = { editTargetProfileId = ready.profile.id },
                        onDelete = { deleteTargetProfileId = ready.profile.id },
                    )
                }
                item(key = "exercise-insights") {
                    ProfileExerciseInsights(
                        selectedExercise = state.selectedExercise,
                        missingExerciseId = state.missingExerciseId,
                        selectionFailure = state.selectionFailure,
                        currentOneRepMax = state.currentOneRepMax,
                        prHighlights = state.prHighlights,
                        recentSessions = state.recentSessions,
                        weightUnit = ready.preferences.core.value.weightUnit,
                        onChooseExercise = { pickerProfileId = ready.profile.id },
                        onViewFullHistory = onNavigateToExerciseDetail,
                    )
                }
            }
        }
    }

    val editTarget = ready?.profile?.takeIf { it.id == editTargetProfileId }
    if (editTarget != null) {
        ProfileEditDialog(
            profile = editTarget,
            isSubmitting = state.identityMutationInFlight,
            onConfirm = { name, colorIndex ->
                pendingIdentityProfileId = editTarget.id
                viewModel.updateIdentity(name, colorIndex)
            },
            onDismiss = {
                if (!state.identityMutationInFlight) editTargetProfileId = null
            },
        )
    }

    val deleteTarget = ready?.profile?.takeIf { it.id == deleteTargetProfileId }
    if (deleteTarget != null && canDeleteProfile(deleteTarget)) {
        ProfileDeleteDialog(
            profile = deleteTarget,
            isSubmitting = state.identityMutationInFlight,
            onConfirm = {
                pendingIdentityProfileId = deleteTarget.id
                viewModel.deleteActiveProfile()
            },
            onDismiss = {
                if (!state.identityMutationInFlight) deleteTargetProfileId = null
            },
        )
    }

    ExercisePickerDialog(
        showDialog = pickerProfileId == ready?.profile?.id,
        onDismiss = { pickerProfileId = null },
        onExerciseSelected = { exercise ->
            pickerProfileId = null
            viewModel.selectExercise(exercise)
        },
        exerciseRepository = exerciseRepository,
        enableVideoPlayback = enableVideoPlayback,
        themeMode = themeMode,
        enableCustomExercises = false,
    )
}

@Composable
private fun ProfileHeaderCard(
    profile: UserProfile,
    identityMutationInFlight: Boolean,
    onSwitchProfile: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val switchLabel = stringResource(Res.string.switch_profile)
    val editLabel = stringResource(Res.string.action_edit)
    val deleteLabel = stringResource(Res.string.action_delete)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatar(profile = profile, isActive = true, size = 56.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onSwitchProfile,
                    enabled = !identityMutationInFlight,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = switchLabel
                            role = Role.Button
                        },
                ) {
                    Text(switchLabel)
                }
            }
            IconButton(
                onClick = onEdit,
                enabled = !identityMutationInFlight,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(TestTags.ACTION_EDIT_PROFILE),
            ) {
                Icon(Icons.Default.Edit, contentDescription = editLabel)
            }
            if (canDeleteProfile(profile)) {
                IconButton(
                    onClick = onDelete,
                    enabled = !identityMutationInFlight,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(TestTags.ACTION_DELETE_PROFILE),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = deleteLabel)
                }
            }
        }
    }
}
