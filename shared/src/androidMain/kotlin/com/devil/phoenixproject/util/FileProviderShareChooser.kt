package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Starts the system share sheet for a FileProvider [uri].
 *
 * CSV export, JSON backup, and database-zip export use the same grant flag,
 * ClipData handling, and chooser flag: [Intent.FLAG_GRANT_READ_URI_PERMISSION]
 * on the send intent, no [android.content.ClipData], and
 * [Intent.FLAG_ACTIVITY_NEW_TASK] on the chooser. [newTaskOnSendIntent] also
 * sets [Intent.FLAG_ACTIVITY_NEW_TASK] on the send intent. CSV export and
 * backup share pass true; database export passes false.
 *
 * Failures from [Context.startActivity] propagate so each caller can keep its
 * own policy.
 */
internal fun Context.launchFileProviderShareChooser(
    uri: Uri,
    mimeType: String,
    chooserTitle: String,
    subject: String,
    newTaskOnSendIntent: Boolean,
) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (newTaskOnSendIntent) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    startActivity(
        Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
