package com.devil.phoenixproject.data.csv

import android.content.Context
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.BoundedUriContent
import com.devil.phoenixproject.util.readUpTo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Android side of the CSV import bridge (#1242). The intake offers terminal results
 * (bytes or error) per delivery; the shared collector consumes them explicitly.
 * No source URI, inline CSV body or query string is retained or logged here.
 */
object AndroidCsvImportBridge {
    private val log = Logger.withTag("CsvImportIntake")

    private val _offers = MutableSharedFlow<CsvImportOffer>(
        // Replay keeps a delivery offered before the collector composed (cold start runs
        // splash/EULA/migrations first). Ownership still grants at-most-once handling: a
        // replayed offer for a consumed delivery is silently dropped.
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val offers: Flow<CsvImportOffer> = _offers.asSharedFlow()

    private val acknowledged = ArrayDeque<String>()
    private val outstandingReads = AtomicInteger(0)

    /** Publishes one delivery's terminal result. Acknowledged (consumed) ids never replay. */
    @Synchronized
    fun offer(deliveryId: String, result: CsvImportDeliveryResult) {
        if (acknowledged.contains(deliveryId)) return
        log.i { "csv_import_delivery outcome=${outcomeName(result)}" }
        _offers.tryEmit(CsvImportOffer(deliveryId, result))
    }

    /** Explicit consume/acknowledge of a delivered offer. */
    @Synchronized
    fun acknowledge(deliveryId: String) {
        if (!acknowledged.contains(deliveryId)) acknowledged.addLast(deliveryId)
        while (acknowledged.size > ACK_MEMORY) acknowledged.removeFirst()
    }

    /**
     * Bounded, off-main read of an incoming provider URI. Streams are always closed and a
     * cancelled coroutine interrupts the blocking read (a timeout alone never proved that).
     * Failures log the outcome only — never the URI or provider exception detail.
     */
    suspend fun readCsvImportText(uri: String, maxBytes: Int): CsvImportDeliveryResult =
        withContext(Dispatchers.IO) {
            if (outstandingReads.incrementAndGet() > MAX_OUTSTANDING_READS) {
                outstandingReads.decrementAndGet()
                log.i { "csv_import_read outcome=busy" }
                return@withContext CsvImportDeliveryResult.Busy
            }
            try {
                val context: Context = getKoin().get()
                val stream = context.contentResolver.openInputStream(uri.toUri())
                if (stream == null) {
                    log.i { "csv_import_read outcome=unreadable" }
                    return@withContext CsvImportDeliveryResult.Unreadable
                }
                val bounded = stream.use { open ->
                    runInterruptible { readUpTo(open, maxBytes) }
                }
                when (bounded) {
                    is BoundedUriContent.Read -> {
                        log.i { "csv_import_read outcome=read" }
                        CsvImportDeliveryResult.Read(bounded.content)
                    }

                    BoundedUriContent.TooLarge -> {
                        log.i { "csv_import_read outcome=too_large" }
                        CsvImportDeliveryResult.TooLarge
                    }

                    BoundedUriContent.Unreadable -> {
                        log.i { "csv_import_read outcome=unreadable" }
                        CsvImportDeliveryResult.Unreadable
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Scoped-storage refusal, revoked grant, missing provider: all ordinary
                // unreadable outcomes. Log no URI and no provider exception detail.
                log.i { "csv_import_read outcome=unreadable" }
                CsvImportDeliveryResult.Unreadable
            } finally {
                outstandingReads.decrementAndGet()
            }
        }

    private fun outcomeName(result: CsvImportDeliveryResult): String = when (result) {
        is CsvImportDeliveryResult.Read -> "read"
        CsvImportDeliveryResult.TooLarge -> "too_large"
        CsvImportDeliveryResult.Unreadable -> "unreadable"
        CsvImportDeliveryResult.Busy -> "busy"
    }

    private const val ACK_MEMORY = 64

    /** At most two provider reads in flight; a third delivery reports Busy. */
    private const val MAX_OUTSTANDING_READS = 2
}

actual fun csvImportOffers(): Flow<CsvImportOffer> = AndroidCsvImportBridge.offers

actual fun acknowledgeCsvImportOffer(deliveryId: String) = AndroidCsvImportBridge.acknowledge(deliveryId)
