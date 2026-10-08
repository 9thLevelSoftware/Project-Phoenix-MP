package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.sync.GenerateRoutineErrorKind
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.domain.model.meetsAiRoutineTier
import com.devil.phoenixproject.domain.usecase.GeneratedDraftMapper
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineGenerateOutcome
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineGenerationCoordinator
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.ai_routine_disclosure
import projectphoenix.shared.generated.resources.ai_routine_draft_profile_changed_message
import projectphoenix.shared.generated.resources.ai_routine_draft_profile_changed_title
import projectphoenix.shared.generated.resources.ai_routine_error_invalid_draft
import projectphoenix.shared.generated.resources.ai_routine_error_invalid_request
import projectphoenix.shared.generated.resources.ai_routine_error_not_available
import projectphoenix.shared.generated.resources.ai_routine_error_not_subscribed
import projectphoenix.shared.generated.resources.ai_routine_error_rate_limited_minutes
import projectphoenix.shared.generated.resources.ai_routine_error_rate_limited_seconds
import projectphoenix.shared.generated.resources.ai_routine_error_temporary
import projectphoenix.shared.generated.resources.ai_routine_error_unauthorized
import projectphoenix.shared.generated.resources.ai_routine_gate_link_account
import projectphoenix.shared.generated.resources.ai_routine_gate_message
import projectphoenix.shared.generated.resources.ai_routine_gate_title
import projectphoenix.shared.generated.resources.ai_routine_generate
import projectphoenix.shared.generated.resources.ai_routine_generating
import projectphoenix.shared.generated.resources.ai_routine_hint_limitation
import projectphoenix.shared.generated.resources.ai_routine_load_toggle
import projectphoenix.shared.generated.resources.ai_routine_load_toggle_helper
import projectphoenix.shared.generated.resources.ai_routine_minutes_invalid
import projectphoenix.shared.generated.resources.ai_routine_minutes_label
import projectphoenix.shared.generated.resources.ai_routine_prompt_char_count
import projectphoenix.shared.generated.resources.ai_routine_prompt_hint
import projectphoenix.shared.generated.resources.ai_routine_prompt_label
import projectphoenix.shared.generated.resources.ai_routine_regenerate
import projectphoenix.shared.generated.resources.ai_routine_retention
import projectphoenix.shared.generated.resources.ai_routine_title

/** Prompt length cap (server contract: prompt trimmed 1–1000 chars). */
internal const val AI_ROUTINE_MAX_PROMPT_CHARS = 1000

/** Valid target-minutes value (server contract: optional int 10–120). */
internal fun validAiRoutineTargetMinutes(raw: String): Int? {
    val minutes = raw.trim().toIntOrNull() ?: return null
    return minutes.takeIf { it in 10..120 }
}

/** The minutes field is optional; a blank field means "no target". */
internal fun aiRoutineTargetMinutesOrNull(raw: String): Int? {
    if (raw.isBlank()) return null
    return validAiRoutineTargetMinutes(raw)
}

/** Generate is disabled until the input is valid. */
internal fun isValidAiRoutineInput(prompt: String, minutesRaw: String): Boolean {
    val trimmed = prompt.trim()
    return trimmed.isNotEmpty() &&
        trimmed.length <= AI_ROUTINE_MAX_PROMPT_CHARS &&
        (minutesRaw.isBlank() || validAiRoutineTargetMinutes(minutesRaw) != null)
}

private val LEADING_DURATION_REGEX = Regex(
    """^(\d{1,3})\s*(-|\s)?\s*(m|min|mins|minute|minutes)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * A leading duration in the prompt ("35-min upper body", "45 minutes of chest")
 * may fill the optional minutes field. Only in-range values (10–120) are used.
 */
internal fun parseLeadingDurationMinutes(prompt: String): Int? {
    val match = LEADING_DURATION_REGEX.find(prompt.trimStart()) ?: return null
    val minutes = match.groupValues[1].toIntOrNull() ?: return null
    return minutes.takeIf { it in 10..120 }
}

/**
 * AI routine creation entry (issue #1223 FE-C): tier gate, prompt form with the
 * §1.4 consent/disclosure copy, blocking generation with Cancel, and the
 * generated-draft preview (see [AiRoutineDraftPreview]).
 *
 * Response scoping (binding amendment 4) runs through
 * [AiRoutineGenerationCoordinator]/MainViewModel's in-memory holder: cancel,
 * navigation away, logout, or a profile/account switch invalidates late responses
 * and clears staged drafts. Nothing here writes to any repository — the first and
 * only write is the user's explicit Save in [RoutineEditorScreen].
 */
@Composable
fun AiRoutinePromptScreen(
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
) {
    val portalTokenStorage: PortalTokenStorage = koinInject()
    val portalApiClient: PortalApiClient = koinInject()
    val draftMapper: GeneratedDraftMapper = koinInject()
    val baselineRepository: ProfileExerciseBaselineRepository = koinInject()
    val routineTimeEstimator: RoutineTimeEstimator = koinInject()

    val activeProfileId by viewModel.activeProfileId.collectAsState()

    // Tier gate (issue #1223): FLAME or higher via the portal ladder — never
    // isPremium, never a billing screen. Null tier (signed out) is below Flame.
    val subscriptionTier = portalTokenStorage.getSubscriptionTier()
    val tierGateOpen = meetsAiRoutineTier(subscriptionTier)

    val holder = viewModel.aiRoutineDraftHolder
    val coordinator = remember(portalApiClient, draftMapper, baselineRepository, holder) {
        AiRoutineGenerationCoordinator(
            client = portalApiClient,
            mapper = draftMapper,
            baselineRepository = baselineRepository,
            holder = holder,
            accountIdProvider = { portalTokenStorage.currentUser.value?.id },
            profileIdProvider = { viewModel.activeProfileId.value },
        )
    }

    var prompt by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var includeLoadContext by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<AiRoutinePreviewData?>(null) }
    var errorKind by remember { mutableStateOf<GenerateRoutineErrorKind?>(null) }
    var errorRetryAfterSeconds by remember { mutableStateOf<Int?>(null) }
    var showProfileChangedDialog by remember { mutableStateOf(false) }
    var handingOffToEditor by remember { mutableStateOf(false) }
    var generateJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // Navigation away / logout / profile switch invalidates late responses and
    // clears staged drafts (binding amendment 4). The explicit Edit handoff to the
    // editor is the one sanctioned exit that keeps the staged draft alive.
    DisposableEffect(Unit) {
        onDispose {
            if (!handingOffToEditor) {
                generateJob?.cancel()
                holder.invalidate()
            }
        }
    }

    fun startGenerate() {
        if (!isValidAiRoutineInput(prompt, minutesText)) return
        val targetMinutes = aiRoutineTargetMinutesOrNull(minutesText)
        errorKind = null
        errorRetryAfterSeconds = null
        preview = null
        generating = true
        generateJob = scope.launch {
            val outcome = coordinator.generate(
                prompt = prompt,
                targetMinutes = targetMinutes,
                includeLoadContext = includeLoadContext,
            )
            when (outcome) {
                is AiRoutineGenerateOutcome.Draft -> {
                    val estimate = routineTimeEstimator.estimateRoutineDuration(
                        outcome.routine,
                        viewModel.activeProfileId.value,
                    )
                    preview = AiRoutinePreviewData(
                        routine = outcome.routine,
                        droppedExerciseNames = outcome.warnings,
                        unmetConstraints = outcome.unmetConstraints,
                        requestedMinutes = outcome.requestedMinutes,
                        estimatedSeconds = estimate.totalSeconds,
                        remainingToday = outcome.remainingToday,
                    )
                }
                AiRoutineGenerateOutcome.EmptyDraft -> {
                    // The 422-style message; the editor never opens for an empty draft.
                    errorKind = GenerateRoutineErrorKind.INVALID_DRAFT
                }
                is AiRoutineGenerateOutcome.Rejected -> {
                    // Late response after cancel / navigation away / logout / switch:
                    // rejected, stages nothing. The form (prompt + minutes) is retained.
                }
                is AiRoutineGenerateOutcome.Failed -> {
                    errorKind = outcome.kind
                    errorRetryAfterSeconds = outcome.retryAfterSeconds
                }
            }
            generating = false
        }
    }

    fun cancelGenerate() {
        generateJob?.cancel()
        generateJob = null
        // Cancel retains prompt and minutes, writes nothing, and invalidates any
        // late response for this request.
        holder.invalidate()
        generating = false
    }

    fun editDraft(data: AiRoutinePreviewData) {
        // Re-check the profile before Edit (binding amendment 4): if it changed,
        // offer regenerate rather than moving prior-profile loads around.
        if (holder.stagedProfileMatches(viewModel.activeProfileId.value)) {
            handingOffToEditor = true
            viewModel.stageGeneratedRoutine(data.routine)
            navController.navigate(NavigationRoutes.RoutineEditor.createRoute(data.routine.id))
        } else {
            showProfileChangedDialog = true
        }
    }

    if (showProfileChangedDialog) {
        AlertDialog(
            onDismissRequest = { showProfileChangedDialog = false },
            title = { Text(stringResource(Res.string.ai_routine_draft_profile_changed_title)) },
            text = { Text(stringResource(Res.string.ai_routine_draft_profile_changed_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showProfileChangedDialog = false
                    preview = null
                    startGenerate()
                }) {
                    Text(stringResource(Res.string.ai_routine_regenerate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileChangedDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    val backgroundGradient = screenBackgroundBrush()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
    ) {
        val currentPreview = preview
        when {
            !tierGateOpen -> AiRoutineTierGate(
                onLinkAccount = {
                    navController.navigate(NavigationRoutes.LinkAccount.route)
                },
            )

            generating -> AiRoutineGeneratingContent(onCancel = ::cancelGenerate)

            currentPreview != null -> AiRoutineDraftPreview(
                data = currentPreview,
                onEdit = { editDraft(currentPreview) },
                onDiscard = {
                    // Discard/back writes nothing; the draft lives only in memory.
                    preview = null
                },
            )

            else -> AiRoutinePromptForm(
                prompt = prompt,
                onPromptChange = { newPrompt ->
                    prompt = newPrompt.take(AI_ROUTINE_MAX_PROMPT_CHARS)
                    // A leading duration in the prompt may fill the minutes field.
                    if (minutesText.isBlank()) {
                        parseLeadingDurationMinutes(prompt)?.let { minutesText = it.toString() }
                    }
                },
                minutesText = minutesText,
                onMinutesChange = { raw ->
                    minutesText = raw.filter { it.isDigit() }.take(3)
                },
                includeLoadContext = includeLoadContext,
                onIncludeLoadContextChange = { includeLoadContext = it },
                errorKind = errorKind,
                errorRetryAfterSeconds = errorRetryAfterSeconds,
                onGenerate = ::startGenerate,
            )
        }
    }
}

/** Below-Flame / signed-out gate: explanation + link to the existing LinkAccount screen. */
@Composable
private fun AiRoutineTierGate(
    onLinkAccount: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.ai_routine_gate_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(Res.string.ai_routine_gate_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onLinkAccount) {
            Text(stringResource(Res.string.ai_routine_gate_link_account))
        }
    }
}

/** Blocking generation progress with Cancel. */
@Composable
private fun AiRoutineGeneratingContent(
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(Res.string.ai_routine_generating),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = onCancel) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}

@Composable
private fun AiRoutinePromptForm(
    prompt: String,
    onPromptChange: (String) -> Unit,
    minutesText: String,
    onMinutesChange: (String) -> Unit,
    includeLoadContext: Boolean,
    onIncludeLoadContextChange: (Boolean) -> Unit,
    errorKind: GenerateRoutineErrorKind?,
    errorRetryAfterSeconds: Int?,
    onGenerate: () -> Unit,
) {
    val minutesInvalid = minutesText.isNotBlank() && validAiRoutineTargetMinutes(minutesText) == null
    val generateEnabled = isValidAiRoutineInput(prompt, minutesText)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            stringResource(Res.string.ai_routine_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text(stringResource(Res.string.ai_routine_prompt_label)) },
            placeholder = { Text(stringResource(Res.string.ai_routine_prompt_hint)) },
            supportingText = {
                Text(
                    stringResource(
                        Res.string.ai_routine_prompt_char_count,
                        prompt.length,
                        AI_ROUTINE_MAX_PROMPT_CHARS,
                    ),
                )
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = minutesText,
            onValueChange = onMinutesChange,
            label = { Text(stringResource(Res.string.ai_routine_minutes_label)) },
            isError = minutesInvalid,
            supportingText = {
                if (minutesInvalid) {
                    Text(stringResource(Res.string.ai_routine_minutes_invalid))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Consent / disclosure (spec §1.4) — always shown before the first
        // Generate, independent of the load toggle.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(Res.string.ai_routine_disclosure),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.ai_routine_retention),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Opt-in load context toggle — OFF by default.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeLoadContext,
                onCheckedChange = onIncludeLoadContextChange,
            )
            Column {
                Text(
                    stringResource(Res.string.ai_routine_load_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.ai_routine_load_toggle_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(Res.string.ai_routine_hint_limitation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (errorKind != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(generateErrorText(errorKind, errorRetryAfterSeconds))
                    TextButton(onClick = onGenerate) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = onGenerate,
            enabled = generateEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.ai_routine_generate))
        }
    }
}

/** User-facing copy for one generate-routine failure; never raw provider errors. */
@Composable
internal fun generateErrorText(
    kind: GenerateRoutineErrorKind,
    retryAfterSeconds: Int?,
): String = when (kind) {
    GenerateRoutineErrorKind.NOT_SUBSCRIBED ->
        stringResource(Res.string.ai_routine_error_not_subscribed)

    GenerateRoutineErrorKind.NOT_AVAILABLE ->
        stringResource(Res.string.ai_routine_error_not_available)

    GenerateRoutineErrorKind.INVALID_DRAFT ->
        stringResource(Res.string.ai_routine_error_invalid_draft)

    GenerateRoutineErrorKind.RATE_LIMITED -> {
        val seconds = (retryAfterSeconds ?: 0).coerceAtLeast(1)
        if (seconds >= 60) {
            stringResource(
                Res.string.ai_routine_error_rate_limited_minutes,
                (seconds + 59) / 60,
            )
        } else {
            stringResource(Res.string.ai_routine_error_rate_limited_seconds, seconds)
        }
    }

    GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE ->
        stringResource(Res.string.ai_routine_error_temporary)

    GenerateRoutineErrorKind.UNAUTHORIZED ->
        stringResource(Res.string.ai_routine_error_unauthorized)

    GenerateRoutineErrorKind.INVALID_REQUEST ->
        stringResource(Res.string.ai_routine_error_invalid_request)
}
