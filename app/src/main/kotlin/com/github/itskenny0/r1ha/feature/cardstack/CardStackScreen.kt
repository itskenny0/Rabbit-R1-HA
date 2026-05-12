package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectStateAsLife
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.ui.components.EntityCard

@Composable
fun CardStackScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: CardStackViewModel = viewModel(
        factory = CardStackViewModel.factory(
            haRepository = haRepository,
            settings = settings,
            wheelInput = wheelInput,
        )
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectStateAsLife(initialValue = AppSettings())

    // Wheel events are processed ONLY while CardStackScreen is composed. Navigating away
    // (e.g. into FavoritesPicker or Settings) suspends the collection so spinning the wheel
    // there can't silently move the active card's brightness behind the user's back.
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event -> vm.onWheel(event) }
    }

    val haptic = LocalHapticFeedback.current
    // Honour the user's "Haptic feedback" toggle: only fire when enabled.
    LaunchedEffect(state.activeState?.percent) {
        if (state.activeState != null && appSettings.behavior.haptics) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Honour the user's "Keep screen on" toggle by toggling the View flag while CardStack
    // is composed. DisposableEffect ensures we clear the flag when the screen leaves
    // composition so the system can sleep when the user is elsewhere.
    val view = LocalView.current
    DisposableEffect(appSettings.behavior.keepScreenOn) {
        view.keepScreenOn = appSettings.behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Card content. Gestures are applied ONLY when there is an active card; the
        // empty state is a plain Composable so its Button receives taps without our
        // gesture handler in the middle.
        val activeState = state.activeState
        if (activeState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .cardStackGestures(
                        onTap = { if (appSettings.behavior.tapToToggle) vm.tapToggle() },
                        onSwipeUp = { vm.next() },
                        onSwipeDown = { vm.previous() },
                        onSwipeLeft = { onOpenSettings() },
                        onSwipeRight = { onOpenFavoritesPicker() },
                    )
            ) {
                EntityCard(
                    state = activeState,
                    onTapToggle = { vm.tapToggle() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Two empty-state variants:
            //  - User has set favourites but Home Assistant hasn't echoed states yet:
            //    say so explicitly so they don't think the data was lost.
            //  - User has no favourites yet: prompt to add some.
            val loading = state.favouritesCount > 0
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .swipeOnlyGestures(
                        onSwipeLeft = { onOpenSettings() },
                        onSwipeRight = { onOpenFavoritesPicker() },
                    )
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    text = if (loading) "Loading entities…" else "No favourites yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (loading) "Connecting to ${state.favouritesCount} favourite${if (state.favouritesCount == 1) "" else "s"}…"
                    else "Tap below to add some.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onOpenFavoritesPicker) {
                    Text(if (loading) "Edit favourites" else "Add favourites")
                }
            }
        }

        // Top chrome — sits below the status bar so the tap targets aren't
        // clipped under transparent system bars.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Top-left: favourites hamburger
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onOpenFavoritesPicker() }
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Favourites",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Position dots (centre) — honour the user's showPositionDots toggle
            if (state.cards.size > 1 && appSettings.ui.showPositionDots) {
                PositionDots(
                    count = state.cards.size,
                    current = state.currentIndex,
                )
            } else {
                Spacer(Modifier.size(44.dp))
            }

            // Top-right: settings gear
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onOpenSettings() }
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PositionDots(count: Int, current: Int) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val isActive = index == current
            Box(
                modifier = Modifier
                    .size(if (isActive) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .dotBackground(if (isActive) onBg else onBg.copy(alpha = 0.35f))
            )
        }
    }
}

/**
 * Gesture modifier that disambiguates vertical swipe, horizontal swipe, and tap in a single
 * pointer input block. Uses awaitEachGesture + manual axis tracking to correctly handle
 * all three gestures without one blocking the others.
 *
 * Mental trace:
 *  - swipe up → classified='v', dy negative, dy > threshold → onSwipeUp() called once then exits
 *  - swipe right → classified='h', dx positive, dx > threshold → onSwipeRight() called once
 *  - tap → no slop exceeded at pointer-up → onTap() called
 */
private fun Modifier.cardStackGestures(
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = pointerInput(Unit) {
    val slopPx = 24.dp.toPx()
    val thresholdPx = 64.dp.toPx()

    awaitEachGesture {
        // Receive the down event in the Initial pass so child clickables can't pre-empt
        // us. requireUnconsumed = false because some children (Button, IconButton) consume
        // the down in their own handlers; we still want the event so we can disambiguate
        // tap-vs-drag.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        var classified: Char? = null  // 'v' = vertical, 'h' = horizontal, null = undecided

        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (change.changedToUp()) {
                // Pointer lifted — decide action
                if (classified == null) {
                    // No slop exceeded: treat as tap
                    if (kotlin.math.abs(dx) < slopPx && kotlin.math.abs(dy) < slopPx) {
                        onTap()
                    }
                } else {
                    when (classified) {
                        'v' -> if (kotlin.math.abs(dy) > thresholdPx) {
                            if (dy < 0) onSwipeUp() else onSwipeDown()
                        }
                        'h' -> if (kotlin.math.abs(dx) > thresholdPx) {
                            if (dx < 0) onSwipeLeft() else onSwipeRight()
                        }
                    }
                }
                break
            }

            val dragAmount = change.positionChange()
            dx += dragAmount.x
            dy += dragAmount.y

            if (classified == null) {
                if (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx) {
                    classified =
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 'h' else 'v'
                }
            }

            change.consume()
        }
    }
}

/** Disambiguated background helper to avoid import clash with foundation.background. */
private fun Modifier.dotBackground(color: Color): Modifier =
    this.background(color = color, shape = CircleShape)

/**
 * Gesture handler for the empty CardStack state: only fires on horizontal swipes (so the
 * Button inside still receives plain taps normally). Vertical swipes are ignored since
 * there's no card stack to page through.
 */
private fun Modifier.swipeOnlyGestures(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = pointerInput(Unit) {
    val slopPx = 24.dp.toPx()
    val thresholdPx = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        var classifiedHorizontal = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUp()) {
                if (classifiedHorizontal && kotlin.math.abs(dx) > thresholdPx) {
                    if (dx < 0) onSwipeLeft() else onSwipeRight()
                }
                break
            }
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            if (!classifiedHorizontal && kotlin.math.abs(dx) > slopPx
                && kotlin.math.abs(dx) > kotlin.math.abs(dy)
            ) {
                classifiedHorizontal = true
            }
            if (classifiedHorizontal) change.consume()
        }
    }
}
