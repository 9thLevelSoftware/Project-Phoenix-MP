package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
private val HANDLE_WIDTH = 24.dp
private val HANDLE_HEIGHT = 48.dp
private val EDGE_SWIPE_THRESHOLD = 20.dp

/**
 * Slide-in profile panel from right edge.
 * Replaces ProfileSpeedDial FAB with a less intrusive side panel.
 */
@Composable
fun ProfileSidePanel(
    profiles: List<UserProfile>,
    activeProfile: UserProfile?,
    profileRepository: UserProfileRepository,
    scope: CoroutineScope,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isOpen by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf<UserProfile?>(null) }
    var showEditDialog by remember { mutableStateOf<UserProfile?>(null) }
    var showDeleteDialog by remember { mutableStateOf<UserProfile?>(null) }

    // Responsive panel width based on screen size
    val windowSizeClass = LocalWindowSizeClass.current
    val panelWidth = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 280.dp
        WindowWidthSizeClass.Medium -> 240.dp
        WindowWidthSizeClass.Compact -> 200.dp
    }

    val density = LocalDensity.current
    val panelWidthPx = with(density) { panelWidth.toPx() }

    // Panel offset animation
    val panelOffset by animateDpAsState(
        targetValue = if (isOpen) 0.dp else panelWidth,
        animationSpec = tween(durationMillis = 300),
        label = "panelOffset"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim (when open)
        if (isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isOpen = false }
                    .zIndex(1f)
            )
        }

        // Edge swipe detection zone
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(EDGE_SWIPE_THRESHOLD)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount < -10) {
                            isOpen = true
                        }
                    }
                }
        )

        // Panel + Handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(panelOffset.roundToPx(), 0) }
                .zIndex(2f)
        ) {
            // Chevron handle (visible when closed)
            Surface(
                modifier = Modifier
                    .offset(x = -HANDLE_WIDTH)
                    .align(Alignment.CenterStart)
                    .width(HANDLE_WIDTH)
                    .height(HANDLE_HEIGHT)
                    .clickable { isOpen = !isOpen },
                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                color = ProfileColors.getOrElse(activeProfile?.colorIndex ?: 0) { ProfileColors[0] }.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = if (isOpen) "Close profiles" else "Open profiles",
                        tint = Color.White
                    )
                }
            }

            // Main panel - wraps content height
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount > 20) {
                                isOpen = false
                            }
                        }
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp
            ) {
                Column {
                    // Header
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )

                    HorizontalDivider()

                    // Profile list
                    Column {
                        profiles.forEach { profile ->
                            val isActive = profile.id == activeProfile?.id

                            Box {
                                ProfileListItem(
                                    profile = profile,
                                    isActive = isActive,
                                    onClick = {
                                        scope.launch { profileRepository.setActiveProfile(profile.id) }
                                        isOpen = false
                                    },
                                    onLongClick = {
                                        showContextMenu = profile
                                    }
                                )

                                // Context menu dropdown
                                DropdownMenu(
                                    expanded = showContextMenu?.id == profile.id,
                                    onDismissRequest = { showContextMenu = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit") },
                                        onClick = {
                                            showEditDialog = profile
                                            showContextMenu = null
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        }
                                    )
                                    // Don't show delete for default profile
                                    if (profile.id != "default") {
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = {
                                                showDeleteDialog = profile
                                                showContextMenu = null
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Add profile button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isOpen = false
                                onAddProfile()
                            },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Text(
                                text = "Add Profile",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // Edit dialog
    showEditDialog?.let { profile ->
        EditProfileDialog(
            profile = profile,
            profileRepository = profileRepository,
            scope = scope,
            onDismiss = { showEditDialog = null }
        )
    }

    // Delete dialog
    showDeleteDialog?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            profileRepository = profileRepository,
            scope = scope,
            onDismiss = { showDeleteDialog = null }
        )
    }
}
