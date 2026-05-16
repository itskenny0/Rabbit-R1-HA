package com.github.itskenny0.r1ha.core.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-way channel from [com.github.itskenny0.r1ha.MainActivity]'s
 * intent layer to the Compose nav graph. App-shortcut taps (and any
 * future deep-link source) emit the requested route here; the nav
 * graph collects and pushes it onto the back stack.
 *
 * Process-scoped singleton because the route hand-off has to survive
 * across:
 *   - Cold start (MainActivity.onCreate → setContent → AppNavGraph
 *     first compose tick) and
 *   - Warm start (MainActivity.onNewIntent → AppNavGraph already in
 *     composition).
 *
 * A `SharedFlow` with `extraBufferCapacity = 1` + DROP_OLDEST is
 * exactly the right shape: if the nav graph hasn't subscribed yet
 * (cold start before first compose), the single buffered emission
 * sits waiting; if the user mashes a shortcut twice quickly, the
 * second tap replaces the first rather than queuing both.
 *
 * We do NOT use a replay buffer because the nav graph's
 * LaunchedEffect re-collects across recomposition; a replay buffer
 * would cause the same route to be pushed onto the back stack on
 * every recomposition, which is exactly the bug we want to avoid.
 */
object ShortcutBus {
    private val _requests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    /** Push a route name onto the bus. Called from
     *  [com.github.itskenny0.r1ha.MainActivity] on app-shortcut
     *  intent delivery. */
    fun request(route: String) {
        _requests.tryEmit(route)
    }
}
