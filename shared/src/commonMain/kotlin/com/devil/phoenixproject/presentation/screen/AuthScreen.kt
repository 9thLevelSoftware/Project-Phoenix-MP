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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.repository.AuthState
import com.devil.phoenixproject.presentation.components.AppleIcon
import com.devil.phoenixproject.presentation.components.GoogleIcon
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.auth_apple
import vitruvianprojectphoenix.shared.generated.resources.auth_google
import vitruvianprojectphoenix.shared.generated.resources.cd_back
import vitruvianprojectphoenix.shared.generated.resources.label_confirm_password
import vitruvianprojectphoenix.shared.generated.resources.label_email
import vitruvianprojectphoenix.shared.generated.resources.label_password

enum class AuthMode {
    SIGN_IN,
    SIGN_UP,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(authRepository: AuthRepository, onAuthSuccess: () -> Unit, onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val authState by authRepository.authState.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Navigate away when already authenticated — wrapped in LaunchedEffect to avoid
    // calling the navigation side-effect during composition (causes duplicate nav on recomposition).
    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            onAuthSuccess()
        }
    }
    if (authState is AuthState.Authenticated) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (authMode == AuthMode.SIGN_IN) "Sign In" else "Create Account") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (authMode == AuthMode.SIGN_IN) {
                    "Welcome back! Sign in to access your premium features."
                } else {
                    "Create an account to unlock premium features."
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Email field
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    errorMessage = null
                },
                label = { Text(stringResource(Res.string.label_email)) },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage != null,
            )

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text(stringResource(Res.string.label_password)) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (authMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.clearFocus() },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage != null,
            )

            // Confirm password (sign up only)
            if (authMode == AuthMode.SIGN_UP) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Res.string.label_confirm_password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                )
            }

            // Error message
            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Submit button
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null

                        // Validation
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Please fill in all fields"
                            isLoading = false
                            return@launch
                        }

                        if (authMode == AuthMode.SIGN_UP && password != confirmPassword) {
                            errorMessage = "Passwords do not match"
                            isLoading = false
                            return@launch
                        }

                        val result = if (authMode == AuthMode.SIGN_IN) {
                            authRepository.signInWithEmail(email, password)
                        } else {
                            authRepository.signUpWithEmail(email, password)
                        }

                        result.fold(
                            onSuccess = { onAuthSuccess() },
                            onFailure = { e -> errorMessage = e.message ?: "Authentication failed" },
                        )
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (authMode == AuthMode.SIGN_IN) "Sign In" else "Create Account")
            }

            // Toggle auth mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (authMode == AuthMode.SIGN_IN) {
                        "Don't have an account?"
                    } else {
                        "Already have an account?"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        authMode = if (authMode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                        errorMessage = null
                    },
                ) {
                    Text(if (authMode == AuthMode.SIGN_IN) "Sign Up" else "Sign In")
                }
            }

            // OAuth sign-in (sign-in mode only).
            // Mobile does NOT offer OAuth sign-up: new accounts must be created on the
            // portal web app, where provider consent + email verification land correctly.
            // Mobile reuses an existing portal account via sign-in only.
            if (authMode == AuthMode.SIGN_IN) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Or continue with",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                authRepository.signInWithGoogle().fold(
                                    onSuccess = { onAuthSuccess() },
                                    onFailure = { e -> errorMessage = e.message ?: "Google sign-in failed" },
                                )
                                isLoading = false
                            }
                        },
                        enabled = !isLoading,
                    ) {
                        GoogleIcon(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.auth_google))
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                authRepository.signInWithApple().fold(
                                    onSuccess = { onAuthSuccess() },
                                    onFailure = { e -> errorMessage = e.message ?: "Apple sign-in failed" },
                                )
                                isLoading = false
                            }
                        },
                        enabled = !isLoading,
                    ) {
                        AppleIcon(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.auth_apple))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// GoogleIcon / AppleIcon now live in
// com.devil.phoenixproject.presentation.components.OAuthProviderIcons so the
// LinkAccount surface can reuse them. Imports above point to that file.
