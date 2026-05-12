package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.EntityCard
import com.github.itskenny0.r1ha.ui.components.HamburgerGlyph
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.SettingsCogGlyph
import com.github.itskenny0.r1ha.ui.components.r1Pressable
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
    //
    // For read-only cards (sensors) the wheel doesn't drive any value, so we promote it
    // to card-stack navigation instead — wheel up = previous card, wheel down = next.
    // The pager state lives inside VerticalCardPager so we publish a navigation
    // request through this MutableSharedFlow which the pager observes.
    val pagerNavRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<com.github.itskenny0.r1ha.core.input.WheelEvent.Direction>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event ->
            val active = vm.state.value.activeState
            // Sensors / binary_sensors → navigate. Action entities (scene/script/button)
            // keep their normal "wheel does nothing" so the script-overscroll feature can
            // own that gesture surface.
            if (active != null && active.id.domain.isSensor) {
                pagerNavRequests.tryEmit(event.direction)
            } else {
                vm.onWheel(event)
            }
        }
    }

    val view = LocalView.current
    // Honour the user's "Haptic feedback" toggle and throttle to ~20 Hz so a fast wheel spin
    // doesn't fire a continuous unpleasant buzz from the haptic motor. Keying on both id and
    // percent so swiping to a new card with the same percent still fires a tactile click.
    // We bypass Compose's HapticFeedback (which is gated to a small set of feedback types on
    // some devices) and go straight to View.performHapticFeedback with CLOCK_TICK — the same
    // constant the system uses for picker wheels and reliably produces a tick on the R1.
    val lastHapticMs = remember { longArrayOf(0L) }
    // Coalesce the haptic key into a single "perceived value" so a switch entity doesn't
    // tick twice per toggle (once on optimistic, then again when the cache catches up and
    // the optimistic clears — for switch entities the cached percent is always null, so
    // applying the override and then clearing it flips percent null→100→null and the
    // earlier key (percent only) double-fired). For scalar entities the value is the
    // percent itself; for switches it's 0 or 1 keyed on isOn.
    val hapticKey = state.activeState?.let { active ->
        if (active.supportsScalar) active.percent else if (active.isOn) 1 else 0
    }
    LaunchedEffect(state.activeState?.id, hapticKey) {
        if (state.activeState == null || !appSettings.behavior.haptics) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastHapticMs[0] < 50L) return@LaunchedEffect
        lastHapticMs[0] = now
        @Suppress("DEPRECATION")  // CLOCK_TICK is available on all our target SDKs (33+)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    DisposableEffect(appSettings.behavior.keepScreenOn) {
        view.keepScreenOn = appSettings.behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Customize-dialog entry from the card stack. `customizingId` is the entity_id under
    // edit; null means the dialog is closed. We hold it locally because the dialog is a
    // transient UI overlay — no need to thread it through the VM state.
    val customizingId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalHaRepository provides haRepository,
        com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides appSettings.entityOverrides,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        // displayedCards = cards with optimistic overrides applied per entity. Binding the
        // UI to this list (rather than to the raw HA-confirmed cards) is what makes the
        // brightness/value track the wheel *instantly* instead of waiting for the HA
        // round-trip to echo a state_changed event back.
        val cards = state.displayedCards
        if (cards.isNotEmpty()) {
            VerticalCardPager(
                cards = cards,
                vm = vm,
                appSettings = appSettings,
                navRequests = pagerNavRequests,
            )
        } else {
            EmptyState(
                loading = state.favouritesCount > 0,
                favouritesCount = state.favouritesCount,
                connection = connection,
                onOpenFavoritesPicker = onOpenFavoritesPicker,
                onOpenSettings = onOpenSettings,
                onRetry = { haRepository.reconnectNow() },
            )
        }

        ChromeRow(
            connection = connection,
            cardsCount = cards.size,
            currentIndex = state.currentIndex,
            showCounter = cards.size > 1,
            onOpenFavoritesPicker = onOpenFavoritesPicker,
            onOpenSettings = onOpenSettings,
            onEditActive = {
                // Only allow editing when there's an active card to edit — empty deck
                // is a no-op.
                state.activeState?.let { customizingId.value = it.id.value }
            },
            solidBackdrop = appSettings.ui.hideCardTailAbove,
        )

        // ── Customize dialog ────────────────────────────────────────────────────────
        // Reuses the favourites-picker's RenameDialog so the customize flow is identical
        // from both entry points. Renders OVER the chrome since it's part of the screen-
        // level Box stack here, not inside any pager content.
        val editingId = customizingId.value
        if (editingId != null) {
            val entity = state.displayedCards.firstOrNull { it.id.value == editingId }
                ?: state.cards.firstOrNull { it.id.value == editingId }
            if (entity != null) {
                val initialOverride = appSettings.entityOverrides[editingId]
                    ?: com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE
                val initialName = appSettings.nameOverrides[editingId] ?: entity.friendlyName
                com.github.itskenny0.r1ha.feature.favoritespicker.RenameDialog(
                    entity = entity,
                    initialName = initialName,
                    initialOverride = initialOverride,
                    onSave = { newName, newOverride ->
                        vm.saveCustomize(editingId, newName, newOverride)
                        customizingId.value = null
                    },
                    onCancel = { customizingId.value = null },
                )
            } else {
                // Stale id (entity removed from favourites while dialog was open) — drop it.
                customizingId.value = null
            }
        }
    }
    }
}

@Composable
private fun VerticalCardPager(
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    vm: CardStackViewModel,
    appSettings: AppSettings,
    navRequests: kotlinx.coroutines.flow.SharedFlow<com.github.itskenny0.r1ha.core.input.WheelEvent.Direction>,
) {
    val pagerState = rememberPagerState(
        initialPage = vm.state.value.currentIndex.coerceAtMost(cards.size - 1).coerceAtLeast(0),
        pageCount = { cards.size },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { vm.setCurrentIndex(it) }
    }
    // Wheel-as-navigation, fired from CardStackScreen when the active card is read-only.
    // animateScrollToPage so the transition is the same gentle spring the user gets when
    // swiping the pager by finger — no jarring snap.
    LaunchedEffect(pagerState, navRequests) {
        navRequests.collect { dir ->
            val current = pagerState.currentPage
            val target = (current + when (dir) {
                com.github.itskenny0.r1ha.core.input.WheelEvent.Direction.UP -> -1
                com.github.itskenny0.r1ha.core.input.WheelEvent.Direction.DOWN -> +1
            }).coerceIn(0, cards.lastIndex)
            if (target != current) pagerState.animateScrollToPage(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            // No peek — off-screen cards are hidden entirely until the user starts dragging.
            // During the drag, each page's graphicsLayer (below) gives the deck an overlap
            // with a big drop shadow.
            contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp),
            pageSize = PageSize.Fill,
            pageSpacing = 0.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            // Compute this page's offset from the currently-settled page. During a drag this
            // is fractional; the deeper magnitude is, the more we offset/dim the page so it
            // visually slides BEHIND the active card with a chunky shadow rather than just
            // scrolling past it.
            val pageOffset = (
                (pagerState.currentPage - page) +
                    pagerState.currentPageOffsetFraction
            )
            // ~85% viewport — pad the card inward so the bg shows around it. Combined with a
            // rounded corner shape and the shadow elevation, the card looks like a free-
            // floating panel rather than a full-screen surface.
            val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Look up the per-card long-press action so EntityCard only wires the
                // gesture when there's actually something to fire (otherwise the heavier
                // r1RowPressable would replace the cheaper r1Pressable for no gain).
                val longPressTarget = appSettings.entityOverrides[cards[page].id.value]?.longPressTarget
                EntityCard(
                    state = cards[page],
                    onTapToggle = { vm.tapToggle() },
                    tapToToggleEnabled = appSettings.behavior.tapToToggle,
                    onSetOn = { on -> vm.setSwitchOn(on) },
                    onLongPress = longPressTarget?.let { target -> { vm.fireLongPress(target) } },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val abs = kotlin.math.abs(pageOffset)
                            // The active page (offset ≈ 0) casts a strong shadow that fades
                            // to nothing as the page slides off screen.
                            shadowElevation = (24.dp.toPx() * (1f - abs).coerceIn(0f, 1f))
                            // Slight scale-down on the incoming card so the active one feels
                            // forward in the stack.
                            val scale = 1f - (abs * 0.04f).coerceIn(0f, 0.04f)
                            scaleX = scale
                            scaleY = scale
                            // Clip = true with a rounded shape applies the radius AND makes
                            // the shadow follow the contour.
                            shape = cardShape
                            clip = true
                        },
                )
            }
        }

        // ── Chevron hint ──────────────────────────────────────────────────────────────
        // Down hint at the bottom edge when there's a next card. The up hint was dropped
        // because it landed underneath the chrome's vertical position pip — redundant
        // information at best, visual collision at worst. The down hint stays useful
        // because the bottom of the card is otherwise empty.
        val currentPage = pagerState.currentPage
        androidx.compose.animation.AnimatedVisibility(
            visible = currentPage < cards.size - 1,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        ) {
            Chevron(direction = ChevronDirection.Down, size = 14.dp, tint = R1.InkMuted)
        }
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    favouritesCount: Int,
    connection: ConnectionState,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    // After STALLED_AFTER_MS of loading without any cards arriving, surface a "Stuck?"
    // affordance pointing to Settings. Without it, an unreachable HA leaves the user on a
    // pure spinner with no idea what to do; the reconnect-backoff in the repo can be 30s
    // between attempts and the user shouldn't be expected to wait that out blindly.
    val stalled = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(loading) {
        stalled.value = false
        if (loading) {
            kotlinx.coroutines.delay(STALLED_AFTER_MS)
            stalled.value = true
        }
    }

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
        R1Button(
            text = if (loading) "EDIT FAVOURITES" else "ADD FAVOURITES",
            onClick = onOpenFavoritesPicker,
        )
        // Stalled-loading affordance. Two paths once we know the spinner has lingered too
        // long: a one-tap "retry connection" (cancels the backoff, fires immediately) and a
        // fallback "open settings" for the case where the auth tokens themselves are the
        // problem and reconnecting won't help. The status colour follows the connection
        // state: amber while still optimistically retrying, red once we know auth or the
        // server actively refused us.
        if (loading && stalled.value) {
            val color = when (connection) {
                is ConnectionState.AuthLost -> R1.StatusRed
                is ConnectionState.Disconnected -> R1.StatusRed
                else -> R1.StatusAmber
            }
            Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .r1Pressable(onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "STILL LOADING — TAP TO RETRY",
                    style = R1.labelMicro,
                    color = color,
                )
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .r1Pressable(onOpenSettings)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "OPEN SETTINGS →",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}

private const val STALLED_AFTER_MS = 10_000L

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
    onEditActive: () -> Unit = {},
    solidBackdrop: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Solid backdrop hides the previous card's tail as it slides into the
            // 72 dp content-padding area above the active card. Transparent backdrop
            // keeps the deck-overlap aesthetic where the user can see the preceding
            // card peeking under the chrome.
            .then(if (solidBackdrop) Modifier.background(R1.Bg) else Modifier)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-left: favourites hamburger (custom 3-stroke glyph, not Material's filled icon).
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .r1Pressable(onOpenFavoritesPicker),
            contentAlignment = Alignment.Center,
        ) {
            HamburgerGlyph(size = 18.dp)
        }

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

        // Top-right cluster: edit pencil + settings gear + connection-state dot. Grouped
        // in a Row so the parent SpaceBetween keeps the pip centred between hamburger
        // and this cluster (rather than treating each element independently).
        Row(verticalAlignment = Alignment.CenterVertically) {

        // Edit pencil — opens the customize dialog for the active card. This was the
        // entry point users were missing from the card stack (previously only in the
        // favourites picker). 36 dp tap target rather than 44 so the gear next to it
        // doesn't get crowded out on the R1's narrow chrome.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .r1Pressable(onEditActive),
            contentAlignment = Alignment.Center,
        ) {
            com.github.itskenny0.r1ha.ui.components.EditGlyph(size = 14.dp, tint = R1.Ink.copy(alpha = 0.85f))
        }

        // Settings gear + connection-state dot. The gear is a Canvas-drawn wireframe
        // (see `SettingsCogGlyph`) so it matches the rest of the chrome's hairline-stroke
        // language instead of Material's filled gear.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .r1Pressable(onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            SettingsCogGlyph(size = 18.dp)
            // Connection dot: only visible when NOT connected (subtle when healthy, loud
            // when not). Animated colour transition so the amber→red flip on a failed
            // reconnect reads as deliberate rather than a UI bounce; AnimatedVisibility on
            // the dot itself so its appear/disappear doesn't snap when state crosses the
            // Connected boundary.
            val statusColor = when (connection) {
                is ConnectionState.Connected -> null
                ConnectionState.Idle,
                ConnectionState.Connecting,
                ConnectionState.Authenticating -> R1.StatusAmber
                else -> R1.StatusRed
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = statusColor != null,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            ) {
                // Lock in the *last non-null* colour so the dot doesn't flash a default
                // colour during the exit animation when state transitions back to Connected.
                val animatedColor by androidx.compose.animation.animateColorAsState(
                    targetValue = statusColor ?: R1.StatusAmber,
                    label = "conn-dot-color",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(animatedColor),
                )
            }
        }
        }  // end right-cluster Row
    }
}

/**
 * "Mission-control" vertical position pip: hairline track + accent-coloured thumb whose
 * position maps to the current page, with a small "N/M" counter on the right. Whole thing
 * sits inside a dark pill so it stays legible against the Colourful Cards gradient.
 */
@Composable
private fun VerticalPagePip(count: Int, current: Int) {
    val trackHeight = 22.dp
    val thumbHeight = 6.dp
    val frac = if (count <= 1) 0f else current.toFloat() / (count - 1).toFloat()
    Row(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.Bg.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vertical track + thumb.
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(8.dp),
        ) {
            // Track (dim hairline).
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(trackHeight)
                    .width(2.dp)
                    .background(R1.Hairline),
            )
            // Thumb — offset down by frac of available travel.
            val travel = trackHeight - thumbHeight
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = travel * frac)
                    .height(thumbHeight)
                    .width(4.dp)
                    .background(R1.AccentWarm),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${current + 1}/$count",
            style = R1.numeralS,
            color = R1.Ink,
        )
    }
}

