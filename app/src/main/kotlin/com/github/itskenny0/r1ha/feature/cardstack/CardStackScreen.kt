package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.foundation.horizontalScroll
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    // Signed delta — positive = forward (next card), negative = back. Carrying the
    // delta (rather than a Direction enum) lets the wheel handler scale it up on fast
    // spins: a sustained spin at 12 events/sec on a 30-card deck can move 3-4 cards
    // per detent so the user reaches the far end in a couple of seconds, while a
    // gentle tap-tap still moves exactly one card per event.
    val pagerNavRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<Int>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    // Jump-target index pushed from the jump-to-card sheet. Each PageDeck collects
    // this flow and animates its VerticalPager to the target index when the deck
    // belongs to the active page. Decoupling this from a directly-held PagerState
    // (the prior single-deck model) lets every page maintain its own pager state
    // while still being addressable from screen scope.
    val jumpRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<Int>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    // Sliding-window of wheel timestamps for the nav acceleration ramp. Mirrors the
    // VM's own deque (which accelerates scalar percent steps) but lives at the screen
    // layer because navigation is a screen concern, not a per-card one.
    val navTimestamps = remember { ArrayDeque<Long>() }
    // Transient hint shown on read-only / explicit-button-only cards when the user
    // spins the wheel — they previously expected nav, but the wheel no longer moves
    // between cards (swipe / pip-tap are the deck-nav affordances). The hint surfaces
    // inline on the card for ~2 s then fades, so the user learns the new gesture
    // vocabulary without a permanent piece of chrome. Declared here (rather than
    // inside the chrome-render block) so the LaunchedEffect that observes wheel events
    // can capture it.
    val wheelHintAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // Hoisted LazyListState for the jump-to-card overlay so the wheel handler can
    // animateScrollBy it while the overlay is open — without this hoist the wheel
    // would fall through to the active card's onWheel and adjust e.g. brightness
    // behind the overlay, which the user noticed and reported.
    val jumpListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Tab-management overlay state. Holds the page id being managed; the sentinel
    // [NEW_PAGE_SENTINEL] means "add a fresh page" rather than editing an existing
    // one. Null = overlay closed. Lifted to screen scope so the management modal
    // can render above the TabStrip + card stack.
    val tabManagementForId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    /** Quick-actions sheet visibility — opened by a long-press on the chrome
     *  hamburger. Holds 'all off on this page' as the only action today;
     *  designed to grow into a generic per-page quick-actions surface. */
    val quickActionsOpen = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Jump-to-card overlay visibility — tapped open from the chrome counter to let
    // the user pick a target card by name rather than scrolling through the deck.
    // Declared here (rather than at the chrome-render site) so the wheel-events
    // LaunchedEffect can read its value to gate scroll-routing.
    val jumpPickerOpen = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event ->
            // Defensive: never let a wheel event crash the collector — a single
            // bad event in the wheel-handler pipeline would tear down the
            // LaunchedEffect for the rest of the session and the user would
            // have to relaunch to recover. The runCatching wrap logs at ERROR
            // level so the dev-menu log viewer surfaces the cause; downstream
            // (the toast feed at ERROR is always on) flashes a red toast so the
            // user knows something went wrong without losing wheel input
            // entirely.
            runCatching {
            val active = vm.state.value.activeState
            val dir = event.direction
            // Wheel never navigates the deck — that's swipe-and-tap-the-pip only. So
            // on cards with nothing to drive (sensors, actions, non-scalar switches
            // when the toggle setting is off) the wheel becomes a no-op and we surface
            // a transient hint so the user learns the new vocabulary.
            val sign = com.github.itskenny0.r1ha.core.input.WheelInput.applyDirection(
                dir, appSettings.wheel.invertDirection,
            )
            // When the jump-to-card overlay is open the wheel should scroll the list
            // rather than reach past the modal and adjust the card underneath. One
            // detent ≈ one row of pixel height — same idea as a desktop scroll
            // wheel scrolling a focused list. Direction inversion is applied via
            // [sign] above so the user's wheel-direction preference still wins.
            if (jumpPickerOpen.value) {
                // 60 px per detent ≈ one row on the R1's default density; lets a
                // couple-second sustained spin scan a long favourites list end to
                // end. Sign convention: wheel-down ⇒ user wants to see further-
                // down items ⇒ animateScrollBy(positive pixels). [sign] is +1 for
                // UP and -1 for DOWN after invertDirection, so negating it yields
                // the right scroll direction for both wheel orientations.
                pagerScope.launch { jumpListState.animateScrollBy(-sign * 60f) }
                return@collect
            }
            val now = event.timestampMillis
            navTimestamps.addLast(now)
            while (navTimestamps.isNotEmpty() && now - navTimestamps.first() > 250L) {
                navTimestamps.removeFirst()
            }
            val ratePerSec = navTimestamps.size * (1000.0 / 250L)
            val navStep = com.github.itskenny0.r1ha.core.input.WheelInput.effectiveStep(
                base = 1,
                ratePerSec = ratePerSec,
                accelerate = appSettings.wheel.acceleration,
                curve = appSettings.wheel.accelerationCurve,
            ).coerceIn(1, 8)
            val navDelta = sign * navStep
            when {
                active == null -> Unit
                // Sensors / actions have nothing to drive — show the hint.
                active.id.domain.isSensor || active.id.domain.isAction ->
                    wheelHintAt.longValue = now
                // Non-scalar entities (locks, covers without position, vacuums, plain
                // switches) — if the user hasn't opted into wheel-toggles-switches via
                // Settings, the wheel is a no-op here too (and shows the hint). When
                // the setting IS on, fall through to the scalar path's setSwitch via
                // vm.onWheel for the actual toggle.
                !active.supportsScalar && !appSettings.behavior.wheelTogglesSwitches ->
                    wheelHintAt.longValue = now
                // Select entities — wheel cycles through the option list with the same
                // accelerated step so a fast spin can hop several options at once.
                active.id.domain.isSelect ->
                    vm.cycleSelectOption(active.id, navDelta)
                else -> vm.onWheel(event)
            }
            }.onFailure { t ->
                com.github.itskenny0.r1ha.core.util.R1Log.e(
                    "CardStack.wheel", "handler threw on event=$event", t,
                )
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
        // Defensive: View.performHapticFeedback can theoretically fail when
        // the view is detaching or the device's haptic motor is in a weird
        // state. Wrap in runCatching so a haptic miss doesn't tear down the
        // LaunchedEffect for the whole session.
        runCatching {
            if (state.activeState == null || !appSettings.behavior.haptics) return@runCatching
            val now = System.currentTimeMillis()
            if (now - lastHapticMs[0] < 50L) return@runCatching
            lastHapticMs[0] = now
            @Suppress("DEPRECATION")  // CLOCK_TICK is available on all our target SDKs (33+)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    DisposableEffect(appSettings.behavior.keepScreenOn) {
        view.keepScreenOn = appSettings.behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Auto-surface the last crash report if one exists on disk. Fires once
    // per CardStackScreen composition (i.e. once per launch). The expandable
    // error toast carries the full trace; tapping it expands so the user can
    // share with the developer. Deletes the file after surfacing so we don't
    // re-pop on every recomposition.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching {
            val crashFile = java.io.File(context.filesDir, "last_crash.txt")
            if (crashFile.exists() && crashFile.length() > 0L) {
                // Cap the read at 32 KB — a crash report is typically a few
                // KB; any more and we're holding it in memory unnecessarily.
                // Truncation suffix tells the user there's more available
                // via the dev menu's LAST CRASH button (which reads the
                // full file).
                val maxBytes = 32 * 1024L
                val raw = if (crashFile.length() <= maxBytes) {
                    crashFile.readText(Charsets.UTF_8)
                } else {
                    crashFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(maxBytes.toInt())
                        val n = reader.read(buf, 0, buf.size)
                        String(buf, 0, n.coerceAtLeast(0)) +
                            "\n\n[truncated — full report in dev menu LAST CRASH]"
                    }
                }
                com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                    shortText = "Crash detected — tap for trace",
                    fullText = raw,
                )
                // Don't delete — keep it accessible via the dev menu's
                // LAST CRASH button in case the user wants to revisit. Just
                // rename the file with a 'seen' suffix so we don't auto-pop
                // again on next launch.
                runCatching {
                    java.io.File(context.filesDir, "last_crash_seen.txt").writeText(raw)
                    crashFile.delete()
                }
            }
        }
    }

    // Customize-dialog entry from the card stack. `customizingId` is the entity_id under
    // edit; null means the dialog is closed. We hold it locally because the dialog is a
    // transient UI overlay — no need to thread it through the VM state.
    val customizingId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    // Hoisted state for the screen-level effect picker overlay. When non-null, an
    // overlay sheet renders above all card chrome listing the bulb's effects. Lifted
    // here (rather than inside each card) so the picker can use the full screen rather
    // than being clipped to the card body — a Nanoleaf can ship 30+ effects and a
    // card-bound picker would be cramped on the R1's 320 px tall display.
    val effectPickerFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    // Parallel state for the select-option picker overlay (Server Fan Mode = auto /
    // manual, etc.). Same screen-scope hoisting as the effect picker so it can use the
    // full display rather than being clipped to the card body.
    val selectPickerFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalHaRepository provides haRepository,
        com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl provides appSettings.server?.url,
        com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides appSettings.entityOverrides,
        com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightMode provides { id -> vm.cycleLightWheelMode(id) },
        com.github.itskenny0.r1ha.core.theme.LocalOnSetLightWheelMode provides { id, mode -> vm.setLightWheelMode(id, mode) },
        com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightEffect provides { id -> vm.cycleLightEffect(id) },
        com.github.itskenny0.r1ha.core.theme.LocalOnSetLightEffect provides { id, effect -> vm.setLightEffect(id, effect) },
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenEffectPicker provides { id -> effectPickerFor.value = id },
        com.github.itskenny0.r1ha.core.theme.LocalOnMediaTransport provides { id, action -> vm.mediaTransport(id, action) },
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenSelectPicker provides { id -> selectPickerFor.value = id },
        com.github.itskenny0.r1ha.core.theme.LocalOnSetSelectOption provides { id, option -> vm.setSelectOption(id, option) },
        com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent provides { id, pct -> vm.setEntityPercent(id, pct) },
    ) {
    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        // displayedCards = cards with optimistic overrides applied per entity. Binding the
        // UI to this list (rather than to the raw HA-confirmed cards) is what makes the
        // brightness/value track the wheel *instantly* instead of waiting for the HA
        // round-trip to echo a state_changed event back.
        val cards = state.displayedCards
        when {
            // Cold-start splash. DataStore is async on first read so for a brief
            // window the VM has its default state. Without this branch the user
            // momentarily saw the 'No favourites yet' EmptyState before the real
            // data arrived, which they read as a permanent error. Plain throbber,
            // no copy — once settings load we route into the horizontal pager.
            !state.settingsLoaded -> StartupSplash()
            state.pages.isEmpty() -> {
                // Defensive: settings loaded but pages list is empty (shouldn't
                // happen post-migration, but the migration runs on first read so a
                // half-loaded state could theoretically slip through here). Fall
                // through to the legacy single-deck rendering.
                val reconnectAt by haRepository.reconnectNextAttemptAtMillis
                    .collectAsStateWithLifecycle()
                EmptyState(
                    loading = state.favouritesCount > 0,
                    favouritesCount = state.favouritesCount,
                    connection = connection,
                    reconnectAt = reconnectAt,
                    onOpenFavoritesPicker = onOpenFavoritesPicker,
                    onOpenSettings = onOpenSettings,
                    onRetry = { haRepository.reconnectNow() },
                )
            }
            else -> {
                // Horizontal pager — one slot per FavoritePage. The user swipes
                // left/right to switch decks; the active page's id syncs back to
                // the VM so wheel routing and chrome state follow the visible
                // page. Each PageDeck holds its own VerticalPager state so a
                // swipe-away-and-back lands on the user's previous card.
                val activePageIndex = state.pages.indexOfFirst { it.id == state.activePageId }
                    .coerceAtLeast(0)
                val pageIds = state.pages.map { it.id }
                // Rebuild the horizontal pager state whenever the page set changes
                // (add/delete/rename moves indices around). Keyed on the list of
                // ids so re-ordering ALSO rebuilds — otherwise the pager would
                // remember its previous currentPage while pageIds shifted under
                // it and we'd land on the wrong page.
                val horizontalPagerState = androidx.compose.runtime.key(pageIds) {
                    androidx.compose.foundation.pager.rememberPagerState(
                        initialPage = activePageIndex,
                        pageCount = { state.pages.size },
                    )
                }
                // Sync activePageId → horizontal pager: when the user taps a tab
                // chip or a page is added programmatically, animate the pager so
                // the chrome and the deck stay in lockstep.
                //
                // Compare against [targetPage] (where the pager is HEADING) not
                // [currentPage] (the dominant visible page). If the pager is
                // already animating toward the new active page (e.g., the
                // user is mid-swipe and snapshotFlow has pushed the new id
                // back to the VM), targetPage already equals idx and we
                // skip the redundant animate. This was the source of an
                // observable tab-flicker loop — without the targetPage
                // check, calling animateScrollToPage mid-fling could re-aim
                // the pager between two pages back and forth.
                androidx.compose.runtime.LaunchedEffect(
                    horizontalPagerState, state.activePageId, pageIds,
                ) {
                    val idx = state.pages.indexOfFirst { it.id == state.activePageId }
                    if (idx >= 0 && idx != horizontalPagerState.targetPage) {
                        horizontalPagerState.animateScrollToPage(idx)
                    }
                }
                // Sync horizontal pager → activePageId: when the user swipes to a
                // different page, push the new active id back into the VM so the
                // tab strip's active highlight follows and wheel routing targets
                // the visible deck. Fires a CLOCK_TICK haptic on settle when the
                // user's "Haptic feedback" setting is on, giving the swipe a
                // tactile end-state to match the wheel and card-swipe haptics.
                // Skips the first emission so opening the screen doesn't fire
                // a phantom haptic for the initial-page settle.
                androidx.compose.runtime.LaunchedEffect(horizontalPagerState, pageIds) {
                    var firstSettle = true
                    snapshotFlow { horizontalPagerState.settledPage }
                        .distinctUntilChanged()
                        .collect { idx ->
                            val pageId = state.pages.getOrNull(idx)?.id
                            if (pageId != null && pageId != state.activePageId) {
                                vm.setActivePage(pageId)
                                if (!firstSettle && appSettings.behavior.haptics) {
                                    @Suppress("DEPRECATION")
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CLOCK_TICK,
                                    )
                                }
                            }
                            firstSettle = false
                        }
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = horizontalPagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Pre-compose one page on each side of the visible one so
                    // a swipe between tabs reveals fully-rendered cards
                    // immediately. The crash trace identified the pip-thumb
                    // spring overshoot as the cause, not this peek; restored.
                    beyondViewportPageCount = 1,
                ) { pageIdx ->
                    val page = state.pages.getOrNull(pageIdx) ?: return@HorizontalPager
                    val pageCardsRaw = state.cardsByPage[page.id].orEmpty()
                    // Apply optimistic overrides to this page's cards so wheel
                    // changes track instantly even when the page becomes active
                    // mid-edit. Same path the legacy displayedCards used; just
                    // scoped per-page.
                    val pageCards = pageCardsRaw.map { card ->
                        val overridePct = state.optimisticPercents[card.id]
                        if (overridePct != null) {
                            if (card.supportsScalar) card.copy(percent = overridePct)
                            else card.copy(percent = overridePct, isOn = overridePct > 0)
                        } else {
                            card
                        }
                    }
                    val isActive = page.id == state.activePageId
                    if (pageCards.isEmpty()) {
                        val reconnectAt by haRepository.reconnectNextAttemptAtMillis
                            .collectAsStateWithLifecycle()
                        EmptyState(
                            loading = page.favorites.isNotEmpty(),
                            favouritesCount = page.favorites.size,
                            connection = connection,
                            reconnectAt = reconnectAt,
                            onOpenFavoritesPicker = onOpenFavoritesPicker,
                            onOpenSettings = onOpenSettings,
                            onRetry = { haRepository.reconnectNow() },
                        )
                    } else {
                        PageDeck(
                            pageId = page.id,
                            cards = pageCards,
                            initialIndex = state.indexByPage[page.id] ?: 0,
                            isActive = isActive,
                            vm = vm,
                            appSettings = appSettings,
                            navRequests = pagerNavRequests,
                            jumpRequests = jumpRequests,
                            lightWheelModes = state.lightWheelMode,
                        )
                    }
                }
            }
        }

        // Top chrome stack: ChromeRow on top, TabStrip directly under it. The two
        // are siblings inside the outer Box so the page chips sit above the active
        // card without affecting the pager's contentPadding (which is already
        // tuned for ChromeRow's 64 dp tall area). When there's only one page the
        // strip is empty visual chrome — collapses to zero height. Hidden during
        // the cold-start splash so the user sees a clean throbber and not chrome
        // perched above a loading spinner.
        if (state.settingsLoaded) androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
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
                onTapCounter = { jumpPickerOpen.value = true },
                onLongPressHamburger = { quickActionsOpen.value = true },
                solidBackdrop = appSettings.ui.hideCardTailAbove,
            )
            // Tab strip — chip per page. Tap to switch active. Long-press opens a
            // management overlay (add / rename / delete). The '+' chip on the
            // right is the discovery surface for adding more pages, so the strip
            // is rendered whenever there's at least one page (always, post-
            // migration). On single-page installs the user just sees "HOME" plus
            // the '+' chip — a low-noise hint that more pages are possible.
            if (appSettings.pages.isNotEmpty()) {
                TabStrip(
                    pages = appSettings.pages,
                    activePageId = appSettings.activePageId,
                    onTapPage = { id -> vm.setActivePage(id) },
                    onLongPressPage = { id -> tabManagementForId.value = id },
                    onAddPage = { tabManagementForId.value = NEW_PAGE_SENTINEL },
                    onReorder = { from, to -> vm.reorderPages(from, to) },
                    solidBackdrop = appSettings.ui.hideCardTailAbove,
                )
            }
        }

        // ── Wheel-no-op hint ────────────────────────────────────────────────────────
        // When the active card has nothing for the wheel to drive (sensors, actions,
        // non-scalar switches when the toggle setting is off) the wheel becomes a
        // no-op. Surface a transient hint so the user learns to swipe or tap the pip
        // to navigate, rather than wondering why the wheel does nothing. Auto-fades
        // after 2 s of no fresh wheel events.
        WheelHintOverlay(triggerAt = wheelHintAt.longValue)

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

        // ── Effect picker overlay ───────────────────────────────────────────────────
        // Renders ABOVE the chrome (this Box stack draws bottom-up) so the picker
        // covers everything, not just the card body. Active entity is looked up in
        // displayedCards by id — if it's no longer present (e.g. user un-favourited
        // mid-pick) we close instead of rendering an empty list.
        val pickerEntityId = effectPickerFor.value
        if (pickerEntityId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == pickerEntityId }
                ?: state.cards.firstOrNull { it.id == pickerEntityId }
            if (entity != null && entity.effectList.isNotEmpty()) {
                com.github.itskenny0.r1ha.core.theme.EffectPickerSheet(
                    entityId = pickerEntityId,
                    current = entity.effect,
                    effects = entity.effectList,
                    accent = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                    onPick = { effect ->
                        vm.setLightEffect(pickerEntityId, effect)
                        effectPickerFor.value = null
                    },
                    onDismiss = { effectPickerFor.value = null },
                )
            } else {
                effectPickerFor.value = null
            }
        }

        // ── Select-option picker overlay ────────────────────────────────────────────
        // Same shape as the effect picker — fullscreen list, tap to apply, system-back
        // / CLOSE chip to dismiss. Mirrors the effect-picker pattern rather than
        // building a second variant; the only difference at render time is the source
        // of the list (entity.selectOptions vs. entity.effectList) and the apply
        // callback. The picker sheet is reused as-is via [SelectPickerSheet].
        val selectId = selectPickerFor.value
        if (selectId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == selectId }
                ?: state.cards.firstOrNull { it.id == selectId }
            if (entity != null && entity.selectOptions.isNotEmpty()) {
                com.github.itskenny0.r1ha.core.theme.SelectPickerSheet(
                    entityId = selectId,
                    current = entity.currentOption,
                    options = entity.selectOptions,
                    accent = com.github.itskenny0.r1ha.core.theme.R1.AccentCool,
                    onPick = { option ->
                        vm.setSelectOption(selectId, option)
                        selectPickerFor.value = null
                    },
                    onDismiss = { selectPickerFor.value = null },
                )
            } else {
                selectPickerFor.value = null
            }
        }

        // ── Jump-to-card overlay ────────────────────────────────────────────────────
        // Opens from a tap on the chrome's position pip — lists every card in the
        // deck by friendly name so the user can hop straight to an entity by name
        // instead of scrolling. In infinite-scroll mode we land on the nearest
        // virtual page that maps to the chosen index (relative to current page) so
        // the wrap-around scroll stays seamless; in finite mode we just animate to
        // that page directly.
        // Per-row context menu opened by long-pressing a JumpRow. Holds the index
        // of the card whose menu is open; null = closed. Lifted to screen scope
        // so the menu can render above the JumpToCardSheet itself (matches the
        // pattern used by [tabManagementForId]).
        val cardContextMenuIdx = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<Int?>(null)
        }
        if (jumpPickerOpen.value && cards.size > 1) {
            JumpToCardSheet(
                cards = cards,
                currentIndex = state.currentIndex,
                listState = jumpListState,
                onPick = { targetIdx ->
                    // Publish the target into [jumpRequests]; the active page's
                    // PageDeck collects it and animates its VerticalPager to the
                    // matching virtual / real page. Decoupling from a hoisted
                    // pagerState lets every page hold its own state.
                    pagerScope.launch { jumpRequests.emit(targetIdx) }
                    jumpPickerOpen.value = false
                },
                onReorder = { from, to -> vm.reorderFavorite(from, to) },
                onOpenMenu = { idx -> cardContextMenuIdx.value = idx },
                onDismiss = { jumpPickerOpen.value = false },
            )
        }

        // Context menu on the long-pressed JumpRow. Surfaces page-move actions
        // and a duplicate of the remove affordance in a focused modal. Hidden
        // when there's only one page (nowhere to move to AND remove already on
        // the row) so the long-press is a no-op rather than opening an empty
        // sheet.
        val ctxIdx = cardContextMenuIdx.value
        if (ctxIdx != null) {
            val ctxCard = cards.getOrNull(ctxIdx)
            if (ctxCard == null) {
                cardContextMenuIdx.value = null
            } else {
                val ctxContext = androidx.compose.ui.platform.LocalContext.current
                CardContextMenu(
                    entityName = ctxCard.friendlyName,
                    entityId = ctxCard.id.value,
                    pages = appSettings.pages,
                    sourcePageId = appSettings.activePageId,
                    haServerUrl = appSettings.server?.url,
                    onMove = { targetPageId ->
                        vm.moveFavoriteToPage(ctxCard.id.value, targetPageId)
                        cardContextMenuIdx.value = null
                    },
                    onRemove = {
                        vm.removeFavorite(ctxCard.id.value)
                        cardContextMenuIdx.value = null
                    },
                    onOpenInHa = { url ->
                        runCatching {
                            ctxContext.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        cardContextMenuIdx.value = null
                    },
                    onDismiss = { cardContextMenuIdx.value = null },
                )
            }
        }

        // ── Tab manage modal ────────────────────────────────────────────────────────
        // Opened from a long-press on a page chip (edit mode) or a tap on the '+'
        // chip (add mode, signalled by NEW_PAGE_SENTINEL). The dialog renders ABOVE
        // every other overlay in this Box stack so the user can never lose track of
        // it behind the chrome or a picker sheet.
        val manageId = tabManagementForId.value
        if (manageId != null) {
            val targetPage = if (manageId == NEW_PAGE_SENTINEL) null
                else appSettings.pages.firstOrNull { it.id == manageId }
            val targetIdx = appSettings.pages.indexOfFirst { it.id == targetPage?.id }
            TabManageDialog(
                isAdd = manageId == NEW_PAGE_SENTINEL,
                page = targetPage,
                canDelete = appSettings.pages.size > 1,
                canMoveLeft = targetIdx > 0,
                canMoveRight = targetIdx >= 0 && targetIdx < appSettings.pages.lastIndex,
                onAdd = { name ->
                    vm.addPage(name)
                    tabManagementForId.value = null
                },
                onRename = { id, name ->
                    vm.renamePage(id, name)
                    tabManagementForId.value = null
                },
                onDelete = { id ->
                    vm.deletePage(id)
                    tabManagementForId.value = null
                },
                onMoveLeft = { id -> vm.movePageLeft(id) },
                onMoveRight = { id -> vm.movePageRight(id) },
                onSetAccent = { id, argb -> vm.setPageAccent(id, argb) },
                onSetIcon = { id, icon -> vm.setPageIcon(id, icon) },
                onDismiss = { tabManagementForId.value = null },
            )
        }

        // Quick-actions sheet — currently only 'all off' on the active page.
        // Long-press the chrome's hamburger to open. Two-stage confirm via
        // armed/commit pattern (same as page delete) since this fires N
        // service calls and the user can't undo with one tap.
        if (quickActionsOpen.value) {
            val activePageCards = state.cardsByPage[appSettings.activePageId].orEmpty()
            val playingMediaCount = activePageCards.count { ent ->
                ent.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER &&
                    ent.rawState?.equals("playing", ignoreCase = true) == true
            }
            QuickActionsSheet(
                activePageName = appSettings.pages.firstOrNull { it.id == appSettings.activePageId }?.name
                    ?: "this page",
                cardCount = state.cards.size,
                playingMediaCount = playingMediaCount,
                onAllOn = {
                    vm.turnOnActivePage()
                    quickActionsOpen.value = false
                },
                onAllOff = {
                    vm.turnOffActivePage()
                    quickActionsOpen.value = false
                },
                onPauseMedia = {
                    vm.pauseAllMedia()
                    quickActionsOpen.value = false
                },
                onDismiss = { quickActionsOpen.value = false },
            )
        }
    }
    }
}

@Composable
private fun PageDeck(
    pageId: String,
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    initialIndex: Int,
    isActive: Boolean,
    vm: CardStackViewModel,
    appSettings: AppSettings,
    navRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    lightWheelModes: Map<com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode>,
) {
    // One pager state per page, keyed on pageId + infinite-scroll mode + the
    // presence of cards. Re-keying on the card-presence boolean (rather than
    // cards.size) means adding a fresh card doesn't rebuild the pager state and
    // bounce the user back to the start of the deck.
    val infiniteScroll = appSettings.ui.infiniteScroll
    // Capture cards.size at composition time. Including it in the
    // rememberPagerState key means the pager state rebuilds whenever the
    // deck shrinks or grows — fixes a class of bug where the pageCount
    // lambda closed over a stale `cards` reference (Compose preserves the
    // first-composition closure inside a remembered PagerState) and the
    // pager kept reporting the old size for currentPage validation.
    // Reported symptom: 'scroll-up crash, especially on the first card'
    // — when state mutations (entity add/remove via the new '…' menu, or
    // periodic state-changed events) shifted the deck, the next scroll
    // hit a size mismatch and Compose's Pager would throw on internal
    // invariants. Re-keying on size restores invariant safety.
    val pagerState = androidx.compose.runtime.key(pageId, infiniteScroll, cards.size) {
        androidx.compose.foundation.pager.rememberPagerState(
            initialPage = if (infiniteScroll && cards.isNotEmpty()) {
                val anchor = INFINITE_PAGER_VIRTUAL_PAGES / 2
                val aligned = anchor - (anchor % cards.size)
                aligned + initialIndex.coerceAtLeast(0).coerceAtMost(cards.size - 1)
            } else {
                initialIndex
                    .coerceAtMost((cards.size - 1).coerceAtLeast(0))
                    .coerceAtLeast(0)
            },
            pageCount = {
                if (infiniteScroll && cards.isNotEmpty()) INFINITE_PAGER_VIRTUAL_PAGES else cards.size
            },
        )
    }

    // Map a (possibly virtual) pager page to a real card index. In infinite-scroll mode
    // the pager uses a 200k-page virtual range, so we modulo back into 0..cards.size-1
    // before indexing the cards list or reporting currentIndex up to the VM.
    val realIndexOf: (Int) -> Int = { page ->
        if (cards.isEmpty()) 0
        else ((page % cards.size) + cards.size) % cards.size
    }
    // Report the settled card index up to the VM, scoped to this page. Active page
    // writes through setCurrentIndex (which also updates the legacy currentIndex
    // field); inactive pages write through setIndexForPage so background scroll is
    // persisted without disturbing the active deck's state.
    LaunchedEffect(pagerState, cards.size, pageId, isActive) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val idx = realIndexOf(page)
                if (isActive) vm.setCurrentIndex(idx) else vm.setIndexForPage(pageId, idx)
            }
    }
    // Wheel-as-navigation, fired from CardStackScreen when the active card is read-only.
    // animateScrollToPage so the transition is the same gentle spring the user gets when
    // swiping the pager by finger — no jarring snap. In infinite-scroll mode we don't
    // wrap by modulo: we simply animate to currentPage ± 1, which the giant virtual
    // pageCount makes effectively boundless. (Modulo'ing inside the pager's range would
    // make the pager skip from page 199_999 back to 0 with a huge animateScroll instead
    // of a single-page glide.) In finite mode we clamp to [0, lastIndex]. Gated on
    // isActive so a wheel event never moves the deck on a page the user can't see.
    LaunchedEffect(pagerState, navRequests, infiniteScroll, isActive) {
        if (!isActive) return@LaunchedEffect
        navRequests.collect { delta ->
            if (cards.isEmpty() || delta == 0) return@collect
            val current = pagerState.currentPage
            val target = if (infiniteScroll) {
                (current + delta).coerceIn(0, INFINITE_PAGER_VIRTUAL_PAGES - 1)
            } else {
                (current + delta).coerceIn(0, cards.lastIndex)
            }
            if (target != current) pagerState.animateScrollToPage(target)
        }
    }
    // Jump-to-card requests — the active page collects the target index and
    // animates to it. Mirrors the previous direct pagerState.animateScrollToPage
    // call site but routed through a flow so the picker doesn't need a direct
    // reference to whichever page is active.
    LaunchedEffect(pagerState, jumpRequests, infiniteScroll, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        jumpRequests.collect { targetIdx ->
            if (cards.isEmpty()) return@collect
            val current = pagerState.currentPage
            val target = if (infiniteScroll) {
                val curIdx = ((current % cards.size) + cards.size) % cards.size
                var diff = targetIdx - curIdx
                if (diff > cards.size / 2) diff -= cards.size
                if (diff < -cards.size / 2) diff += cards.size
                (current + diff).coerceIn(0, INFINITE_PAGER_VIRTUAL_PAGES - 1)
            } else {
                targetIdx.coerceIn(0, cards.lastIndex)
            }
            pagerState.animateScrollToPage(target)
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
                // Infinite-scroll uses a virtual page index well past cards.size, so we
                // modulo back into the real card index before any lookup.
                //
                // Guard against the cards list shrinking under us mid-frame. The
                // pager's content lambda can be invoked with a stale `page` index
                // when state transitions (entity removed via the '…' menu, or the
                // persister-loaded cache is overwritten by a smaller fresh state
                // push) shrink cards.size between composition cycles. Without the
                // guard, `cards[cardIdx]` was throwing IOOB on swipes that
                // coincided with the state transition — surfaced by the user as
                // 'scrolling up on cards crashes, especially the top card' when
                // the persister had loaded N cards and HA echoed back N-1.
                if (cards.isEmpty()) return@Box
                val realSize = cards.size
                val cardIdx = realIndexOf(page).coerceIn(0, realSize - 1)
                val card = cards.getOrNull(cardIdx) ?: return@Box
                val longPressTarget = appSettings.entityOverrides[card.id.value]?.longPressTarget
                val pageLightMode = lightWheelModes[card.id]
                EntityCard(
                    state = card,
                    onTapToggle = { vm.tapToggle() },
                    tapToToggleEnabled = appSettings.behavior.tapToToggle,
                    onSetOn = { on -> vm.setSwitchOn(on) },
                    onLongPress = longPressTarget?.let { target -> { vm.fireLongPress(target) } },
                    lightWheelMode = pageLightMode,
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
        // Chevron hint at the bottom — visible whenever there's a next card to scroll
        // to. In infinite-scroll mode there's *always* a next card (the deck wraps), so
        // the hint shows on every page; in finite mode it hides on the last card.
        val hasNext = if (appSettings.ui.infiniteScroll) cards.size > 1
            else currentPage < cards.size - 1
        androidx.compose.animation.AnimatedVisibility(
            visible = hasNext,
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

/**
 * Cold-start splash shown until [CardStackUiState.settingsLoaded] flips true.
 * Wordmark over a throbber so the user knows the app is loading (a bare
 * spinner during the brief DataStore read window could look like the device
 * froze; on the R1's slow boot path the splash can sit visible for a couple
 * of hundred ms). Once settings arrive the screen routes into either
 * [EmptyState] (with onboarding copy) or [VerticalCardPager] (with the
 * user's deck) as appropriate.
 *
 * Tag-style 'R1 · HA' uses the same uppercase letterspaced numeral the rest
 * of the dashboard uses for section headers, so the splash reads as part of
 * the design language rather than a generic loader.
 */
@Composable
private fun StartupSplash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "R1 · HA",
            style = R1.sectionHeader,
            color = R1.AccentWarm,
        )
        Spacer(Modifier.height(14.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = R1.AccentWarm,
        )
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    favouritesCount: Int,
    connection: ConnectionState,
    reconnectAt: Long?,
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
    // Reconnect countdown — when the repo has a backoff in flight, tick a once-per-second
    // recomputed "RECONNECTING IN Xs…" so the user can see the indefinite-spinner state is
    // actually doing something. We only need a coarse 1-Hz refresh; the actual reconnect
    // fires from the repo's coroutine, not from this tick. Driven by a wall-clock-now
    // mutableState that the LaunchedEffect rewrites every second while there's a future
    // target — cheap to recompose on, and goes silent once reconnectAt clears.
    val nowMs = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(reconnectAt) {
        if (reconnectAt == null) return@LaunchedEffect
        while (true) {
            nowMs.value = System.currentTimeMillis()
            // 1 Hz is more than enough fidelity for human-readable seconds; faster ticks
            // just burn frames without changing the rendered string.
            kotlinx.coroutines.delay(1_000)
        }
    }
    val countdownSeconds = reconnectAt?.let {
        ((it - nowMs.value) / 1000L).coerceAtLeast(0L)
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
        // Countdown chip — only meaningful while we're loading AND there's a backoff
        // scheduled. (Without the loading gate, a transient reconnectAt during normal use
        // would briefly leak through here.) Friendlier than the bare spinner: the user
        // can see something will happen in 14 seconds, not just "loading forever". We
        // suppress it once seconds reaches zero — at that point the repo has fired and
        // is actively reconnecting; the spinner alone is correct.
        if (loading && countdownSeconds != null && countdownSeconds > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "RECONNECTING IN ${countdownSeconds}s…",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
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

/** Virtual page count used by the pager when infinite-scroll is enabled. Big enough
 *  that even an entire afternoon of aggressive swiping doesn't run out of pages (200 k
 *  pages ÷ 1 swipe-per-half-second × 60 s × 60 min = ~28 hours of continuous swiping
 *  before hitting an end), small enough that the pager's per-page Compose bookkeeping
 *  stays cheap. Capped well under Int.MAX_VALUE to avoid arithmetic overflow corners. */
private const val INFINITE_PAGER_VIRTUAL_PAGES = 200_000

/** Sentinel id meaning 'open the TabManageDialog in "add new page" mode'. Real
 *  page ids are random UUIDs so this fixed string never collides. */
private const val NEW_PAGE_SENTINEL = "__new_page__"

/**
 * Tab strip — one chip per page, plus a trailing '+' chip to add a new page. The
 * active page chip fills with accent; others sit on the muted surface.
 *
 * Gesture map:
 *  - Tap: switch to that page.
 *  - Long-press + drag horizontally: live-reorder. A swap fires every time the
 *    finger crosses half a chip-width worth of travel (~40 dp). Same pattern as
 *    [DragReorderColumn] but flipped to the horizontal axis.
 *  - Long-press + release without dragging: open the manage modal (rename /
 *    delete / explicit MOVE LEFT / MOVE RIGHT).
 *
 * Sits directly under the chrome row when there's more than one page. Hidden on
 * single-page installs so the pre-tabs aesthetic is preserved for users who
 * never opt into multi-page.
 */
@Composable
private fun TabStrip(
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    activePageId: String,
    onTapPage: (String) -> Unit,
    onLongPressPage: (String) -> Unit,
    onAddPage: () -> Unit,
    onReorder: (fromIdx: Int, toIdx: Int) -> Unit,
    solidBackdrop: Boolean,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Per-chip measured X bounds (left .. right, in pixels relative to the Row's
    // origin). Populated via onGloballyPositioned on each chip and read by the
    // auto-scroll LaunchedEffect so the active chip is brought into view when
    // the user swipes to a new page on the horizontal-pager below. Without
    // this, swiping between pages on a long tab strip would leave the visible
    // page label stuck off-screen.
    val chipBounds = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<String, IntRange>()
    }
    // Snap the active chip into view whenever the active page id changes. Pads
    // the scroll target so the chip isn't flush against the viewport edge — a
    // small breathing margin keeps it readable + leaves a hint that more chips
    // exist to either side. Animated rather than instant so the transition
    // visibly mirrors the page swipe happening underneath.
    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(activePageId, chipBounds.size, scroll.maxValue) {
        val r = chipBounds[activePageId] ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val margin = with(density) { 16.dp.toPx() }.toInt()
        val visibleStart = scroll.value
        val visibleEnd = visibleStart + viewport
        val target = when {
            r.first < visibleStart + margin ->
                (r.first - margin).coerceAtLeast(0)
            r.last > visibleEnd - margin ->
                (r.last - viewport + margin).coerceAtMost(scroll.maxValue)
            else -> return@LaunchedEffect
        }
        pagerScope.launch { scroll.animateScrollTo(target) }
    }
    // rememberUpdatedState lets the long-lived pointerInput lambda reach the
    // *current* pages + callbacks every drag event, even though pointerInput is
    // keyed on the stable page.id (and so isn't rebuilt on every recomposition).
    // Without this, a chip that just got swapped would see the pre-swap pages
    // list and fire a duplicate swap.
    val currentPages = androidx.compose.runtime.rememberUpdatedState(pages)
    val currentOnReorder = androidx.compose.runtime.rememberUpdatedState(onReorder)
    val currentOnLongPress = androidx.compose.runtime.rememberUpdatedState(onLongPressPage)
    // Half-chip's worth of travel. Chips on the R1 are roughly 56-72 dp wide
    // depending on the page name; 40 dp lands somewhere between "easy to
    // trigger" and "easy to overshoot by accident". Same magnitude as
    // DragReorderColumn's vertical threshold so the tactile feel matches.
    val swapThresholdPx = with(density) { 40.dp.toPx() }
    /** ID of the chip currently in flight. Drives the visual lift effect. */
    var draggedKey by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (solidBackdrop) Modifier.background(R1.Bg) else Modifier)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEach { page ->
            val active = page.id == activePageId
            val isDragging = draggedKey == page.id
            // Per-chip mutable drag state. Reset whenever the long-press
            // starts so each new drag begins from zero offset. Keyed on
            // page.id so a chip's state survives reorders.
            val hasDragged = androidx.compose.runtime.remember(page.id) {
                androidx.compose.runtime.mutableStateOf(false)
            }
            val dragOffsetPx = androidx.compose.runtime.remember(page.id) {
                androidx.compose.runtime.mutableFloatStateOf(0f)
            }
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    // Record this chip's measured x-bounds (relative to the
                    // Row) so the LaunchedEffect above can scroll the strip
                    // when the active page changes. Stored as IntRange so the
                    // auto-scroll math can compare against scroll.value
                    // directly without a separate width/offset pair.
                    .onGloballyPositioned { coords ->
                        val left = coords.positionInParent().x.toInt()
                        val right = left + coords.size.width
                        chipBounds[page.id] = left..right
                    }
                    // While dragging: translate the chip along the user's
                    // finger via the accumulated offset, lift it slightly with
                    // a scale > 1 (keeps the tap target physically the same)
                    // and dim the alpha so adjacent chips read as 'in the
                    // background'. The translation makes the gesture feel
                    // physical — the finger drags the chip rather than the
                    // chip teleporting between slots on threshold-cross. The
                    // accumulated offset resets toward 0 after each swap, so
                    // the translation magnitude stays bounded.
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = dragOffsetPx.floatValue
                            scaleX = 1.06f
                            scaleY = 1.06f
                            this.alpha = 0.88f
                        }
                    }
                    .clip(R1.ShapeS)
                    .background(
                        if (active) {
                            // Per-page accent override; falls back to the warm
                            // default for pages that haven't been customised.
                            page.accentArgb?.let { androidx.compose.ui.graphics.Color(it) }
                                ?: R1.AccentWarm
                        } else R1.SurfaceMuted,
                    )
                    .r1Pressable(onClick = { onTapPage(page.id) })
                    .pointerInput(page.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                hasDragged.value = false
                                dragOffsetPx.floatValue = 0f
                                draggedKey = page.id
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragOffsetPx.floatValue += drag.x
                                // Re-resolve current index every event — the
                                // chip may have already shifted due to a prior
                                // swap in the same drag. currentPages is the
                                // up-to-date list via rememberUpdatedState.
                                val curIdx = currentPages.value.indexOfFirst { it.id == page.id }
                                if (curIdx < 0) return@detectDragGesturesAfterLongPress
                                // Swap right.
                                while (dragOffsetPx.floatValue > swapThresholdPx &&
                                    currentPages.value.indexOfFirst { it.id == page.id }
                                        .let { it >= 0 && it < currentPages.value.lastIndex }
                                ) {
                                    val i = currentPages.value.indexOfFirst { it.id == page.id }
                                    currentOnReorder.value(i, i + 1)
                                    dragOffsetPx.floatValue -= swapThresholdPx
                                    hasDragged.value = true
                                }
                                // Swap left.
                                while (dragOffsetPx.floatValue < -swapThresholdPx &&
                                    currentPages.value.indexOfFirst { it.id == page.id } > 0
                                ) {
                                    val i = currentPages.value.indexOfFirst { it.id == page.id }
                                    currentOnReorder.value(i, i - 1)
                                    dragOffsetPx.floatValue += swapThresholdPx
                                    hasDragged.value = true
                                }
                            },
                            onDragEnd = {
                                draggedKey = null
                                // Long-press without any drag movement falls
                                // through to the manage-modal callback so
                                // users still have a way to open it.
                                if (!hasDragged.value) currentOnLongPress.value(page.id)
                            },
                            onDragCancel = {
                                draggedKey = null
                                if (!hasDragged.value) currentOnLongPress.value(page.id)
                            },
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                // Page icon + name + entity-count badge. Icon is a single
                // Unicode glyph chosen from the manage modal's curated row;
                // rendered first so the user's eye lands on it. Name follows.
                // The "· N" suffix appears only when the page has favourites
                // — empty pages would otherwise get a misleading "· 0" that
                // crowds the chip without conveying anything useful. Same
                // labelMicro style for both segments so they read as one
                // unit; the count is dimmed slightly so it doesn't compete
                // with the page name for attention.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!page.icon.isNullOrEmpty()) {
                        Text(
                            text = page.icon,
                            style = R1.labelMicro,
                            color = if (active) R1.Bg else R1.InkSoft,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = page.name,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                    if (page.favorites.isNotEmpty()) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "·",
                            style = R1.labelMicro,
                            color = if (active) R1.Bg.copy(alpha = 0.55f)
                                else R1.InkMuted,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = page.favorites.size.toString(),
                            style = R1.labelMicro,
                            color = if (active) R1.Bg.copy(alpha = 0.85f)
                                else R1.InkMuted,
                        )
                    }
                }
            }
        }
        // '+' chip — always last. Tap → open the manage modal in 'add' mode.
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .r1Pressable(onClick = onAddPage)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = "+", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}

/**
 * Modal for adding, renaming, or deleting a page. Two modes share the same panel
 * so users learn one surface instead of three:
 *
 *  * **Add mode** ([isAdd] = true, [page] = null) — single text field defaulting
 *    to "NEW", a SAVE button and a CANCEL chip. No DELETE row.
 *  * **Edit mode** ([isAdd] = false, [page] non-null) — text field pre-filled
 *    with the page's current name; SAVE renames, CANCEL discards. A DELETE
 *    button appears below when [canDelete] is true (i.e. there's more than one
 *    page — the user can never delete their last page out from under the deck).
 *
 * Styling follows the rename-dialog conventions: dim backdrop, sharp 2 dp panel,
 * hairline border, monospace where it helps. Back press dismisses, matching the
 * other R1 modals.
 */
@Composable
private fun TabManageDialog(
    isAdd: Boolean,
    page: com.github.itskenny0.r1ha.core.prefs.FavoritePage?,
    canDelete: Boolean,
    /** True when the page being edited has a left neighbour — gates the MOVE LEFT
     *  button. Ignored in add mode. */
    canMoveLeft: Boolean,
    /** Mirror of [canMoveLeft] for the right side. */
    canMoveRight: Boolean,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveLeft: (String) -> Unit,
    onMoveRight: (String) -> Unit,
    onSetAccent: (pageId: String, accentArgb: Int?) -> Unit,
    onSetIcon: (pageId: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = if (isAdd) "NEW" else (page?.name ?: "")
    var name by androidx.compose.runtime.remember(isAdd, page?.id) {
        androidx.compose.runtime.mutableStateOf(initial)
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(
                text = if (isAdd) "NEW PAGE" else "EDIT PAGE",
                style = R1.sectionHeader,
                color = R1.AccentWarm,
            )
            if (!isAdd && page != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = page.id,
                    style = R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Auto-focus the name field on dialog open so the keyboard appears
            // without a stray tap. The user just hit '+' (add) or long-pressed
            // a chip (edit) — they want to type. The 50 ms delay gives the
            // dialog a frame to commit composition before we yank focus into
            // the BasicTextField; without it the request occasionally lands
            // before the field is laid out and gets dropped.
            val nameFocus = androidx.compose.runtime.remember(isAdd, page?.id) {
                androidx.compose.ui.focus.FocusRequester()
            }
            androidx.compose.runtime.LaunchedEffect(isAdd, page?.id) {
                kotlinx.coroutines.delay(50)
                runCatching { nameFocus.requestFocus() }
            }
            com.github.itskenny0.r1ha.ui.components.R1TextField(
                value = name,
                onValueChange = { name = it.take(20) },
                placeholder = "PAGE NAME",
                monospace = false,
                focusRequester = nameFocus,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                R1Button(
                    text = "CANCEL",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                R1Button(
                    text = "SAVE",
                    onClick = {
                        val trimmed = name.trim().ifBlank { if (isAdd) "NEW" else (page?.name ?: "PAGE") }
                        if (isAdd) onAdd(trimmed) else page?.let { onRename(it.id, trimmed) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // MOVE LEFT / MOVE RIGHT — shifts the page one slot in either
            // direction in the tab strip. Hidden buttons (canMoveLeft/Right =
            // false) on the leftmost/rightmost page rather than disabled, so
            // the row size adjusts and the dialog stays tidy on the R1's
            // narrow display. The arrow glyphs avoid any text-wrapping at the
            // labelMicro size.
            if (!isAdd && page != null && (canMoveLeft || canMoveRight)) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canMoveLeft) {
                        R1Button(
                            text = "◀  MOVE LEFT",
                            onClick = { onMoveLeft(page.id) },
                            modifier = Modifier.weight(1f),
                            variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                        )
                    }
                    if (canMoveRight) {
                        R1Button(
                            text = "MOVE RIGHT  ▶",
                            onClick = { onMoveRight(page.id) },
                            modifier = Modifier.weight(1f),
                            variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                        )
                    }
                }
            }
            // Accent colour row — only meaningful in edit mode where there's an
            // existing page to recolour. Six presets matched against the R1
            // palette (warm / cool / amber / red / green / muted) plus a
            // 'default' swatch that clears the override. The active selection
            // gets a hairline border so it's obvious which preset is current;
            // others render as flat swatches.
            if (!isAdd && page != null) {
                Spacer(Modifier.height(14.dp))
                Text(text = "ACCENT", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                val accentPresets = listOf<Pair<String, Int?>>(
                    "DEFAULT" to null,
                    "WARM" to R1.AccentWarm.value.toInt(),
                    "COOL" to R1.AccentCool.value.toInt(),
                    "AMBER" to R1.StatusAmber.value.toInt(),
                    "RED" to R1.StatusRed.value.toInt(),
                    "GREEN" to R1.AccentGreen.value.toInt(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((label, argb) in accentPresets) {
                        val swatchColor = argb?.let { androidx.compose.ui.graphics.Color(it) }
                            ?: R1.SurfaceMuted
                        val selected = page.accentArgb == argb
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(R1.ShapeS)
                                .background(swatchColor)
                                .then(
                                    if (selected) Modifier.border(1.5.dp, R1.Ink, R1.ShapeS)
                                    else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                                )
                                .r1Pressable(onClick = { onSetAccent(page.id, argb) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 'DEFAULT' tile gets a tiny label since the muted
                            // colour alone isn't distinguishable from an unset
                            // / disabled state. Coloured tiles speak for
                            // themselves.
                            if (argb == null) {
                                Text(
                                    text = "—",
                                    style = R1.labelMicro,
                                    color = R1.InkMuted,
                                )
                            }
                        }
                    }
                }
            }
            // Icon row — curated set of Unicode glyphs that read cleanly on
            // the R1's mono-style display. Tap to apply; '—' clears the
            // override (no icon prepended to the chip). Edit mode only,
            // mirroring the accent row's gating.
            if (!isAdd && page != null) {
                Spacer(Modifier.height(10.dp))
                Text(text = "ICON", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                val iconPresets = listOf<String?>(
                    null, "⌂", "★", "◆", "◇", "☀", "☾", "♪", "⚙",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (preset in iconPresets) {
                        val selected = page.icon == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .then(
                                    if (selected) Modifier.border(1.5.dp, R1.Ink, R1.ShapeS)
                                    else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                                )
                                .r1Pressable(onClick = {
                                    com.github.itskenny0.r1ha.core.util.R1Log.d(
                                        "TabManage", "setPageIcon ${page.id} -> ${preset ?: "(clear)"}",
                                    )
                                    onSetIcon(page.id, preset)
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset ?: "—",
                                style = R1.labelMicro,
                                color = if (preset == null) R1.InkMuted else R1.Ink,
                            )
                        }
                    }
                }
            }
            // DELETE only shows in edit-mode AND when at least one other page would
            // remain afterward. Deleting the last page would leave the user with an
            // empty deck and no way to switch back to a page, so we hide the option
            // entirely rather than relying on a runtime block.
            //
            // Two-stage confirm: first tap arms the button (label flips to
            // 'CONFIRM DELETE · TAP AGAIN'), second tap commits. Auto-disarms
            // after 3 seconds via a LaunchedEffect so a stray arm doesn't sit
            // hot indefinitely. Mirrors how desktop OSes guard accidental
            // destructive actions — a one-tap delete on a populated page was
            // too easy to fire from muscle memory.
            if (!isAdd && page != null && canDelete) {
                Spacer(Modifier.height(8.dp))
                val armed = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(armed.value) {
                    if (armed.value) {
                        kotlinx.coroutines.delay(3_000)
                        armed.value = false
                    }
                }
                R1Button(
                    text = if (armed.value) "CONFIRM DELETE · TAP AGAIN" else "DELETE",
                    onClick = {
                        if (armed.value) onDelete(page.id) else armed.value = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    accent = R1.StatusRed,
                )
                if (armed.value) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Will remove the page and its ${page.favorites.size} favourite${if (page.favorites.size == 1) "" else "s"} from this view (HA entities aren't deleted).",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
        }
    }
}

/**
 * Per-card context menu opened by long-pressing a JumpRow. Currently surfaces
 * page-move actions ("Move to PAGE_NAME" once per page other than the source)
 * plus a duplicate REMOVE so the menu is the canonical 'do something to this
 * card' surface. Dismisses on backdrop tap or BackHandler.
 *
 * Visual styling mirrors [TabManageDialog]: dim full-screen backdrop, sharp
 * 2 dp inner panel with hairline border, warm-accent section header, monospace
 * entity_id reminder beneath the friendly name. Keeps the modal language
 * consistent across the dashboard.
 */
@Composable
private fun CardContextMenu(
    entityName: String,
    entityId: String,
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    sourcePageId: String,
    /** HA server URL — used to build the deep-link for the 'Open in HA' button.
     *  Null when the user isn't signed in (the button is then hidden). */
    haServerUrl: String?,
    onMove: (targetPageId: String) -> Unit,
    onRemove: () -> Unit,
    onOpenInHa: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Text(text = "CARD ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = entityName,
                style = R1.body,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = entityId,
                style = R1.labelMicro.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Move-to-page entries. Filtered to pages OTHER than the source so
            // we never offer a self-move. When there's only one page total,
            // this section collapses to a 'no other pages' affordance pointing
            // at the '+' chip so the user discovers the page-creation route.
            //
            // Rendered as a wrapping FlowRow of compact chips rather than
            // one full-width R1Button per page — users with 8+ pages were
            // seeing the modal fill the whole screen with MOVE TO buttons.
            // Each chip sizes to its text + a small horizontal padding,
            // wrapping onto multiple rows only when the page count actually
            // requires it. Active accent border so each chip reads as
            // tappable; same labelMicro text style as the page chips on
            // the main tab strip for visual consistency.
            val targetPages = pages.filter { it.id != sourcePageId }
            Spacer(Modifier.height(14.dp))
            Text(text = "MOVE TO", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(6.dp))
            if (targetPages.isEmpty()) {
                Text(
                    text = "No other pages yet — add one with the '+' chip on the tab strip.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (p in targetPages) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .border(1.dp, R1.AccentWarm, R1.ShapeS)
                                .r1Pressable(onClick = { onMove(p.id) })
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = p.name.uppercase(),
                                style = R1.labelMicro,
                                color = R1.AccentWarm,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // Open in HA — deep-link to the entity's history page in the HA
            // web UI. Useful when the user wants to see HA's full sensor
            // history / device controls / configure automations. Hidden
            // when the user isn't signed in.
            if (!haServerUrl.isNullOrBlank()) {
                val url = "${haServerUrl.trimEnd('/')}/history?entity_id=$entityId"
                R1Button(
                    text = "OPEN IN HA",
                    onClick = { onOpenInHa(url) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.height(8.dp))
            }
            // Remove from this page — same destructive action surfaced via the
            // inline '✕' chip. Duplicated here so the long-press menu is a
            // complete 'manage this card' surface; a user who long-pressed
            // expecting to remove (and missed that the inline chip existed)
            // still finds the affordance.
            R1Button(
                text = "REMOVE FROM PAGE",
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusRed,
            )
            Spacer(Modifier.height(8.dp))
            R1Button(
                text = "CANCEL",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        }
    }
}

/**
 * Quick-actions sheet — opened by long-pressing the chrome hamburger.
 * Currently exposes a single 'turn off everything on this page' action with
 * a two-stage confirm. Designed to grow over time: future actions could
 * include 'all-on for night mode', 'lock all', 'pause all media'.
 */
@Composable
private fun QuickActionsSheet(
    activePageName: String,
    cardCount: Int,
    playingMediaCount: Int,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onPauseMedia: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val armed = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(armed.value) {
        if (armed.value) {
            kotlinx.coroutines.delay(3_000)
            armed.value = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(text = "QUICK ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = activePageName.uppercase(),
                style = R1.body,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(14.dp))
            // 'Turn all on' — one-tap fire. Lights/switches/fans coming on
            // accidentally is recoverable (re-tap the card or the all-off
            // route), so the safety bar can be lower than for turn-off.
            R1Button(
                text = "TURN ALL ON",
                onClick = onAllOn,
                modifier = Modifier.fillMaxWidth(),
                accent = R1.AccentGreen,
            )
            // 'Pause N media' — surfaces only when there's at least one
            // playing media_player on the active page. Single-tap fire
            // since pausing is non-destructive and the user can immediately
            // tap play on any card to resume.
            if (playingMediaCount > 0) {
                Spacer(Modifier.height(8.dp))
                R1Button(
                    text = "PAUSE $playingMediaCount MEDIA",
                    onClick = onPauseMedia,
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 'Turn all off' — two-stage confirm. Off is the more disruptive
            // direction (lights you wanted on, media you wanted playing) so
            // the second-tap guard prevents muscle-memory accidents.
            R1Button(
                text = if (armed.value) "CONFIRM · TURN OFF $cardCount" else "TURN ALL OFF",
                onClick = { if (armed.value) onAllOff() else armed.value = true },
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusAmber,
            )
            if (armed.value) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Fires turn_off on every controllable entity on this page (lights, switches, fans, media_players, covers).",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            R1Button(
                text = "CANCEL",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
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
    onEditActive: () -> Unit = {},
    /** Tap on the position pip / counter opens the jump-to-card picker. Null in
     *  previews; defaults to a no-op so the pip becomes inert when there's no
     *  picker to open. */
    onTapCounter: () -> Unit = {},
    /** Long-press on the hamburger opens the quick-actions sheet (currently
     *  just 'all off'). Defaulted to a no-op so existing previews that
     *  don't care about the gesture don't need to thread it through. */
    onLongPressHamburger: () -> Unit = {},
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
            // Consume any tap that lands in the chrome strip but misses one of the
            // explicit buttons (hamburger / pip / pencil / gear). Without this, a
            // tap in the SpaceBetween gaps falls through to the pager content below,
            // which extends UP into the contentPadding zone — the user reported
            // 'top-left corner of cards turns them on' because that's where the gap
            // between hamburger and pip sits. Empty-onClick clickable with no
            // indication / interactionSource so the chrome doesn't paint a ripple.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-left: favourites hamburger (custom 3-stroke glyph, not Material's filled icon).
        // Tap: open the favourites picker. Long-press: open the quick-actions
        // sheet (currently just 'all off' on the active page). r1RowPressable
        // gives both gestures on the same tile.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .r1RowPressable(
                    onTap = onOpenFavoritesPicker,
                    onLongPress = onLongPressHamburger,
                ),
            contentAlignment = Alignment.Center,
        ) {
            HamburgerGlyph(size = 18.dp)
        }

        // Centre: vertical position indicator. Hairline track + a 3dp filled segment at the
        // current page. Visually communicates "vertical stack" — wheel of cards going up
        // and down — rather than the horizontal row of dots that read as left/right.
        if (showCounter) {
            // The pip carries its own r1Pressable so the tap target follows the
            // intrinsic pill width — wrapping it in a fixed-size Box clipped the
            // counter ("1/30") onto multiple lines when the rounded-rect pill ran
            // out of horizontal room. Tap opens the jump-to-card picker.
            VerticalPagePip(
                count = cardsCount,
                current = currentIndex,
                onClick = onTapCounter,
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
                // While the connection is amber (Idle/Connecting/Authenticating) the
                // Infinite-pulse alpha while connecting / authenticating. The
                // dot pulses between 40% and 100% alpha to signal 'work in
                // progress'. Restored — the LAST CRASH trace pointed at the
                // pip-thumb spring overshoot, not this animation.
                val isWorking = connection is ConnectionState.Connecting ||
                    connection is ConnectionState.Authenticating ||
                    connection == ConnectionState.Idle
                val transition = androidx.compose.animation.core.rememberInfiniteTransition(
                    label = "conn-dot-pulse",
                )
                val pulse by transition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(
                            durationMillis = 750,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "conn-dot-pulse-alpha",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            // Clamp alpha to [0, 1] defensively — the bouncy-
                            // free tween shouldn't overshoot but a small
                            // coerceIn() costs nothing.
                            animatedColor.copy(
                                alpha = if (isWorking) pulse.coerceIn(0f, 1f) else 1f,
                            ),
                        ),
                )
            }
        }
        }  // end right-cluster Row
    }
}

/**
 * Transient hint surfaced on the card stack when the user spins the wheel on a card
 * that has nothing for the wheel to drive (sensors, actions, non-scalar switches with
 * wheel-toggles-switches off). Tells them how to actually navigate the deck. The
 * caller drives visibility via a monotonically-increasing [triggerAt] timestamp; each
 * new value re-arms the 2-second visibility window so a rapid wheel spin keeps the
 * hint on screen continuously.
 */
@Composable
private fun BoxScope.WheelHintOverlay(triggerAt: Long) {
    val visible = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(triggerAt) {
        if (triggerAt > 0L) {
            visible.value = true
            kotlinx.coroutines.delay(2_000)
            visible.value = false
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible.value,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            // Sit just below the chrome row (~52 dp tall) so the hint reads as
            // belonging to the current card without overlapping the centre pip.
            .padding(top = 56.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeRound)
                .background(R1.Bg.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "WHEEL DOES NOTHING HERE — SWIPE OR TAP THE PIP",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * Fullscreen jump-to-card list — opens from a tap on the chrome's position pip and
 * lets the user pick a card by friendly name instead of scrolling through the deck.
 * Mirrors the visual shape of [EffectPickerSheet] / [SelectPickerSheet] so the user
 * only has to learn one picker convention. The current card is highlighted; tapping
 * any row dispatches an animateScrollToPage that handles infinite-scroll's
 * virtual-page math at the call site.
 */
@Composable
private fun JumpToCardSheet(
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    currentIndex: Int,
    /** Hoisted LazyListState so the screen-level wheel handler can scroll the list
     *  while the overlay is open. */
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPick: (Int) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    /** Open the per-card context menu (move-to-page, remove). Surfaced by the
     *  '…' chip on each row — replaces the prior pair of inline '✕' + long-press
     *  affordances. Callback receives the row's index; the screen resolves that
     *  to an entity_id and shows [CardContextMenu]. */
    onOpenMenu: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "JUMP TO", style = R1.sectionHeader, color = R1.Ink)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${currentIndex + 1} / ${cards.size}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Text(
                text = "TAP JUMP · LONG-PRESS DRAG · '…' MENU · WHEEL SCROLLS",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            // On open, snap the list so the current card is roughly centred — on a
            // 30-card deck the user would otherwise have to wheel down to find it.
            // Keyed on currentIndex so a re-open after a deck swap also re-centres.
            androidx.compose.runtime.LaunchedEffect(currentIndex) {
                val target = (currentIndex - 2).coerceAtLeast(0)
                listState.scrollToItem(target)
            }
            com.github.itskenny0.r1ha.ui.components.DragReorderColumn(
                items = cards,
                keyOf = { it.id.value },
                onReorder = onReorder,
                modifier = Modifier.fillMaxSize(),
                listState = listState,
            ) { card, dragHandle, isDragging ->
                val idx = cards.indexOf(card)
                JumpRow(
                    index = idx,
                    name = card.friendlyName,
                    domainPrefix = card.id.domain.prefix.uppercase(),
                    isActive = idx == currentIndex,
                    isDragging = isDragging,
                    onClick = { onPick(idx) },
                    onOpenMenu = { onOpenMenu(idx) },
                    dragHandle = dragHandle,
                )
            }
        }
    }
}

/** Local alias for the foundation verticalScroll modifier so the picker call site
 *  reads cleanly without a fully-qualified Modifier.then() dance. */
private fun Modifier.androidxVerticalScroll(
    state: androidx.compose.foundation.ScrollState,
): Modifier = this.then(verticalScroll(state))

@Composable
private fun JumpRow(
    index: Int,
    name: String,
    domainPrefix: String,
    isActive: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    dragHandle: Modifier,
) {
    // Drag-handle modifier wraps the whole row so the user can long-press anywhere
    // on the row to grab it. r1Pressable for the tap-to-jump action sits on top —
    // single tap fires onClick, long-press promotes to drag. The '…' chip on the
    // right opens the per-card context menu (move-to-page, remove); its own
    // r1Pressable absorbs the tap so it doesn't fall through to the row jump.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(R1.ShapeS)
            .background(
                when {
                    isDragging -> R1.AccentWarm.copy(alpha = 0.65f)
                    isActive -> R1.AccentWarm
                    else -> R1.SurfaceMuted
                },
            )
            .then(dragHandle)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%2d".format(index + 1),
                style = R1.labelMicro,
                color = if (isActive) R1.Bg else R1.InkMuted,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = R1.body,
                    color = if (isActive) R1.Bg else R1.Ink,
                    maxLines = 2,
                )
                Text(
                    text = domainPrefix,
                    style = R1.labelMicro,
                    color = if (isActive) R1.Bg.copy(alpha = 0.7f) else R1.InkSoft,
                )
            }
            if (isActive) {
                Text(text = "●", style = R1.labelMicro, color = R1.Bg)
                Spacer(Modifier.width(8.dp))
            }
            // '…' chip — opens the per-card context menu. Replaces the previous
            // inline '✕' remove button; the menu now holds every per-card action
            // (move-to-page + remove) in one place so the row stays clean. Same
            // sizing as the old remove chip so muscle memory carries over.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.Bg.copy(alpha = if (isActive) 0.4f else 0.7f))
                    .r1Pressable(onClick = onOpenMenu)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "…",
                    style = R1.labelMicro,
                    color = if (isActive) R1.Bg else R1.InkSoft,
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
private fun VerticalPagePip(count: Int, current: Int, onClick: (() -> Unit)? = null) {
    val trackHeight = 22.dp
    val thumbHeight = 6.dp
    // Clamp target to [0, 1] — `current` can momentarily exceed `count - 1`
    // when a deck shrinks under the pager (e.g., between observeFavorites
    // emissions). Without the clamp targetFrac > 1 and the spring
    // animation's overshoot landed negative on the *other* side too.
    val targetFrac = if (count <= 1) 0f
        else (current.toFloat() / (count - 1).toFloat()).coerceIn(0f, 1f)
    // CRITICAL: use DampingRatioNoBouncy (was LowBouncy) so the spring
    // never overshoots BELOW 0. The bouncy spring would briefly visit
    // ~-0.05 during settle — and the displayed fraction is fed into
    // .padding(top = travel * animatedFrac), which Compose hard-throws
    // on with IllegalArgumentException('Padding must be non-negative').
    // Confirmed by a user crash trace at r1ha-2026.05.14.1741. Pair with
    // a defensive .coerceIn at the use site so any future change to the
    // animation spec can't reintroduce the bug.
    val animatedFrac by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "r1-pip-thumb",
    )
    Row(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.Bg.copy(alpha = 0.75f))
            // Pressable applied on the existing pill rather than a wrapping Box so
            // the tap target follows the intrinsic width (which contains the counter
            // text). Wrapping in a fixed Box.size(...) clipped "1/30" to two lines.
            .let { m -> if (onClick != null) m.r1Pressable(onClick = onClick) else m }
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
            // Thumb — offset down by the animated fraction of available travel.
            // Critically: use Modifier.offset (not .padding) for the dynamic Dp.
            // Modifier.padding throws IllegalArgumentException on negative
            // values, and any spring overshoot or stale arithmetic that
            // briefly visits negative territory would crash the whole
            // composition with 'Padding must be non-negative'.
            // Modifier.offset accepts any Dp (positive, negative, or zero)
            // and just translates the layout — never throws. SwitchCard's
            // ON/OFF thumb hit the same issue and adopted .offset for the
            // same reason; same pattern applies here.
            val travel = trackHeight - thumbHeight
            val safeFrac = animatedFrac.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = travel * safeFrac)
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

