package com.github.itskenny0.r1ha.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Universal Search surface — search every HA entity by name / id /
 * area; tap to fire (scenes / scripts / buttons) or toggle (lights /
 * switches / etc.). Read-only sensors and other non-toggle entities
 * surface a detail toast on tap rather than dispatching anything.
 *
 * Empty query renders an instructional placeholder rather than
 * dumping the entire entity registry — on a big install that's
 * thousands of rows which would be slow to scroll and not what the
 * user wants anyway.
 */
@Composable
fun SearchScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) {
        vm.refresh()
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "QUICK SEARCH", onBack = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "FIND", style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.padding(end = 8.dp))
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = ui.query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = "kitchen light, scene, .door, ...",
                    monospace = false,
                    focusRequester = focus,
                )
            }
            if (ui.query.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(28.dp).r1Pressable({ vm.setQuery("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
        }
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
            ui.query.isBlank() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Text(
                        text = "${ui.all.size} entities indexed.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "Type a name, entity_id, or area to find. Tap a result to fire (scenes / scripts / buttons) or toggle (lights / switches / fans).",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            ui.results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matches for '${ui.query}'.",
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
                items(items = ui.results, key = { it.id.value }) { entity ->
                    SearchResultRow(entity, onTap = { vm.activate(entity) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(entity: EntityState, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entity.id.domain.prefix.uppercase().take(6),
            style = R1.labelMicro,
            color = accentFor(entity.id.domain),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.friendlyName, style = R1.body, color = R1.Ink, maxLines = 1)
            val stateLine = buildString {
                append(entity.id.value)
                entity.rawState?.let { append("  ·  ").append(it) }
                entity.area?.let { append("  ·  ").append(it) }
            }
            Text(text = stateLine, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        // Action affordance hint — what tap will do.
        val actionLabel = when (entity.id.domain) {
            Domain.SCENE, Domain.SCRIPT -> "FIRE"
            Domain.BUTTON, Domain.INPUT_BUTTON -> "PRESS"
            Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
            Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
            Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
            Domain.VALVE -> if (entity.isOn) "OFF" else "ON"
            else -> "INFO"
        }
        Text(text = actionLabel, style = R1.labelMicro, color = R1.AccentWarm)
    }
}

private fun accentFor(domain: Domain): androidx.compose.ui.graphics.Color = when (domain) {
    Domain.LIGHT, Domain.FAN, Domain.MEDIA_PLAYER, Domain.SWITCH, Domain.INPUT_BOOLEAN -> R1.AccentWarm
    Domain.SENSOR, Domain.BINARY_SENSOR, Domain.COVER, Domain.VALVE, Domain.NUMBER,
    Domain.INPUT_NUMBER -> R1.AccentCool
    Domain.SCENE, Domain.SCRIPT, Domain.AUTOMATION, Domain.BUTTON,
    Domain.INPUT_BUTTON -> R1.AccentGreen
    else -> R1.AccentNeutral
}
