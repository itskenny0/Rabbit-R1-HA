package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import kotlinx.coroutines.launch

/**
 * Drive [listState] with the physical scroll wheel: each detent scrolls by ~[stepDp] dp,
 * animated so the motion feels native instead of snapping. Each screen that wants wheel
 * scrolling calls this once at composition; collection automatically suspends when the
 * screen leaves composition, so wheel events fired from elsewhere don't accidentally
 * scroll a Settings page that isn't visible.
 */
@Composable
fun WheelScrollFor(
    wheelInput: WheelInput,
    listState: LazyListState,
    stepDp: Int = 56,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        wheelInput.events.collect { event ->
            val stepPx = with(density) { stepDp.dp.toPx() }
            val delta = if (event.direction == WheelEvent.Direction.UP) -stepPx else stepPx
            // Don't suspend the wheel-event collector while the scroll is animating —
            // launch into a coroutine so back-to-back detents queue up smoothly.
            scope.launch { listState.animateScrollBy(delta) }
        }
    }
}
