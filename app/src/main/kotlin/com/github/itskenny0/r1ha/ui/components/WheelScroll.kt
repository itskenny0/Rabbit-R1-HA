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
        // PERF: rapid wheel spins were launching one `animateScrollBy`
        // coroutine per detent — those queued behind the LazyListState's
        // scroll mutex and each ~300ms animation played in serial. A
        // 5-event spin took ~1.5 s to settle, and the screen visibly
        // chugged on the way. Now we keep a reference to the latest
        // animation job and cancel it before launching the next; the
        // cancelled scroll snaps to its current position, then the new
        // one starts from there toward the latest cumulative target.
        // Result: a fast spin animates ONCE to the final target instead
        // of N times to N intermediate targets.
        var pendingJob: kotlinx.coroutines.Job? = null
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
            // Cancel any in-flight animation. The next launch will scroll
            // from wherever the old one settled, which combined with the
            // event rate gives the appearance of a continuous smooth
            // motion. The cancellation is cooperative — the scroll mutex
            // releases promptly and the new launch picks up immediately.
            pendingJob?.cancel()
            pendingJob = scope.launch { listState.animateScrollBy(delta) }
        }
    }
}

/**
 * Variant of [WheelScrollFor] that drives a [androidx.compose.foundation.ScrollState]
 * — the type returned by `rememberScrollState()` and consumed by `verticalScroll()`.
 *
 * Used by the Dashboard which composes a tall Column with `verticalScroll(...)`
 * rather than a LazyColumn — the same wheel-acceleration + cancellation behaviour
 * as the LazyListState overload, just routed through a different ScrollableState.
 */
@Composable
fun WheelScrollForScrollState(
    wheelInput: WheelInput,
    scrollState: androidx.compose.foundation.ScrollState,
    stepDp: Int = 56,
    settings: SettingsRepository? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val accelEnabled by (settings?.settings?.map { it.wheel.acceleration }
        ?: kotlinx.coroutines.flow.flowOf(false))
        .collectAsState(initial = AppSettings().wheel.acceleration)
    LaunchedEffect(scrollState, accelEnabled) {
        val timestamps = ArrayDeque<Long>()
        val windowMs = 250L
        var pendingJob: kotlinx.coroutines.Job? = null
        wheelInput.events.collect { event ->
            val now = event.timestampMillis
            timestamps.addLast(now)
            while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
                timestamps.removeFirst()
            }
            val ratePerSec = timestamps.size * (1000.0 / windowMs)
            val effective = if (accelEnabled) {
                WheelInput.effectiveStep(stepDp, ratePerSec, accelerate = true)
            } else {
                stepDp
            }
            val stepPx = with(density) { effective.dp.toPx() }
            val delta = if (event.direction == WheelEvent.Direction.UP) -stepPx else stepPx
            pendingJob?.cancel()
            pendingJob = scope.launch { scrollState.animateScrollBy(delta) }
        }
    }
}
