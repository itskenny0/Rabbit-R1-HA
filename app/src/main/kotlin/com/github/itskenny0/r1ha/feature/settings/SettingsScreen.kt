package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    wheelInput: WheelInput,
    onOpenThemePicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(settings = settings, tokens = tokens),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    // SAF launchers for backup export / import. Using CreateDocument / OpenDocument
    // routes through the Android system file picker, so the user can save to the
    // R1's local storage, a USB stick, or any cloud-storage app they have wired
    // up (Drive, Nextcloud, etc.) without us shipping permissions for direct FS
    // access. CreateDocument keeps the chosen MIME type as the file's display
    // type so a downstream viewer can open it; we use application/json so
    // editors recognise the format on a desktop too.
    val context = androidx.compose.ui.platform.LocalContext.current
    // Holds the JSON blob produced by exportBackupBlob until the user picks
    // the destination file via SAF. The launcher reads this when its
    // ActivityResult lands and writes to the picked URI.
    val pendingBackupBlob = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: android.net.Uri? ->
        val blob = pendingBackupBlob.value
        pendingBackupBlob.value = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open output stream")
            com.github.itskenny0.r1ha.core.util.Toaster.show("Backup saved")
        }.onFailure { t ->
            com.github.itskenny0.r1ha.core.util.R1Log.w(
                "Settings.exportBackup", "write failed: ${t.message}",
            )
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Backup save failed",
                fullText = "Couldn't write the backup file.\n\n${t.message ?: t.toString()}",
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("couldn't open input stream")
        }.fold(
            onSuccess = { raw -> vm.importBackupBlob(raw) },
            onFailure = { t ->
                com.github.itskenny0.r1ha.core.util.R1Log.w(
                    "Settings.importBackup", "read failed: ${t.message}",
                )
                com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                    shortText = "Backup read failed",
                    fullText = "Couldn't read the backup file.\n\n${t.message ?: t.toString()}",
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "SETTINGS", onBack = onBack)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            // ── Server ─────────────────────────────────────────────────────────────
            item { Section("SERVER") }
            item {
                InfoRow(
                    label = "URL",
                    value = s.server?.url ?: "(not connected)",
                    mono = true,
                )
            }
            item {
                s.server?.haVersion?.let { InfoRow(label = "HA version", value = it, mono = true) }
            }
            // RECONNECT NOW — force-flush the WS + re-fetch fresh state without
            // touching tokens. Useful when the connection has gone stale (HA
            // restarted, Wi-Fi dropped briefly, etc.) and the user wants live
            // updates back without going through the full sign-out cycle.
            // Outlined variant so it visually pairs with the destructive
            // SIGN-OUT below but doesn't compete for primary attention.
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                ) {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "RECONNECT NOW",
                        onClick = {
                            haRepository.reconnectNow()
                            com.github.itskenny0.r1ha.core.util.Toaster.show("Reconnecting…")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    DangerButton(
                        text = "SIGN OUT & RECONNECT",
                        onClick = { vm.signOut(onSignedOut) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Scroll wheel ───────────────────────────────────────────────────────
            item { Section("SCROLL WHEEL") }
            item {
                LabeledControl(label = "Step size") {
                    SegmentedIntPicker(
                        options = listOf(1, 2, 5, 10),
                        selected = s.wheel.stepPercent,
                        label = { "$it%" },
                        onSelect = { vm.setWheelStep(it) },
                    )
                }
            }
            item {
                SwitchRow(
                    label = "Acceleration",
                    subtitle = "Spin faster to jump further",
                    checked = s.wheel.acceleration,
                    onCheckedChange = { vm.setWheelAcceleration(it) },
                )
            }
            if (s.wheel.acceleration) {
                item {
                    LabeledControl(label = "Acceleration curve") {
                        SegmentedEnumPicker(
                            options = com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.entries,
                            selected = s.wheel.accelerationCurve,
                            label = {
                                when (it) {
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.SUBTLE -> "SUBTLE"
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.MEDIUM -> "MEDIUM"
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.AGGRESSIVE -> "AGGRESSIVE"
                                }
                            },
                            onSelect = { vm.setAccelerationCurve(it) },
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    label = "Invert direction",
                    checked = s.wheel.invertDirection,
                    onCheckedChange = { vm.setWheelInvert(it) },
                )
            }
            item {
                LabeledControl(label = "Key source") {
                    SegmentedEnumPicker(
                        options = WheelKeySource.entries,
                        selected = s.wheel.keySource,
                        label = {
                            when (it) {
                                WheelKeySource.AUTO -> "AUTO"
                                WheelKeySource.DPAD -> "D-PAD"
                                WheelKeySource.VOLUME -> "VOL"
                            }
                        },
                        onSelect = { vm.setWheelKeySource(it) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Card UI ────────────────────────────────────────────────────────────
            item { Section("CARD UI") }
            item {
                LabeledControl(label = "Display mode") {
                    SegmentedEnumPicker(
                        options = DisplayMode.entries,
                        selected = s.ui.displayMode,
                        label = {
                            when (it) {
                                DisplayMode.PERCENT -> "PERCENT"
                                DisplayMode.RAW -> "RAW"
                            }
                        },
                        onSelect = { vm.setDisplayMode(it) },
                    )
                }
            }
            item {
                SwitchRow(
                    label = "Show on/off pill",
                    checked = s.ui.showOnOffPill,
                    onCheckedChange = { vm.setShowOnOffPill(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Show area label",
                    checked = s.ui.showAreaLabel,
                    onCheckedChange = { vm.setShowAreaLabel(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Show position pip",
                    subtitle = "Bar in the chrome that shows current card position",
                    checked = s.ui.showPositionDots,
                    onCheckedChange = { vm.setShowPositionDots(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Hide card hint above current",
                    subtitle = "Solid chrome backdrop covers the previous card's tail",
                    checked = s.ui.hideCardTailAbove,
                    onCheckedChange = { vm.setHideCardTailAbove(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Infinite scroll",
                    subtitle = "Wheel past the last card wraps to the first",
                    checked = s.ui.infiniteScroll,
                    onCheckedChange = { vm.setInfiniteScroll(it) },
                )
            }
            item {
                LabeledControl(label = "Sensor decimals") {
                    SegmentedIntPicker(
                        options = listOf(0, 1, 2, 3, 4),
                        selected = s.ui.maxDecimalPlaces,
                        label = { if (it == 0) "INT" else "$it" },
                        onSelect = { vm.setMaxDecimalPlaces(it) },
                    )
                }
            }
            item {
                LabeledControl(label = "Temperature unit") {
                    SegmentedEnumPicker(
                        options = com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.entries,
                        selected = s.ui.tempUnit,
                        label = {
                            when (it) {
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.AUTO -> "AUTO"
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.CELSIUS -> "°C"
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.FAHRENHEIT -> "°F"
                            }
                        },
                        onSelect = { vm.setTempUnit(it) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Behaviour ──────────────────────────────────────────────────────────
            item { Section("BEHAVIOUR") }
            item {
                SwitchRow(
                    label = "Haptic feedback",
                    checked = s.behavior.haptics,
                    onCheckedChange = { vm.setHaptics(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Keep screen on",
                    checked = s.behavior.keepScreenOn,
                    onCheckedChange = { vm.setKeepScreenOn(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Tap to toggle",
                    subtitle = "Off (default): the whole-card tap is inert so a miss " +
                        "while aiming for the chrome buttons doesn't accidentally turn " +
                        "the entity on. On: tap anywhere on the card to flip it.",
                    checked = s.behavior.tapToToggle,
                    onCheckedChange = { vm.setTapToToggle(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Hide status bar",
                    subtitle = "Swipe down to peek the bar; auto-hides after release",
                    checked = s.behavior.hideStatusBar,
                    onCheckedChange = { vm.setHideStatusBar(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Wheel toggles switches",
                    subtitle = "On (default): wheel-up turns locks, covers, vacuums, plain " +
                        "switches on; wheel-down turns them off. Off: wheel does nothing on " +
                        "those cards — useful if a casual brush is accidentally relocking your door.",
                    checked = s.behavior.wheelTogglesSwitches,
                    onCheckedChange = { vm.setWheelTogglesSwitches(it) },
                )
            }
            item { ToastLogLevelRow(current = s.behavior.toastLogLevel, onSelect = { vm.setToastLogLevel(it) }) }

            item { SectionDivider() }

            // ── Backup & restore ───────────────────────────────────────────────────
            item { Section("BACKUP & RESTORE") }
            item {
                InfoRow(
                    label = "What's included",
                    value = "Server URL · pages · favourites · all settings (no tokens)",
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "EXPORT",
                        onClick = {
                            vm.exportBackupBlob { blob ->
                                pendingBackupBlob.value = blob
                                val stamp = java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmm",
                                    java.util.Locale.US,
                                ).format(java.util.Date())
                                exportLauncher.launch("r1ha-backup-$stamp.json")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "IMPORT",
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f),
                        variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    )
                }
            }

            item { SectionDivider() }

            // ── Appearance ─────────────────────────────────────────────────────────
            item { Section("APPEARANCE") }
            item {
                NavRow(
                    label = "Theme",
                    value = s.theme.name
                        .replace('_', ' ')
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    onClick = onOpenThemePicker,
                )
            }

            item { SectionDivider() }

            item {
                NavRow(label = "About", onClick = onOpenAbout)
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

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
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

/**
 * Horizontal-scroll chip row selecting the in-app toast log threshold. OFF is the
 * default (no diagnostic toasts); WARN is the friendly diagnostic level (failures
 * + decoder drops). Tap a chip to switch.
 */
@Composable
private fun ToastLogLevelRow(
    current: com.github.itskenny0.r1ha.core.prefs.ToastLogLevel,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.ToastLogLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text("Toast log level", style = R1.bodyEmph, color = R1.Ink)
        Text(
            text = "Off (default): no diagnostic toasts. Warn: surface failures and " +
                "decoder drops as tappable expanding toasts — useful for 'where's my " +
                "entity?' on devices without adb. Debug: everything R1Log emits.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
        ) {
            com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.entries.forEach { level ->
                val active = level == current
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(R1.ShapeS)
                        .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                        .r1Pressable({ onSelect(level) })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = level.name,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // r1Pressable instead of bare clickable so the whole row dips on press AND fires
            // a CLOCK_TICK haptic to match the rest of the app. The inner R1Switch ignores
            // the synthetic click here — it'll fire its own haptic on the toggle thumb tap.
            .r1Pressable(onClick = { onCheckedChange(!checked) }, hapticOnClick = false)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = R1.body, color = R1.InkMuted)
            }
        }
        R1Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun NavRow(
    label: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = R1.body,
                color = R1.InkSoft,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        com.github.itskenny0.r1ha.ui.components.Chevron(
            direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Right,
            size = 10.dp,
            tint = R1.InkMuted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = if (mono) R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                else R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Hairline border in StatusRed so the destructive intent reads at a glance — the
            // earlier flat `SurfaceMuted` fill didn't signal "danger" from across the screen.
            .r1Pressable(onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = R1.labelMicro, color = R1.StatusRed.copy(alpha = 0.92f))
    }
}

/**
 * Bespoke segmented picker — rectangular cells, hairline borders, selected = orange fill on
 * black text. Reads like a hardware mode selector instead of Material's pill chips.
 */
@Composable
private fun <T> SegmentedIntPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> SegmentedEnumPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(option) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
            // Hairline divider between cells (skip after last).
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(R1.Bg),
                )
            }
        }
    }
}
