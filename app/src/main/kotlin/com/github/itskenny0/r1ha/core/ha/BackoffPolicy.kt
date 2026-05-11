package com.github.itskenny0.r1ha.core.ha

import kotlin.math.min
import kotlin.random.Random

/** Exponential-backoff schedule with ±jitter. Pure function for easy testing. */
data class BackoffPolicy(
    val baseMillis: Long = 1_000,
    val capMillis: Long = 30_000,
    val jitter: Double = 0.25,
    val rng: Random = Random.Default,
) {
    fun delayForAttempt(attempt: Int): Long {
        val raw = baseMillis shl attempt.coerceIn(0, 20)
        val capped = min(raw, capMillis)
        if (jitter == 0.0) return capped
        val window = (capped * jitter).toLong()
        val delta = if (window == 0L) 0L else rng.nextLong(-window, window + 1)
        return (capped + delta).coerceAtLeast(0)
    }
}
