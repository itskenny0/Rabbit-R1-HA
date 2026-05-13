package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key debouncer with an optional max-interval force-fire so an in-progress
 * gesture (continuous wheel spin / touch drag) doesn't starve out the service call
 * indefinitely. Two windows:
 *
 *  - [debounceMillis] — trailing-edge delay. Latest value per key wins after this
 *    much silence. Use this to coalesce a quick flurry of identical events into one
 *    HA call.
 *  - [maxIntervalMillis] — if the user keeps submitting events without ever
 *    reaching the [debounceMillis] quiet window, force-fire the latest pending
 *    value after this much time since the first event in the burst, and then reset
 *    the cycle. This is how we get 'state syncs while the gesture is in flight'
 *    rather than only on release.
 *
 * The optimistic-UI override + the action callback is the contract: the VM
 * applies an immediate optimistic state update; the action fires the wire call;
 * the repo eventually echoes back the confirmed state; the optimistic clears in
 * the reconcile path (see CardStackViewModel.observeFavorites). Client always
 * wins visually until HA confirms the actual value back.
 */
class DebouncedCaller<K, V>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val maxIntervalMillis: Long = Long.MAX_VALUE,
    private val action: suspend (K, V) -> Unit,
) {
    private data class Pending<V>(val value: V, val job: Job, val firstSubmittedAt: Long)
    private val pending = mutableMapOf<Any?, Pending<V>>()
    private val mutex = Mutex()

    suspend fun submit(key: K, value: V) {
        val now = System.currentTimeMillis()
        var forceFireValue: V? = null
        var forceFire = false
        mutex.withLock {
            val existing = pending[key]
            if (existing != null && now - existing.firstSubmittedAt >= maxIntervalMillis) {
                // Force-fire window crossed: cancel the trailing delay, schedule the
                // immediate action, and clear so the next submit starts a fresh
                // burst with its own firstSubmittedAt.
                existing.job.cancel()
                pending.remove(key)
                forceFireValue = value
                forceFire = true
            } else {
                existing?.job?.cancel()
                val firstSubmittedAt = existing?.firstSubmittedAt ?: now
                val job = scope.launch {
                    delay(debounceMillis)
                    mutex.withLock { pending.remove(key) }
                    action(key, value)
                }
                pending[key] = Pending(value, job, firstSubmittedAt)
            }
        }
        if (forceFire) {
            // Fire outside the lock so action() can't deadlock against another submit
            // that lands on the same key during dispatch.
            @Suppress("UNCHECKED_CAST")
            action(key, forceFireValue as V)
        }
    }
}
