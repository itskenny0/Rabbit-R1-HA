package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Re-runs [block] on entry and then every [everyMillis] for as long
 * as the composable is part of the composition. Cancels cleanly on
 * exit via the normal LaunchedEffect lifecycle.
 *
 * Replaces the per-screen `LaunchedEffect(Unit) { while(true) { ... ;
 * delay(N) } }` pattern that landed across the Dashboard,
 * Notifications, Logbook, Weather, Persons, and Calendars screens.
 * Same semantics, one call site to tune.
 */
@Composable
fun AutoRefresh(everyMillis: Long, block: () -> Unit) {
    LaunchedEffect(everyMillis) {
        while (true) {
            block()
            delay(everyMillis)
        }
    }
}
