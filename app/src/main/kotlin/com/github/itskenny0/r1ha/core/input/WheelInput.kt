package com.github.itskenny0.r1ha.core.input

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
    private val _events = MutableSharedFlow<WheelEvent>(extraBufferCapacity = 32)
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
         */
        fun effectiveStep(base: Int, ratePerSec: Double, accelerate: Boolean): Int {
            if (!accelerate) return base
            val excess = (ratePerSec - 4.0).coerceIn(0.0, 12.0)
            val multiplier = 1.0 + (excess * 0.5)
            return (base * multiplier).roundToInt()
        }

        fun applyDirection(d: WheelEvent.Direction, invert: Boolean): Int =
            when (d) { WheelEvent.Direction.UP -> if (invert) -1 else +1; WheelEvent.Direction.DOWN -> if (invert) +1 else -1 }
    }
}
