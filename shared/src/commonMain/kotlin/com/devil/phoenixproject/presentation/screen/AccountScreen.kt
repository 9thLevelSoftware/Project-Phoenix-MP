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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.repository.AuthState
import com.devil.phoenixproject.domain.subscription.SubscriptionManager
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    authRepository: AuthRepository,
    subscriptionManager: SubscriptionManager,
    onBackClick: () -> Unit,
    onSignInClick: () -> Unit,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authState by authRepository.authState.collectAsState()
    // TODO: Uncomment when subscription system is ready
    // val hasProAccess by subscriptionManager.hasProAccess.collectAsState()
    // val customerInfo by subscriptionManager.customerInfo.collectAsState()
    val scope = rememberCoroutineScope()
    var isSigningOut by remember { mutableStateOf(false) }

    // Suppress unused parameter warnings for disabled features
    @Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")
    val _unused1 = onUpgradeClick
    @Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")
    val _unused2 = subscriptionManager

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account info card
            when (val state = authState) {
                is AuthState.Authenticated -> {
                    AuthenticatedAccountCard(
                        email = state.user.email ?: "Unknown",
                        displayName = state.user.displayName,
                        onSignOut = {
                            scope.launch {
                                isSigningOut = true
                                authRepository.signOut()
                                subscriptionManager.logoutFromRevenueCat()
                                isSigningOut = false
                            }
                        },
                        isSigningOut = isSigningOut
                    )
                }
                is AuthState.NotAuthenticated -> {
                    NotAuthenticatedCard(onSignInClick = onSignInClick)
                }
                else -> {
                    // Loading state - show placeholder
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(Res.string.label_loading))
                        }
                    }
                }
            }

            // TODO: Uncomment when subscription system is ready for public release
            // Subscription status card
            // SubscriptionStatusCard(
            //     hasProAccess = hasProAccess,
            //     expirationDate = customerInfo?.entitlements?.active?.get("pro_access")?.expirationDateMillis,
            //     onUpgradeClick = onUpgradeClick
            // )

            // Manage subscription
            // if (hasProAccess) {
            //     Card(
            //         modifier = Modifier.fillMaxWidth()
            //     ) {
            //         Column(
            //             modifier = Modifier.padding(16.dp),
            //             verticalArrangement = Arrangement.spacedBy(8.dp)
            //         ) {
            //             Text(
            //                 text = "Manage Subscription",
            //                 style = MaterialTheme.typography.titleMedium,
            //                 fontWeight = FontWeight.SemiBold
            //             )
            //             Text(
            //                 text = "To cancel or modify your subscription, visit your device's app store settings.",
            //                 style = MaterialTheme.typography.bodyMedium,
            //                 color = MaterialTheme.colorScheme.onSurfaceVariant
            //             )
            //         }
            //     }
            // }
        }
    }
}

@Composable
private fun AuthenticatedAccountCard(
    email: String,
    displayName: String?,
    onSignOut: () -> Unit,
    isSigningOut: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column {
                    Text(
                        text = displayName ?: "Phoenix User",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                enabled = !isSigningOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (isSigningOut) "Signing out..." else "Sign Out")
            }
        }
    }
}

@Composable
private fun NotAuthenticatedCard(
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Sign in to sync your data",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Create an account to sync your workouts across devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onSignInClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(Res.string.sign_in_create_account))
            }
        }
    }
}

@Composable
private fun SubscriptionStatusCard(
    hasProAccess: Boolean,
    expirationDate: Long?,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasProAccess)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasProAccess) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column {
                    Text(
                        text = if (hasProAccess) "Phoenix Pro" else "Free Plan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (hasProAccess && expirationDate != null) {
                        Text(
                            text = "Renews automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!hasProAccess) {
                Text(
                    text = "Upgrade to Pro to unlock AI routines, cloud sync, and more!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onUpgradeClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(Res.string.upgrade_to_pro))
                }
            }
        }
    }
}
