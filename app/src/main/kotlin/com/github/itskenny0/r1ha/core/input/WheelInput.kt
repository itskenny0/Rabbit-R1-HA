package com.github.itskenny0.r1ha.core.input

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.roundToInt

data class WheelEvent(val direction: Direction, val timestampMillis: Long) {
    enum class Direction { UP, DOWN }
}

/**
 * Stateless math + a small hot flow. The Activity's dispatchKeyEvent emits raw events; ViewModels
 * combine them with current settings to apply effective steps.
 */
class WheelInput {
    // A small buffer with DROP_OLDEST so an in-flight subscriber consuming a slow frame doesn't
    // lose events fired during that frame — but bursts that exceed the buffer drop the oldest
    // entries rather than the newest, keeping the most recent rotation direction "fresh".
    //
    // SharedFlow buffers are scoped to current subscribers: when CardStackScreen leaves
    // composition the subscriber cancels and any buffered events vanish with it. So this does
    // NOT re-introduce the "wheel events queue while off-screen and replay on return" bug
    // that buffer=0 fixed earlier — the no-subscriber case still silently drops.
    private val _events = MutableSharedFlow<WheelEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<WheelEvent> = _events.asSharedFlow()

    fun emit(direction: WheelEvent.Direction, now: Long = System.currentTimeMillis()) {
        _events.tryEmit(WheelEvent(direction, now))
    }

    companion object {
        /**
         * Compute effective step in percent.
         *  base: base step (1, 2, 5, or 10)
         *  ratePerSec: measured event rate in the last sliding window
         *  accelerate: whether to apply acceleration
         *  curve: shape of the acceleration response (SUBTLE / MEDIUM / AGGRESSIVE)
         *
         * Curves share the same "kicks in above 4 ev/s" threshold but differ in slope
         * and cap. SUBTLE peaks at ~3× base (precise dimming for cinema lights);
         * MEDIUM peaks at ~7× base (the previous behaviour, comfortable for most
         * brightness sweeps); AGGRESSIVE peaks at ~13× base so a fast spin clears
         * the 0..100 range in 2-3 detents. The threshold is fixed because the
         * "discrete tap vs continuous spin" boundary is a property of the hardware,
         * not user preference.
         */
        fun effectiveStep(
            base: Int,
            ratePerSec: Double,
            accelerate: Boolean,
            curve: com.github.itskenny0.r1ha.core.prefs.AccelerationCurve =
                com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.MEDIUM,
        ): Int {
            if (!accelerate) return base
            val excess = (ratePerSec - 4.0).coerceIn(0.0, 12.0)
            val slope = when (curve) {
                com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.SUBTLE -> 0.2
                com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.MEDIUM -> 0.5
                com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.AGGRESSIVE -> 1.0
            }
            val multiplier = 1.0 + (excess * slope)
            return (base * multiplier).roundToInt()
        }

        fun applyDirection(d: WheelEvent.Direction, invert: Boolean): Int =
            when (d) { WheelEvent.Direction.UP -> if (invert) -1 else +1; WheelEvent.Direction.DOWN -> if (invert) +1 else -1 }
    }
}
