package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.RoutineRecoveryItem
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Issue #1162 — recoverable routines surface (DailyRoutinesScreen/RoutinesTab).
 *
 * Shows routines that were retained in the local-only recovery store before a
 * destructive coalesce or a server-reported hard delete, for the signed-in portal
 * user and the active profile only. `cycle_routine_*` template deletions are
 * never surfaced as user routines. Restoring is explicit restore-as-copy: fresh
 * routine and child UUIDs in the same profile, never the server-deleted identity,
 * never a silent cycle or history reconnect. The list screen is never blocked by
 * a dialog — this is a dismissible sheet behind an explicit entry point, and the
 * preview shows the conflicting source programming including planned-set slots
 * that were dropped when duplicates were cleaned up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineRecoveryHost(
    modifier: Modifier = Modifier,
    workoutRepository: WorkoutRepository = koinInject(),
    profileRepository: UserProfileRepository = koinInject(),
) {
    val activeProfile by profileRepository.activeProfile.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RoutineRecoveryItem>>(emptyList()) }
    var sheetOpen by remember { mutableStateOf(false) }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val profile = activeProfile
        items = if (profile == null) {
            emptyList()
        } else {
            try {
                workoutRepository.listRoutineRecoveries(
                    profileId = profile.id,
                    portalUserId = profile.supabaseUserId ?: "",
                )
            } catch (e: Exception) {
                Logger.w(e) { "RoutineRecovery: could not load recoverable routines" }
                emptyList()
            }
        }
    }

    LaunchedEffect(activeProfile?.id, activeProfile?.supabaseUserId) {
        reload()
    }

    if (items.isNotEmpty() && !sheetOpen) {
        FilledTonalButton(
            onClick = { sheetOpen = true },
            modifier = modifier,
        ) {
            Text("Recover deleted routines (${items.size})")
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "Recover deleted routines",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "These routines were removed by a duplicate cleanup or a server-side " +
                        "deletion and can be restored as copies. The preview includes the " +
                        "conflicting programming that was dropped, such as planned-set slots.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { "${it.recoveryId}#${it.graphIndex}" }) { item ->
                        val key = "${item.recoveryId}#${item.graphIndex}"
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(item.routineName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (item.reason) {
                                        "alias_coalesce" -> "Removed as a duplicate of the same routine"
                                        else -> "Deleted on the server"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    TextButton(onClick = {
                                        expandedKey = if (expandedKey == key) null else key
                                    }) {
                                        Text(if (expandedKey == key) "Hide preview" else "Preview")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = {
                                        scope.launch {
                                            try {
                                                workoutRepository.restoreRoutineRecoveryAsCopy(
                                                    recoveryId = item.recoveryId,
                                                    graphIndex = item.graphIndex,
                                                    profileId = activeProfile?.id ?: return@launch,
                                                    portalUserId = activeProfile?.supabaseUserId ?: "",
                                                )
                                            } catch (e: Exception) {
                                                Logger.w(e) { "RoutineRecovery: restore failed for $key" }
                                            }
                                            reload()
                                        }
                                    }) {
                                        Text("Restore as copy")
                                    }
                                }
                                if (expandedKey == key) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(12.dp),
                                    ) {
                                        item.exercises.forEach { exercise ->
                                            Text(
                                                exercise.exerciseName,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                "sets: ${exercise.setReps}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            exercise.plannedSets.forEach { plannedSet ->
                                                Text(
                                                    "slot ${plannedSet.setNumber} (${plannedSet.setType})" +
                                                        (plannedSet.targetReps?.let { " · $it reps" } ?: "") +
                                                        (plannedSet.targetWeightKg?.let { " · $it kg" } ?: ""),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(start = 8.dp),
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
