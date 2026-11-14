package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.devil.phoenixproject.data.media.ExerciseMediaLimits
import com.devil.phoenixproject.data.media.StagedMedia
import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.media_add_photos
import projectphoenix.shared.generated.resources.media_copying_progress
import projectphoenix.shared.generated.resources.media_error_generic
import projectphoenix.shared.generated.resources.media_error_too_large
import projectphoenix.shared.generated.resources.media_error_too_many
import projectphoenix.shared.generated.resources.media_error_unreadable
import projectphoenix.shared.generated.resources.media_error_unsupported
import projectphoenix.shared.generated.resources.media_flipbook_note
import projectphoenix.shared.generated.resources.media_photo_badge
import projectphoenix.shared.generated.resources.media_photos_count
import projectphoenix.shared.generated.resources.media_photos_helper
import projectphoenix.shared.generated.resources.media_photos_privacy_note
import projectphoenix.shared.generated.resources.media_preference_off_notice
import projectphoenix.shared.generated.resources.media_preview_close
import projectphoenix.shared.generated.resources.media_preview_next
import projectphoenix.shared.generated.resources.media_preview_previous
import projectphoenix.shared.generated.resources.media_preview_title
import projectphoenix.shared.generated.resources.media_remove_photo
import projectphoenix.shared.generated.resources.media_remove_confirm_message
import projectphoenix.shared.generated.resources.media_remove_confirm_title
import projectphoenix.shared.generated.resources.media_removal_queued

/**
 * One photo slot in the editor strip: a saved row (DB-backed), a staged draft
 * copy, or a saved row queued for removal at Save (SD2).
 */
private data class PhotoEntry(
    val key: String,
    val url: String,
    val displayName: String,
    val imageId: Long?, // null for staged drafts
    val queuedForRemoval: Boolean,
)

/**
 * Photo attach/manage section for the custom-exercise dialog (issue #1358, P4).
 *
 * Draft-on-Save (SD2): picks stage copies and removals only queue — both apply
 * at Save. All photos are manageable in the editor (SD12); the in-use flipbook
 * shows at most the first two, disclosed below. Preference OFF (SD10) blocks
 * Add but keeps thumbnails, preview, review and removal.
 */
@Composable
fun ExerciseMediaAttachments(
    coordinator: ExerciseMediaSaveCoordinator,
    savedImages: List<ExerciseImageEntity>,
    enableAttach: Boolean,
    onRequestPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewIndex by remember { mutableStateOf(-1) }
    var confirmRemoveKey by remember { mutableStateOf<String?>(null) }

    val entries = buildList {
        savedImages.forEach { row ->
            add(
                PhotoEntry(
                    key = "saved-${row.id}",
                    url = row.url,
                    displayName = row.url.substringAfterLast('/'),
                    imageId = row.id,
                    queuedForRemoval = coordinator.isQueuedForRemoval(row.id),
                ),
            )
        }
        coordinator.stagedAdditions.forEach { staged ->
            add(
                PhotoEntry(
                    key = "staged-${staged.stagedPath}",
                    url = staged.stagedPath,
                    displayName = staged.displayName,
                    imageId = null,
                    queuedForRemoval = false,
                ),
            )
        }
    }
    val total = entries.size
    val remaining = ExerciseMediaLimits.MAX_PHOTOS_PER_EXERCISE - total
    val copying = coordinator.phase is MediaPhase.Copying

    Column(modifier = modifier.fillMaxWidth()) {
        // Header count: "Photos · N of 6"
        Text(
            text = stringResource(Res.string.media_photos_count, total, ExerciseMediaLimits.MAX_PHOTOS_PER_EXERCISE),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.media_photos_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.media_photos_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.media_flipbook_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!enableAttach) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.media_preference_off_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (total == 0) {
            // Empty state
            OutlinedButton(
                onClick = onRequestPick,
                enabled = enableAttach && !copying,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.media_add_photos))
            }
        } else {
            // Thumbnail strip in pick order
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(entries, key = { it.key }) { entry ->
                    PhotoThumbnail(
                        entry = entry,
                        index = entries.indexOf(entry),
                        onOpenPreview = { previewIndex = entries.indexOf(entry) },
                        onRemove = {
                            if (entry.imageId != null) {
                                confirmRemoveKey = entry.key
                            } else {
                                coordinator.stagedAdditions.removeAll {
                                    "staged-${it.stagedPath}" == entry.key
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestPick,
                enabled = enableAttach && !copying && remaining > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.media_add_photos))
            }
        }

        // Copy progress: "Copying photos… n of m" (Add disabled above while copying)
        val phase = coordinator.phase
        if (phase is MediaPhase.Copying) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.media_copying_progress, phase.done.coerceAtLeast(1), phase.total),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Inline error (whole-batch rejection copy, SD11)
        coordinator.errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = message },
            )
        }
    }

    // Management preview modal (all photos manageable — SD12)
    if (previewIndex in entries.indices) {
        val entry = entries[previewIndex]
        AlertDialog(
            onDismissRequest = { previewIndex = -1 },
            title = {
                Text(
                    stringResource(
                        Res.string.media_preview_title,
                        previewIndex + 1,
                        total,
                    ),
                )
            },
            text = {
                Column {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(entry.url)
                                .build(),
                            contentDescription = stringResource(
                                Res.string.media_preview_title,
                                previewIndex + 1,
                                total,
                            ),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = entry.displayName },
                    )
                    if (entry.queuedForRemoval) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.media_removal_queued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { if (previewIndex > 0) previewIndex -= 1 },
                        enabled = previewIndex > 0,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.media_preview_previous))
                    }
                    TextButton(
                        onClick = { if (previewIndex < total - 1) previewIndex += 1 },
                        enabled = previewIndex < total - 1,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.media_preview_next))
                    }
                }
            },
            dismissButton = {
                Row {
                    if (entry.imageId != null && !entry.queuedForRemoval) {
                        TextButton(
                            onClick = {
                                confirmRemoveKey = entry.key
                                previewIndex = -1
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.media_remove_photo))
                        }
                    }
                    TextButton(
                        onClick = { previewIndex = -1 },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.media_preview_close))
                    }
                }
            },
        )
    }

    // Removal confirmation (saved rows only): queues at Save, never deletes now
    confirmRemoveKey?.let { key ->
        val entry = entries.firstOrNull { it.key == key }
        if (entry == null || entry.imageId == null) {
            confirmRemoveKey = null
        } else {
            AlertDialog(
                onDismissRequest = { confirmRemoveKey = null },
                title = { Text(stringResource(Res.string.media_remove_confirm_title)) },
                text = { Text(stringResource(Res.string.media_remove_confirm_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            coordinator.queueRemove(entry.imageId, entry.url)
                            confirmRemoveKey = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.media_remove_photo))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmRemoveKey = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.media_preview_close))
                    }
                },
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(
    entry: PhotoEntry,
    index: Int,
    onOpenPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = stringResource(Res.string.media_photo_badge)
    val removeLabel = stringResource(Res.string.media_remove_photo)
    Box(
        modifier = Modifier
            .size(88.dp)
            .semantics {
                contentDescription = "$label ${index + 1}: ${entry.displayName}"
            },
    ) {
        Card(
            modifier = Modifier
                .size(88.dp)
                .clickable(onClick = onOpenPreview),
            shape = RoundedCornerShape(8.dp),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(entry.url)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Badge(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(2.dp),
        ) {
            Text(label)
        }
        if (entry.queuedForRemoval) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.media_removal_queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .semantics { contentDescription = removeLabel },
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
