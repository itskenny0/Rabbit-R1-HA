package com.github.itskenny0.r1ha.feature.devmenu

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AdvancedSettings
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.R1LogBuffer
import com.github.itskenny0.r1ha.feature.settings.SettingsViewModel
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Dev menu — a single scrollable surface with every advanced setting + the in-memory
 * log buffer viewer. Aimed at the user who already knows what they're doing; minimal
 * hand-holding. Every toggle / number picker writes through [SettingsViewModel] to
 * the same DataStore as the regular settings; the AdvancedSettings struct is
 * persisted as a single JSON blob so adding a new field doesn't require a
 * preferences migration.
 */
@Composable
fun DevMenuScreen(
    settings: SettingsRepository,
    tokens: com.github.itskenny0.r1ha.core.prefs.TokenStore,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val state by vm.state.collectAsStateWithLifecycle(initialValue = AppSettings())
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val advanced = state.advanced

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "DEV MENU", onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            // ── Service-call timing ─────────────────────────────────────────────────
            item { Section("SERVICE CALL TIMING") }
            item {
                IntStepperRow(
                    label = "Debounce (ms)",
                    subtitle = "Trailing-edge silence window before the wire call fires.",
                    value = advanced.serviceDebounceMs,
                    step = 10,
                    range = 10..500,
                    onSet = { v -> vm.updateAdvanced { it.copy(serviceDebounceMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Max interval (ms)",
                    subtitle = "Force-fire after this much continuous in-flight gesture.",
                    value = advanced.serviceMaxIntervalMs,
                    step = 25,
                    range = 50..1000,
                    onSet = { v -> vm.updateAdvanced { it.copy(serviceMaxIntervalMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Wheel rate window (ms)",
                    subtitle = "Sliding window used to compute events/sec for the acceleration ramp.",
                    value = advanced.wheelRateWindowMs,
                    step = 25,
                    range = 50..1000,
                    onSet = { v -> vm.updateAdvanced { it.copy(wheelRateWindowMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Nav step cap",
                    subtitle = "Max cards per detent during a fast wheel spin.",
                    value = advanced.navAccelCap,
                    step = 1,
                    range = 1..20,
                    onSet = { v -> vm.updateAdvanced { it.copy(navAccelCap = v) } },
                )
            }
            item { SectionDivider() }

            // ── Network ─────────────────────────────────────────────────────────────
            item { Section("NETWORK") }
            item {
                IntStepperRow(
                    label = "REST timeout (s)",
                    subtitle = "Per-request timeout for /api/states and /api/history.",
                    value = advanced.restTimeoutSec,
                    step = 5,
                    range = 5..120,
                    onSet = { v -> vm.updateAdvanced { it.copy(restTimeoutSec = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Reconnect backoff cap (s)",
                    subtitle = "Maximum seconds between WS reconnect attempts.",
                    value = advanced.reconnectBackoffMaxSec,
                    step = 5,
                    range = 5..300,
                    onSet = { v -> vm.updateAdvanced { it.copy(reconnectBackoffMaxSec = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "WS ping interval (s)",
                    subtitle = "0 = OkHttp default (30 s). Increase on flaky networks if HA drops the WS.",
                    value = advanced.wsPingIntervalSec,
                    step = 5,
                    range = 0..300,
                    onSet = { v -> vm.updateAdvanced { it.copy(wsPingIntervalSec = v) } },
                )
            }
            item { SectionDivider() }

            // ── Sensor / history ────────────────────────────────────────────────────
            item { Section("HISTORY") }
            item {
                IntStepperRow(
                    label = "Sensor history hours",
                    subtitle = "Span fetched per sensor card on open. Smaller = faster initial render.",
                    value = advanced.sensorHistoryHours,
                    step = 1,
                    range = 1..168,
                    onSet = { v -> vm.updateAdvanced { it.copy(sensorHistoryHours = v) } },
                )
            }
            item { SectionDivider() }

            // ── Toggles ─────────────────────────────────────────────────────────────
            item { Section("BEHAVIOUR FLAGS") }
            item {
                DevSwitchRow(
                    label = "Keep log buffer",
                    subtitle = "Append R1Log entries to a 500-row ring for the viewer below.",
                    checked = advanced.keepLogBuffer,
                    onChange = { v -> vm.updateAdvanced { it.copy(keepLogBuffer = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Strict entity decode",
                    subtitle = "Drop rows that fail to construct an EntityState instead of logging and skipping. Useful for spotting decoder issues — sets the floor lower so problems surface.",
                    checked = advanced.strictEntityDecode,
                    onChange = { v -> vm.updateAdvanced { it.copy(strictEntityDecode = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Pin optimistic",
                    subtitle = "Never auto-clear the optimistic UI override. Diagnostic for the reconcile path.",
                    checked = advanced.pinOptimistic,
                    onChange = { v -> vm.updateAdvanced { it.copy(pinOptimistic = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Slow pager transitions",
                    subtitle = "Stretch the swipe animation by 1.4× — makes the deck feel more physical.",
                    checked = advanced.slowPagerTransitions,
                    onChange = { v -> vm.updateAdvanced { it.copy(slowPagerTransitions = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Show entity_id on cards",
                    subtitle = "Render the HA entity_id under the friendly name. Useful for debugging.",
                    checked = advanced.showEntityIdOnCards,
                    onChange = { v -> vm.updateAdvanced { it.copy(showEntityIdOnCards = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Show debug strip",
                    subtitle = "Per-card debug strip — cached percent, supportsScalar, rawState.",
                    checked = advanced.showDebugStripOnCards,
                    onChange = { v -> vm.updateAdvanced { it.copy(showDebugStripOnCards = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Persist cache to disk",
                    subtitle = "Snapshot the HA entity cache on every change so cold starts paint cards from disk before the WebSocket connects. Off by default — needs an app restart to take effect.",
                    checked = advanced.persistCacheToDisk,
                    onChange = { v -> vm.updateAdvanced { it.copy(persistCacheToDisk = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose service calls",
                    subtitle = "Log every HA service call payload via R1Log.i (surface in toast if level high enough).",
                    checked = advanced.verboseServiceCalls,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseServiceCalls = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose HTTP",
                    subtitle = "Log REST request/response details. Heavy.",
                    checked = advanced.verboseHttp,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseHttp = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose WebSocket",
                    subtitle = "Log every inbound/outbound WS frame at DEBUG. Very chatty.",
                    checked = advanced.verboseWebSocket,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseWebSocket = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Skip preflight refresh",
                    subtitle = "Don't call TokenRefresher.ensureFresh() before REST. Tests the 401-retry path.",
                    checked = advanced.skipPreflightRefresh,
                    onChange = { v -> vm.updateAdvanced { it.copy(skipPreflightRefresh = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Keep optimistic on failure",
                    subtitle = "Don't roll back the optimistic UI override when HA rejects a service call.",
                    checked = advanced.keepOptimisticOnFailure,
                    onChange = { v -> vm.updateAdvanced { it.copy(keepOptimisticOnFailure = v) } },
                )
            }
            item { SectionDivider() }

            // ── Log viewer ──────────────────────────────────────────────────────────
            item { Section("APP LOG") }
            item { LogViewer() }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/**
 * Process-scope log viewer — taps the in-memory ring [R1LogBuffer] and renders the
 * last N entries newest → oldest. Tapping an entry expands it with the stack trace
 * (when present); tapping CLEAR empties the buffer.
 */
@Composable
private fun LogViewer() {
    // Subscribe to the bump-on-append flag so the viewer recomposes when new
    // entries land. The snapshot itself is read inline.
    val tick by R1LogBuffer.updates.collectAsStateWithLifecycle()
    val expanded = remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val entries = remember(tick) { R1LogBuffer.snapshot().reversed() }
    // SAF launcher for the EXPORT button — writes the log buffer as a plain
    // text file the user can share for diagnostics. Held outside the button's
    // onClick so it's stable across recompositions (the activity-result
    // contract registers once per composition).
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingExport = remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: android.net.Uri? ->
        val blob = pendingExport.value
        pendingExport.value = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open output stream")
            com.github.itskenny0.r1ha.core.util.Toaster.show("Logs saved")
        }.onFailure { t ->
            R1Log.w("DevMenu.exportLogs", "write failed: ${t.message}")
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Log export failed",
                fullText = "Couldn't write the log file.\n\n${t.message ?: t.toString()}",
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${entries.size} entries (newest first)",
                style = R1.body,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { R1LogBuffer.clear() })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("CLEAR", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        R1Log.i("DevMenu", "test-INFO ping from dev menu — verify the log viewer + toasts route correctly")
                        R1Log.w("DevMenu", "test-WARN ping from dev menu")
                        R1Log.e("DevMenu", "test-ERROR ping from dev menu", IllegalStateException("synthetic"))
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("PING", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // Clear the in-memory + on-disk album-cover cache. Useful when a
            // user's HA media_player_proxy URL changes and the cached bytes
            // would otherwise paint a stale cover until eviction. Pushes a
            // toast so the user sees it took effect (force-shown so it lands
            // even with diagnostic toasts off — this is a deliberate action).
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        com.github.itskenny0.r1ha.ui.components.AsyncBitmapCache.clear()
                        R1Log.i("DevMenu", "AsyncBitmapCache cleared")
                        com.github.itskenny0.r1ha.core.util.Toaster.error("Image cache cleared")
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("IMG CACHE", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // EXPORT — drop the entire log buffer to a SAF-picked file the
            // user can share for diagnostics. Plain text (one line per
            // entry) so non-developers can read it; timestamps in ISO
            // format for grep-friendliness; throwables appended after their
            // message with indented stack frames.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        val now = R1LogBuffer.snapshot()
                        val blob = buildString {
                            append("R1HA log export · ")
                            append(java.time.Instant.now().toString())
                            append('\n')
                            append("App ${com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME} (${com.github.itskenny0.r1ha.BuildConfig.VERSION_CODE})\n")
                            append("${now.size} entries\n\n")
                            for (e in now) {
                                val ts = java.time.Instant.ofEpochMilli(e.timestampMillis).toString()
                                append("[$ts] ${e.level} ${e.tag} — ${e.message}\n")
                                e.throwable?.let { t ->
                                    append("    ").append(t::class.java.name)
                                    t.message?.let { append(": ").append(it) }
                                    append('\n')
                                    for (line in t.stackTraceToString().lines().take(20)) {
                                        append("    ").append(line).append('\n')
                                    }
                                }
                            }
                        }
                        pendingExport.value = blob
                        val stamp = java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmm",
                            java.util.Locale.US,
                        ).format(java.util.Date())
                        exportLauncher.launch("r1ha-logs-$stamp.txt")
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("EXPORT", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // LAST CRASH — reads the persisted crash report written by the
            // uncaught-exception handler in App.onCreate and surfaces it
            // through the expandable error toast. After-the-fact diagnostics
            // for the most-recent crash; the file persists until overwritten
            // by the next crash, so the user has a window to retrieve it
            // after re-launching.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        val file = java.io.File(context.filesDir, "last_crash.txt")
                        if (!file.exists() || file.length() == 0L) {
                            com.github.itskenny0.r1ha.core.util.Toaster.show("No crash report on disk")
                        } else {
                            val raw = runCatching { file.readText(Charsets.UTF_8) }
                                .getOrElse { "(read failed: ${it.message})" }
                            // Use the gated info toast since this is a
                            // deliberate user action — they want to see the
                            // long text. errorExpandable would render red
                            // which feels wrong for retrospective viewing.
                            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                                shortText = "Last crash · ${raw.lineSequence().firstOrNull()?.take(40) ?: "(empty)"}",
                                fullText = raw,
                            )
                        }
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("LAST CRASH", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
        Spacer(Modifier.height(8.dp))
        entries.forEachIndexed { idx, entry ->
            val isOpen = expanded.value == idx
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(
                        when (entry.level) {
                            R1LogBuffer.Level.E -> R1.StatusRed.copy(alpha = 0.18f)
                            R1LogBuffer.Level.W -> R1.StatusAmber.copy(alpha = 0.18f)
                            R1LogBuffer.Level.I -> R1.SurfaceMuted
                            R1LogBuffer.Level.D -> R1.SurfaceMuted
                        },
                    )
                    .r1Pressable(onClick = { expanded.value = if (isOpen) null else idx })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.level.name,
                        style = R1.labelMicro,
                        color = when (entry.level) {
                            R1LogBuffer.Level.E -> R1.StatusRed
                            R1LogBuffer.Level.W -> R1.StatusAmber
                            else -> R1.InkSoft
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Text(
                    text = entry.message,
                    style = R1.body.copy(fontFamily = FontFamily.Monospace),
                    color = R1.Ink,
                    maxLines = if (isOpen) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (isOpen && entry.throwable != null) {
                    Text(
                        text = entry.throwable.stackTraceToString(),
                        style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                        color = R1.InkMuted,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .background(R1.Hairline)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun DevSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onChange(!checked) })
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (checked) R1.AccentWarm else R1.SurfaceMuted)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                style = R1.labelMicro,
                color = if (checked) R1.Bg else R1.InkSoft,
            )
        }
    }
}

@Composable
private fun IntStepperRow(
    label: String,
    subtitle: String,
    value: Int,
    step: Int,
    range: IntRange,
    onSet: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSet(coerce(value - step, range.first, range.last)) }),
                contentAlignment = Alignment.Center,
            ) { Text("−", style = R1.bodyEmph, color = R1.Ink) }
            Spacer(Modifier.width(6.dp))
            Text(
                text = value.toString(),
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.Ink,
                modifier = Modifier.width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSet(coerce(value + step, range.first, range.last)) }),
                contentAlignment = Alignment.Center,
            ) { Text("+", style = R1.bodyEmph, color = R1.Ink) }
        }
    }
}

private fun coerce(value: Int, low: Int, high: Int): Int =
    if (value < low) low else if (value > high) high else value
