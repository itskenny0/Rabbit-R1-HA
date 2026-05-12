package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun FavoritesPickerScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val vm: FavoritesPickerViewModel = viewModel(
        factory = FavoritesPickerViewModel.factory(repo = haRepository, settings = settings)
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "FAVOURITES", onBack = onBack)

        // Filter chip row — always visible (so the user can switch filters from any
        // state). Sits between the topbar and the list so it stays pinned while the list
        // scrolls underneath. Hidden during the initial-load spinner; not much sense
        // showing filters before we know what's available.
        if (!ui.loading && ui.error == null) {
            FilterChipRow(
                selected = ui.filter,
                counts = ui.countsByFilter,
                onSelect = { vm.setFilter(it) },
            )
        }

        when {
            ui.loading -> CenteredLoading()
            ui.error != null -> ErrorState(message = ui.error ?: "Error")
            ui.rows.isEmpty() -> FilteredEmptyState(filter = ui.filter)
            else -> ChannelList(
                rows = ui.rows,
                listState = listState,
                onToggle = { vm.toggle(it) },
                onMoveUp = { vm.moveUp(it) },
                onMoveDown = { vm.moveDown(it) },
            )
        }
    }
}

/**
 * Horizontal-scroll row of filter chips. Each chip shows the filter label + a tiny count
 * suffix (e.g. "LIGHTS · 7") so the user can see at a glance which filters have entries.
 * Selected chip is filled with the accent colour; unselected chips are hairline-bordered.
 */
@Composable
private fun FilterChipRow(
    selected: PickerFilter,
    counts: Map<PickerFilter, Int>,
    onSelect: (PickerFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerFilter.entries.forEach { filter ->
            val count = counts[filter] ?: 0
            // Hide chips with zero matches (except ALL/FAVS which always show) — keeps
            // the row tight on installs with only a handful of domains.
            if (count == 0 && filter != PickerFilter.ALL && filter != PickerFilter.FAVS) {
                return@forEach
            }
            val isSelected = filter == selected
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) R1.AccentWarm else R1.Bg)
                    .then(
                        if (isSelected) Modifier
                        else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                    )
                    .r1Pressable(onClick = { onSelect(filter) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${filter.label} · $count",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun FilteredEmptyState(filter: PickerFilter) {
    // Three flavours of "nothing here": no entities at all, filter pruned them all, or
    // the favourites-only view with no favourites set yet. Each gets a short hint.
    val (heading, body) = when (filter) {
        PickerFilter.ALL -> "NO CONTROLLABLE ENTITIES" to
            "Home Assistant didn't return anything we know how to drive — no lights,\nswitches, scenes, or sensors."
        PickerFilter.FAVS -> "NO FAVOURITES YET" to
            "Pick a chip above to start browsing, then tap an entity to favourite it."
        else -> "NONE IN THIS FILTER" to "Tap ALL above to see every entity."
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(heading, style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(8.dp))
        Text(body, style = R1.body, color = R1.InkMuted)
    }
}

@Composable
private fun CenteredLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.height(16.dp))
            Text("FETCHING ENTITIES…", style = R1.sectionHeader, color = R1.InkMuted)
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ERROR", style = R1.labelMicro, color = R1.StatusRed)
        Spacer(Modifier.height(8.dp))
        Text(message, style = R1.body, color = R1.Ink)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Open Settings → Sign out & reconnect to recover.",
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun ChannelList(
    rows: List<FavoritesPickerViewModel.Row>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
) {
    // favCount used to drive move-arrow enable logic. Pre-computed once per list rather
    // than once per row composition.
    val favCount = rows.count { it.isFavorite }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
        // contentType lets Compose recycle row composables across items rather than
        // throwing away the layout tree for every scroll step. Two contentTypes: one for
        // favourite rows (have move-arrows) and one for non-favourites (don't). Without
        // this hint, every row re-composes from scratch on swap.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
    ) {
        items(
            items = rows,
            key = { it.state.id.value },
            contentType = { if (it.isFavorite) "fav" else "non-fav" },
        ) { row ->
            ChannelRow(
                row = row,
                favCount = favCount,
                onToggle = { onToggle(row.state.id.value) },
                onMoveUp = { onMoveUp(row.state.id.value) },
                onMoveDown = { onMoveDown(row.state.id.value) },
            )
        }
    }
}

@Composable
private fun ChannelRow(
    row: FavoritesPickerViewModel.Row,
    favCount: Int,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = row.state.id.domain
    val domainAccent = domainAccentFor(domain)
    val domainCode = domainLabel(domain)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .r1Pressable(onToggle)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        // ── Left: domain block (coloured tab) + identity ────────────────────────────
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .background(if (row.isFavorite) domainAccent else R1.Hairline),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domainCode, style = R1.labelMicro, color = domainAccent)
                // Tag the row so the user knows what kind of control they'll get when they
                // favourite this entity. ACTION for fire-and-forget (scenes/scripts/buttons),
                // SENSOR for read-only sensors, ON/OFF for on-off-only switches, and silent
                // for scalar entities (the percent control is implicit from the domain).
                val tag = when {
                    row.state.id.domain.isAction -> "TRIGGER"
                    row.state.id.domain == Domain.SENSOR -> "READING"
                    !row.state.supportsScalar -> "ON/OFF"
                    else -> null
                }
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "· $tag", style = R1.labelMicro, color = R1.InkMuted)
                }
                if (row.isFavorite && row.orderIndex != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "·",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "POS ${row.orderIndex + 1}",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.state.friendlyName,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
            )
            Text(
                text = row.state.id.value,
                style = R1.numeralS,
                color = R1.InkMuted,
                maxLines = 1,
            )
        }

        // ── Right: move arrows (only for favourites) + selection state ──────────────
        // Plain if/else instead of AnimatedVisibility — the fade-in/out costs a measure
        // pass per row per state change, and with 50+ entities scrolling that snowballs.
        // The arrows appearing/disappearing on favourite toggle is fine without animation;
        // the SelectBox itself is the focal point of the state change anyway.
        if (row.isFavorite) {
            val canMoveUp = row.orderIndex != null && row.orderIndex > 0
            val canMoveDown = row.orderIndex != null && row.orderIndex < favCount - 1
            MoveChevron(
                onClick = onMoveUp,
                enabled = canMoveUp,
                direction = ChevronDirection.Up,
                description = "Move up",
            )
            MoveChevron(
                onClick = onMoveDown,
                enabled = canMoveDown,
                direction = ChevronDirection.Down,
                description = "Move down",
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectBox(selected = row.isFavorite, onClick = onToggle, accent = domainAccent)
    }
}

@Composable
private fun MoveChevron(
    onClick: () -> Unit,
    enabled: Boolean,
    direction: ChevronDirection,
    description: String,
) {
    // 32dp tap target with the chevron centred. We attach the contentDescription to the
    // outer Box (Chevron itself is a Canvas with no built-in semantic role) so TalkBack
    // still reads "Move up" / "Move down" even though we dropped Material's IconButton.
    Box(
        modifier = Modifier
            .size(32.dp)
            .semantics { contentDescription = description }
            .then(if (enabled) Modifier.r1Pressable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(
            direction = direction,
            size = 14.dp,
            tint = if (enabled) R1.InkSoft else R1.Hairline,
        )
    }
}

/**
 * Bespoke selection box — much more clearly a "patch slot is selected" indicator than
 * Material 3's stock Checkbox. Empty hairline-bordered square when unselected, accent-filled
 * square with a tick when selected. Uses a proper [border] modifier (rather than the previous
 * two-tone background trick) so the unselected state reads as a crisp 1dp outline on the
 * R1's tiny display rather than a near-invisible darker square.
 */
@Composable
private fun SelectBox(selected: Boolean, onClick: () -> Unit, accent: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(R1.ShapeS)
            .background(if (selected) accent else R1.Bg)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, R1.InkMuted, R1.ShapeS),
            )
            .r1Pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(text = "✓", style = R1.labelMicro, color = R1.Bg)
        }
    }
}

private fun domainAccentFor(domain: Domain): Color = when (domain) {
    Domain.LIGHT, Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
    Domain.CLIMATE, Domain.BUTTON -> R1.AccentWarm
    Domain.FAN, Domain.SCENE -> R1.AccentGreen
    Domain.COVER, Domain.LOCK -> R1.AccentNeutral
    Domain.MEDIA_PLAYER, Domain.HUMIDIFIER, Domain.SCRIPT, Domain.SENSOR -> R1.AccentCool
    Domain.BINARY_SENSOR -> R1.AccentNeutral
}

private fun domainLabel(domain: Domain): String = when (domain) {
    Domain.LIGHT -> "LIGHT"
    Domain.FAN -> "FAN"
    Domain.COVER -> "COVER"
    Domain.MEDIA_PLAYER -> "MEDIA"
    Domain.SWITCH -> "SWITCH"
    Domain.INPUT_BOOLEAN -> "TOGGLE"
    Domain.AUTOMATION -> "AUTOMATION"
    Domain.LOCK -> "LOCK"
    Domain.HUMIDIFIER -> "HUMIDIFIER"
    Domain.CLIMATE -> "CLIMATE"
    Domain.SCENE -> "SCENE"
    Domain.SCRIPT -> "SCRIPT"
    Domain.BUTTON -> "BUTTON"
    Domain.SENSOR -> "SENSOR"
    Domain.BINARY_SENSOR -> "DETECTOR"
}
