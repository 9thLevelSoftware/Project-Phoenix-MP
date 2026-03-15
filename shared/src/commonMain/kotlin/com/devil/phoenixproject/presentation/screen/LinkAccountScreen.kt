package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.ui.sync.LinkAccountUiState
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import com.devil.phoenixproject.util.KmpUtils
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkAccountScreen(
    viewModel: LinkAccountViewModel = koinInject(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phoenix Portal") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sync your workouts across devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isAuthenticated && currentUser != null) {
                // Logged in state
                LinkedAccountContent(
                    user = currentUser!!,
                    syncState = syncState,
                    lastSyncTime = lastSyncTime,
                    onSync = { viewModel.sync() },
                    onLogout = { viewModel.logout() }
                )
            } else {
                // Login/Signup form
                LoginSignupForm(
                    uiState = uiState,
                    onLogin = { email, password -> viewModel.login(email, password) },
                    onSignup = { email, password, name -> viewModel.signup(email, password, name) },
                    onClearError = { viewModel.clearError() }
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
    onLogout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Linked Account",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = user.email)
            user.displayName?.let { name ->
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (user.isPremium) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("Premium") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync status
            when (syncState) {
                is SyncState.Syncing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Syncing...")
                }
                is SyncState.Success -> {
                    Text("Last synced: ${formatSyncTimestamp(syncState.syncTime)}")
                }
                is SyncState.Error -> {
                    Text(
                        text = "Sync error: ${syncState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is SyncState.NotPremium -> {
                    Text(
                        text = "Premium subscription required for sync",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    if (lastSyncTime > 0) {
                        Text("Last synced: ${formatSyncTimestamp(lastSyncTime)}")
                    } else {
                        Text("Never synced")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onLogout) {
                    Text("Unlink Account")
                }

                Button(
                    onClick = onSync,
                    enabled = syncState !is SyncState.Syncing
                ) {
                    Text("Sync Now")
                }
            }
        }
    }
}

@Composable
private fun LoginSignupForm(
    uiState: LinkAccountUiState,
    onLogin: (String, String) -> Unit,
    onSignup: (String, String, String) -> Unit,
    onClearError: () -> Unit
) {
    var isSignupMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Tab row for Login/Signup
            PrimaryTabRow(
                selectedTabIndex = if (isSignupMode) 1 else 0
            ) {
                Tab(
                    selected = !isSignupMode,
                    onClick = { isSignupMode = false; onClearError() }
                ) {
                    Text("Login", modifier = Modifier.padding(16.dp))
                }
                Tab(
                    selected = isSignupMode,
                    onClick = { isSignupMode = true; onClearError() }
                ) {
                    Text("Sign Up", modifier = Modifier.padding(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isSignupMode) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password"
                        )
                    }
                }
            )

            // Error message
            if (uiState is LinkAccountUiState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isSignupMode) {
                        onSignup(email, password, displayName)
                    } else {
                        onLogin(email, password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() &&
                        (!isSignupMode || displayName.isNotBlank()) &&
                        uiState !is LinkAccountUiState.Loading
            ) {
                if (uiState is LinkAccountUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isSignupMode) "Create Account" else "Login")
                }
            }
        }
    }
}

private fun formatSyncTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val date = KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")
    val time = KmpUtils.formatTimestamp(timestamp, "h:mm a")
    return "$date, $time"
}
