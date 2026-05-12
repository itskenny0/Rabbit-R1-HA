package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.github.itskenny0.r1ha.ui.components.ChevronBack
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
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
    WheelScrollFor(wheelInput = wheelInput, listState = listState)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        TopBar(title = "SETTINGS", onBack = onBack)

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
                    subtitle = "Tap the card to flip the entity on/off",
                    checked = s.behavior.tapToToggle,
                    onCheckedChange = { vm.setTapToToggle(it) },
                )
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
private fun TopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
        ) {
            ChevronBack(onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(title, style = R1.screenTitle, color = R1.Ink)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
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
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
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
            .clickable { onCheckedChange(!checked) }
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = R1.Bg,
                checkedTrackColor = R1.AccentWarm,
                checkedBorderColor = R1.AccentWarm,
                uncheckedThumbColor = R1.InkSoft,
                uncheckedTrackColor = R1.Bg,
                uncheckedBorderColor = R1.Hairline,
            ),
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
            .clickable(onClick = onClick)
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = R1.InkMuted,
            modifier = Modifier.size(12.dp),
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
            .clickable(onClick = onClick)
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
                    .clickable { onSelect(option) }
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
