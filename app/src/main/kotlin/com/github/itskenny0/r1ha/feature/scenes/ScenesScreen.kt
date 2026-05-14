package com.github.itskenny0.r1ha.feature.scenes

import androidx.compose.foundation.background
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
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Fast-fire launcher for HA scenes + scripts. Pulls the full entity list
 * via the REST `/api/states` endpoint (same call the favourites picker
 * uses), filters to scene.* / script.*, and renders a dense LazyColumn
 * the user can scroll with the wheel. Tap a row → fires the appropriate
 * service (scene.turn_on for scenes, script.<script_id> for scripts) +
 * shows a brief confirmation toast.
 *
 * Why a dedicated surface: scenes / scripts are the muscle-memory
 * affordances of a HA setup — 'movie night', 'dinner mode', 'all off'.
 * Putting each one as a card on the card stack works but requires
 * scrolling to it. A flat list with a tap-fire interaction is faster.
 */
@Composable
fun ScenesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: ScenesViewModel = viewModel(factory = ScenesViewModel.factory(haRepository))
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
        R1TopBar(title = "SCENES & SCRIPTS", onBack = onBack)
        // Filter chips — ALL / SCENES / SCRIPTS. Tap to switch the visible
        // subset. Counts come from the loaded entity list so users with no
        // scripts (or no scenes) see an empty subset chip rather than a
        // misleading 'ALL' result.
        FilterChips(
            current = ui.filter,
            counts = ui.counts,
            onSelect = { vm.setFilter(it) },
        )
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No scenes or scripts in HA — define them in HA's UI to see them here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = ui.entries, key = { it.id.value }) { entry ->
                    SceneRow(entry, onFire = { vm.fire(entry) })
                }
            }
        }
    }
}

@Composable
private fun SceneRow(entry: ScenesViewModel.Entry, onFire: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onFire)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (entry.kind) {
                ScenesViewModel.Kind.SCENE -> "SCENE"
                ScenesViewModel.Kind.SCRIPT -> "SCRIPT"
            },
            style = R1.labelMicro,
            color = when (entry.kind) {
                ScenesViewModel.Kind.SCENE -> R1.AccentWarm
                ScenesViewModel.Kind.SCRIPT -> R1.AccentCool
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = entry.name, style = R1.body, color = R1.Ink, maxLines = 2)
            Text(
                text = entry.id.value,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilterChips(
    current: ScenesViewModel.Filter,
    counts: Map<ScenesViewModel.Filter, Int>,
    onSelect: (ScenesViewModel.Filter) -> Unit,
) {
    val items = listOf(
        ScenesViewModel.Filter.ALL to "ALL",
        ScenesViewModel.Filter.SCENES to "SCENES",
        ScenesViewModel.Filter.SCRIPTS to "SCRIPTS",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((filter, label) in items) {
            val active = filter == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(filter) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "$label · ${counts[filter] ?: 0}",
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}
