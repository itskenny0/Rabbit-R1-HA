package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-key trailing debouncer: latest value per key wins after `debounceMillis` of silence. */
class DebouncedCaller<K, V>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val action: suspend (K, V) -> Unit,
) {
    private data class Pending<V>(val value: V, val job: Job)
    private val pending = mutableMapOf<Any?, Pending<V>>()
    private val mutex = Mutex()

    suspend fun submit(key: K, value: V) {
        mutex.withLock {
            pending[key]?.job?.cancel()
            val job = scope.launch {
                delay(debounceMillis)
                mutex.withLock { pending.remove(key) }
                action(key, value)
            }
            pending[key] = Pending(value, job)
        }
    }
}
