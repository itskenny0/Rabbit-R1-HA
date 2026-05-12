package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

        when {
            ui.loading -> CenteredLoading()
            ui.error != null -> ErrorState(message = ui.error ?: "Error")
            ui.rows.isEmpty() -> EmptyState()
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
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("NO CONTROLLABLE ENTITIES", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Home Assistant didn't return any lights, fans, covers, or media\nplayers with a settable scalar.",
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
    val favCount = rows.count { it.isFavorite }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 6.dp),
    ) {
        items(rows, key = { it.state.id.value }) { row ->
            ChannelRow(
                row = row,
                favCount = favCount,
                onToggle = { onToggle(row.state.id.value) },
                onMoveUp = { onMoveUp(row.state.id.value) },
                onMoveDown = { onMoveDown(row.state.id.value) },
                modifier = Modifier.animateItem(),
            )
        }
        item { Spacer(Modifier.height(48.dp)) }
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
                if (!row.state.supportsScalar) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· ON/OFF",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
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

        // ── Right: move arrows (only for favorites) + selection state ───────────────
        AnimatedVisibility(
            visible = row.isFavorite,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
    Domain.LIGHT, Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.CLIMATE -> R1.AccentWarm
    Domain.FAN -> R1.AccentGreen
    Domain.COVER, Domain.LOCK -> R1.AccentNeutral
    Domain.MEDIA_PLAYER, Domain.HUMIDIFIER -> R1.AccentCool
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
}
