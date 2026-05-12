package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.EntityCard
import kotlinx.coroutines.flow.distinctUntilChanged

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
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val connection by haRepository.connection.collectAsStateWithLifecycle()

    // Wheel events are processed ONLY while CardStackScreen is composed. Navigating away
    // (e.g. into FavoritesPicker or Settings) suspends the collection so spinning the wheel
    // there can't silently move the active card's brightness behind the user's back.
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event -> vm.onWheel(event) }
    }

    val haptic = LocalHapticFeedback.current
    // Honour the user's "Haptic feedback" toggle and throttle to ~20 Hz so a fast wheel spin
    // doesn't fire a continuous unpleasant buzz from the haptic motor. Keying on both id and
    // percent so swiping to a new card with the same percent still fires a tactile click.
    val lastHapticMs = remember { longArrayOf(0L) }
    LaunchedEffect(state.activeState?.id, state.activeState?.percent) {
        if (state.activeState == null || !appSettings.behavior.haptics) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastHapticMs[0] < 50L) return@LaunchedEffect
        lastHapticMs[0] = now
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val view = LocalView.current
    DisposableEffect(appSettings.behavior.keepScreenOn) {
        view.keepScreenOn = appSettings.behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        val cards = state.cards
        if (cards.isNotEmpty()) {
            VerticalCardPager(
                cards = cards,
                vm = vm,
                appSettings = appSettings,
            )
        } else {
            EmptyState(
                loading = state.favouritesCount > 0,
                favouritesCount = state.favouritesCount,
                onOpenFavoritesPicker = onOpenFavoritesPicker,
            )
        }

        ChromeRow(
            connection = connection,
            cardsCount = cards.size,
            currentIndex = state.currentIndex,
            showCounter = cards.size > 1,
            onOpenFavoritesPicker = onOpenFavoritesPicker,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun VerticalCardPager(
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    vm: CardStackViewModel,
    appSettings: AppSettings,
) {
    val pagerState = rememberPagerState(
        initialPage = vm.state.value.currentIndex.coerceAtMost(cards.size - 1).coerceAtLeast(0),
        pageCount = { cards.size },
    )
    // Sync pager → VM: when the pager settles on a new page, update the VM so wheel events
    // address the correct active card. snapshotFlow + distinctUntilChanged so we only fire
    // once per actual settled change (not on every transient drag).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { vm.setCurrentIndex(it) }
    }

    VerticalPager(
        state = pagerState,
        // Peek strip: 72dp top (under the chrome) and 56dp bottom shows a slice of the next
        // card. The deliberate asymmetry leaves the chrome row clear at the top and a
        // generous peek at the bottom — that's where the "next card" visually waits.
        contentPadding = PaddingValues(top = 72.dp, bottom = 56.dp),
        pageSize = PageSize.Fill,
        pageSpacing = 10.dp,
        // Hard 1.0 stiffness — the snap should feel mechanical, not bouncy. The slider's
        // bouncy spring lives at the value indicator level instead.
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        EntityCard(
            state = cards[page],
            onTapToggle = { if (appSettings.behavior.tapToToggle) vm.tapToggle() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    favouritesCount: Int,
    onOpenFavoritesPicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.height(20.dp))
        }
        Text(
            text = (if (loading) "Loading entities" else "No favourites yet").uppercase(),
            style = R1.sectionHeader,
            color = R1.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (loading) {
                "Connecting to $favouritesCount favourite${if (favouritesCount == 1) "" else "s"}…"
            } else {
                "Pick the lights, fans, covers, and media players you want\non the wheel."
            },
            style = R1.body,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenFavoritesPicker,
            shape = R1.ShapeM,
            colors = ButtonDefaults.buttonColors(
                containerColor = R1.AccentWarm,
                contentColor = R1.Bg,
            ),
        ) {
            Text(
                text = if (loading) "EDIT FAVOURITES" else "ADD FAVOURITES",
                style = R1.labelMicro,
            )
        }
    }
}

/**
 * Top chrome — hamburger left, vertical position pip + counter centre, settings gear right
 * with a small connection-state dot overlay. Sits *above* the pager so the peek strip
 * doesn't bleed visually into the icons.
 */
@Composable
private fun ChromeRow(
    connection: ConnectionState,
    cardsCount: Int,
    currentIndex: Int,
    showCounter: Boolean,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-left: favourites hamburger
        ChromeIconButton(
            icon = Icons.Default.Menu,
            description = "Favourites",
            onClick = onOpenFavoritesPicker,
        )

        // Centre: vertical position indicator. Hairline track + a 3dp filled segment at the
        // current page. Visually communicates "vertical stack" — wheel of cards going up
        // and down — rather than the horizontal row of dots that read as left/right.
        if (showCounter) {
            VerticalPagePip(
                count = cardsCount,
                current = currentIndex,
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }

        // Top-right: settings gear + connection-state dot.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = R1.Ink.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
            // Connection dot: only visible when NOT connected (subtle when healthy, screaming
            // when not).
            val statusColor = when (connection) {
                is ConnectionState.Connected -> null
                ConnectionState.Idle,
                ConnectionState.Connecting,
                ConnectionState.Authenticating -> R1.StatusAmber
                else -> R1.StatusRed
            }
            if (statusColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = R1.Ink.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * "Mission-control" vertical position pip: a 28dp-tall track with a 3dp filled bar whose
 * position maps to the current page. For more than ~8 cards it'd be hard to read individual
 * dots, so this works for any list size and reads instantly as "you are HERE in the stack".
 */
@Composable
private fun VerticalPagePip(count: Int, current: Int) {
    val trackHeight = 28.dp
    val trackWidth = 2.dp
    val thumbHeight = 8.dp
    val frac = if (count <= 1) 0f else current.toFloat() / (count - 1).toFloat()
    Box(
        modifier = Modifier
            .height(44.dp)
            .width(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(trackWidth)
                .background(R1.Hairline),
        )
        // Thumb — offset by frac of the available track height.
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val travel = trackHeight - thumbHeight
            Spacer(Modifier.height(travel * frac))
            Box(
                modifier = Modifier
                    .height(thumbHeight)
                    .width(4.dp)
                    .background(R1.AccentWarm),
            )
        }
        // Small counter — "3/5" — appears next to the pip but kept very small.
        Text(
            text = "${current + 1}/$count",
            style = R1.numeralS,
            color = R1.InkMuted,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 0.dp),
        )
    }
}

// Suppress unused warning on Color import (some themes use it via R1.Bg etc.).
@Suppress("unused")
private val unusedColorRef: Color = R1.Bg
