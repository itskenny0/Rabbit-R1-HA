package com.github.itskenny0.r1ha.core.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-scoped in-app toast bus — replaces the OS Toast that R1's tiny 240×320
 * display truncated mid-sentence for any non-trivial message. Each pushed event
 * carries the full text; the rendering layer (see `ToastHost` in feature/cardstack
 * or wherever it's mounted) shows a short version by default and expands to the
 * full text on tap.
 *
 * Two intentional design choices:
 *
 *  1. **Bus, not single-shot.** R1Log writes to this bus on every call. The user
 *     sets a [minLevel] in Settings → Behaviour → Toast log level (default OFF) to
 *     filter what actually reaches the toast UI. Setting it to WARN turns the toast
 *     into a live diagnostic feed without flooding the screen with INFO chatter.
 *  2. **No app-context required.** Unlike the OS Toast, callers don't have to hold
 *     an Application instance. Push from anywhere — repositories, value-class
 *     init blocks, deeply nested coroutines — and the host-side Compose subscriber
 *     picks it up.
 */
internal object R1Toast {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * One past toast event. [shortText] is what the host renders inline (truncated
     * if long); [fullText] is what it expands to on tap. They're separate fields so
     * the host can show e.g. `HaRepo.call · light_panels: 401` while keeping the
     * full HA error message available for diagnostic copy/paste.
     */
    data class Event(
        val level: Level,
        val tag: String,
        val shortText: String,
        val fullText: String,
        val timestampMillis: Long = System.currentTimeMillis(),
    )

    /** Visibility threshold. Events at or above this level reach subscribers; the
     *  rest are dropped at the push site. */
    @Volatile var minLevel: Level = Level.WARN

    /**
     * On / off master switch — when false, nothing reaches the toast UI regardless
     * of [minLevel]. Set to OFF by default so a fresh install doesn't fire toasts
     * unexpectedly; users opt in via Settings to use this as a diagnostic.
     */
    @Volatile var enabled: Boolean = false

    private val _bus = MutableSharedFlow<Event>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val bus: SharedFlow<Event> = _bus.asSharedFlow()

    /**
     * Emit an event. The caller doesn't have to coalesce or rate-limit — the
     * SharedFlow's DROP_OLDEST overflow keeps memory bounded if the host can't
     * consume as fast as the producer.
     */
    fun push(level: Level, tag: String, shortText: String, fullText: String = shortText) {
        if (!enabled) return
        if (level.ordinal < minLevel.ordinal) return
        _bus.tryEmit(Event(level, tag, shortText, fullText))
    }

    /**
     * Force-emit a user-facing event regardless of [enabled] / [minLevel]. Used by
     * [Toaster.show] so direct user feedback (service-call failures, save errors)
     * is always surfaced even when the user hasn't opted into the diagnostic toast
     * feed. Distinct from [push] because diagnostic chatter must stay gated by
     * the user's settings — only intentional user-facing messages bypass.
     */
    fun userPush(level: Level, tag: String, shortText: String, fullText: String = shortText) {
        _bus.tryEmit(Event(level, tag, shortText, fullText))
    }
}
