package com.github.itskenny0.r1ha.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped ring buffer of the last [CAPACITY] R1Log entries. Backs the dev-menu
 * log viewer so users without adb can still see what the app is logging. Lossy by
 * design (newer entries evict older ones) so memory stays bounded regardless of how
 * chatty a session is.
 *
 * The viewer reads the buffer via [snapshot]; it can also subscribe to [updates] for
 * live tailing if it wants to refresh as new entries land.
 */
internal object R1LogBuffer {
    enum class Level { D, I, W, E }
    data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val timestampMillis: Long = System.currentTimeMillis(),
    )

    private const val CAPACITY = 500
    private val ring = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()
    private val _updates = MutableStateFlow(0L)

    /** Incremented every time an entry is appended so the viewer can recompose
     *  without polling. The value itself isn't meaningful — just a token. */
    val updates: StateFlow<Long> = _updates.asStateFlow()

    fun append(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(Entry(level, tag, message, throwable))
        }
        _updates.value = _updates.value + 1L
    }

    /** Current contents, oldest → newest. Returns a defensive copy so callers can
     *  iterate without holding the lock. */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _updates.value = _updates.value + 1L
    }
}
