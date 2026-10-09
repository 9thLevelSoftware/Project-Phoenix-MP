package com.devil.phoenixproject.data.csv

import kotlinx.coroutines.flow.Flow

/**
 * Shared handoff for intent-based routine CSV import (#1242). The Android intake reads the
 * incoming bytes (bounded, off-main, privacy-safe) and offers the terminal result here; the
 * shared collector in `EnhancedMainScreen` consumes the offer explicitly. Only bytes or a
 * terminal error cross this bridge — never a source URI.
 */
sealed interface CsvImportDeliveryResult {
    /** UTF-8 CSV text, already bounded to `RoutineCsvFormat.MAX_BYTES`. */
    data class Read(val content: String) : CsvImportDeliveryResult

    /** The source was larger than the byte bound; nothing was retained. */
    data object TooLarge : CsvImportDeliveryResult

    /** The source could not be read (revoked grant, missing file, parse-ineligible text). */
    data object Unreadable : CsvImportDeliveryResult

    /** Rejected: another import delivery is in flight (B4). Requires a fresh share. */
    data object Busy : CsvImportDeliveryResult
}

/** One terminal result of one accepted intent delivery. Identity is per delivery, never per content. */
data class CsvImportOffer(val deliveryId: String, val result: CsvImportDeliveryResult)

/** Offers of accepted CSV import deliveries. Empty on iOS (no intake there). */
expect fun csvImportOffers(): Flow<CsvImportOffer>

/** Explicit consume/acknowledge: a consumed delivery is never replayed to the collector. */
expect fun acknowledgeCsvImportOffer(deliveryId: String)

/** Phase of the single active intake state machine (B4 amendment 4). */
enum class CsvImportIntakePhase { IDLE, READING, WAITING_STARTUP, PREVIEW, COMMITTING, RESULT, CONSUMED }

/**
 * Pure delivery ownership for the intake state machine: one active delivery at a time, one
 * consumption per delivery, and a fresh identity for every genuine delivery (a deliberate
 * re-share of the same file imports again; there is no content-hash suppression).
 */
class CsvImportIntakeOwnership {
    private val consumedIds = ArrayDeque<String>()

    var phase: CsvImportIntakePhase = CsvImportIntakePhase.IDLE
        private set

    var activeDeliveryId: String? = null
        private set

    /**
     * Takes ownership of [deliveryId]. Returns false when another delivery is in flight
     * (busy; the caller must not touch in-flight state) or when this delivery was already
     * consumed (a replay after commit, resume or recreation).
     */
    fun begin(deliveryId: String): Boolean {
        if (isConsumed(deliveryId)) return false
        if (activeDeliveryId != null && activeDeliveryId != deliveryId) return false
        activeDeliveryId = deliveryId
        if (phase == CsvImportIntakePhase.IDLE || phase == CsvImportIntakePhase.CONSUMED) {
            phase = CsvImportIntakePhase.READING
        }
        return true
    }

    /** Records a phase transition of the active delivery. */
    fun moveTo(next: CsvImportIntakePhase) {
        if (activeDeliveryId != null) phase = next
    }

    /** Consumes the active (or named) delivery: at most once per delivery id. */
    fun markConsumed(deliveryId: String? = null) {
        val id = deliveryId ?: activeDeliveryId ?: return
        if (!isConsumed(id)) {
            consumedIds.addLast(id)
            while (consumedIds.size > CONSUMED_MEMORY) consumedIds.removeFirst()
        }
        if (activeDeliveryId == id) {
            activeDeliveryId = null
            phase = CsvImportIntakePhase.CONSUMED
        }
    }

    fun isConsumed(deliveryId: String): Boolean = consumedIds.contains(deliveryId)

    fun isBusy(): Boolean = activeDeliveryId != null

    private companion object {
        /** Bound the consumed-delivery memory so replay suppression stays cheap. */
        const val CONSUMED_MEMORY = 64
    }
}
