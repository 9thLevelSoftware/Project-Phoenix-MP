package com.devil.phoenixproject.csv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.data.csv.AndroidCsvImportIntake
import com.devil.phoenixproject.data.csv.CsvImportDeliveryResult
import com.devil.phoenixproject.data.csv.CsvImportInbox
import com.devil.phoenixproject.domain.csv.CsvImportPayload
import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.returnToApp
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Receives a routine CSV shared to or opened with the app (#1242): system share ("Send to"),
 * "Open with", and `phoenix://import?data=` links.
 *
 * A transparent trampoline, like the OAuth redirect: share and open intents usually arrive
 * without `FLAG_ACTIVITY_NEW_TASK`, so registering them on [MainActivity] would start a second
 * [MainActivity] inside the sending app's task. This activity instead reads the file while it
 * still holds the sender's read grant, hands only the bytes (or an error) to [CsvImportInbox],
 * brings the app's own task forward and finishes. The import preview then opens in the app.
 *
 * A file shared while another import is in flight is turned away (the app says so) and never
 * disturbs the one in flight.
 */
class CsvImportActivity : ComponentActivity() {
    private val inbox: CsvImportInbox by inject()

    /** The delivery this instance accepted and has not handed over yet. */
    private var pendingDeliveryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = AndroidCsvImportIntake.describe(intent)
        if (payload == CsvImportPayload.Ignore) {
            finish()
            return
        }
        val deliveryId = generateUUID()
        if (!inbox.tryBegin(deliveryId)) {
            inbox.reportBusy()
            leave()
            return
        }
        pendingDeliveryId = deliveryId
        when (payload) {
            is CsvImportPayload.StreamUri -> lifecycleScope.launch {
                val result = AndroidCsvImportIntake.read(this@CsvImportActivity, payload.uri, RoutineCsvFormat.MAX_BYTES)
                deliver(deliveryId, result)
            }

            is CsvImportPayload.InlineText -> deliver(deliveryId, CsvImportDeliveryResult.Read(payload.text))
            CsvImportPayload.TooLarge -> deliver(deliveryId, CsvImportDeliveryResult.TooLarge)
            CsvImportPayload.Unreadable -> deliver(deliveryId, CsvImportDeliveryResult.Unreadable)
        }
    }

    override fun onDestroy() {
        // Destroyed before the read finished (the read is cancelled with this activity): free
        // the inbox now, so the next share is not turned away as busy.
        pendingDeliveryId?.let(inbox::abandon)
        pendingDeliveryId = null
        super.onDestroy()
    }

    private fun deliver(deliveryId: String, result: CsvImportDeliveryResult) {
        inbox.deliver(deliveryId, result)
        pendingDeliveryId = null
        leave()
    }

    private fun leave() {
        returnToApp(tag = "CsvImportActivity")
        finish()
    }
}
