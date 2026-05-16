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
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Active-page favourites set — used to swap the star glyph for
    // entities that are already favourited so the user doesn't try to
    // add them a second time (no-op anyway, but the visual feedback
    // closes the loop). Recomputed live as pages/favourites change.
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val activeFavourites = remember(appSettings.activePageId, appSettings.pages) {
        appSettings.pages.firstOrNull { it.id == appSettings.activePageId }
            ?.favorites?.toSet() ?: emptySet()
    }
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) {
        vm.refresh()
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    // Long-press handler — open the entity in HA's web UI (the
    // /lovelace?edit=1&entity_id=… form HA uses internally to focus a
    // specific entity). Falls back to plain /lovelace when server isn't
    // configured.
    fun openInHa(entity: EntityState) {
        scope.launch {
            val server = runCatching { settings.settings.first().server?.url }.getOrNull()
            if (server.isNullOrBlank()) {
                Toaster.error("No HA server configured")
                return@launch
            }
            val url = "${server.trimEnd('/')}/history?entity_id=${entity.id.value}"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { t ->
                R1Log.w("Search", "open-in-HA failed: ${t.message}")
                Toaster.error("No browser to open $url")
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "QUICK SEARCH", onBack = onBack)
        // Domain-bucket filter chips — ALL / CONTROLS / SENSORS / ACTIONS.
        // Tap to narrow without typing. ALL needs a query; the others
        // surface entities even with an empty search field.
        BucketChips(current = ui.bucket, onSelect = { vm.setBucket(it) })
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
            // Failure path — listAllEntities errored. Show the error +
            // hint at recovery (pull-to-refresh or reconnect via
            // Settings).
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Text(
                        text = "Couldn't load entities.",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = ui.error ?: "Unknown error",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Check Settings → Server, or wait for the WS to reconnect.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
            vm.results.isEmpty() && ui.query.isBlank() &&
                ui.bucket == SearchViewModel.Bucket.ALL -> Box(
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
                        text = "Type a name, entity_id, or area to find — or tap a chip above to narrow by kind. Tap a result to fire (scenes / scripts / buttons) or toggle (lights / switches / fans).",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            vm.results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                    else "No ${ui.bucket.name.lowercase()} entities.",
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
                item("__count_header") {
                    Text(
                        text = "${vm.results.size} result${if (vm.results.size == 1) "" else "s"}",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(items = vm.results, key = { it.id.value }) { entity ->
                    SearchResultRow(
                        entity,
                        isFavorite = entity.id.value in activeFavourites,
                        onTap = { vm.activate(entity) },
                        onLongPress = { openInHa(entity) },
                        onFavorite = { vm.addToFavorites(entity.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketChips(
    current: SearchViewModel.Bucket,
    onSelect: (SearchViewModel.Bucket) -> Unit,
) {
    val items = listOf(
        SearchViewModel.Bucket.ALL to "ALL",
        SearchViewModel.Bucket.CONTROLS to "CONTROLS",
        SearchViewModel.Bucket.SENSORS to "SENSORS",
        SearchViewModel.Bucket.ACTIONS to "ACTIONS",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((bucket, label) in items) {
            val active = bucket == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(bucket) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    entity: EntityState,
    isFavorite: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            // Tap = the domain-appropriate action (fire/press/toggle/info).
            // Long-press = open the entity's /history view in HA's web UI.
            .r1RowPressable(onTap = onTap, onLongPress = onLongPress)
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
        // Star tap target — adds the entity to the active page's
        // favourites. Separate from the row's main r1RowPressable so a
        // tap on the star doesn't fire the entity's action. Filled glyph
        // + accent tint when the entity is already on the active page,
        // so the user doesn't fruitlessly re-tap.
        Box(
            modifier = Modifier
                .size(32.dp)
                .r1Pressable(onClick = onFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = R1.body,
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(4.dp))
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
