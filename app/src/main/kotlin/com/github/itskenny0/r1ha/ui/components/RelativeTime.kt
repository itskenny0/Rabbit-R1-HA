package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.abs

/**
 * Format an [Instant] as a short human-readable relative-time string like
 * 'just now', '5m ago', '12h ago', '3d ago'. Drives the small freshness
 * label on cards that surfaces state age without forcing the user to read
 * a full timestamp.
 *
 * Rules:
 *  * < 30 s → 'just now'
 *  * < 60 s → '<seconds>s ago'
 *  * < 60 min → '<minutes>m ago'
 *  * < 24 h → '<hours>h ago'
 *  * < 7 d → '<days>d ago'
 *  * older → '<weeks>w ago'
 *
 * Future timestamps (rare but possible if the device clock drifts vs HA's)
 * fall back to 'just now' rather than 'in 5m' — a future-time label on a
 * card would read as a bug.
 */
internal fun formatRelativeTime(at: Instant, now: Instant): String {
    val deltaMs = now.toEpochMilli() - at.toEpochMilli()
    if (deltaMs < 0) return "just now"
    val deltaSec = deltaMs / 1000
    return when {
        deltaSec < 30 -> "just now"
        deltaSec < 60 -> "${deltaSec}s ago"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        deltaSec < 7 * 86_400 -> "${deltaSec / 86_400}d ago"
        else -> "${deltaSec / (7 * 86_400)}w ago"
    }
}

/**
 * Live-ticking relative-time label backed by [produceState]. Subscribers
 * automatically recompose when the rendered string would change; between
 * boundary crossings the state stays put. Tick cadence scales with the
 * elapsed time so we don't burn frames recomposing 'just now' every
 * second indefinitely:
 *
 *  * < 60 s old → tick every 5 s
 *  * < 60 min old → tick every 30 s
 *  * < 24 h old → tick every 10 min
 *  * older → tick hourly
 *
 * Returns "" when [at] is null so callers can render unconditionally with
 * `Text(text = rememberRelativeTime(at))` and have the label silently
 * disappear for entities that haven't been observed yet.
 */
@Composable
fun rememberRelativeTime(at: Instant?): String {
    if (at == null) return ""
    val text by produceState(initialValue = formatRelativeTime(at, Instant.now()), at) {
        while (true) {
            val now = Instant.now()
            value = formatRelativeTime(at, now)
            val ageSec = abs(now.toEpochMilli() - at.toEpochMilli()) / 1000
            val nextTickMs = when {
                ageSec < 60 -> 5_000L
                ageSec < 3600 -> 30_000L
                ageSec < 86_400 -> 600_000L
                else -> 3_600_000L
            }
            delay(nextTickMs)
        }
    }
    return text
}
