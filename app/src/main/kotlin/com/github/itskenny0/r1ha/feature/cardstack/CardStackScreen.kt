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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
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
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event -> vm.onWheel(event) }
    }

    val view = LocalView.current
    // Honour the user's "Haptic feedback" toggle and throttle to ~20 Hz so a fast wheel spin
    // doesn't fire a continuous unpleasant buzz from the haptic motor. Keying on both id and
    // percent so swiping to a new card with the same percent still fires a tactile click.
    // We bypass Compose's HapticFeedback (which is gated to a small set of feedback types on
    // some devices) and go straight to View.performHapticFeedback with CLOCK_TICK — the same
    // constant the system uses for picker wheels and reliably produces a tick on the R1.
    val lastHapticMs = remember { longArrayOf(0L) }
    LaunchedEffect(state.activeState?.id, state.activeState?.percent) {
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
                onOpenSettings = onOpenSettings,
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
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { vm.setCurrentIndex(it) }
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
                EntityCard(
                    state = cards[page],
                    onTapToggle = { if (appSettings.behavior.tapToToggle) vm.tapToggle() },
                    onSetOn = { on -> vm.setSwitchOn(on) },
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

        // ── Chevron hints ─────────────────────────────────────────────────────────────
        // A small ↑ just under the chrome row when there's a previous card, and a small ↓
        // just above the bottom edge when there's a next card. They live ABOVE the pager
        // visually but don't intercept its gestures (no clickable on either). Drawn with
        // the same Chevron primitive as the back-button so the whole screen language stays
        // consistent — no Material `KeyboardArrowUp/Down` glyphs.
        val currentPage = pagerState.currentPage
        if (currentPage > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
            ) {
                Chevron(direction = ChevronDirection.Up, size = 14.dp, tint = R1.InkMuted)
            }
        }
        if (currentPage < cards.size - 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                Chevron(direction = ChevronDirection.Down, size = 14.dp, tint = R1.InkMuted)
            }
        }
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    favouritesCount: Int,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
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
        // Stalled-loading affordance.
        if (loading && stalled.value) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .r1Pressable(onOpenSettings)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "STILL LOADING — TAP TO OPEN SETTINGS",
                    style = R1.labelMicro,
                    color = R1.StatusAmber,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

        // Top-right: settings gear + connection-state dot. The gear is a Canvas-drawn
        // wireframe (see `SettingsCogGlyph`) so it matches the rest of the chrome's
        // hairline-stroke language instead of Material's filled gear.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .r1Pressable(onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            SettingsCogGlyph(size = 18.dp)
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

// Suppress unused warning on Color import (some themes use it via R1.Bg etc.).
@Suppress("unused")
private val unusedColorRef: Color = R1.Bg
