package com.devil.phoenixproject.data.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val NOTIFICATION_SUBSCRIPTION_TIMEOUT_MS = 2_000L

internal suspend fun awaitBleNotificationSubscription(
    scope: CoroutineScope,
    timeoutMs: Long = NOTIFICATION_SUBSCRIPTION_TIMEOUT_MS,
    startCollector: suspend (onSubscription: suspend () -> Unit) -> Unit,
    onStart: () -> Unit = {},
    onSubscribed: () -> Unit = {},
    onCollectorFailure: (Throwable, Boolean) -> Unit = { _, _ -> },
    onSubscriptionTimeout: (Long) -> Unit = {},
    onAwaitFailure: (Throwable) -> Unit = {},
): Boolean {
    val subscribed = CompletableDeferred<Unit>()
    val observationJob = scope.launch {
        try {
            onStart()
            startCollector {
                if (subscribed.complete(Unit)) {
                    onSubscribed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val subscriptionAlreadyResolved = subscribed.isCompleted
            if (!subscriptionAlreadyResolved) {
                subscribed.completeExceptionally(e)
            }
            onCollectorFailure(e, subscriptionAlreadyResolved)
        }
    }

    return try {
        withTimeout(timeoutMs) {
            subscribed.await()
        }
        true
    } catch (e: TimeoutCancellationException) {
        observationJob.cancel()
        if (!subscribed.isCompleted) {
            subscribed.completeExceptionally(e)
        }
        onSubscriptionTimeout(timeoutMs)
        false
    } catch (e: CancellationException) {
        observationJob.cancel()
        throw e
    } catch (e: Exception) {
        observationJob.cancel()
        onAwaitFailure(e)
        false
    }
}
