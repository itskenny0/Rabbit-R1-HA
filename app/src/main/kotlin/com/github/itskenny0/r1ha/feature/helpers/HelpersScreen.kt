package com.github.itskenny0.r1ha.feature.helpers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Helpers browser — mirrors HA's frontend Helpers configuration panel
 * (Settings → Devices & Services → Helpers).
 *
 * Each helper domain gets its own per-row control archetype:
 *   - input_boolean → ON / OFF chip (tap toggles)
 *   - input_number → −/+ stepper with current value + unit
 *   - counter → −/+ + RESET (counter only steps by the configured `step`)
 *   - input_select → tap to cycle through options (long-press shows full list — future)
 *   - input_text / input_datetime → read-only value display (editing
 *     these on a wheel-input device isn't great UX; we display only)
 *   - input_button → PRESS chip
 *   - timer → state label + START / PAUSE / CANCEL pills
 *
 * The screen never tries to be a full editor; for that the user has the
 * web UI. This is the at-a-glance "what helpers do I have, can I poke
 * them?" surface — matches HA Companion's helpers list parity item.
 */
@Composable
fun HelpersScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: HelpersViewModel = viewModel(factory = HelpersViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "HELPERS", onBack = onBack)
        BucketChips(current = ui.bucket, counts = ui.counts, onSelect = { vm.setBucket(it) })
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
        when {
            ui.loading && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Helpers load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No helpers defined — add them under Settings → " +
                        "Devices & Services → Helpers in HA's web UI.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            ui.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                    else "No helpers in '${ui.bucket.label}'.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = ui.entries, key = { it.id.value }) { entry ->
                        HelperRow(entry = entry, vm = vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun BucketChips(
    current: HelpersViewModel.Bucket,
    counts: Map<HelpersViewModel.Bucket, Int>,
    onSelect: (HelpersViewModel.Bucket) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HelpersViewModel.Bucket.entries.forEach { bucket ->
            val count = counts[bucket] ?: 0
            // Hide empty per-kind chips (except ALL) so the strip stays
            // tight on small installs.
            if (count == 0 && bucket != HelpersViewModel.Bucket.ALL) return@forEach
            val active = bucket == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(bucket) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${bucket.label} · $count",
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "kitchen, away, …",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(28.dp).r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun HelperRow(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.kind.name,
                style = R1.labelMicro,
                color = accentForKind(entry.kind),
            )
        }
        Text(
            text = entry.id.value,
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 1,
        )
        Spacer(Modifier.size(6.dp))
        // Per-kind control row — branches on entry.kind so each domain
        // gets the affordance that fits HA's native semantic.
        when (entry.kind) {
            HelpersViewModel.Kind.BOOLEAN -> BooleanControl(entry, vm)
            HelpersViewModel.Kind.NUMBER -> NumberControl(entry, vm)
            HelpersViewModel.Kind.COUNTER -> CounterControl(entry, vm)
            HelpersViewModel.Kind.SELECT -> SelectControl(entry, vm)
            HelpersViewModel.Kind.TEXT -> ReadOnlyValue(entry.state)
            HelpersViewModel.Kind.DATETIME -> ReadOnlyValue(entry.state)
            HelpersViewModel.Kind.BUTTON -> ButtonControl(entry, vm)
            HelpersViewModel.Kind.TIMER -> TimerControl(entry, vm)
            HelpersViewModel.Kind.UNKNOWN -> ReadOnlyValue(entry.state)
        }
    }
}

@Composable
private fun BooleanControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val isOn = entry.state.equals("on", ignoreCase = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (isOn) R1.AccentGreen.copy(alpha = 0.22f) else R1.Bg)
                .border(
                    1.dp,
                    if (isOn) R1.AccentGreen.copy(alpha = 0.5f) else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable(onClick = { vm.toggleBoolean(entry) })
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (isOn) "ON" else "OFF",
                style = R1.body,
                color = if (isOn) R1.AccentGreen else R1.InkSoft,
            )
        }
    }
}

@Composable
private fun NumberControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val value = entry.numericValue
    val step = entry.step ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepPill(label = "−", onClick = {
            if (value != null) vm.setNumber(entry, (value - step).coerceAtLeast(entry.min ?: Double.NEGATIVE_INFINITY))
        })
        Spacer(Modifier.width(8.dp))
        // Live value display — formatted as integer when the step is
        // whole, otherwise as a one-decimal float so 0.5° helpers
        // don't get rounded to an unhelpful integer.
        val formatted = when {
            value == null -> entry.state
            step % 1.0 == 0.0 -> "${value.toInt()}"
            else -> "%.1f".format(value)
        }
        val withUnit = if (entry.unit.isNullOrBlank()) formatted else "$formatted ${entry.unit}"
        Text(text = withUnit, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
        StepPill(label = "+", onClick = {
            if (value != null) vm.setNumber(entry, (value + step).coerceAtMost(entry.max ?: Double.POSITIVE_INFINITY))
        })
    }
}

@Composable
private fun CounterControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val value = entry.numericValue
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepPill(label = "−", onClick = { vm.counterDecrement(entry) })
        Spacer(Modifier.width(8.dp))
        Text(
            text = value?.toInt()?.toString() ?: entry.state,
            style = R1.body,
            color = R1.Ink,
            modifier = Modifier.weight(1f),
        )
        StepPill(label = "+", onClick = { vm.counterIncrement(entry) })
        Spacer(Modifier.width(6.dp))
        // RESET is destructive-ish (loses the count) so we render it
        // in StatusAmber to flag the secondary nature.
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = { vm.counterReset(entry) })
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(text = "RESET", style = R1.labelMicro, color = R1.StatusAmber)
        }
    }
}

@Composable
private fun SelectControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    // Tap cycles to the next option — keeps the row compact. The
    // current option is highlighted; HA's select dropdown UX doesn't
    // translate well to the R1's portrait display.
    val options = entry.options
    val currentIdx = options.indexOf(entry.state).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.AccentWarm.copy(alpha = 0.18f))
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                .r1Pressable(onClick = {
                    if (options.isNotEmpty()) {
                        val next = options[(currentIdx + 1) % options.size]
                        vm.selectOption(entry, next)
                    }
                })
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = entry.state, style = R1.body, color = R1.AccentWarm, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${currentIdx + 1} / ${options.size.coerceAtLeast(1)}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun ButtonControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.AccentGreen.copy(alpha = 0.18f))
            .border(1.dp, R1.AccentGreen.copy(alpha = 0.5f), R1.ShapeS)
            .r1Pressable(onClick = { vm.pressButton(entry) })
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = "PRESS", style = R1.body, color = R1.AccentGreen)
    }
}

@Composable
private fun TimerControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = when (entry.state.lowercase()) {
            "active" -> "RUNNING" to R1.AccentGreen
            "paused" -> "PAUSED" to R1.StatusAmber
            "idle" -> "IDLE" to R1.InkSoft
            else -> entry.state.uppercase() to R1.InkSoft
        }
        Text(text = label, style = R1.labelMicro, color = color, modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(6.dp))
        // Show either the static remaining string (paused) or a
        // ticking countdown (active). Falls through to plain text for
        // idle timers.
        if (entry.state.equals("paused", ignoreCase = true) && !entry.remaining.isNullOrBlank()) {
            Text(text = entry.remaining, style = R1.labelMicro, color = color)
        } else if (entry.state.equals("active", ignoreCase = true)) {
            RelativeTimeLabel(at = entry.finishesAt, color = color, style = R1.labelMicro)
        } else if (!entry.remaining.isNullOrBlank()) {
            Text(text = entry.remaining, style = R1.labelMicro, color = R1.InkMuted)
        }
        Spacer(Modifier.weight(1f))
        val isActive = entry.state.equals("active", ignoreCase = true)
        val isIdle = entry.state.equals("idle", ignoreCase = true)
        // START / PAUSE swap based on the live timer state; CANCEL is
        // a constant red secondary action.
        StepPill(
            label = if (isActive) "PAUSE" else "START",
            onClick = {
                vm.timerService(entry, if (isActive) "pause" else "start")
            },
        )
        if (!isIdle) {
            Spacer(Modifier.width(6.dp))
            StepPill(label = "✕", onClick = { vm.timerService(entry, "cancel") })
        }
    }
}

@Composable
private fun StepPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = R1.body, color = R1.InkSoft)
    }
}

@Composable
private fun ReadOnlyValue(value: String) {
    Text(
        text = value,
        style = R1.body,
        color = R1.Ink,
        maxLines = 2,
    )
}

private fun accentForKind(kind: HelpersViewModel.Kind): androidx.compose.ui.graphics.Color =
    when (kind) {
        HelpersViewModel.Kind.BOOLEAN -> R1.AccentWarm
        HelpersViewModel.Kind.NUMBER, HelpersViewModel.Kind.COUNTER -> R1.AccentCool
        HelpersViewModel.Kind.SELECT, HelpersViewModel.Kind.TEXT,
        HelpersViewModel.Kind.DATETIME -> R1.AccentNeutral
        HelpersViewModel.Kind.BUTTON -> R1.AccentGreen
        HelpersViewModel.Kind.TIMER -> R1.AccentWarm
        HelpersViewModel.Kind.UNKNOWN -> R1.InkMuted
    }
