package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.sync.AuthEvent
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.ui.sync.LinkAccountUiState
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_login
import vitruvianprojectphoenix.shared.generated.resources.cd_back
import vitruvianprojectphoenix.shared.generated.resources.label_email
import vitruvianprojectphoenix.shared.generated.resources.label_password
import vitruvianprojectphoenix.shared.generated.resources.label_premium
import vitruvianprojectphoenix.shared.generated.resources.last_synced
import vitruvianprojectphoenix.shared.generated.resources.never_synced
import vitruvianprojectphoenix.shared.generated.resources.phoenix_portal
import vitruvianprojectphoenix.shared.generated.resources.sync_now
import vitruvianprojectphoenix.shared.generated.resources.syncing
import vitruvianprojectphoenix.shared.generated.resources.unlink_account

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkAccountScreen(onNavigateBack: () -> Unit) {
    // Get ViewModel from Koin - rememberKoinInject ensures stable instance across recompositions
    val viewModel: LinkAccountViewModel = koinInject()

    // Cancel pending coroutines when the screen leaves composition
    // Use rememberUpdatedState to capture the current viewModel for disposal
    val currentViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(Unit) {
        onDispose {
            currentViewModel.clear()
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Observe auth events for user feedback
    LaunchedEffect(Unit) {
        viewModel.authEvents.collect { event ->
            when (event) {
                is AuthEvent.SessionExpired -> {
                    snackbarHostState.showSnackbar("Session expired: ${event.reason}")
                }
                is AuthEvent.RefreshFailed -> {
                    val message = if (event.isRecoverable)
                        "Connection issue. Please try again."
                    else
                        "Please sign in again."
                    snackbarHostState.showSnackbar(message)
                }
                AuthEvent.LoggedOut -> {
                    // Navigation handled elsewhere
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.phoenix_portal)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Sync your workouts across devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isAuthenticated && currentUser != null) {
                // Logged in state
                LinkedAccountContent(
                    user = currentUser!!,
                    syncState = syncState,
                    lastSyncTime = lastSyncTime,
                    onSync = { viewModel.sync() },
                    onForceFullResync = { viewModel.forceFullResync() },
                    onLogout = { viewModel.logout() },
                )
            } else {
                // Login form (sign up happens via Phoenix Portal)
                LoginForm(
                    uiState = uiState,
                    onLogin = { email, password -> viewModel.login(email, password) },
                    onClearError = { viewModel.clearError() },
                )
            }
        }
    }
}

@Composable
private fun LinkedAccountContent(
    user: com.devil.phoenixproject.data.sync.PortalUser,
    syncState: SyncState,
    lastSyncTime: Long,
    onSync: () -> Unit,
    onForceFullResync: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Linked Account",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = user.email)
            user.displayName?.let { name ->
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (user.isPremium) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(Res.string.label_premium)) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync status
            when (syncState) {
                is SyncState.Syncing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.syncing))
                }

                is SyncState.SyncingWithProgress -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Syncing... (${syncState.pagesProcessed} pages, ${syncState.entitiesFetched} items)")
                }

                is SyncState.Success -> {
                    Text(stringResource(Res.string.last_synced, formatSyncTimestamp(syncState.syncTime)))
                }

                is SyncState.Error -> {
                    Text(
                        text = "Sync error: ${syncState.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is SyncState.NotPremium -> {
                    Text(
                        text = "Premium subscription required for sync",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is SyncState.NotAuthenticated -> {
                    Text(
                        text = "Authentication failed — please sign out and sign back in",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is SyncState.Idle -> {
                    if (lastSyncTime > 0) {
                        Text(stringResource(Res.string.last_synced, formatSyncTimestamp(lastSyncTime)))
                    } else {
                        Text(stringResource(Res.string.never_synced))
                    }
                }

                is SyncState.PartialSuccess -> {
                    Text(
                        text = "Sync partially complete — pull failed: ${syncState.pullError ?: "unknown error"}",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = onLogout) {
                    Text(stringResource(Res.string.unlink_account))
                }

                Button(
                    onClick = onSync,
                    enabled = syncState !is SyncState.Syncing,
                ) {
                    Text(stringResource(Res.string.sync_now))
                }
            }

            // Force full resync option for when delta sync misses data
            TextButton(
                onClick = onForceFullResync,
                enabled = syncState !is SyncState.Syncing,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "Force Full Resync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun LoginForm(uiState: LinkAccountUiState, onLogin: (String, String) -> Unit, onClearError: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.action_login),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    onClearError()
                },
                label = { Text(stringResource(Res.string.label_email)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    onClearError()
                },
                label = { Text(stringResource(Res.string.label_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
            )

            // Error message
            if (uiState is LinkAccountUiState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onLogin(email, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() &&
                    uiState !is LinkAccountUiState.Loading,
            ) {
                if (uiState is LinkAccountUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(Res.string.action_login))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Log in with your existing Phoenix Portal account to sync workouts across devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatSyncTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val date = KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")
    val time = KmpUtils.formatTimestamp(timestamp, "h:mm a")
    return "$date, $time"
}
