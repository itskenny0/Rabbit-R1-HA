package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.ui.components.ChevronBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onOpenThemePicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(settings = settings, tokens = tokens),
    )
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ChevronBack(onClick = onBack)
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Server ───────────────────────────────────────────────────────
            item { Section("Server") }
            item {
                InfoRow(
                    label = "URL",
                    value = s.server?.url ?: "(not connected)",
                )
            }
            item {
                s.server?.haVersion?.let { InfoRow(label = "HA version", value = it) }
            }
            item {
                // Sign out + reconnect: clears tokens and server URL, then routes back to
                // onboarding so the user can re-enter their HA URL.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { vm.signOut(onSignedOut) }) {
                        Text("Sign out & reconnect")
                    }
                }
            }

            item { SectionDivider() }

            // ── Scroll wheel ─────────────────────────────────────────────────
            item { Section("Scroll wheel") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("Step size", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    SegmentedIntPicker(
                        options = listOf(1, 2, 5, 10),
                        selected = s.wheel.stepPercent,
                        label = { "$it %" },
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("Key source", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    SegmentedEnumPicker(
                        options = WheelKeySource.entries,
                        selected = s.wheel.keySource,
                        label = {
                            when (it) {
                                WheelKeySource.AUTO -> "Auto"
                                WheelKeySource.DPAD -> "D-Pad"
                                WheelKeySource.VOLUME -> "Volume"
                            }
                        },
                        onSelect = { vm.setWheelKeySource(it) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Card UI ──────────────────────────────────────────────────────
            item { Section("Card UI") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("Display mode", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    SegmentedEnumPicker(
                        options = DisplayMode.entries,
                        selected = s.ui.displayMode,
                        label = {
                            when (it) {
                                DisplayMode.PERCENT -> "Percent"
                                DisplayMode.RAW -> "Raw"
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
                    label = "Show position dots",
                    checked = s.ui.showPositionDots,
                    onCheckedChange = { vm.setShowPositionDots(it) },
                )
            }

            item { SectionDivider() }

            // ── Behavior ─────────────────────────────────────────────────────
            item { Section("Behavior") }
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
                    subtitle = "Tap the card to toggle the entity on/off",
                    checked = s.behavior.tapToToggle,
                    onCheckedChange = { vm.setTapToToggle(it) },
                )
            }

            item { SectionDivider() }

            // ── Appearance ───────────────────────────────────────────────────
            item { Section("Appearance") }
            item {
                NavRow(
                    label = "Theme",
                    value = s.theme.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    onClick = onOpenThemePicker,
                )
            }

            item { SectionDivider() }

            // ── About ────────────────────────────────────────────────────────
            item {
                NavRow(
                    label = "About",
                    onClick = onOpenAbout,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp),
        )
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedIntPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedEnumPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
