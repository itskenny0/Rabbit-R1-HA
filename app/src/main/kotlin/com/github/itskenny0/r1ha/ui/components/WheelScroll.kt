package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drive [listState] with the physical scroll wheel: each detent scrolls by ~[stepDp] dp,
 * animated so the motion feels native instead of snapping. Each screen that wants wheel
 * scrolling calls this once at composition; collection automatically suspends when the
 * screen leaves composition, so wheel events fired from elsewhere don't accidentally
 * scroll a Settings page that isn't visible.
 *
 * When [settings] is provided and the user has wheel acceleration enabled, the per-event
 * scroll distance scales with how fast the user is spinning — same rate-window logic as
 * CardStackViewModel. A slow tick gives `stepDp`; a sustained spin gives up to ~7× that.
 * The acceleration kicks in around 5 events/sec which is the rate where a finger drag is
 * clearly continuous rather than discrete taps.
 */
@Composable
fun WheelScrollFor(
    wheelInput: WheelInput,
    listState: LazyListState,
    stepDp: Int = 56,
    settings: SettingsRepository? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Subscribe to the wheel-acceleration setting so toggling it in Settings takes effect
    // immediately in any currently-composed list. Null upstream → default to off (no
    // acceleration), which matches the previous fixed-step behaviour.
    val accelEnabled by (settings?.settings?.map { it.wheel.acceleration }
        ?: kotlinx.coroutines.flow.flowOf(false))
        .collectAsState(initial = AppSettings().wheel.acceleration)
    LaunchedEffect(listState, accelEnabled) {
        // Sliding-window rate of recent wheel events for the acceleration calculation.
        // Same 250 ms window CardStackViewModel uses so the feel matches between the
        // card stack and the list views.
        val timestamps = ArrayDeque<Long>()
        val windowMs = 250L
        wheelInput.events.collect { event ->
            val now = event.timestampMillis
            timestamps.addLast(now)
            while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
                timestamps.removeFirst()
            }
            val ratePerSec = timestamps.size * (1000.0 / windowMs)
            // Reuse the existing wheel-step accelerator with base step = stepDp; it
            // returns an Int but we pass stepDp.toDouble back through Dp math below.
            val effective = if (accelEnabled) {
                WheelInput.effectiveStep(stepDp, ratePerSec, accelerate = true)
            } else {
                stepDp
            }
            val stepPx = with(density) { effective.dp.toPx() }
            val delta = if (event.direction == WheelEvent.Direction.UP) -stepPx else stepPx
            // Don't suspend the wheel-event collector while the scroll is animating —
            // launch into a coroutine so back-to-back detents queue up smoothly.
            scope.launch { listState.animateScrollBy(delta) }
        }
    }
}
